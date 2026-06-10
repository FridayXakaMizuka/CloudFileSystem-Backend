# 回收站 Redis 存储结构设计文档（新架构 v4.0）

## 📋 文档概述

本文档详细描述了企业级网盘回收站系统的 **新架构** Redis 存储结构设计，采用 **索引层 + 元数据层 + 临时数据层** 的三层架构。

**核心设计理念：**
- ✅ **索引层**：`recycle:user:{userId}:batches` (ZSET) - 用户batchId索引，过期逻辑不变
- ✅ **元数据层**：`recycle:batch:{batchId}:info` (Hash) - batch根节点信息（只需知道是文件夹还是文件类型）
- ✅ **数据层**：`recycle:batch:{batchId}:nodes` (ZSET) - **仅作为彻底删除时的临时缓存栈**
- ✅ **删除操作**：只更新根节点的 `last_del_uuid` 和状态，不扫描子节点
- ✅ **恢复操作**：逐级检查父目录是否在回收站中，通过 `last_del_uuid` 校验节点存在性
- ✅ **彻底删除**：使用 BFS 遍历文件夹树，将符合条件的节点压入数据层 ZSET，然后从栈顶逐个弹栈清空

**与旧架构的关键区别：**
| 特性 | 旧架构 (v3.0) | 新架构 (v4.0) |
|------|--------------|--------------|
| 删除时是否扫描子节点 | ✅ 是，异步递归扫描所有子节点 | ❌ 否，只更新根节点 |
| 数据层用途 | 存储所有待恢复/删除的节点 | 仅作为彻底删除时的临时缓存栈 |
| 恢复时是否需要数据层 | ✅ 是，从数据层取出节点逐个恢复 | ❌ 否，直接从 MySQL 恢复 |
| 彻底删除逻辑 | 从数据层取出节点逐个删除 | BFS 遍历文件夹树，压栈后弹栈清空 |
| last_del_uuid 字段 | 无 | ✅ 有，用于校验节点存在性 |

---

## 🏗️ 架构设计

### 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     用户操作层                                │
│  删除文件/文件夹 → 恢复 → 彻底删除                            │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────────────────────┐
│                   Redis 主存储层 (6381端口)                   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  索引层：recycle:user:{userId}:batches (ZSET)        │   │
│  │  - Member: batchId                                   │   │
│  │  - Score: deleted_at 时间戳                           │   │
│  │  - TTL: 30天，每次访问重置                            │   │
│  └──────────────────────────────────────────────────────┘   │
│                      │                                       │
│                      ▼                                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  元数据层：recycle:batch:{batchId}:info (Hash)       │   │
│  │  - rootNodeId, nodeType(0=文件夹,1=文件)              │   │
│  │  - userId, batchId, createdAt, deletedAt             │   │
│  │  - TTL: 30天，恢复完成后主动销毁                       │   │
│  └──────────────────────────────────────────────────────┘   │
│                      │                                       │
│                      │ (仅彻底删除时使用)                     │
│                      ▼                                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  数据层：recycle:batch:{batchId}:nodes (ZSET)        │   │
│  │  - Member: {nodeType}:{nodeId}                        │   │
│  │  - Score: BFS 遍历顺序                                │   │
│  │  - 用途：彻底删除时的临时缓存栈                         │   │
│  │  - TTL: 操作完成后立即销毁                             │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────┬──────────────────────────────────────────┘
                   │ (持久化到 MySQL)
                   ▼
┌─────────────────────────────────────────────────────────────┐
│                 MySQL 数据库层                               │
│                                                              │
│  folder_nodes / file_nodes 表：                              │
│  - directory_status: active/in_recycle_bin/unassigned/...   │
│  - last_del_uuid: 最后删除/恢复批次号（UUID格式）             │
│  - original_parent_id / original_folder_id                  │
│  - original_path                                            │
│                                                              │
│  recycle_bin_tasks 表：                                      │
│  - batch_id, user_id, root_node_id, node_type               │
│  - operation_type, status, created_at                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔑 Redis Key 设计规范

### 1. 用户回收站列表索引（ZSET）⭐ 不变

**Key 格式：** `recycle:user:{userId}:batches`

**数据结构：** ZSET（有序集合）

