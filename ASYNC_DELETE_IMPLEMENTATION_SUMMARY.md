# 目录后台异步删除实现总结

## 概述

本文档总结了目录节点后台异步删除功能的完整实现，包括滑动窗口限流器、异步任务管理、会话追踪等核心组件。

---

## 实现的功能

### ✅ 1. 滑动窗口限流器

- **Lua 脚本**: `src/main/resources/lua/sliding_window_rate_limiter.lua`
- **服务接口**: `RateLimiterService.java`
- **服务实现**: `RedisSlidingWindowRateLimiter.java`
- **特性**:
  - 基于 Redis ZSet 的原子操作
  - 可配置的 IOPS 限制（默认 1000）
  - 自动清理过期数据
  - 支持阻塞和非阻塞两种模式

### ✅ 2. 异步任务配置

- **配置文件**: `AsyncConfig.java`
- **线程池**:
  - `deleteTaskExecutor`: 删除任务专用（核心10/最大20/队列100）
  - `restoreTaskExecutor`: 恢复任务专用（核心5/最大10/队列50）
- **特性**:
  - 独立线程池隔离
  - 优雅关闭支持
  - CallerRunsPolicy 拒绝策略

### ✅ 3. 删除会话管理

- **DTO**: `DeleteSession.java`
- **服务**: `DeleteSessionService.java`
- **存储**: Redis ZSet (`delete_sessions:{userId}`)
- **特性**:
  - 实时追踪删除进度
  - 自动清理过期会话（24小时）
  - 支持状态更新（running/completed/failed）

### ✅ 4. 异步删除服务

- **服务**: `AsyncDirectoryDeleteService.java`
- **功能**:
  - 递归删除文件夹及其所有子节点
  - 分批处理（每批100个节点）
  - 限流控制（集成滑动窗口限流器）
  - 进度更新到 Redis

### ✅ 5. Controller 层修改

- **文件**: `FileController.java`
- **修改**:
  - 新增 `sessionId` 参数（可选）
  - 自动生成 sessionId（如果前端未传）
  - 传递 sessionId 到 Service 层

### ✅ 6. Service 层修改

- **文件**: `DirectoryService.java`
- **修改**:
  - `deleteNode` 方法新增 `sessionId` 参数
  - 文件夹删除改为两步：
    1. 立即标记根目录为删除状态
    2. 启动异步任务递归删除子节点
  - 文件删除保持同步（直接标记删除）

### ✅ 7. Mapper 层扩展

- **FolderNodeMapper.java**:
  - `findChildren(parentId)`: 查询直接子文件夹
  - `findAllDescendants(folderId)`: 递归查询所有后代文件夹
  
- **FileNodeMapper.java**:
  - `findActiveChildren(folderId)`: 查询文件夹中的活跃文件

---

## 数据流程

### 文件夹删除流程

```
用户发起删除请求
    ↓
FileController.deleteNode(nodeId, sessionId)
    ↓
DirectoryService.deleteNode(nodeId, userId, sessionId)
    ↓
1. 验证权限
2. 计算回收站路径和过期时间
3. 立即标记根目录为删除状态（softDeleteFolderRoot）
    ↓
4. 启动异步任务 asyncDirectoryDeleteService.asyncDeleteFolder()
    ↓
5. 立即返回响应 {recycleBinPath, expiresAt}
    ↓
【后台异步执行】
    ↓
AsyncDirectoryDeleteService.asyncDeleteFolder()
    ↓
1. 创建删除会话（存入 Redis）
2. 统计需要删除的节点总数
3. 递归删除子文件夹（带限流控制）
   - 每次操作前调用 rateLimiterService.acquireWithBackoff()
   - 每10个节点更新一次进度
4. 批量删除子文件（带限流控制）
5. 更新会话状态为 completed
    ↓
定时任务清理过期会话（每小时执行）
```

### 文件删除流程（保持同步）

```
用户发起删除请求
    ↓
FileController.deleteNode(nodeId, sessionId)
    ↓
DirectoryService.deleteNode(nodeId, userId, sessionId)
    ↓
1. 验证权限
2. 计算回收站路径和过期时间
3. 标记文件为删除状态（softDeleteFile）
    ↓
4. 立即返回响应 {recycleBinPath, expiresAt}
```

---

## 关键代码示例

### 1. Lua 脚本调用

```java
@Override
public boolean tryAcquire(String key, int maxIops) {
    long now = System.currentTimeMillis();
    
    Long result = stringRedisTemplate.execute(
        rateLimiterScript,
        Collections.singletonList(key),
        String.valueOf(now),
        String.valueOf(DEFAULT_WINDOW_SIZE_MS),
        String.valueOf(maxIops)
    );
    
    return result != null && result == 1L;
}
```

### 2. 异步删除任务

