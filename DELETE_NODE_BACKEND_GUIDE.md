# 删除节点后端 Redis 配置与实现指南

## 概述

本文档描述了文件/文件夹删除操作的后端实现方案，包括 Redis 缓存配置、会话管理、限流控制和异步删除机制。

---

## 核心设计

### 1. 架构思路

- **异步删除**：删除操作立即返回，后端在后台异步执行实际的删除任务
- **会话追踪**：每个删除请求分配唯一的 `sessionId`，用于标识和追踪删除进程
- **Redis 缓存**：使用 Redis ZSet 存储删除会话信息，支持快速查询和自动清理
- **限流控制**：全局限制并发 I/O 操作（约 1000 IOPS），防止系统过载
- **分级处理**：
  - **文件**：直接标记删除，立即移入回收站
  - **文件夹**：先标记根目录删除，返回成功后再异步递归删除子节点

### 2. 技术栈

- **Redis ZSet**：存储删除会话，score 为时间戳，member 为会话信息 JSON
- **独立 SessionId**：格式 `sess_del_{timestamp}_{random}`，唯一标识每个删除请求
- **后台线程池**：处理异步删除任务，避免阻塞主请求
- **Lettuce 连接池**：高性能 Redis 客户端，支持连接池管理

---

## Redis 配置

### 1. 配置文件（application.yaml）

```yaml
directo:
  redis:
    # 公用 Redis 配置
    host: localhost
    port: 6381
    # 删除操作专用 Redis 配置（database 0）
    delete:
      database: 0
      lettuce:
        pool:
          max-active: 50    # 最大活跃连接数
          max-idle: 20      # 最大空闲连接数
          min-idle: 5       # 最小空闲连接数
    
    # 恢复操作专用 Redis 配置（database 1）
    restore:
      database: 1
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 5
```

### 2. 配置说明

| 配置项 | 说明                | 推荐值 |
|--------|-------------------|--------|
| `host` | Redis 服务器地址       | localhost / 内网IP |
| `port` | Redis 端口号         | 6381（自定义） |
| `password` | Redis 访问密码（暂时无密码） | 强密码 |
| `database` | Redis 数据库编号       | 0（删除）/ 1（恢复） |
| `max-active` | 最大活跃连接数           | 50（根据并发调整） |
| `max-idle` | 最大空闲连接数           | 20 |
| `min-idle` | 最小空闲连接数           | 5 |

### 3. 为什么分离 database？

- **隔离性**：删除和恢复操作互不干扰
- **性能优化**：避免键空间冲突，提高查询效率
- **独立清理策略**：可以针对不同操作设置不同的过期时间
- **监控粒度**：可以分别统计删除和恢复的 QPS、命中率等指标

---

## API 接口规范

### 1. 删除节点

**接口**: `DELETE /files/delete?nodeId={nodeId}&nodeType={nodeType}&sessionId={sessionId}`

**功能**: 软删除节点，移入回收站（30天后彻底删除）

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `nodeId` | Long | ✅ | 节点ID（文件或文件夹） |
| `nodeType` | Integer | ✅ | 节点类型（0=文件夹，1=文件） |
| `sessionId` | String | ✅ | 会话ID（用于后端唯一标识删除请求） |

#### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "已移入回收站，30天后彻底删除",
  "data": {
    "recycleBinPath": "_root/_recycle_bin/10001/deleted_folder_001",
    "expiresAt": "2026-06-04T10:00:00"
  }
}
```

#### 后端处理流程

```java
@DeleteMapping("/files/delete")
public ResponseEntity<ApiResponse<DeleteResult>> deleteNode(
    @RequestParam Long nodeId,
    @RequestParam Integer nodeType,
    @RequestParam String sessionId,
    @RequestHeader("Authorization") String token) {
    
    Long userId = JwtUtil.extractUserId(token);
    
    // 1. 验证权限
    if (!nodeService.hasPermission(userId, nodeId)) {
        return ResponseEntity.status(403).body(ApiResponse.error("权限不足"));
    }
    
    // 2. 生成删除会话信息
    DeleteSession session = new DeleteSession();
    session.setSessionId(sessionId);
    session.setNodeId(nodeId);
    session.setNodeType(nodeType);
    session.setUserId(userId);
    session.setStartTime(Instant.now());
    session.setStatus("running");
    
    // 3. 存入 Redis
    String redisKey = "delete_sessions:" + userId;
    long score = System.currentTimeMillis();
    String member = JSON.toJSONString(session);
    redisTemplate.opsForZSet().add(redisKey, member, score);
    
    // 4. 立即标记节点为删除状态（移入回收站）
    Node node = nodeRepository.findById(nodeId);
    node.setDeleted(true);
    node.setDeletedAt(Instant.now());
    node.setRecycleBinPath(generateRecycleBinPath(userId, node));
    node.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
    nodeRepository.save(node);
    
    // 5. 如果是文件夹，启动异步递归删除子节点
    if (nodeType == 0) { // folder
        deleteService.asyncDeleteFolder(nodeId, sessionId, userId);
    }
    
    // 6. 立即返回成功响应
    DeleteResult result = new DeleteResult();
    result.setRecycleBinPath(node.getRecycleBinPath());
    result.setExpiresAt(node.getExpiresAt());
    
    return ResponseEntity.ok(ApiResponse.success(result));
}
```

---

## 异步删除实现

### 1. 文件夹递归删除（后台任务）

```java
@Async("deleteExecutor")
public void asyncDeleteFolder(Long folderId, String sessionId, Long userId) {
    try {
        log.info("开始异步删除文件夹: folderId={}, sessionId={}", folderId, sessionId);
        
        // 1. 获取所有子节点（递归查询）
        List<Node> children = nodeRepository.findAllChildren(folderId);
        
        log.info("找到 {} 个子节点待删除", children.size());
        
        // 2. 分批处理（每批 100 个节点）
        int batchSize = 100;
        int totalProcessed = 0;
        
        for (int i = 0; i < children.size(); i += batchSize) {
            // 限流控制
            ioSemaphore.acquire();
            
            try {
                List<Node> batch = children.subList(
                    i, 
                    Math.min(i + batchSize, children.size())
                );
                
                // 批量标记删除
                for (Node child : batch) {
                    child.setDeleted(true);
                    child.setDeletedAt(Instant.now());
                    child.setRecycleBinPath(generateRecycleBinPath(userId, child));
                    child.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
                }
                
                nodeRepository.saveAll(batch);
                totalProcessed += batch.size();
                
                log.info("已处理 {}/{} 个子节点", totalProcessed, children.size());
                
            } finally {
                ioSemaphore.release();
            }
        }
        
        // 3. 更新删除会话状态为完成
        updateSessionStatus(sessionId, "completed");
        
        log.info("文件夹删除完成: folderId={}, totalProcessed={}", folderId, totalProcessed);
        
    } catch (Exception e) {
        log.error("异步删除文件夹失败: folderId={}, sessionId={}", folderId, sessionId, e);
        updateSessionStatus(sessionId, "failed", e.getMessage());
    }
}
```

### 2. 限流控制

```java
@Configuration
public class DeleteConfig {
    
    // 全局 I/O 信号量，限制并发数为 1000
    @Bean
    public Semaphore ioSemaphore() {
        return new Semaphore(1000);
    }
    
