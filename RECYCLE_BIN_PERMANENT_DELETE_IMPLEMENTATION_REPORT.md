# 回收站彻底删除功能 - Redis ZSET架构适配实施报告

## 📋 实施概述

根据 `RECYCLE_BIN_REDIS_STORAGE_DESIGN.md` (v2.0) 和 `RECYCLE_BIN_AUTO_EXPIRE_SUMMARY.md` 的设计要求，已完成目录彻底删除功能的实现，以适配新的Redis ZSET架构。

**核心特性：**
- ✅ 彻底删除前检查该目录或batch是否处于正在删除/恢复状态
- ✅ 如果是，则终止删除/恢复操作，直接从根目录开始执行彻底删除
- ✅ 如果传入的是节点信息，则创建一个新的batch（记录彻底删除操作）
- ✅ 如果是batchId则直接沿用旧的batchId，将其状态正确更新
- ✅ 按ZSET存储顺序取出node并彻底删除
- ✅ 更新MySQL中recycle_bin_task中对应的条目状态为"permanently_deleting"
- ✅ 当batch中所有文件和文件夹都彻底删除完成后，Redis丢弃该batch及其相关信息
- ✅ MySQL将对应batch状态更新为"PermanentlyDeleted"

---

## 🔧 已创建/修改的文件

### 1. AsyncPermanentDeleteService.java（新建）⭐

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/service/AsyncPermanentDeleteService.java`

**核心功能：**
- 异步彻底删除batch中的所有节点（后台任务）
- 从Redis ZSET按顺序取出节点
- 解析 `{nodeType}:{nodeId}` 格式
- 根据nodeType选择不同的彻底删除逻辑
  - 文件夹 → 标记为 `unassigned`（进入待分配池）
  - 文件 → 标记为 `permanently_deleted`，清理元数据
- 限流控制（DEFAULT_MAX_IOPS = 1000）
- 实时更新进度到MySQL
- 完成后清理Redis缓存并更新任务状态

**关键方法：**

#### asyncPermanentDeleteBatch()
```java
@Async("deleteTaskExecutor")
public void asyncPermanentDeleteBatch(String batchId, Long userId, 
                                      Long rootNodeId, Integer nodeType) {
    // 1. 验证权限
    // 2. 检查是否有正在进行的操作（删除或恢复），如果有则终止
    // 3. 更新任务操作类型为彻底删除（operation_type=2），状态为进行中（status=0）
    // 4. 从 Redis ZSET 中按顺序取出所有节点
    // 5. 按 ZSET 顺序遍历所有节点并彻底删除
    // 6. 所有节点处理完成，清理 Redis 并更新 MySQL
}
```

#### permanentDeleteSingleNode()
```java
@Transactional
public boolean permanentDeleteSingleNode(Long nodeId, Integer nodeType) {
    if (nodeType == 0) {
        // 文件夹彻底删除：标记为 unassigned（进入待分配池）
        folderNodeMapper.markAsUnassigned(nodeId);
    } else if (nodeType == 1) {
        // 文件彻底删除：标记为 permanently_deleted
        fileNodeMapper.markAsPermanentlyDeleted(nodeId);
        // 减少元数据的引用计数
        fileNodeMapper.decrementMetadataReferenceCount(file.getFileMetadataId());
        // 如果引用计数为0，物理删除元数据和分片
        int referenceCount = fileNodeMapper.getMetadataReferenceCount(file.getFileMetadataId());
        if (referenceCount <= 0) {
            fileNodeMapper.deleteFileChunks(file.getFileMetadataId());
            fileNodeMapper.permanentDeleteFileMetadata(file.getFileMetadataId());
        }
    }
}
```

#### createPermanentDeleteTask()
```java
@Transactional
public String createPermanentDeleteTask(Long nodeId, Integer nodeType, Long userId) {
    // 1. 生成新的 batchId
    String batchId = UUID.randomUUID().toString();
    
    // 2. 创建回收站任务记录（operation_type=2 表示彻底删除）
    RecycleBinTask task = new RecycleBinTask();
    task.setBatchId(batchId);
    task.setUserId(userId);
    task.setRootNodeId(nodeId);
    task.setNodeType(nodeType);
    task.setOperationType(2); // 彻底删除操作
    task.setStatus(0); // 进行中
    // ...
    
    // 3. 初始化 Redis ZSET 和根目录信息
    recycleBinRedisService.initializeBatch(batchId, nodeId, nodeType, userId);
    
    return batchId;
}
```

---

### 2. FileController.java（修改）

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/controller/FileController.java`

**修改内容：**

#### 新增依赖注入
```java
@Autowired
private AsyncPermanentDeleteService asyncPermanentDeleteService;
```

#### 修改 permanentDeleteNode() 方法