```java
@Async("deleteTaskExecutor")
public void asyncDeleteFolder(Long folderId, String sessionId, Long userId, 
                               String recycleBinPath, LocalDateTime expiresAt) {
    // 1. 创建删除会话
    DeleteSession session = new DeleteSession();
    session.setSessionId(sessionId);
    session.setStatus("running");
    deleteSessionService.createSession(session);
    
    // 2. 递归删除子节点（带限流）
    String rateLimitKey = "rate_limit:delete:" + userId;
    rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
    
    // 执行删除操作...
    
    // 3. 更新会话状态
    deleteSessionService.updateSessionStatus(userId, sessionId, "completed", 
        processedNodes, totalNodes, null);
}
```

### 3. 限流控制

```java
// 在删除每个节点前进行限流检查
for (FolderNode childFolder : childFolders) {
    // 限流控制（阻塞直到成功）
    String rateLimitKey = "rate_limit:delete:" + userId;
    try {
        rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("删除任务被中断", e);
    }
    
    // 执行删除操作...
}
```

---

## 配置说明

### application.yaml

```yaml
# 已有的 Directo Redis 配置（无需修改）
directo:
  redis:
    host: localhost
    port: 6381
    delete:
      database: 0
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 5
    restore:
      database: 1
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 5
```

### 限流阈值调整

在 `AsyncDirectoryDeleteService.java` 中修改常量：

```java
// 默认 IOPS 限制：每秒最多1000次操作
private static final int DEFAULT_MAX_IOPS = 1000;

// 批处理大小：每批处理的节点数
private static final int BATCH_SIZE = 100;
```

---

## API 接口变更

### DELETE /files/delete

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| nodeId | Long | ✅ | 节点ID |
| sessionId | String | ❌ | 会话ID（后端自动生成） |

**响应示例**:

```json
{
  "code": 200,
  "success": true,
  "message": "已移入回收站，30天后彻底删除",
  "data": {
    "recycleBinPath": "_root/_recycle_bin/10001/deleted_folder_1717387800000",
    "expiresAt": "2026-07-04T10:00:00"
  }
}
```

---

## Redis 数据结构

### 1. 删除会话

**Key**: `delete_sessions:{userId}`  
**Type**: ZSet  
**Member**: JSON 字符串（DeleteSession 对象）  
**Score**: 时间戳（毫秒）

```json
{
  "sessionId": "sess_del_1717387800000_abc123",
  "nodeId": 12345,
  "nodeType": 0,
  "userId": 10001,
  "startTime": "2026-06-05T10:30:00",
  "status": "running",
  "totalNodes": 150,
  "processedNodes": 45,
  "recycleBinPath": "_root/_recycle_bin/10001/folder_name",
  "expiresAt": "2026-07-04T10:30:00"
}
```

### 2. 限流计数

**Key**: `rate_limit:delete:{userId}`  
**Type**: ZSet  
**Member**: `{timestamp}:{random}`  
**Score**: 时间戳（毫秒）

---

## 监控与日志

### 关键日志点

```java
// 1. 删除开始
log.info("[异步删除] 开始 - FolderId: {}, SessionId: {}, UserId: {}", ...);

// 2. 节点统计
log.info("[异步删除] 待删除节点总数: {}", totalNodes);

// 3. 删除完成
log.info("[异步删除] 完成 - FolderId: {}, Duration: {}ms, Processed: {}/{}", ...);

// 4. 删除失败
log.error("[异步删除] 失败 - FolderId: {}, SessionId: {}, Duration: {}ms", ...);

// 5. 会话清理
log.info("[删除会话] 清理过期会话 - Key: {}, Count: {}", key, removed);
```

### Prometheus 指标（可选扩展）

```java
// 删除操作总数
meterRegistry.counter("delete.operations.total").increment();

// 删除操作耗时
meterRegistry.timer("delete.operations.duration")
    .record(duration, TimeUnit.MILLISECONDS);

// 限流触发次数
meterRegistry.counter("rate_limiter.rejected").increment();
```

---

## 测试建议

### 1. 单元测试

```java
@Test
public void testAsyncFolderDeletion() throws InterruptedException {
    // 1. 创建测试文件夹（包含子节点）
    Long folderId = createTestFolderWithChildren();
    
    // 2. 生成 sessionId
    String sessionId = "sess_del_" + System.currentTimeMillis();
    
    // 3. 发起删除请求
    DeleteNodeResponse response = directoryService.deleteNode(folderId, userId, sessionId);
    
    // 4. 验证根目录已标记删除
    assertNotNull(response.getRecycleBinPath());
    
    // 5. 等待异步删除完成
    Thread.sleep(5000);
    
    // 6. 验证会话状态为 completed
    // （需要实现查询会话状态的方法）
}
```

### 2. 压力测试