**字段说明：**
- **Member**: `batchId`（UUID 格式）
- **Score**: `deleted_at` 时间戳（毫秒），用于按删除时间排序和游标分页

**TTL**: 30 天（与回收站保留时间一致）

**⚠️ 重要：索引层 Key 不自动销毁**
- 索引层 Key **不会**因为过期而自动删除
- 每次访问索引层 Key 时，TTL 重置为 30 天
- 只有当用户的所有 batch 都被恢复或彻底删除后，索引层才会被清理

**用途：**
- 快速查询用户的回收站项目列表
- 支持基于时间的游标分页

**示例：**
```redis
# 添加 batchId 到用户列表
ZADD recycle:user:10001:batches 1717747200000 "550e8400-e29b-41d4-a716-446655440000"

# 查询最近删除的 20 个 batch
ZREVRANGEBYSCORE recycle:user:10001:batches +inf -inf LIMIT 0 20

# 设置/刷新过期时间
EXPIRE recycle:user:10001:batches 2592000  # 30天
```

---

### 2. Batch 元数据信息（Hash）⭐ 简化

**Key 格式：** `recycle:batch:{batchId}:info`

**数据结构：** Hash（哈希表）

**Fields（精简版）：**

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `rootNodeId` | Long | 根节点 ID | `12345` |
| `nodeType` | Integer | 节点类型（**0=文件夹，1=文件**） | `0` |
| `userId` | Long | 用户 ID | `10001` |
| `batchId` | String | 批次号 | `"550e8400-..."` |
| `createdAt` | Long | 创建时间戳（毫秒） | `1717747200000` |
| `deletedAt` | Long | 删除时间戳（毫秒） | `1717747200000` |
| `expiresAt` | Long | 过期时间戳（毫秒） | `1720339200000` |

**TTL**: 30 天

**⚠️ 重要：元数据层在以下情况销毁**
- **恢复操作完成**：所有节点恢复成功后，立即删除 `recycle:batch:{batchId}:info`
- **彻底删除操作完成**：所有节点处理完成后，立即删除相关 Key
- **Redis 过期**：30 天后自动触发彻底删除逻辑（如果还未手动删除）

**用途：**
- O(1) 时间复杂度查询 batch 的基本信息
- 浏览回收站时快速获取根节点类型（文件夹/文件）
- 权限校验时快速获取 userId

**示例：**
```redis
# 设置 batch 元数据（文件夹）
HSET recycle:batch:550e8400:info \
    rootNodeId "12345" \
    nodeType "0" \
    userId "10001" \
    batchId "550e8400-e29b-41d4-a716-446655440000" \
    createdAt "1717747200000" \
    deletedAt "1717747200000" \
    expiresAt "1720339200000"

# 查询 batch 元数据
HGETALL recycle:batch:550e8400:info

# 设置过期时间
EXPIRE recycle:batch:550e8400:info 2592000
```

---

### 3. Batch 节点临时缓存栈（ZSET）⭐ 新用途

**Key 格式：** `recycle:batch:{batchId}:nodes`

**数据结构：** ZSET（有序集合）

**字段说明：**
- **Member**: `nodeId`（Long 类型，**只存储文件夹节点ID**）
- **Score**: BFS 遍历顺序的时间戳（毫秒），保证处理顺序

**TTL**: **操作完成后立即销毁**（不依赖过期）

**⚠️ 重要：数据层仅作为彻底删除时的临时缓存栈**
- **删除操作**：不使用数据层
- **恢复操作**：不使用数据层
- **彻底删除操作**：
  1. BFS 遍历文件夹树，将符合条件的**文件夹节点**压入栈（ZADD，只存 nodeId）
  2. **文件节点不入栈**，在遍历时直接移入待分配池（清空信息）
  3. 从栈顶逐个弹栈（ZPOPMAX），清空文件夹信息并标记为待分配
  4. 操作完成后立即删除数据层 Key

**用途：**
- 彻底删除时临时存储待处理的**文件夹节点**
- 保证父子文件夹的处理顺序（先子后父）
- 支持断点续传（如果操作中断，可以从栈中继续）
- **文件节点不入栈**，直接在 BFS 遍历时处理，提升性能