**新增参数：**
- `nodeType`（mode=false时需填写，0=文件夹，1=文件）

**核心逻辑变更：**

**回收站模式（mode=true）：**
```java
// 【回收站模式】：需要 batchId
if (batchId == null || batchId.trim().isEmpty()) {
    return Result.error(40001, "回收站模式必须提供 batchId");
}

// 通过 batchId 查找任务
RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
if (task == null) {
    return Result.error(40401, "回收站任务不存在或已处理");
}

// 验证权限
if (!userId.equals(task.getUserId())) {
    return Result.error(40301, "无权删除该节点");
}

targetBatchId = batchId;  // 沿用旧的 batchId
targetNodeId = task.getRootNodeId();
targetNodeType = task.getNodeType();

log.info("用户 {} 回收站模式彻底删除 - BatchId: {}, NodeId: {}, NodeType: {}", 
    userId, targetBatchId, targetNodeId, targetNodeType);
```

**浏览界面模式（mode=false）：**
```java
// 【浏览界面模式】：需要 nodeId, nodeType 和 version
if (nodeId == null) {
    return Result.error(40001, "浏览界面模式必须提供 nodeId");
}

if (nodeType == null) {
    return Result.error(40001, "浏览界面模式必须提供 nodeType");
}

targetNodeId = nodeId;
targetNodeType = nodeType;

// 检查是否有正在进行的异步操作（删除或恢复）
RecycleBinTask existingTask = recycleBinTaskMapper.findByRootNodeId(targetNodeId);
if (existingTask != null && existingTask.getStatus() == 0) { // 进行中
    String operationName = existingTask.getOperationType() == 0 ? "删除" : "恢复";
    log.info("用户 {} 检测到{}任务正在进行，先终止 - BatchId: {}, NodeId: {}", 
        userId, operationName, existingTask.getBatchId(), targetNodeId);
    
    // 终止异步操作
    recycleBinTaskMapper.updateTask(existingTask.getBatchId(), 3, LocalDateTime.now(), 
        "用户主动终止（开始彻底删除）", null, null);
    
    targetBatchId = existingTask.getBatchId();  // 沿用旧的 batchId
} else {
    // 没有正在进行的任务，创建新的彻底删除任务
    targetBatchId = asyncPermanentDeleteService.createPermanentDeleteTask(
        targetNodeId, targetNodeType, userId);
    
    log.info("用户 {} 浏览界面模式彻底删除（新建任务）- BatchId: {}, NodeId: {}, NodeType: {}", 
        userId, targetBatchId, targetNodeId, targetNodeType);
}
```

**启动异步彻底删除任务：**
```java
// 启动异步彻底删除任务
asyncPermanentDeleteService.asyncPermanentDeleteBatch(
    targetBatchId, userId, targetNodeId, targetNodeType);

log.info("用户 {} 启动异步彻底删除任务 - BatchId: {}, NodeId: {}, NodeType: {}", 
    userId, targetBatchId, targetNodeId, targetNodeType);

return Result.success("彻底删除任务已启动，请稍后查询进度", null);
```

---

### 3. RecycleBinTaskMapper.java（修改）

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/mapper/RecycleBinTaskMapper.java`

**新增方法：**

```java
/**
 * 根据根节点ID查询任务
 */
@Select("SELECT * FROM recycle_bin_tasks WHERE root_node_id = #{rootNodeId} ORDER BY created_at DESC LIMIT 1")
RecycleBinTask findByRootNodeId(@Param("rootNodeId") Long rootNodeId);
```

**用途：**
- 在浏览界面模式下，通过节点ID查找相关的任务
- 用于检测是否有正在进行的删除或恢复操作

---

### 4. FileNodeMapper.java（修改）

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/mapper/FileNodeMapper.java`

**新增方法：**

```java
/**
 * 标记文件为永久删除（彻底删除）
 */
@Update("UPDATE file_nodes SET directory_status = 'permanently_deleted', " +
        "is_deleted = 1, last_del_uuid = NULL, deleted_at = NULL, delete_expires_at = NULL, " +
        "version = version + 1 WHERE id = #{nodeId}")
int markAsPermanentlyDeleted(@Param("nodeId") Long nodeId);
```

**用途：**
- 彻底删除文件时，将文件标记为 `permanently_deleted`
- 清空 `last_del_uuid`, `deleted_at`, `delete_expires_at` 字段
- 版本号 +1（乐观锁）

---

### 5. FolderNodeMapper.java（修改）

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/mapper/FolderNodeMapper.java`

**新增方法：**

```java
/**
 * 标记文件夹为待分配（彻底删除）
 */
