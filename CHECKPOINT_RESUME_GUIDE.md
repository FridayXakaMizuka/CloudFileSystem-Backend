# 断点续传功能说明

## 问题背景

在实际执行异步删除时，如果遇到以下情况：
- 服务重启
- 数据库连接中断
- 线程池满导致任务被拒绝
- 其他异常

**原实现的问题**：删除任务会从头开始重新遍历整个目录树，已删除的节点虽然不会被重复删除（因为有 `is_deleted=1` 过滤），但会浪费大量时间重新遍历。

---

## 解决方案：游标持久化

### ✅ 已实现的改进

#### 1. **会话中增加游标字段**

在 `DeleteSession.java` 中新增：

```java
/**
 * 当前处理的父文件夹ID（用于断点续传）
 */
private Long currentParentId;

/**
 * 最后处理的子节点ID（用于断点续传）
 */
private Long lastProcessedNodeId;
```

#### 2. **每次处理节点后更新游标**

在 `AsyncDirectoryDeleteService.java` 中：

```java
// 每处理一个节点，立即更新游标位置
deleteSessionService.updateSessionWithCursor(userId, sessionId, "running", 
    processedNodes, totalNodes, null, parentFolderId, childFolder.getId());
```

#### 3. **任务启动时检查断点**

```java
// 从 Redis 恢复会话
DeleteSession existingSession = deleteSessionService.getSession(userId, sessionId);

if (existingSession != null && existingSession.getCurrentParentId() != null) {
    resumeFromParentId = existingSession.getCurrentParentId();
    resumeFromNodeId = existingSession.getLastProcessedNodeId();
    log.info("[异步删除] 检测到断点，从 ParentId={}, LastNodeId={} 继续", 
        resumeFromParentId, resumeFromNodeId);
}
```

#### 4. **遍历时跳过已处理节点**

```java
boolean shouldResume = (resumeFromParentId != null && resumeFromParentId.equals(parentFolderId));

for (FolderNode childFolder : childFolders) {
    // 断点续传：跳过已处理的节点
    if (shouldResume && resumeFromNodeId != null && childFolder.getId() <= resumeFromNodeId) {
        log.debug("[断点续传] 跳过已处理节点 - NodeId: {}", childFolder.getId());
        continue;  // 跳过，不重复处理
    }
    
    // 处理新节点...
}
```

---

## 工作原理

### 正常流程

```
开始删除文件夹 A
    ↓
处理子节点 B → 更新游标 (currentParentId=A, lastProcessedNodeId=B)
    ↓
处理子节点 C → 更新游标 (currentParentId=A, lastProcessedNodeId=C)
    ↓
处理子节点 D → 更新游标 (currentParentId=A, lastProcessedNodeId=D)
    ↓
完成
```

### 异常中断后恢复

```
删除到子节点 C 时发生异常
    ↓
Redis 中游标状态: (currentParentId=A, lastProcessedNodeId=C)
    ↓
【服务重启或重试】
    ↓
读取游标，发现 lastProcessedNodeId=C
    ↓
遍历子节点时：
  - B: ID <= C，跳过 ✓
  - C: ID <= C，跳过 ✓
  - D: ID > C，继续处理 ✓
    ↓
从 D 开始继续删除
```

---

## Redis 数据结构示例

### 删除会话（带游标）

```json
{
  "sessionId": "sess_del_1717387800000_abc123",
  "nodeId": 12345,
  "nodeType": 0,
  "userId": 10001,
  "startTime": "2026-06-05T10:30:00",
  "status": "running",
  "totalNodes": 1500,
  "processedNodes": 450,
  "currentParentId": 12345,        // ← 新增：当前正在处理的父文件夹
  "lastProcessedNodeId": 12567,    // ← 新增：最后处理的子节点ID
  "recycleBinPath": "_root/_recycle_bin/10001/folder_a",
  "expiresAt": "2026-07-04T10:30:00"
}
```

---

## 优势

### ✅ 1. 避免重复遍历

- **原实现**：中断后重新遍历所有节点（即使已删除）
- **新实现**：直接从断点继续，跳过已处理节点

### ✅ 2. 减少数据库压力

- **原实现**：重复查询相同的子节点列表
- **新实现**：只查询未处理的节点

### ✅ 3. 提高可靠性

- **原实现**：大文件夹删除失败后需要很长时间重试
- **新实现**：快速从断点恢复，缩短总耗时

### ✅ 4. 精确进度追踪

- **原实现**：只知道处理了多少节点
- **新实现**：知道具体处理到哪个节点，便于调试

---

## 注意事项

### ⚠️ 1. 节点ID必须递增