    // 删除任务线程池
    @Bean(name = "deleteExecutor")
    public Executor deleteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("delete-async-");
        executor.initialize();
        return executor;
    }
}
```

---

## Redis 数据结构设计

### 1. 键名规范

```
delete_sessions:{userId}
```

示例：`delete_sessions:10001`

### 2. ZSet 结构

- **Key**: `delete_sessions:10001`
- **Member**: JSON 字符串（删除会话信息）
- **Score**: 开始时间戳（用于排序和清理）

```redis
ZADD delete_sessions:10001 1717387800 "{
  \"sessionId\": \"sess_del_1717387800000_abc123\",
  \"nodeId\": 12345,
  \"nodeType\": 0,
  \"userId\": 10001,
  \"startTime\": \"2026-06-03T10:30:00Z\",
  \"status\": \"running\",
  \"totalNodes\": 150,
  \"processedNodes\": 45
}"
```

### 3. 会话信息字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话唯一标识 |
| `nodeId` | Long | 被删除的节点ID |
| `nodeType` | Integer | 节点类型（0=文件夹，1=文件） |
| `userId` | Long | 用户ID |
| `startTime` | String | ISO 8601 格式的开始时间 |
| `status` | String | 状态（running/completed/failed） |
| `totalNodes` | Integer | 总节点数（仅文件夹） |
| `processedNodes` | Integer | 已处理节点数（仅文件夹） |
| `errorMessage` | String | 错误消息（失败时） |

### 4. 自动清理策略

```java
@Component
public class DeleteSessionCleanup {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    // 定时任务：每小时清理一次
    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredSessions() {
        long cutoffTime = System.currentTimeMillis() - 24 * 3600 * 1000; // 24小时前
        
        Set<String> keys = redisTemplate.keys("delete_sessions:*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        
        for (String key : keys) {
            // 移除 score < cutoffTime 的成员
            Long removed = redisTemplate.opsForZSet()
                .removeRangeByScore(key, 0, cutoffTime);
            
            if (removed != null && removed > 0) {
                log.info("清理过期删除会话: key={}, count={}", key, removed);
            }
        }
    }
}
```

---

## 前端集成要点

### 1. SessionId 生成规则

```javascript
// 格式：sess_del_{timestamp}_{random}
const sessionId = `sess_del_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`
```

### 2. 删除流程

```javascript
const handleDelete = async (file) => {
  if (!confirm(`确定要删除 "${file.name}" 吗？`)) {
    return
  }
  
  try {
    const nodeType = file.type === 'folder' ? 0 : 1
    
    // 生成唯一的 sessionId
    const sessionId = `sess_del_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`
    
    logger.info('删除节点:', { nodeId: file.id, nodeType, sessionId })
    
    const result = await deleteNode(file.id, nodeType, sessionId)
    
    if (result.success) {
      // 从列表中移除被删除的节点
      browseState.state.files = browseState.state.files.filter(f => f.id !== file.id)
      showSuccess(`"${file.name}" 已移入回收站`)
    } else {
      showError(`删除失败：${result.message}`)
    }
  } catch (error) {
    showError('网络错误，请稍后重试')
    logger.error('删除节点异常:', error)
  }
}
```

### 3. 用户体验优化

- **立即反馈**：删除请求发出后立即从列表移除，无需等待后台完成
- **友好提示**：显示"已移入回收站，30天后彻底删除"
- **错误处理**：网络错误时显示重试提示

---

## 错误处理

### 1. 常见错误场景

| 错误场景 | HTTP 状态码 | 错误消息 |
|----------|------------|---------|
| 节点不存在 | 404 | "节点不存在或已被删除" |
| 无权限删除 | 403 | "您没有权限删除此节点" |
| 节点已在回收站 | 400 | "该节点已在回收站中" |
| Redis 连接失败 | 500 | "系统繁忙，请稍后重试" |
| 限流触发 | 429 | "当前删除任务过多，请稍后重试" |

### 2. 错误响应格式

```json
{
  "code": 403,
  "success": false,
  "message": "您没有权限删除此节点",
  "data": null
}
```

---

## 性能优化建议

### 1. 批量删除优化

对于包含大量子节点的文件夹：

```java
// 分批处理，每批 100 个节点
int batchSize = 100;
List<Node> children = nodeRepository.findChildren(folderId);

for (int i = 0; i < children.size(); i += batchSize) {
    List<Node> batch = children.subList(i, Math.min(i + batchSize, children.size()));
    
    // 批量更新（单次 SQL）
    nodeRepository.markAsDeletedBatch(batch, userId, recycleBinPath, expiresAt);
    
    // 更新进度
    updateProgress(sessionId, i + batchSize, children.size());
}
```

### 2. Redis 管道操作

```java
// 使用 Pipeline 批量写入 Redis
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (DeleteSession session : sessions) {
        connection.zAdd(
            redisKey.getBytes(),
            session.getStartTime().toEpochMilli(),
            JSON.toJSONString(session).getBytes()
        );
    }
    return null;
});
```

### 3. 索引优化

```sql
-- 为节点表添加索引，加速递归查询
CREATE INDEX idx_node_parent_deleted ON nodes(parent_id, deleted);
CREATE INDEX idx_node_user_deleted ON nodes(user_id, deleted);
```

---

## 监控与日志

### 1. 关键日志点

```java
log.info("启动删除进程: sessionId={}, nodeId={}, userId={}", 
    sessionId, nodeId, userId);

log.info("删除完成: sessionId={}, nodeName={}, duration={}ms", 
    sessionId, nodeName, duration);

log.error("删除失败: sessionId={}, error={}", 
    sessionId, e.getMessage(), e);
```

### 2. 监控指标

- **活跃删除任务数**：`ZCARD delete_sessions:{userId}`
- **平均删除时间**：统计 completed 会话的 duration
- **失败率**：统计 failed 会话占比
- **限流触发次数**：记录 Semaphore 拒绝次数
- **Redis 命中率**：监控连接池使用情况

### 3. Prometheus 指标示例

```java
@Component
public class DeleteMetrics {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public void recordDeleteStart() {
        meterRegistry.counter("delete.operations.started").increment();
    }
    