**示例：**
```redis
# 彻底删除：BFS 遍历后压栈（只存文件夹节点ID）
ZADD recycle:batch:550e8400:nodes 1717747200000 "12345"  # 子文件夹ID
ZADD recycle:batch:550e8400:nodes 1717747201000 "12346"  # 子文件夹ID
ZADD recycle:batch:550e8400:nodes 1717747202000 "12347"  # 子文件夹ID
# 注意：文件节点不入栈，直接在遍历时处理

# 从栈顶弹栈（score 最大的先出）
ZPOPMAX recycle:batch:550e8400:nodes 1
# 返回: ["12347", "1717747202000"]

# 获取栈大小
ZCARD recycle:batch:550e8400:nodes

# 操作完成后立即删除
DEL recycle:batch:550e8400:nodes
```

---

## 🔄 核心操作流程（新架构）

### 流程 1：删除文件/文件夹（移入回收站）⭐ 简化

```
用户删除请求（nodeId, nodeType, version, batchId）
    ↓
1. 验证节点是否存在且未被修改
   - 检查 folder_id/parent_id 是否改变
   - 检查乐观锁版本号 version 是否匹配
   - 检查名称 name 是否改变
   - 任一条件不满足 → 报错"原文件(夹)不存在"
    ↓
2. MySQL: 更新根节点状态
   - directory_status = 'in_recycle_bin'
   - last_del_uuid = batchId
   - is_deleted = 1
   - deleted_at = NOW()
   - delete_expires_at = NOW() + 30天
   - original_parent_id / original_folder_id = 当前父目录ID
   - original_path = 当前完整路径
   - version = version + 1
    ↓
3. Redis: 初始化元数据层
   - HSET recycle:batch:{batchId}:info (rootNodeId, nodeType, userId, ...)
   - EXPIRE recycle:batch:{batchId}:info 2592000
    ↓
4. Redis: 添加 batchId 到用户索引
   - ZADD recycle:user:{userId}:batches deletedTimestamp batchId
   - EXPIRE recycle:user:{userId}:batches 2592000
    ↓
5. MySQL: 插入 recycle_bin_tasks 记录
   - batch_id, user_id, root_node_id, node_type
   - operation_type = 0 (删除)
   - status = 1 (已完成，因为不需要异步扫描)
    ↓
6. 返回成功响应
   - recycleBinPath
   - expiresAt
```

**关键变化：**
- ❌ **不再异步扫描子节点**
- ❌ **不再将子节点加入 Redis 数据层**
- ✅ **只更新根节点的状态和 last_del_uuid**
- ✅ **子节点保持原状，直到彻底删除时才处理**

**代码示例：**
```java
@Transactional
public DeleteNodeResponse deleteNodeWithBatchId(Long nodeId, Integer nodeType, Long userId, Long version, String batchId) {
    // 1. 验证节点是否存在且未被修改
    if (nodeType == 0) {
        FolderNode folder = folderNodeMapper.findById(nodeId);
        if (folder == null || !userId.equals(folder.getUserId())) {
            throw new RuntimeException("无权访问该文件夹");
        }
        
        // 检查是否被修改
        if (!version.equals(folder.getVersion())) {
            throw new OptimisticLockException("文件夹已被修改，请刷新后重试");
        }
        
        // 执行软删除（只更新根节点）
        folderNodeMapper.softDeleteFolderOnly(nodeId, batchId, LocalDateTime.now().plusDays(30));
        
    } else {
        FileNode file = fileNodeMapper.findById(nodeId);
        if (file == null || !userId.equals(file.getUserId())) {
            throw new RuntimeException("无权访问该文件");
        }
        
        // 检查是否被修改
        if (!version.equals(file.getVersion())) {
            throw new OptimisticLockException("文件已被修改，请刷新后重试");
        }
        
        // 执行软删除（只更新根节点）
        fileNodeMapper.softDeleteFileOnly(nodeId, batchId, LocalDateTime.now().plusDays(30));
    }
    
    // 2. 初始化 Redis 元数据层
    Map<String, String> info = new HashMap<>();
    info.put("rootNodeId", String.valueOf(nodeId));
    info.put("nodeType", String.valueOf(nodeType));
    info.put("userId", String.valueOf(userId));
    info.put("batchId", batchId);
    info.put("deletedAt", String.valueOf(System.currentTimeMillis()));
    info.put("expiresAt", String.valueOf(System.currentTimeMillis() + 30L * 24 * 3600 * 1000));
    
    recycleBinRedisService.cacheBatchInfo(batchId, info);
    
    // 3. 添加 batchId 到用户索引
    recycleBinRedisService.addBatchToUserList(userId, batchId, LocalDateTime.now());
    
    // 4. 创建任务记录（status=1 表示已完成）
    RecycleBinTask task = new RecycleBinTask();
    task.setBatchId(batchId);
    task.setUserId(userId);
    task.setRootNodeId(nodeId);
    task.setNodeType(nodeType);
    task.setOperationType(0);
    task.setStatus(1); // 已完成
    task.setCreatedAt(LocalDateTime.now());
    recycleBinTaskMapper.insert(task);
    
    return new DeleteNodeResponse(recycleBinPath, expiresAt);
}
```