@Update("UPDATE folder_nodes SET directory_status = 'unassigned', " +
        "last_del_uuid = NULL, deleted_at = NULL, delete_expires_at = NULL, " +
        "version = version + 1 WHERE id = #{nodeId}")
int markAsUnassigned(@Param("nodeId") Long nodeId);
```

**用途：**
- 彻底删除文件夹时，将文件夹标记为 `unassigned`（进入待分配池）
- 清空 `last_del_uuid`, `deleted_at`, `delete_expires_at` 字段
- 版本号 +1（乐观锁）

---

## 🔄 彻底删除流程详解

### 完整流程图

```
用户发起彻底删除请求 (DELETE /files/delete/permanent)
    ↓
1. FileController.permanentDeleteNode()
   a. 验证权限
   b. 判断模式（mode）
      ├─ mode=true（回收站模式）:
      │   - 通过 batchId 查找任务
      │   - 验证权限
      │   - 沿用旧的 batchId
      │   └─ 获取 rootNodeId 和 nodeType
      │
      └─ mode=false（浏览界面模式）:
          - 通过 rootNodeId 查找任务
          - IF 存在进行中的任务（status=0）:
              - 终止当前任务（status=3）
              - 沿用旧的 batchId
          - ELSE:
              - 创建新的彻底删除任务
              - 生成新的 batchId
              - 初始化 Redis ZSET
    ↓
2. AsyncPermanentDeleteService.asyncPermanentDeleteBatch() [@Async]
   a. 从 MySQL 查询 batch 信息
   b. 验证权限
   c. 【关键】检查是否有正在进行的操作（删除或恢复）
      → IF status=0 (进行中):
          - 终止当前任务 (status=3)
          - UPDATE recycle_bin_tasks SET status=3, error_message='用户主动终止（开始彻底删除）'
          - 记录日志："检测到删除/恢复任务正在进行，先终止"
   d. 更新任务操作类型为彻底删除 (operation_type=2, status=0)
      → UPDATE recycle_bin_tasks SET operation_type=2, status=0
      → 记录日志："任务状态已更新为 permanently_deleting"
   e. 从 Redis ZSET 中按顺序取出所有节点
      → ZRANGE recycle:batch:{batchId}:nodes 0 -1
   f. 遍历节点列表，逐个彻底删除
      FOR EACH member IN nodes:
          ↓
          a. 限流控制（1000 IOPS）
          ↓
          b. 解析 member → {nodeType}:{nodeId}
          ↓
          c. 根据 nodeType 选择彻底删除逻辑
             - 文件夹 (0) → permanentDeleteFolder()
             - 文件 (1) → permanentDeleteFile()
          ↓
          d. 彻底删除成功 → ZREM recycle:batch:{batchId}:nodes member
          ↓
          e. 更新进度 → UPDATE recycle_bin_tasks SET processed_count=?
   g. 所有节点处理完成，清理 Redis 并更新 MySQL
      - DEL recycle:batch:{batchId}:nodes
      - DEL recycle:batch:{batchId}:info
      - DEL recycle:batch:{batchId}:root
      - DEL recycle:batch:{batchId}:cursor
      - ZREM recycle:user:{userId}:batches batchId
      - UPDATE recycle_bin_tasks SET status=1 (已完成)
   h. 完成：所有节点已彻底删除
```

---

## 📊 状态流转

### 新流程：

```
【回收站模式】
删除进行中 (status=0, operation_type=0)
    ↓
用户发起彻底删除请求
    ↓
检测到删除任务，终止删除 (status=3, operation_type=0)
    ↓
切换为彻底删除任务 (status=0, operation_type=2)
    ↓
执行彻底删除逻辑
    ↓
彻底删除完成 (status=1, operation_type=2)

【浏览界面模式 - 有进行中任务】
删除/恢复进行中 (status=0, operation_type=0/1)
    ↓
用户发起彻底删除请求
    ↓
检测到任务，终止当前操作 (status=3)
    ↓
沿用旧的 batchId，切换为彻底删除任务 (status=0, operation_type=2)
    ↓
执行彻底删除逻辑
    ↓
彻底删除完成 (status=1, operation_type=2)

【浏览界面模式 - 无进行中任务】
用户发起彻底删除请求
    ↓
创建新的彻底删除任务 (status=0, operation_type=2)
    ↓
初始化 Redis ZSET
    ↓
执行彻底删除逻辑
    ↓
