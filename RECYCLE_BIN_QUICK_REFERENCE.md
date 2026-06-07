# 回收站系统重构 - 快速参考指南

## 🎯 核心变更概览

### 1. 数据库架构变更

#### 新增表: recycle_bin_tasks
```sql
CREATE TABLE recycle_bin_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id VARCHAR(36) NOT NULL UNIQUE COMMENT 'UUID格式批次号',
    user_id BIGINT NOT NULL,
    root_node_id BIGINT NOT NULL,
    node_type TINYINT NOT NULL COMMENT '0=文件夹, 1=文件',
    operation_type TINYINT NOT NULL COMMENT '0=删除, 1=恢复, 2=彻底删除',
    total_count INT DEFAULT 0,
    processed_count INT DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=进行中, 1=已完成, 2=失败, 3=已终止',
    error_message TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME DEFAULT NULL,
    
    INDEX idx_batch_id (batch_id),
    INDEX idx_user_status (user_id, status),
    INDEX idx_created_at (created_at)
);
```

#### 字段变更: folder_nodes & file_nodes
- ✅ 新增: `last_del_uuid VARCHAR(36)` - 最后删除/恢复批次号
- ❌ 废弃: `recycle_bin_path` - 不再使用虚拟目录路径

---

## 🔌 API 变更

### 1. 浏览回收站 `/files/recycle`

**旧版**:
```
GET /files/recycle?currentNodeId=1&lastChildrenNode=100&lastChildrenType=file&maxPageSize=20&sortedBy=2&order=1
```

**新版**:
```
GET /files/recycle?maxPageSize=20&lastBatchId=550e8400-e29b-41d4-a716-446655440000
```

**响应变化**:
```json
// 旧版响应
{
  "code": 200,
  "data": {
    "children": [...],
    "pagination": {
      "lastChildrenNode": 100,
      "lastChildrenType": "file",
      "isEnd": false
    }
  }
}

// 新版响应
{
  "code": 200,
  "data": {
    "children": [
      {
        "id": 5001,
        "name": "work.pdf",
        "type": "file",
        "size": 1048576,
        "deletedAt": "2026-05-05T10:05:00",
        "expiresAt": "2026-06-04T10:05:00",
        "daysRemaining": 30,
        "version": 2,
        "batchId": "550e8400-e29b-41d4-a716-446655440000"
      }
    ],
    "pagination": {
      "lastBatchId": "550e8400-e29b-41d4-a716-446655440000",
      "isEnd": false
    }
  }
}
```

---

### 2. 删除节点 `/files/delete`

**参数变化**:
- ❌ 移除: `sessionId` 
- ✅ 新增: `batchId` (可选，不传则后端生成 UUID)

**请求示例**:
```
POST /files/delete?nodeId=100&nodeType=0&version=5&batchId=660e8400-e29b-41d4-a716-446655440001
```

**后端行为**:
1. 创建 `recycle_bin_tasks` 记录（status=0 进行中）
2. 更新节点的 `last_del_uuid` 字段
3. 文件夹启动异步扫描，文件直接标记完成
4. 返回回收站路径和过期时间

---

### 3. 恢复节点 `/files/recycle/restore/{nodeId}`

**响应变化**（重要！）:

**场景 1: 恢复到原位置（HTTP 200）**
```json
{
  "code": 200,
  "success": true,
  "message": "恢复成功",
  "data": {
    "newName": "restored_folder",
    "nodeType": "folder",
    "restoredPath": "_root/_files/10001/document/restored_folder",
    "newVersion": 2
  }
}
```

**场景 2: 原父目录不存在，重命名后恢复到根目录（HTTP 204）**
```json
{
  "code": 204,
  "success": true,
  "message": "原父目录不存在或已删除，已恢复到用户根目录",
  "data": {
    "newName": "restored_file(3)",
    "nodeType": "file",
    "restoredPath": "_root/_files/10001/restored_file(3)",
    "newVersion": 2
  }
}
```

**新增字段说明**:
- `newName`: 恢复后的名称（可能与原名不同）
- `nodeType`: 节点类型（folder/file）
- `restoredPath`: 恢复后的完整路径
- `newVersion`: 新版本号（乐观锁）