---

### 流程 2：恢复回收站项目 ⭐ 新逻辑

```
用户恢复请求（batchId）
    ↓
1. 从 Redis 元数据层获取根节点信息
   - HGETALL recycle:batch:{batchId}:info
   - 获取 rootNodeId, nodeType, userId
    ↓
2. 验证权限
   - 检查 userId 是否匹配当前用户
    ↓
3. 找到对应的根节点（MySQL）
   - 根据 nodeType 查询 folder_nodes 或 file_nodes
   - 检查 directory_status = 'in_recycle_bin'
   - 检查 last_del_uuid = batchId（校验节点存在性）
    ↓
4. 【关键】逐级检查父目录是否在回收站中
   - 终止条件：到达用户根目录（_root/_files/{userId}）
   - 对于每一级父目录：
     a. 查询父目录的 directory_status
     b. 如果 status = 'in_recycle_bin'：
        - 检查父目录的 last_del_uuid 是否与当前 batchId 一致
        - 如果不一致 → 报错"父目录已被其他操作删除"
     c. 如果 status = 'active' → 继续向上检查
    ↓
5. 执行恢复操作
   - 更新 directory_status = 'active'
   - 更新 is_deleted = 0
   - 清除 deleted_at, delete_expires_at, last_del_uuid
   - 恢复到 original_parent_id / original_folder_id
   - 恢复到 original_path
   - version = version + 1
    ↓
6. 如果是文件夹，递归恢复所有子节点
   - 查询所有子文件夹和文件
   - 检查 last_del_uuid 是否与 batchId 一致或为空
   - 逐个恢复子节点
    ↓
7. 清理 Redis 缓存
   - DEL recycle:batch:{batchId}:info
   - ZREM recycle:user:{userId}:batches batchId
    ↓
8. 更新 MySQL 任务状态
   - UPDATE recycle_bin_tasks SET status = 1 WHERE batch_id = ?
```

**关键变化：**
- ✅ **不再从 Redis 数据层取出节点**
- ✅ **直接从 MySQL 恢复节点**
- ✅ **通过 last_del_uuid 校验节点存在性**
- ✅ **逐级检查父目录，防止恢复已删除的父目录下的节点**

