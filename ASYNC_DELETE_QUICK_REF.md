# 异步删除功能 - 快速参考

## Lua 脚本位置

```
src/main/resources/lua/sliding_window_rate_limiter.lua
```

## 修改限流阈值

### 方法 1：修改常量（推荐）

编辑 `AsyncDirectoryDeleteService.java`：

```java
// 第 29 行
private static final int DEFAULT_MAX_IOPS = 1000;  // 修改此值
```

### 方法 2：从配置文件读取

1. 在 `application.yaml` 添加：

```yaml
rate-limiter:
  delete:
    max-iops: 1000
    window-size-ms: 1000
```

2. 在 `RedisSlidingWindowRateLimiter.java` 注入：

```java
@Value("${rate-limiter.delete.max-iops}")
private int defaultMaxIops;
```

## Redis 命令

### 查看限流状态

```bash
redis-cli -p 6381

# 查看当前请求数
ZCARD rate_limit:delete:10001

# 查看最近记录
ZREVRANGE rate_limit:delete:10001 0 9 WITHSCORES
```

### 查看删除会话

```bash
# 查看用户的所有删除会话
ZRANGE delete_sessions:10001 0 -1 WITHSCORES

# 清理过期会话
ZREMRANGEBYSCORE delete_sessions:10001 0 $(date -d '24 hours ago' +%s)000
```

## 关键参数

| 参数 | 默认值 | 位置 | 说明 |
|------|--------|------|------|
| IOPS 限制 | 1000 | AsyncDirectoryDeleteService.java | 每秒最大操作数 |
| 批处理大小 | 100 | AsyncDirectoryDeleteService.java | 每批处理的节点数 |
| 窗口大小 | 1000ms | RedisSlidingWindowRateLimiter.java | 滑动窗口时间 |
| 退避时间 | 10ms | RedisSlidingWindowRateLimiter.java | 重试间隔 |
| 会话过期 | 24h | DeleteSessionService.java | 会话保留时间 |

## API 使用

### 删除节点

```bash
DELETE /files/delete?nodeId=12345&sessionId=sess_del_1717387800000_abc123
```

**响应**:

```json
{
  "code": 200,
  "success": true,
  "message": "已移入回收站，30天后彻底删除",
  "data": {
    "recycleBinPath": "_root/_recycle_bin/10001/folder_name",
    "expiresAt": "2026-07-04T10:00:00"
  }
}
```

## 日志关键字

```bash
# 查看异步删除日志
grep "\[异步删除\]" logs/application.log

# 查看限流器日志
grep "\[限流器\]" logs/application.log

# 查看会话管理日志
grep "\[删除会话\]" logs/application.log
```

## 常见问题

### Q: 如何禁用限流？

A: 修改 `RedisSlidingWindowRateLimiter.tryAcquire()` 返回 `true`。

### Q: 如何调整线程池大小？

A: 编辑 `AsyncConfig.java` 中的 `deleteTaskExecutor()` 方法。

### Q: 删除任务卡住了怎么办？

A: 
1. 检查 Redis 连接是否正常
2. 查看日志是否有异常
3. 检查数据库锁情况
4. 重启服务

## 监控指标

```java
// 待实现：添加到 AsyncDirectoryDeleteService
log.info("删除完成 - FolderId: {}, Duration: {}ms, Nodes: {}", ...);
```

---

**最后更新**: 2026-06-05