    public void recordDeleteComplete(long durationMs) {
        meterRegistry.timer("delete.operations.duration")
            .record(durationMs, TimeUnit.MILLISECONDS);
    }
    
    public void recordDeleteFailure(String reason) {
        meterRegistry.counter("delete.operations.failed", "reason", reason)
            .increment();
    }
}
```

---

## 测试用例

### 1. 单元测试

```java
@Test
public void testDeleteNodeCreatesSession() {
    // 调用删除接口
    ApiResponse response = deleteService.delete(12345L, 0, "sess_del_test", userId);
    
    // 验证响应成功
    assertTrue(response.isSuccess());
    
    // 验证 Redis 中有会话记录
    String redisKey = "delete_sessions:" + userId;
    Long count = redisTemplate.opsForZSet().size(redisKey);
    assertEquals(1L, count.longValue());
}
```

### 2. 集成测试

```java
@Test
public void testAsyncFolderDeletion() throws InterruptedException {
    // 1. 创建测试文件夹（包含子节点）
    Long folderId = createTestFolderWithChildren();
    
    // 2. 发起删除请求
    String sessionId = "sess_del_" + System.currentTimeMillis();
    deleteService.delete(folderId, 0, sessionId, userId);
    
    // 3. 验证会话已创建
    assertTrue(sessionExists(sessionId, userId));
    
    // 4. 等待异步删除完成
    Thread.sleep(5000);
    
    // 5. 验证所有子节点已标记删除
    List<Node> children = nodeRepository.findAllChildren(folderId);
    assertTrue(children.stream().allMatch(Node::isDeleted));
    
    // 6. 验证会话状态为 completed
    assertEquals("completed", getSessionStatus(sessionId, userId));
}
```

---

## 完整数据流图

```
用户点击"删除"
    ↓
前端生成 sessionId（sess_del_{timestamp}_{random}）
    ↓
发送 DELETE /files/delete?nodeId=&nodeType=&sessionId=
    ↓
后端验证权限 → 创建删除会话 → 存入 Redis ZSet
    ↓
立即标记节点为删除状态（移入回收站）
    ↓
如果是文件夹 → 启动异步任务递归删除子节点
    ↓
立即返回 {recycleBinPath, expiresAt}
    ↓
前端从列表移除该节点 → 显示"已移入回收站"
    ↓
后台异步任务执行：
  - 获取所有子节点
  - 分批标记删除（每批 100 个）
  - 限流控制（1000 IOPS）
  - 更新进度到 Redis
    ↓
删除完成 → 更新会话状态为 completed
    ↓
定时任务清理 24 小时前的会话记录
```

---

## 接口变更总结

### 修改接口

**`DELETE /files/delete`**

- **新增参数**：`sessionId`（必填）
- **行为变更**：
  - 文件：直接标记删除，立即返回
  - 文件夹：标记根目录删除，后台异步递归删除子节点
- **响应不变**：仍返回 `{recycleBinPath, expiresAt}`

### 技术要点

1. **SessionId 作用**：
   - 唯一标识删除请求
   - 追踪异步删除进度
   - 日志关联和问题排查

2. **Redis 优势**：
   - 高性能读写（微秒级延迟）
   - 自动过期清理
   - 支持分布式部署

3. **限流必要性**：
   - 防止大量删除操作拖慢系统
   - 保护数据库和文件系统
   - 保证其他用户的正常使用

---

## 附录：Redis 命令行工具

### 查看删除会话

```bash
# 查看某个用户的所有删除会话
redis-cli -p 6381 -a your_password ZRANGE delete_sessions:10001 0 -1 WITHSCORES

# 查看会话数量
redis-cli -p 6381 -a your_password ZCARD delete_sessions:10001

# 查看最近的 10 个会话
redis-cli -p 6381 -a your_password ZREVRANGEBYSCORE delete_sessions:10001 +inf -inf LIMIT 0 10
```

### 清理会话

```bash
# 手动清理 24 小时前的会话
redis-cli -p 6381 -a your_password ZREMRANGEBYSCORE delete_sessions:10001 0 $(date -d '24 hours ago' +%s)000
```

---

## 联系方式

如有疑问或需要进一步的技术支持，请联系后端开发团队。

**文档版本**: v1.0  
**最后更新**: 2026-06-04