---

### 4. 彻底删除 `/files/permanent/{nodeId}`

**新增行为**:
- 检查是否有正在进行的异步操作（status=0）
- 如果有，先终止任务（status=3）
- 然后执行物理删除
- 清理相关资源

---

## 💻 代码实现要点

### 1. 生成 batchId
```java
String batchId = UUID.randomUUID().toString();
```

### 2. 创建任务记录
```java
RecycleBinTask task = new RecycleBinTask();
task.setBatchId(batchId);
task.setUserId(userId);
task.setRootNodeId(nodeId);
task.setNodeType(nodeType);
task.setOperationType(0); // 删除
task.setStatus(0); // 进行中
recycleBinTaskMapper.insert(task);
```

### 3. 更新节点批次号
```java
folderNodeMapper.updateLastDelUuid(nodeId, batchId);
// 或
fileNodeMapper.updateLastDelUuid(nodeId, batchId);
```

### 4. 更新任务进度
```java
recycleBinTaskMapper.updateProgress(batchId, processedCount, totalCount);
```

### 5. 完成任务
```java
recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, processedCount, totalCount);
```

### 6. 终止任务
```java
recycleBinTaskMapper.updateTask(batchId, 3, LocalDateTime.now(), "用户主动终止", null, null);
```

---

## 🔍 SQL 查询示例

### 浏览回收站
```sql
SELECT 
    COALESCE(fn.id, filen.id) AS id,
    COALESCE(fn.name, filen.name) AS name,
    CASE WHEN fn.id IS NOT NULL THEN 'folder' ELSE 'file' END AS type,
    COALESCE(filen.file_size, 0) AS size,
    COALESCE(fn.deleted_at, filen.deleted_at) AS deletedAt,
    COALESCE(fn.delete_expires_at, filen.delete_expires_at) AS expiresAt,
    DATEDIFF(COALESCE(fn.delete_expires_at, filen.delete_expires_at), NOW()) AS daysRemaining,
    COALESCE(fn.version, filen.version) AS version,
    rbt.batch_id AS batchId
FROM recycle_bin_tasks rbt
LEFT JOIN folder_nodes fn ON rbt.root_node_id = fn.id AND rbt.node_type = 0
    AND fn.directory_status = 'in_recycle_bin'
LEFT JOIN file_nodes filen ON rbt.root_node_id = filen.id AND rbt.node_type = 1
    AND filen.directory_status = 'in_recycle_bin'
WHERE rbt.user_id = :userId
  AND rbt.operation_type = 0
  AND rbt.status IN (0, 1)
ORDER BY rbt.created_at DESC
LIMIT :maxPageSize;
```

### 游标分页
```sql
-- lastBatchId 不为空时
AND rbt.created_at < (
    SELECT created_at FROM recycle_bin_tasks 
    WHERE batch_id = :lastBatchId AND user_id = :userId
)
```

---

## ⚠️ 注意事项

### 1. 并发控制
- 所有节点更新必须使用乐观锁（version 字段）
- 删除/恢复前校验版本号
- 版本冲突返回 HTTP 409

### 2. 重名处理
- 恢复时如果原位置不可用，需生成唯一文件名
- 格式: `original_name(counter).ext`
- 计数器从 1 开始递增，超过 1000 则使用 UUID

### 3. 特殊字符转义
```java
private String sanitizeFileName(String fileName) {
    return fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
}
```

### 4. 限流控制
- 异步删除时使用滑动窗口限流器
- 默认: 60秒窗口，最大 IOPS 根据配置调整
- 使用指数退避策略重试

### 5. 事务管理
- 删除/恢复操作必须在事务中执行
- 异步任务中的进度更新也需要事务
- 避免长事务，及时提交

---

## 🧪 测试用例