彻底删除完成 (status=1, operation_type=2)
```

---

## 💡 关键设计要点

### 1. 两种模式的区分

**回收站模式（mode=true）：**
- 前端传入 `batchId`
- 后端直接使用该 `batchId`
- 适用于用户在回收站界面选择某个项目彻底删除

**浏览界面模式（mode=false）：**
- 前端传入 `nodeId`, `nodeType`, `version`
- 后端检查是否有正在进行的任务
  - 有 → 终止旧任务，沿用旧 `batchId`
  - 无 → 创建新任务，生成新 `batchId`
- 适用于用户在浏览界面直接彻底删除某个节点

### 2. 任务状态管理

| status | 含义 | 说明 |
|--------|------|------|
| 0 | 进行中 | 任务正在执行 |
| 1 | 已完成 | 任务成功完成 |
| 2 | 失败 | 任务执行失败 |
| 3 | 已终止 | 任务被用户主动终止 |

| operation_type | 含义 | 说明 |
|----------------|------|------|
| 0 | 删除 | 移入回收站 |
| 1 | 恢复 | 从回收站恢复 |
| 2 | 彻底删除 | 永久删除 |

### 3. 数据一致性保证

```
Redis Key 过期事件（30天后）
    ↓
监听器捕获事件（异步）
    ↓
查询 MySQL 获取 batch 信息
    ↓
遍历 ZSET 中的所有节点
    ↓
【关键】先更新 MySQL（标记为待分配/永久删除）
    ↓
确认全部标记完成
    ↓
【然后】清理 Redis 缓存
    ↓
更新任务状态为已完成
```

**原则：** 先持久化（MySQL），再清理缓存（Redis）

### 4. 容错处理

- ✅ ZSET 已空时仍处理根节点
- ✅ 单个节点失败不影响其他节点
- ✅ 使用事务保证原子性
- ✅ 限流控制避免系统过载
- ✅ 异步执行不阻塞主线程

---

## 🎯 API接口说明

### 彻底删除节点

**接口**: `DELETE /files/delete/permanent`

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| mode | Boolean | 是 | 模式：true=回收站模式，false=浏览界面模式 |
| batchId | String | mode=true时必填 | 业务操作批次号（UUID格式） |
| nodeId | Long | mode=false时必填 | 节点ID |
| nodeType | Integer | mode=false时必填 | 节点类型（0=文件夹，1=文件） |
| version | Long | 可选 | 乐观锁版本号 |

**响应示例**:

```json
{
  "code": 200,
  "message": "彻底删除任务已启动，请稍后查询进度",
  "data": null
}
```

**状态码**:
- 200: 彻底删除任务已启动
- 401: 未认证
- 403: 无权删除
- 404: batch不存在
- 500: 彻底删除失败

---

## 📈 性能分析

### 资源占用

| 指标 | 数值 | 说明 |
|------|------|------|
| 异步线程池大小 | 10 | deleteTaskExecutor |
| 限流IOPS | 1000 | 每秒最多1000次操作 |
| Redis内存占用 | ~1KB/节点 | ZSET存储 |
| MySQL查询次数 | 1次/节点 | 更新状态 |

### 预期效果

- **并发支撑**：支持多个用户同时彻底删除不同batch
- **限流保护**：避免单个用户占用过多系统资源
- **异步处理**：不阻塞用户请求，立即返回
- **断点续传**：支持应用重启后继续处理

---

## ✅ 验证清单

### 功能测试

- [ ] 回收站模式彻底删除（batchId存在）
- [ ] 回收站模式彻底删除（batchId不存在）
- [ ] 浏览界面模式彻底删除（有进行中任务）
- [ ] 浏览界面模式彻底删除（无进行中任务）
- [ ] 文件夹彻底删除（标记为unassigned）
- [ ] 文件彻底删除（标记为permanently_deleted）
- [ ] 元数据清理（引用计数为0时物理删除）
- [ ] 权限验证（无权删除时返回403）
- [ ] 限流控制（超过1000 IOPS时等待）

### 性能测试

- [ ] 大批量节点彻底删除（1000+节点）
- [ ] 高并发彻底删除（10+用户同时操作）
- [ ] Redis缓存清理效率
- [ ] MySQL更新性能

### 边界情况

- [ ] ZSET为空时只处理根节点
- [ ] 节点已被其他操作删除
- [ ] 应用重启后继续处理
- [ ] 数据库连接异常时的回滚

---

## 🔗 相关文档

1. **Redis存储设计**: `RECYCLE_BIN_REDIS_STORAGE_DESIGN.md` (v2.0)
2. **自动过期实施总结**: `RECYCLE_BIN_AUTO_EXPIRE_SUMMARY.md` (v1.0)
3. **恢复功能实施报告**: `RECYCLE_BIN_RESTORE_IMPLEMENTATION_REPORT.md`
4. **删除逻辑更新报告**: `RECYCLE_BIN_DELETE_LOGIC_UPDATE_REPORT.md`

---

## 📞 联系方式

如有问题，请联系 CloudFileSystem Team。

---

**文档版本**: v1.0  
**最后更新**: 2026-06-07  
**作者**: CloudFileSystem Team