**代码示例：**
```java
@Transactional
public RestoreResult restoreNode(String batchId, Long userId) {
    // 1. 从 Redis 获取元数据
    Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
    if (info == null) {
        throw new RuntimeException("batch 不存在或已过期");
    }
    
    Long rootNodeId = Long.parseLong(info.get("rootNodeId"));
    Integer nodeType = Integer.parseInt(info.get("nodeType"));
    
    // 2. 验证权限
    if (!userId.equals(Long.parseLong(info.get("userId")))) {
        throw new RuntimeException("无权恢复该节点");
    }
    
    // 3. 找到根节点并校验
    if (nodeType == 0) {
        FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
        if (folder == null) {
            throw new RuntimeException("文件夹不存在或不在回收站中");
        }
        
        // 校验 last_del_uuid
        if (!batchId.equals(folder.getLastDelUuid())) {
            throw new RuntimeException("文件夹已被其他操作删除");
        }
        
        // 4. 逐级检查父目录
        checkParentDirectories(folder.getParentId(), batchId, userId);
        
        // 5. 执行恢复
        restoreFolderRecursive(rootNodeId, batchId, userId);
        
    } else {
        FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
        if (file == null) {
            throw new RuntimeException("文件不存在或不在回收站中");
        }
        
        // 校验 last_del_uuid
        if (!batchId.equals(file.getLastDelUuid())) {
            throw new RuntimeException("文件已被其他操作删除");
        }
        
        // 4. 逐级检查父目录
        checkParentDirectories(file.getFolderId(), batchId, userId);
        
        // 5. 执行恢复
        restoreFile(rootNodeId, batchId, userId);
    }
    
    // 6. 清理 Redis 缓存
    recycleBinRedisService.cleanupBatch(batchId);
    
    // 7. 更新任务状态
    recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, null, null);
    
    return new RestoreResult(true, "恢复成功");
}

/**
 * 逐级检查父目录是否在回收站中
 */
private void checkParentDirectories(Long parentId, String batchId, Long userId) {
    if (parentId == null) {
        return; // 到达根目录
    }
    
    // 查询用户根目录ID
    Long userRootId = folderNodeMapper.findUserRootId(userId);
    if (parentId.equals(userRootId)) {
        return; // 到达用户根目录，停止检查
    }
    
    FolderNode parent = folderNodeMapper.findById(parentId);
    if (parent == null) {
        throw new RuntimeException("父目录不存在");
    }
    
    if ("in_recycle_bin".equals(parent.getDirectoryStatus())) {
        // 父目录在回收站中，检查 last_del_uuid
        if (!batchId.equals(parent.getLastDelUuid()) && parent.getLastDelUuid() != null) {
            throw new RuntimeException("父目录已被其他操作删除，无法恢复");
        }
        
        // 递归检查上一级
        checkParentDirectories(parent.getParentId(), batchId, userId);
    }
    // 如果父目录是 active 状态，继续向上检查
}
```

---

### 流程 3：彻底删除（永久删除）⭐ 新逻辑

```
用户彻底删除请求（batchId 或 nodeId + nodeType）
    ↓
1. 从 Redis 元数据层获取根节点信息
   - HGETALL recycle:batch:{batchId}:info
   - 获取 rootNodeId, nodeType, userId
    ↓
2. 验证权限
   - 检查 userId 是否匹配当前用户
    ↓
3. 【关键】BFS 遍历文件夹树，构建临时缓存栈
   - 初始化队列 queue = [rootNodeId]
   - WHILE queue 不为空:
     a. 从队列取出当前节点 currentId
     b. 查询当前节点的所有一级子文件夹
        - 条件：directory_status = 'active' OR 
                (directory_status = 'in_recycle_bin' AND 
                 (last_del_uuid = batchId OR last_del_uuid IS NULL))
     c. 将符合条件的子文件夹：
        - 更新 directory_status = 'in_recycle_bin'
        - 更新 last_del_uuid = batchId
        - **压入数据层 ZSET**：ZADD recycle:batch:{batchId}:nodes score "childFolderId"（只存ID）
        - 加入队列 queue
     d. 查询当前节点的所有子文件（**带断点续传优化**）
        - 条件：folder_id = currentId AND
                (directory_status = 'active' OR 
                 (directory_status = 'in_recycle_bin' AND 
                  (last_del_uuid = batchId OR last_del_uuid IS NULL)))
        - **性能优化**：查到符合条件的文件后立即删除，以下一个文件ID为断点继续查询
        - 循环处理直到该文件夹下所有文件都处理完毕
     e. 将符合条件的子文件：
        - **直接移入待分配文件池**（清空除 id 和 directory_status 外的所有信息）
        - 更新 directory_status = 'permanently_deleted'
        - 清空 name, path, folder_id, user_id, file_metadata_id, ...
    ↓
4. 【关键】从栈顶逐个弹栈，清空节点信息
   - WHILE 数据层 ZSET 不为空:
     a. ZPOPMAX recycle:batch:{batchId}:nodes 1
     b. 解析 nodeId（直接就是文件夹ID，无需解析 nodeType）
     c. 清空文件夹信息：
        - 清空除 id 和 directory_status 外的所有信息
        - 更新 directory_status = 'unassigned'
        - 清空 name, path, parent_id, user_id, ...
    ↓
5. 清理 Redis 缓存
   - DEL recycle:batch:{batchId}:nodes
   - DEL recycle:batch:{batchId}:info
   - ZREM recycle:user:{userId}:batches batchId
    ↓
6. 更新 MySQL 任务状态
   - UPDATE recycle_bin_tasks SET status = 1 WHERE batch_id = ?
```