```bash
# 使用 JMeter 或 wrk 模拟并发删除请求
wrk -t 10 -c 100 -d 60s \
  -H "Authorization: Bearer {token}" \
  "http://localhost:8080/files/delete?nodeId={id}&sessionId=sess_test"
```

---

## 性能优化建议

### 1. 调整线程池参数

根据服务器配置调整 `AsyncConfig.java`：

```java
// 高配服务器（16核/32GB）
executor.setCorePoolSize(20);
executor.setMaxPoolSize(40);
executor.setQueueCapacity(200);

// 低配服务器（4核/8GB）
executor.setCorePoolSize(5);
executor.setMaxPoolSize(10);
executor.setQueueCapacity(50);
```

### 2. 调整批处理大小

```java
// 大文件夹（>1000个子节点）
private static final int BATCH_SIZE = 200;

// 小文件夹（<100个子节点）
private static final int BATCH_SIZE = 50;
```

### 3. Redis 连接池优化

```yaml
directo:
  redis:
    delete:
      lettuce:
        pool:
          max-active: 100  # 高并发场景增加到 100
          max-idle: 50
          min-idle: 10
```

---

## 注意事项

### ⚠️ 1. 事务边界

- `deleteNode` 方法上的 `@Transactional` 仅保证根目录标记删除的原子性
- 异步删除操作在独立事务中执行，不影响主请求

### ⚠️ 2. 异常处理

- 异步任务的异常不会传播到 Controller 层
- 需要通过查询会话状态来判断删除是否成功

### ⚠️ 3. 内存泄漏防护

- Redis ZSet 会自动清理过期数据（通过 EXPIRE 命令）
- 定时任务每小时清理 24 小时前的会话记录

### ⚠️ 4. 分布式环境

- 所有实例共享同一个 Redis 实例，限流全局生效
- 异步任务可能在任意实例上执行，确保所有实例都能访问数据库

---

## 后续扩展

### 📌 1. 前端集成

前端可以轮询查询删除会话状态：

```javascript
// 伪代码
const checkDeleteProgress = async (sessionId) => {
  const response = await fetch(`/files/delete/status/${sessionId}`);
  const { status, processedNodes, totalNodes } = await response.json();
  
  if (status === 'running') {
    console.log(`删除进度: ${processedNodes}/${totalNodes}`);
    setTimeout(() => checkDeleteProgress(sessionId), 1000);
  } else if (status === 'completed') {
    console.log('删除完成');
  } else {
    console.error('删除失败');
  }
};
```

### 📌 2. 添加查询接口

```java
@GetMapping("/delete/status/{sessionId}")
public Result<DeleteSession> getDeleteStatus(@PathVariable String sessionId) {
    Long userId = SecurityUtils.getCurrentUserId();
    DeleteSession session = deleteSessionService.getSession(userId, sessionId);
    return Result.success(session);
}
```

### 📌 3. 批量删除支持

```java
@PostMapping("/delete/batch")
public Result<Void> batchDelete(@RequestBody List<Long> nodeIds) {
    for (Long nodeId : nodeIds) {
        String sessionId = generateSessionId();
        directoryService.deleteNode(nodeId, userId, sessionId);
    }
    return Result.success(null);
}
```

---

## 文件清单

### 新增文件

1. `src/main/resources/lua/sliding_window_rate_limiter.lua` - Lua 限流脚本
2. `src/main/java/com/mizuka/cloudfilesystem/service/RateLimiterService.java` - 限流器接口
3. `src/main/java/com/mizuka/cloudfilesystem/service/RedisSlidingWindowRateLimiter.java` - 限流器实现
4. `src/main/java/com/mizuka/cloudfilesystem/config/AsyncConfig.java` - 异步任务配置
5. `src/main/java/com/mizuka/cloudfilesystem/dto/DeleteSession.java` - 删除会话 DTO
6. `src/main/java/com/mizuka/cloudfilesystem/service/DeleteSessionService.java` - 会话管理服务
7. `src/main/java/com/mizuka/cloudfilesystem/service/AsyncDirectoryDeleteService.java` - 异步删除服务
8. `LUA_RATE_LIMITER_GUIDE.md` - Lua 脚本使用指南
9. `ASYNC_DELETE_IMPLEMENTATION_SUMMARY.md` - 本文档

### 修改文件

1. `src/main/java/com/mizuka/cloudfilesystem/controller/FileController.java` - 新增 sessionId 参数
2. `src/main/java/com/mizuka/cloudfilesystem/service/DirectoryService.java` - 修改 deleteNode 方法
3. `src/main/java/com/mizuka/cloudfilesystem/mapper/FolderNodeMapper.java` - 新增查询方法
4. `src/main/java/com/mizuka/cloudfilesystem/mapper/FileNodeMapper.java` - 新增查询方法

---

## 联系方式

如有疑问或需要技术支持，请联系后端开发团队。

**文档版本**: v1.0  
**最后更新**: 2026-06-05