### 1. 删除文件夹
```java
@Test
void testDeleteFolder() {
    String batchId = UUID.randomUUID().toString();
    DeleteNodeResponse response = directoryService.deleteNodeWithBatchId(
        folderId, 0, userId, version, batchId
    );
    
    assertNotNull(response);
    assertNotNull(response.getRecycleBinPath());
    assertNotNull(response.getExpiresAt());
    
    // 验证任务记录
    RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
    assertNotNull(task);
    assertEquals(0, task.getStatus()); // 进行中
}
```

### 2. 恢复到原位置
```java
@Test
void testRestoreToOriginalLocation() {
    RestoreResult result = directoryService.restoreNodeWithNewFormat(nodeId, userId);
    
    assertEquals(200, result.getCode());
    assertEquals("恢复成功", result.getMessage());
    assertEquals("folder", result.getData().getNodeType());
    assertNotNull(result.getData().getRestoredPath());
}
```

### 3. 恢复并重命名
```java
@Test
void testRestoreWithRename() {
    // 先删除父目录，使原位置不可用
    deleteParentFolder(parentId);
    
    RestoreResult result = directoryService.restoreNodeWithNewFormat(nodeId, userId);
    
    assertEquals(204, result.getCode());
    assertTrue(result.getMessage().contains("已恢复到用户根目录"));
    assertTrue(result.getData().getNewName().matches(".+\\(\\d+\\).*"));
}
```

### 4. 游标分页
```java
@Test
void testBrowseRecycleBinWithCursor() {
    // 第一页
    RecycleBinBrowseResponse page1 = recycleBinService.browseRecycleBin(userId, 20, null);
    assertFalse(page1.getChildren().isEmpty());
    
    String lastBatchId = page1.getPagination().getLastBatchId();
    
    // 第二页
    RecycleBinBrowseResponse page2 = recycleBinService.browseRecycleBin(userId, 20, lastBatchId);
    
    // 验证没有重复数据
    Set<Long> ids1 = page1.getChildren().stream().map(RecycleBinItemDTO::getId).collect(Collectors.toSet());
    Set<Long> ids2 = page2.getChildren().stream().map(RecycleBinItemDTO::getId).collect(Collectors.toSet());
    assertTrue(Collections.disjoint(ids1, ids2));
}
```

---

## 📊 性能指标

### 预期性能
- 浏览回收站: < 100ms (1000条记录以内)
- 删除文件: < 50ms
- 删除文件夹: < 100ms (根节点) + 异步扫描
- 恢复节点: < 100ms
- 彻底删除: < 200ms

### 优化建议
1. **索引优化**:
   ```sql
   ALTER TABLE recycle_bin_tasks ADD INDEX idx_user_operation_created (user_id, operation_type, created_at);
   ALTER TABLE folder_nodes ADD INDEX idx_status_deleted (directory_status, is_deleted, deleted_at);
   ALTER TABLE file_nodes ADD INDEX idx_status_deleted (directory_status, is_deleted, deleted_at);
   ```

2. **缓存策略**:
   - Redis 缓存用户回收站列表（TTL=5分钟）
   - 缓存热点节点的元数据

3. **批量操作**:
   - 大批量删除时使用分批处理（每批 100 个节点）
   - 异步任务使用线程池隔离

---

## 🔗 相关文件

### 已创建
- `RecycleBinTask.java` - 实体类
- `RecycleBinTaskMapper.java` - Mapper 接口
- `RecycleBinTaskMapper.xml` - MyBatis XML
- `RecycleBinItemDTO.java` - DTO
- `RecycleBinBrowseResponse.java` - 响应 DTO
- `RestoreResult.java` - 恢复结果
- `RestoreData.java` - 恢复数据
- `RecycleBinService.java` - 服务类
- `RECYCLE_BIN_REFACTORING_TODO.md` - 待完成任务清单

### 需要修改
- `FileController.java` - ✅ 已完成浏览回收站接口
- `DirectoryService.java` - 待添加新方法
- `FolderNodeMapper.java` - 待添加辅助方法
- `FileNodeMapper.java` - 待添加辅助方法
- `AsyncDirectoryDeleteService.java` - 待支持 batchId

---

**文档版本**: v1.0  
**最后更新**: 2026-06-05  
**状态**: 部分完成（浏览功能已完成，删除/恢复/彻底删除待实施）