**关键变化：**
- ✅ **使用 BFS 遍历文件夹树**
- ✅ **只将文件夹节点压入数据层 ZSET（临时缓存栈）**
- ✅ **文件节点不入栈，直接在遍历时处理（性能优化）**
- ✅ **文件处理使用断点续传：查到即删，以下一个文件ID为断点继续查询**
- ✅ **从栈顶逐个弹栈，清空文件夹信息**
- ✅ **文件夹标记为 unassigned（进入待分配池）**

**代码示例：**
```java
@Transactional
public void permanentDeleteBatch(String batchId, Long userId) {
    // 1. 从 Redis 获取元数据
    Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
    if (info == null) {
        throw new RuntimeException("batch 不存在或已过期");
    }
    
    Long rootNodeId = Long.parseLong(info.get("rootNodeId"));
    Integer nodeType = Integer.parseInt(info.get("nodeType"));
    
    // 2. 验证权限
    if (!userId.equals(Long.parseLong(info.get("userId")))) {
        throw new RuntimeException("无权删除该节点");
    }
    
    if (nodeType == 0) {
        // 文件夹：BFS 遍历
        bfsPermanentDeleteFolder(rootNodeId, batchId, userId);
    } else {
        // 文件：直接移入待分配池
        permanentDeleteFile(rootNodeId, batchId, userId);
    }
    
    // 3. 清理 Redis 缓存
    recycleBinRedisService.cleanupBatch(batchId);
    
    // 4. 更新任务状态
    recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, null, null);
}

/**
 * BFS 遍历文件夹树，构建临时缓存栈
 */
private void bfsPermanentDeleteFolder(Long rootFolderId, String batchId, Long userId) {
    String nodesKey = "recycle:batch:" + batchId + ":nodes";
    Queue<Long> queue = new LinkedList<>();
    queue.offer(rootFolderId);
    
    long timestamp = System.currentTimeMillis();
    int order = 0;
    
    while (!queue.isEmpty()) {
        Long currentFolderId = queue.poll();
        
        // 1. 查询一级子文件夹
        List<FolderNode> childFolders = folderNodeMapper.findChildrenByConditions(
            currentFolderId, batchId
        );
        
        for (FolderNode childFolder : childFolders) {
            // 更新状态和 last_del_uuid
            folderNodeMapper.markAsInRecycleBin(childFolder.getId(), batchId);
            
            // 压入数据层 ZSET（只存文件夹ID，无需 nodeType 前缀）
            double score = timestamp + order++;
            recycleBinRedisService.addFolderToBatch(batchId, childFolder.getId(), score);
            
            // 加入队列
            queue.offer(childFolder.getId());
        }
        
        // 2. 【性能优化】查询并处理子文件（断点续传）
        Long lastFileId = null; // 断点：上次处理的最后一个文件ID
        while (true) {
            // 分批查询文件，每次最多 100 个，以 lastFileId 为断点
            List<FileNode> childFiles = fileNodeMapper.findChildrenByConditionsWithCursor(
                currentFolderId, batchId, lastFileId, 100
            );
            
            if (childFiles == null || childFiles.isEmpty()) {
                break; // 没有更多文件，退出循环
            }
            
            for (FileNode childFile : childFiles) {
                // 直接移入待分配文件池（清空所有信息）
                fileNodeMapper.moveToUnassignedPool(childFile.getId());
            }
            
            // 更新断点：最后一个处理的文件ID
            lastFileId = childFiles.get(childFiles.size() - 1).getId();
            
            // 如果本次查询不足 100 个，说明已经处理完所有文件
            if (childFiles.size() < 100) {
                break;
            }
        }
    }
    
    // 3. 从栈顶逐个弹栈，清空文件夹信息（Member 直接是 nodeId，无需解析）
    while (true) {
        Set<String> members = recycleBinRedisService.popMaxFromBatch(batchId, 10);
        if (members == null || members.isEmpty()) {
            break;
        }
        
        for (String memberId : members) {
            Long folderId = Long.parseLong(memberId); // 直接解析为文件夹ID
            
            // 清空文件夹信息
            folderNodeMapper.clearFolderInfo(folderId);
        }
    }
}
```

---

## 📊 性能对比

