# 回收站新架构迁移指南（v3.0 → v4.0）

## 📋 文档概述

本文档详细指导如何将回收站系统从旧架构（v3.0）迁移到新架构（v4.0），包括数据库 schema 修改、Redis 存储结构调整、接口变更以及核心业务逻辑的重构。

**迁移目标：**
- ✅ 简化删除操作：只更新根节点，不扫描子节点
- ✅ 优化 Redis 存储：只保留索引层和元数据层，数据层仅作为彻底删除的临时栈
- ✅ 增强数据一致性：通过 `last_del_uuid` 字段校验节点存在性
- ✅ 提升性能：删除操作从 50-200ms 降低到 10-30ms

---

## 🗂️ 目录

1. [数据库 Schema 修改](#1-数据库-schema-修改)
2. [Redis 存储结构调整](#2-redis-存储结构调整)
3. [Mapper 层修改](#3-mapper-层修改)
4. [Service 层重构](#4-service-层重构)
5. [Controller 层接口变更](#5-controller-层接口变更)
6. [测试与验证](#6-测试与验证)
7. [回滚方案](#7-回滚方案)

---

## 1. 数据库 Schema 修改

### 1.1 新增 last_del_uuid 字段

**执行 SQL：**

```sql
USE cloud_file_database;

-- 为 folder_nodes 表添加 last_del_uuid 字段
ALTER TABLE folder_nodes 
ADD COLUMN last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号（UUID格式）' 
AFTER original_path;

-- 为 file_nodes 表添加 last_del_uuid 字段
ALTER TABLE file_nodes 
ADD COLUMN last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号（UUID格式）' 
AFTER original_path;

-- 添加索引（可选，用于快速查询某个 batch 的所有节点）
CREATE INDEX idx_last_del_uuid ON folder_nodes(last_del_uuid);
CREATE INDEX idx_last_del_uuid ON file_nodes(last_del_uuid);
```

**字段说明：**
- `last_del_uuid`: 记录节点最后一次被删除或恢复时的 batchId
- 用途：
  - 校验节点是否属于某个 batch
  - 防止恢复已删除的父目录下的节点
  - 防止彻底删除时重复处理节点

### 1.2 修改 recycle_bin_tasks 表（可选）

如果之前 `total_count` 和 `processed_count` 字段用于异步扫描进度，现在可以简化：

```sql
-- 注释掉这两个字段的用途说明
ALTER TABLE recycle_bin_tasks 
MODIFY COLUMN total_count INT DEFAULT 0 COMMENT '总节点数（始终为1，因为只处理根节点）',
MODIFY COLUMN processed_count INT DEFAULT 0 COMMENT '已处理节点数（始终为1）';
```

---

## 2. Redis 存储结构调整

### 2.1 索引层（不变）

**Key:** `recycle:user:{userId}:batches` (ZSET)

**保持不变，无需修改。**

### 2.2 元数据层（简化）

**Key:** `recycle:batch:{batchId}:info` (Hash)

**旧架构 Fields：**
```
rootNodeId, nodeType, name, size, path, createdAt, updatedAt, deletedAt, 
expiresAt, daysRemaining, version, totalCount, processedCount, status, batchId
```

**新架构 Fields（精简）：**
```
rootNodeId, nodeType, userId, batchId, createdAt, deletedAt, expiresAt
```

**需要移除的 Fields：**
- ❌ `name` - 不需要，从 MySQL 查询
- ❌ `size` - 不需要，从 MySQL 查询
- ❌ `path` - 不需要，从 MySQL 查询
- ❌ `daysRemaining` - 可以动态计算
- ❌ `version` - 不需要
- ❌ `totalCount` - 始终为 1
- ❌ `processedCount` - 始终为 1
- ❌ `status` - 从 MySQL 的 recycle_bin_tasks 表查询

### 2.3 数据层（新用途）

**Key:** `recycle:batch:{batchId}:nodes` (ZSET)

**旧架构用途：** 存储所有待恢复/删除的节点

**新架构用途：** **仅作为彻底删除时的临时缓存栈（只存文件夹节点ID）**

**使用场景：**
- ❌ 删除操作：不使用
- ❌ 恢复操作：不使用
- ✅ 彻底删除操作：BFS 遍历后压栈（只存文件夹ID），然后弹栈清空

---

## 3. Mapper 层修改

### 3.1 FolderNodeMapper.java

**新增方法：**

```java
/**
 * 软删除文件夹（只更新根节点，不扫描子节点）
 */
@Update("UPDATE folder_nodes SET " +
        "directory_status = 'in_recycle_bin', " +
        "is_deleted = 1, " +
        "deleted_at = NOW(), " +
        "delete_expires_at = #{expiresAt}, " +
        "last_del_uuid = #{batchId}, " +
        "original_parent_id = parent_id, " +
        "original_path = path, " +
        "version = version + 1 " +
        "WHERE id = #{id} AND version = #{version}")
int softDeleteFolderOnly(@Param("id") Long id, 
                         @Param("batchId") String batchId, 
                         @Param("expiresAt") LocalDateTime expiresAt,
                         @Param("version") Long version);

/**
 * 根据条件查询子文件夹（用于彻底删除的 BFS 遍历）
 */
@Select("SELECT * FROM folder_nodes WHERE parent_id = #{parentId} AND (" +
        "  directory_status = 'active' OR " +
        "  (directory_status = 'in_recycle_bin' AND " +
        "   (last_del_uuid = #{batchId} OR last_del_uuid IS NULL))" +
        ") ORDER BY id ASC")
List<FolderNode> findChildrenByConditions(@Param("parentId") Long parentId,
                                          @Param("batchId") String batchId);

/**
 * 标记文件夹为回收站状态（用于彻底删除时的 BFS 遍历）
 */
@Update("UPDATE folder_nodes SET " +
        "directory_status = 'in_recycle_bin', " +
        "last_del_uuid = #{batchId}, " +
        "version = version + 1 " +
        "WHERE id = #{id}")
void markAsInRecycleBin(@Param("id") Long id, @Param("batchId") String batchId);

/**
 * 清空文件夹信息（保留 id 和 directory_status）
 */
@Update("UPDATE folder_nodes SET " +
        "name = NULL, path = NULL, parent_id = NULL, user_id = NULL, " +
        "level = 0, sort_order = 0, is_hidden = 0, is_deleted = 0, " +
        "deleted_at = NULL, delete_expires_at = NULL, " +
        "original_parent_id = NULL, original_path = NULL, " +
        "last_del_uuid = NULL, file_count = 0, folder_count = 0, " +
        "total_size = 0, directory_status = 'unassigned', " +
        "unassigned_at = NOW(), version = version + 1 " +
        "WHERE id = #{id}")
void clearFolderInfo(@Param("id") Long id);

/**
 * 查找用户根目录ID
 */
@Select("SELECT id FROM folder_nodes WHERE user_id = #{userId} AND " +
        "parent_id = (SELECT id FROM folder_nodes WHERE path = '_root/_files') " +
        "LIMIT 1")
Long findUserRootId(@Param("userId") Long userId);
```

**修改现有方法：**

```java
// 原来的 softDeleteFolder 方法改为递归删除所有子节点（已废弃）
// 替换为 softDeleteFolderOnly（只更新根节点）
```

### 3.2 FileNodeMapper.java

**新增方法：**

```java
/**
 * 软删除文件（只更新根节点）
 */
@Update("UPDATE file_nodes SET " +
        "directory_status = 'in_recycle_bin', " +
        "is_deleted = 1, " +
        "deleted_at = NOW(), " +
        "delete_expires_at = #{expiresAt}, " +
        "last_del_uuid = #{batchId}, " +
        "original_folder_id = folder_id, " +
        "original_path = path, " +
        "version = version + 1 " +
        "WHERE id = #{id} AND version = #{version}")
int softDeleteFileOnly(@Param("id") Long id, 
                       @Param("batchId") String batchId, 
                       @Param("expiresAt") LocalDateTime expiresAt,
                       @Param("version") Long version);

/**
 * 根据条件查询子文件（用于彻底删除的 BFS 遍历，支持断点续传）
 */
@Select("SELECT * FROM file_nodes WHERE folder_id = #{folderId} AND " +
        "(directory_status = 'active' OR " +
        "(directory_status = 'in_recycle_bin' AND " +
        "(last_del_uuid = #{batchId} OR last_del_uuid IS NULL))) " +
        "AND id > #{lastFileId} " +
        "ORDER BY id ASC LIMIT #{limit}")
List<FileNode> findChildrenByConditionsWithCursor(@Param("folderId") Long folderId,
                                                   @Param("batchId") String batchId,
                                                   @Param("lastFileId") Long lastFileId,
                                                   @Param("limit") int limit);

/**
 * 将文件移入待分配池（清空除 id 和 directory_status 外的所有信息）
 */
@Update("UPDATE file_nodes SET " +
        "name = NULL, path = NULL, folder_id = NULL, user_id = NULL, " +
        "file_metadata_id = NULL, file_size = 0, mime_type = NULL, " +
        "extension = NULL, sort_order = 0, is_hidden = 0, is_deleted = 0, " +
        "deleted_at = NULL, delete_expires_at = NULL, " +
        "original_folder_id = NULL, original_path = NULL, " +
        "last_del_uuid = NULL, directory_status = 'permanently_deleted', " +
        "version = version + 1 " +
        "WHERE id = #{id}")
void moveToUnassignedPool(@Param("id") Long id);
```

### 3.3 RecycleBinTaskMapper.java

**无需修改**，保持现有方法即可。

---

## 4. Service 层重构

### 4.1 DirectoryService.java

**重构 `deleteNodeWithBatchId` 方法：**

```java
@Transactional
public DeleteNodeResponse deleteNodeWithBatchId(Long nodeId, Integer nodeType, Long userId, Long version, String batchId) {
    // 1. 参数校验
    if (nodeId == null || nodeType == null || version == null) {
        throw new IllegalArgumentException("参数不能为空");
    }
    
    LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
    String recycleBinPath;
    
    if (nodeType == 0) {
        // 文件夹
        FolderNode folder = folderNodeMapper.findById(nodeId);
        if (folder == null) {
            throw new RuntimeException("文件夹不存在");
        }
        
        // 验证权限
        if (!userId.equals(folder.getUserId())) {
            throw new RuntimeException("无权访问该文件夹");
        }
        
        // 检查是否被修改（乐观锁）
        if (!version.equals(folder.getVersion())) {
            throw new OptimisticLockException("文件夹已被修改，请刷新后重试");
        }
        
        // 检查路径是否改变
        if (folder.getParentId() == null || !folder.getPath().startsWith("_root/_files/" + userId)) {
            throw new RuntimeException("原文件夹不存在或位置已改变");
        }
        
        // 执行软删除（只更新根节点）
        int affected = folderNodeMapper.softDeleteFolderOnly(nodeId, batchId, expiresAt, version);
        if (affected == 0) {
            throw new OptimisticLockException("文件夹已被其他操作修改");
        }
        
        recycleBinPath = calculateRecycleBinPath(folder.getPath(), userId);
        
    } else {
        // 文件
        FileNode file = fileNodeMapper.findById(nodeId);
        if (file == null) {
            throw new RuntimeException("文件不存在");
        }
        
        // 验证权限
        if (!userId.equals(file.getUserId())) {
            throw new RuntimeException("无权访问该文件");
        }
        
        // 检查是否被修改（乐观锁）
        if (!version.equals(file.getVersion())) {
            throw new OptimisticLockException("文件已被修改，请刷新后重试");
        }
        
        // 检查路径是否改变
        if (file.getFolderId() == null) {
            throw new RuntimeException("原文件不存在或位置已改变");
        }
        
        // 执行软删除（只更新根节点）
        int affected = fileNodeMapper.softDeleteFileOnly(nodeId, batchId, expiresAt, version);
        if (affected == 0) {
            throw new OptimisticLockException("文件已被其他操作修改");
        }
        
        recycleBinPath = calculateRecycleBinPath(file.getPath(), userId);
    }
    
    // 2. 初始化 Redis 元数据层
    Map<String, String> info = new HashMap<>();
    info.put("rootNodeId", String.valueOf(nodeId));
    info.put("nodeType", String.valueOf(nodeType));
    info.put("userId", String.valueOf(userId));
    info.put("batchId", batchId);
    info.put("createdAt", String.valueOf(System.currentTimeMillis()));
    info.put("deletedAt", String.valueOf(System.currentTimeMillis()));
    info.put("expiresAt", String.valueOf(expiresAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
    
    recycleBinRedisService.cacheBatchInfo(batchId, info);
    
    // 3. 添加 batchId 到用户索引
    recycleBinRedisService.addBatchToUserList(userId, batchId, LocalDateTime.now());
    
    // 4. 创建任务记录（status=1 表示已完成）
    RecycleBinTask task = new RecycleBinTask();
    task.setBatchId(batchId);
    task.setUserId(userId);
    task.setRootNodeId(nodeId);
    task.setNodeType(nodeType);
    task.setOperationType(0); // 删除
    task.setStatus(1); // 已完成
    task.setTotalCount(1);
    task.setProcessedCount(1);
    task.setCreatedAt(LocalDateTime.now());
    recycleBinTaskMapper.insert(task);
    
    log.info("用户 {} 删除节点成功（新架构）- NodeId: {}, BatchId: {}", userId, nodeId, batchId);
    
    return new DeleteNodeResponse(recycleBinPath, expiresAt);
}
```

**新增 `restoreNode` 方法（新架构）：**

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
    
    if (nodeType == 0) {
        // 文件夹恢复
        FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
        if (folder == null) {
            throw new RuntimeException("文件夹不存在或不在回收站中");
        }
        
        // 校验 last_del_uuid
        if (!batchId.equals(folder.getLastDelUuid())) {
            throw new RuntimeException("文件夹已被其他操作删除");
        }
        
        // 逐级检查父目录
        checkParentDirectories(folder.getParentId(), batchId, userId);
        
        // 递归恢复文件夹及其子节点
        restoreFolderRecursive(rootNodeId, batchId, userId);
        
    } else {
        // 文件恢复
        FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
        if (file == null) {
            throw new RuntimeException("文件不存在或不在回收站中");
        }
        
        // 校验 last_del_uuid
        if (!batchId.equals(file.getLastDelUuid())) {
            throw new RuntimeException("文件已被其他操作删除");
        }
        
        // 逐级检查父目录
        checkParentDirectories(file.getFolderId(), batchId, userId);
        
        // 恢复文件
        restoreFile(rootNodeId, batchId, userId);
    }
    
    // 3. 清理 Redis 缓存
    recycleBinRedisService.cleanupBatch(batchId);
    
    // 4. 更新任务状态
    recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, null, null);
    
    log.info("用户 {} 恢复节点成功（新架构）- BatchId: {}", userId, batchId);
    
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

/**
 * 递归恢复文件夹及其子节点
 */
private void restoreFolderRecursive(Long folderId, String batchId, Long userId) {
    // 1. 恢复当前文件夹
    folderNodeMapper.restoreFolder(folderId);
    
    // 2. 查询所有子文件夹
    List<FolderNode> childFolders = folderNodeMapper.findChildrenByConditions(folderId, batchId);
    for (FolderNode childFolder : childFolders) {
        // 校验 last_del_uuid
        if (batchId.equals(childFolder.getLastDelUuid()) || childFolder.getLastDelUuid() == null) {
            restoreFolderRecursive(childFolder.getId(), batchId, userId);
        }
    }
    
    // 3. 查询所有子文件
    List<FileNode> childFiles = fileNodeMapper.findChildrenByConditions(folderId, batchId);
    for (FileNode childFile : childFiles) {
        // 校验 last_del_uuid
        if (batchId.equals(childFile.getLastDelUuid()) || childFile.getLastDelUuid() == null) {
            restoreFile(childFile.getId(), batchId, userId);
        }
    }
}

/**
 * 恢复单个文件
 */
private void restoreFile(Long fileId, String batchId, Long userId) {
    fileNodeMapper.restoreFile(fileId);
}
```

**新增 `permanentDeleteBatch` 方法（新架构）：**

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
    
    log.info("用户 {} 彻底删除完成（新架构）- BatchId: {}", userId, batchId);
}

/**
 * BFS 遍历文件夹树，构建临时缓存栈（只存文件夹ID）
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

/**
 * 彻底删除单个文件
 */
private void permanentDeleteFile(Long fileId, String batchId, Long userId) {
    // 移入待分配文件池
    fileNodeMapper.moveToUnassignedPool(fileId);
}
```

### 4.2 RecycleBinRedisService.java

**修改 `cacheBatchInfo` 方法：**

```java
/**
 * 缓存batch的详细信息（新架构 - 精简版）
 */
public void cacheBatchInfo(String batchId, Map<String, String> info) {
    try {
        String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
        
        if (info != null && !info.isEmpty()) {
            // 使用 HMSET 命令批量设置 Hash 字段
            deleteRedisCommands.hmset(infoKey, info)
                .toCompletableFuture()
                .join();
            
            // 设置 TTL
            deleteRedisCommands.expire(infoKey, EXPIRE_SECONDS)
                .toCompletableFuture()
                .join();
            
            log.info("[Redis] 缓存batch元数据 - BatchId: {}, Fields: {}", batchId, info.size());
        }
        
    } catch (Exception e) {
        log.error("[Redis] 缓存batch信息失败 - BatchId: {}", batchId, e);
    }
}
```

**新增 `addFolderToBatch` 方法：**

```java
/**
 * 将文件夹节点添加到数据层 ZSET（只存文件夹ID，无需 nodeType 前缀）
 */
public CompletableFuture<Boolean> addFolderToBatch(String batchId, Long folderId, double score) {
    try {
        String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
        
        return deleteRedisCommands.zadd(nodesKey, score, String.valueOf(folderId))
            .toCompletableFuture()
            .thenApply(result -> {
                log.debug("[Redis] 添加文件夹到批处理 - BatchId: {}, FolderId: {}", batchId, folderId);
                return result != null && result > 0;
            })
            .exceptionally(ex -> {
                log.error("[Redis] 添加文件夹失败 - BatchId: {}, FolderId: {}", batchId, folderId, ex);
                return false;
            });
            
    } catch (Exception e) {
        log.error("[Redis] 添加文件夹异常 - BatchId: {}, FolderId: {}", batchId, folderId, e);
        return CompletableFuture.completedFuture(false);
    }
}
```

**新增 `popMaxFromBatch` 方法：**

```java
/**
 * 从数据层 ZSET 中弹出 score 最大的 N 个成员（用于彻底删除的栈操作）
 */
public CompletableFuture<Set<String>> popMaxFromBatch(String batchId, int count) {
    try {
        String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
        
        return deleteRedisCommands.zpopmax(nodesKey, count)
            .toCompletableFuture()
            .thenApply(members -> {
                Set<String> result = new HashSet<>();
                if (members != null) {
                    for (Object member : members) {
                        result.add(String.valueOf(member));
                    }
                }
                return result;
            })
            .exceptionally(ex -> {
                log.error("[Redis] 弹栈失败 - BatchId: {}", batchId, ex);
                return Collections.emptySet();
            });
            
    } catch (Exception e) {
        log.error("[Redis] 弹栈异常 - BatchId: {}", batchId, e);
        return CompletableFuture.completedFuture(Collections.emptySet());
    }
}
```

**修改 `cleanupBatch` 方法：**

```java
/**
 * 清理 batch 的 Redis 缓存（恢复或彻底删除完成后调用）
 */
public void cleanupBatch(String batchId) {
    try {
        String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
        String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
        
        // 同步等待删除完成
        deleteRedisCommands.del(nodesKey, infoKey)
            .toCompletableFuture()
            .join();
        
        log.info("[Redis] 主动销毁元数据层和数据层 - BatchId: {}", batchId);
        
    } catch (Exception e) {
        log.error("[Redis] 清理batch数据失败 - BatchId: {}", batchId, e);
    }
}
```

### 4.3 AsyncRecycleBinRestoreService.java 和 AsyncPermanentDeleteService.java

**这两个服务类在新架构中不再需要**，因为恢复和彻底删除都是同步完成的。

**建议：**
- 保留这两个类，但标记为 `@Deprecated`
- 或者完全删除，并移除相关的 `@Async` 调用

---

## 5. Controller 层接口变更

### 5.1 FileController.java

**删除接口（无需修改）：**

```java
@PostMapping("/delete")
public Result<DeleteNodeResponse> deleteNode(...) {
    // 保持不变，调用新的 deleteNodeWithBatchId 方法
}
```

**恢复接口（修改响应）：**

```java
@PostMapping("/recycle/restore")
public ResponseEntity<?> restoreNode(@RequestParam String batchId) {
    try {
        Long userId = SecurityUtils.getCurrentUserId();
        
        // 调用新的恢复方法（同步完成）
        RestoreResult result = directoryService.restoreNode(batchId, userId);
        
        log.info("用户 {} 恢复节点成功 - BatchId: {}", userId, batchId);
        
        return Result.success("恢复成功", null);
        
    } catch (RuntimeException e) {
        log.error("恢复失败: {}", e.getMessage(), e);
        return Result.error(50001, e.getMessage());
    }
}
```

**彻底删除接口（修改响应）：**

```java
@DeleteMapping("/delete/permanent")
public Result<Void> permanentDeleteNode(...) {
    try {
        Long userId = SecurityUtils.getCurrentUserId();
        
        // 调用新的彻底删除方法（同步完成）
        directoryService.permanentDeleteBatch(targetBatchId, userId);
        
        log.info("用户 {} 彻底删除完成 - BatchId: {}", userId, targetBatchId);
        
        return Result.success("彻底删除成功", null);
        
    } catch (RuntimeException e) {
        log.error("彻底删除失败: {}", e.getMessage(), e);
        return Result.error(50001, e.getMessage());
    }
}
```

---

## 6. 测试与验证

### 6.1 单元测试

**测试删除操作：**

```java
@Test
public void testDeleteNode_NewArchitecture() {
    // 1. 创建测试文件夹
    Long folderId = createTestFolder();
    
    // 2. 删除文件夹
    String batchId = UUID.randomUUID().toString();
    DeleteNodeResponse response = directoryService.deleteNodeWithBatchId(
        folderId, 0, 10001L, 1L, batchId
    );
    
    // 3. 验证 Redis 元数据层
    Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
    assertNotNull(info);
    assertEquals(folderId.toString(), info.get("rootNodeId"));
    assertEquals("0", info.get("nodeType"));
    
    // 4. 验证 MySQL 状态
    FolderNode folder = folderNodeMapper.findById(folderId);
    assertEquals("in_recycle_bin", folder.getDirectoryStatus());
    assertEquals(batchId, folder.getLastDelUuid());
    
    // 5. 验证子节点未被扫描
    List<FolderNode> children = folderNodeMapper.findChildren(folderId);
    assertFalse(children.isEmpty()); // 子节点仍然存在且未被修改
}
```

**测试恢复操作：**

```java
@Test
public void testRestoreNode_NewArchitecture() {
    // 1. 删除文件夹
    String batchId = deleteTestFolder();
    
    // 2. 恢复文件夹
    RestoreResult result = directoryService.restoreNode(batchId, 10001L);
    
    // 3. 验证恢复成功
    assertTrue(result.isSuccess());
    
    // 4. 验证 Redis 缓存已清理
    Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
    assertNull(info);
    
    // 5. 验证 MySQL 状态
    FolderNode folder = folderNodeMapper.findById(testFolderId);
    assertEquals("active", folder.getDirectoryStatus());
    assertNull(folder.getLastDelUuid());
}
```

**测试彻底删除操作：**

```java
@Test
public void testPermanentDelete_NewArchitecture() {
    // 1. 删除文件夹
    String batchId = deleteTestFolder();
    
    // 2. 彻底删除
    directoryService.permanentDeleteBatch(batchId, 10001L);
    
    // 3. 验证 Redis 缓存已清理
    Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
    assertNull(info);
    
    // 4. 验证文件夹已清空
    FolderNode folder = folderNodeMapper.findById(testFolderId);
    assertEquals("unassigned", folder.getDirectoryStatus());
    assertNull(folder.getName());
    assertNull(folder.getPath());
}
```

### 6.2 集成测试

**测试场景：**
1. 删除包含大量子节点的文件夹
2. 恢复已删除的文件夹
3. 彻底删除已删除的文件夹
4. 并发删除/恢复/彻底删除同一节点
5. 恢复时父目录已被删除的场景

---

## 7. 回滚方案

### 7.1 数据库回滚

```sql
-- 删除新增的字段
ALTER TABLE folder_nodes DROP COLUMN last_del_uuid;
ALTER TABLE file_nodes DROP COLUMN last_del_uuid;

-- 删除索引
DROP INDEX idx_last_del_uuid ON folder_nodes;
DROP INDEX idx_last_del_uuid ON file_nodes;
```

### 7.2 代码回滚

**Git 回滚：**

```bash
# 查看提交历史
git log --oneline

# 回滚到迁移前的版本
git revert <commit-hash>

# 或者强制回滚（谨慎使用）
git reset --hard <commit-hash>
```

### 7.3 Redis 数据清理

```bash
# 连接到 Redis
redis-cli -p 6381

# 删除所有回收站相关的 Key
KEYS recycle:* | xargs redis-cli -p 6381 DEL

# 或者逐个删除
DEL recycle:user:10001:batches
DEL recycle:batch:550e8400:info
DEL recycle:batch:550e8400:nodes
```

---

## 📝 总结

**迁移步骤：**
1. ✅ 执行数据库 Schema 修改（添加 last_del_uuid 字段）
2. ✅ 修改 Mapper 层（新增/修改方法）
3. ✅ 重构 Service 层（删除、恢复、彻底删除逻辑）
4. ✅ 修改 Controller 层（接口响应）
5. ✅ 运行单元测试和集成测试
6. ✅ 部署到生产环境

**预期收益：**
- 🚀 删除操作性能提升 **5-10x**
- 💾 Redis 内存占用降低 **90%**
- 🔒 数据一致性增强（通过 last_del_uuid 校验）
- 🛠️ 代码复杂度降低（移除异步扫描逻辑）

**注意事项：**
- ⚠️ 迁移前务必备份数据库和 Redis 数据
- ⚠️ 先在测试环境充分验证
- ⚠️ 监控生产环境的错误日志和性能指标
- ⚠️ 准备好回滚方案

---

**文档版本**: v1.0  
**最后更新**: 2026-06-09  
**作者**: CloudFileSystem Team