断点续传依赖 `childFolder.getId() <= resumeFromNodeId` 判断，要求：
- 数据库使用自增ID
- 查询时按 `ORDER BY id ASC` 排序

✅ 当前实现已满足：

```java
@Select("SELECT * FROM folder_nodes " +
        "WHERE parent_id = #{parentId} " +
        "AND is_deleted = 0 " +
        "ORDER BY id ASC")  // ← 按ID升序
List<FolderNode> findChildren(@Param("parentId") Long parentId);
```

### ⚠️ 2. 并发安全

如果多个实例同时处理同一个 sessionId，可能导致游标冲突。

**解决方案**：
- 确保每个 sessionId 只在一个实例上执行
- 或使用分布式锁（当前未实现，因为 Spring @Async 默认单机）

### ⚠️ 3. 游标更新频率

当前实现：**每处理一个节点就更新一次游标**

**优点**：断点精度高，最多重做1个节点  
**缺点**：频繁写入 Redis

**优化建议**（可选）：

```java
// 每10个节点更新一次游标
if (processedNodes % 10 == 0) {
    deleteSessionService.updateSessionWithCursor(...);
}
```

---

## 测试场景

### 场景 1：正常完成

```bash
# 删除包含1000个子节点的文件夹
DELETE /files/delete?nodeId=12345&sessionId=sess_test_001

# 观察日志
[异步删除] 开始 - FolderId: 12345
[异步删除] 待删除节点总数: 1000
[异步删除] 完成 - FolderId: 12345, Duration: 5000ms, Processed: 1000/1000
```

### 场景 2：中断后恢复

```bash
# 1. 开始删除
DELETE /files/delete?nodeId=12345&sessionId=sess_test_002

# 2. 在处理到第500个节点时重启服务

# 3. 服务重启后，相同 sessionId 再次触发
[异步删除] 检测到断点，从 ParentId=12345, LastNodeId=12567 继续
[断点续传] 跳过已处理节点 - NodeId: 12346
[断点续传] 跳过已处理节点 - NodeId: 12347
...
[异步删除] 完成 - FolderId: 12345, Duration: 3000ms, Processed: 1000/1000

# 总耗时 = 第一次的耗时 + 第二次的耗时（比从头开始快很多）
```

### 场景 3：查看游标状态

```bash
redis-cli -p 6381

# 查看会话详情
ZRANGE delete_sessions:10001 0 -1

# 输出示例（格式化后）
{
  "sessionId": "sess_test_002",
  "processedNodes": 500,
  "currentParentId": 12345,
  "lastProcessedNodeId": 12567
}
```

---

## 性能对比

### 假设场景

- 文件夹包含 10,000 个子节点
- 删除到第 5,000 个节点时中断

| 指标 | 原实现（无断点） | 新实现（有断点） |
|------|-----------------|-----------------|
| 重试时遍历节点数 | 10,000 | 5,000 |
| 重试时数据库查询 | 10,000次 | 5,000次 |
| 重试耗时 | ~10秒 | ~5秒 |
| Redis 写入次数 | 0 | 5,000次 |

**结论**：虽然增加了 Redis 写入开销，但大幅减少了重试时间，整体性能提升明显。

---

## 未来扩展

### 📌 1. 批量更新游标

```java
// 可选：每 N 个节点更新一次，减少 Redis 压力
private static final int CURSOR_UPDATE_INTERVAL = 10;

if (processedNodes % CURSOR_UPDATE_INTERVAL == 0) {
    deleteSessionService.updateSessionWithCursor(...);
}
```

### 📌 2. 支持并行删除

```java
// 将子节点分成多个批次，并行处理
List<List<FolderNode>> batches = partition(childFolders, batchSize);
batches.parallelStream().forEach(batch -> {
    deleteBatch(batch, sessionId, userId);
});
```

### 📌 3. 添加取消接口

```java
@PostMapping("/delete/cancel/{sessionId}")
public Result<Void> cancelDelete(@PathVariable String sessionId) {
    deleteSessionService.updateSessionStatus(userId, sessionId, "cancelled", ...);
    return Result.success(null);
}
```

---

## 总结

✅ **已实现**：基于游标的断点续传机制  
✅ **优势**：避免重复遍历，提高重试效率  
✅ **可靠性**：每次处理节点后立即更新游标  
⚠️ **注意**：依赖节点ID递增和有序查询  

**适用场景**：
- 大文件夹删除（>1000个子节点）
- 网络不稳定环境
- 需要高可靠性的生产环境

---

**文档版本**: v1.0  
**最后更新**: 2026-06-05