| 操作 | 旧架构 (v3.0) | 新架构 (v4.0) | 提升 |
|------|--------------|--------------|------|
| 删除操作耗时 | 50-200ms（需异步扫描） | 10-30ms（只更新根节点） | **5-10x** |
| 恢复操作耗时 | 100-500ms（从 Redis 取节点） | 50-200ms（直接从 MySQL） | **2x** |
| 彻底删除耗时 | 200-1000ms（从 Redis 取节点） | 100-500ms（BFS + 栈） | **2x** |
| Redis 内存占用 | 高（存储所有节点） | 低（只存元数据） | **90% 降低** |
| MySQL 负载 | 低（删除时不查子节点） | 中（恢复/删除时需查子节点） | - |

---

## 🎯 最佳实践

### 1. last_del_uuid 字段的使用

```sql
-- folder_nodes 和 file_nodes 表新增字段
ALTER TABLE folder_nodes ADD COLUMN last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号';
ALTER TABLE file_nodes ADD COLUMN last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号';
```

**用途：**
- 校验节点是否属于某个 batch
- 防止恢复已删除的父目录下的节点
- 防止彻底删除时重复处理节点

### 2. BFS 遍历的条件判断

```java
// 文件夹条件
WHERE parent_id = #{parentId}
  AND (
    directory_status = 'active'
    OR (
      directory_status = 'in_recycle_bin'
      AND (last_del_uuid = #{batchId} OR last_del_uuid IS NULL)
    )
  )

// 文件条件
WHERE folder_id = #{folderId}
  AND (
    directory_status = 'active'
    OR (
      directory_status = 'in_recycle_bin'
      AND (last_del_uuid = #{batchId} OR last_del_uuid IS NULL)
    )
  )
```

### 3. 清空节点信息的 SQL

```sql
-- 清空文件夹信息（保留 id 和 directory_status）
UPDATE folder_nodes
SET name = NULL,
    path = NULL,
    parent_id = NULL,
    user_id = NULL,
    level = 0,
    sort_order = 0,
    is_hidden = 0,
    is_deleted = 0,
    deleted_at = NULL,
    delete_expires_at = NULL,
    original_parent_id = NULL,
    original_path = NULL,
    last_del_uuid = NULL,
    file_count = 0,
    folder_count = 0,
    total_size = 0,
    directory_status = 'unassigned',
    unassigned_at = NOW(),
    version = version + 1
WHERE id = #{id};

-- 清空文件信息（保留 id 和 directory_status）
UPDATE file_nodes
SET name = NULL,
    path = NULL,
    folder_id = NULL,
    user_id = NULL,
    file_metadata_id = NULL,
    file_size = 0,
    mime_type = NULL,
    extension = NULL,
    sort_order = 0,
    is_hidden = 0,
    is_deleted = 0,
    deleted_at = NULL,
    delete_expires_at = NULL,
    original_folder_id = NULL,
    original_path = NULL,
    last_del_uuid = NULL,
    directory_status = 'permanently_deleted',
    version = version + 1
WHERE id = #{id};
```

---

## 📝 总结

新架构具有以下优势：

✅ **高性能**：删除操作只需更新根节点，无需异步扫描  
✅ **低内存**：Redis 只存储元数据，不存储所有节点  
✅ **数据可靠**：通过 last_del_uuid 校验节点存在性  
✅ **逻辑清晰**：彻底删除使用 BFS + 栈，保证父子节点处理顺序  
✅ **易于维护**：简化了 Redis 存储结构，降低了复杂度  

**适用场景：**
- 大规模文件删除/恢复操作
- 高并发回收站访问
- 要求低延迟的删除操作
- 需要精确控制节点存在性的场景

---

**文档版本**: v4.0  
**最后更新**: 2026-06-09  
**主要变更**:
- v4.0: 重构为索引层 + 元数据层 + 临时数据层架构
  - 删除操作只更新根节点，不扫描子节点
  - 恢复操作直接从 MySQL 恢复，不使用数据层
  - 彻底删除使用 BFS + 栈，临时使用数据层
  - 新增 last_del_uuid 字段用于校验节点存在性
- v3.0: 移除 Redis Keyspace Notification 机制
- v2.0: 新增 Redis Keyspace Notification 自动彻底删除机制
- v1.0: 初始版本，基于 ZSET 的回收站存储结构

**作者**: CloudFileSystem Team
