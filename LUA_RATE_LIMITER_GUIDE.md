# 滑动窗口限流器 Lua 脚本使用指南

## 概述

本文档描述了基于 Redis ZSet 实现的滑动窗口限流器，用于控制目录删除操作的 IOPS（每秒操作数）。

---

## Lua 脚本位置

```
src/main/resources/lua/sliding_window_rate_limiter.lua
```

---

## 脚本功能

该 Lua 脚本实现了**滑动窗口限流算法**，具有以下特点：

1. **原子性操作**：整个限流逻辑在 Redis 中原子执行，避免并发问题
2. **滑动窗口**：基于时间戳的滑动窗口，比固定窗口更平滑
3. **自动清理**：自动清理窗口外的旧记录，防止内存泄漏
4. **可配置**：支持动态调整窗口大小和最大请求数

---

## 脚本参数

### KEYS

| 参数 | 说明 | 示例 |
|------|------|------|
| `KEYS[1]` | 限流键名 | `rate_limit:delete:10001` |

### ARGV

| 参数 | 说明 | 类型 | 示例 |
|------|------|------|------|
| `ARGV[1]` | 当前时间戳（毫秒） | Long | `1717387800000` |
| `ARGV[2]` | 窗口大小（毫秒） | Long | `1000` (1秒) |
| `ARGV[3]` | 最大请求数 | Integer | `1000` (IOPS限制) |

### 返回值

| 值 | 说明 |
|----|------|
| `1` | 允许通过（未超限） |
| `0` | 拒绝请求（已超限） |

---

## 使用方法

### 1. Java 代码调用

```java
@Autowired
private RateLimiterService rateLimiterService;

// 非阻塞方式：立即返回
boolean allowed = rateLimiterService.tryAcquire("rate_limit:delete:" + userId, 1000);
if (allowed) {
    // 执行删除操作
} else {
    // 被限流，返回错误提示
}

// 阻塞方式：等待直到成功
try {
    rateLimiterService.acquireWithBackoff("rate_limit:delete:" + userId, 1000);
    // 执行删除操作
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException("删除任务被中断", e);
}
```

### 2. Redis CLI 直接测试

```bash
# 连接到 Redis
redis-cli -p 6381

# 加载并执行 Lua 脚本
EVAL "$(cat src/main/resources/lua/sliding_window_rate_limiter.lua)" \
  1 \
  "rate_limit:test" \
  "$(date +%s)000" \
  1000 \
  10
```

---

## 限流配置

### 默认配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| 窗口大小 | 1000ms (1秒) | 滑动窗口的时间范围 |
| 最大 IOPS | 1000 | 每秒最多允许的操作数 |
| 退避时间 | 10ms | 被限流后重试的间隔 |
| 最大重试次数 | 100 | 超过此次数将抛出异常 |

### 修改限流阈值

#### 方法 1：修改 Java 代码

在 `AsyncDirectoryDeleteService.java` 中修改常量：

```java
// 修改前
private static final int DEFAULT_MAX_IOPS = 1000;

// 修改后（降低到 500 IOPS）
private static final int DEFAULT_MAX_IOPS = 500;
```

#### 方法 2：从配置文件读取

在 `application.yaml` 中添加配置：

```yaml
rate-limiter:
  delete:
    max-iops: 1000
    window-size-ms: 1000
    backoff-ms: 10
    max-retries: 100
```

然后在 `RedisSlidingWindowRateLimiter.java` 中注入：

```java
@Value("${rate-limiter.delete.max-iops}")
private int defaultMaxIops;

@Value("${rate-limiter.delete.window-size-ms}")
private long windowSizeMs;
```

---

## Lua 脚本详解

### 核心逻辑

```lua
-- 1. 计算窗口起始时间
local window_start = now - window_size

-- 2. 清理窗口外的旧记录（原子操作）
redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

-- 3. 统计当前窗口内的请求数
local current_count = redis.call('ZCARD', key)

-- 4. 判断是否超限
if current_count < max_requests then
    -- 未超限，添加新记录
    local member = now .. ':' .. math.random(1000000)
    redis.call('ZADD', key, now, member)
    
    -- 设置过期时间
    local expire_seconds = math.ceil(window_size / 1000) + 1
    redis.call('EXPIRE', key, expire_seconds)
    
    return 1  -- 允许
else
    return 0  -- 拒绝
end
```

### 数据结构

使用 Redis ZSet 存储请求记录：

- **Key**: `rate_limit:delete:{userId}`
- **Member**: `{timestamp}:{random}` (例如: `1717387800000:123456`)
- **Score**: 时间戳（毫秒）

示例：

```redis
ZADD rate_limit:delete:10001 1717387800000 "1717387800000:123456"
ZADD rate_limit:delete:10001 1717387800001 "1717387800001:789012"
ZCARD rate_limit:delete:10001  # 返回 2
```

---

## 监控与调试

### 查看当前限流状态

```bash
# 查看某个用户的当前请求数
redis-cli -p 6381 ZCARD rate_limit:delete:10001

# 查看最近的 10 条记录
redis-cli -p 6381 ZREVRANGE rate_limit:delete:10001 0 9 WITHSCORES

# 查看窗口内的所有记录
redis-cli -p 6381 ZRANGEBYSCORE rate_limit:delete:10001 $(date +%s)000 +inf
```

### 手动清理限流数据

```bash
# 清空某个用户的限流计数
redis-cli -p 6381 DEL rate_limit:delete:10001

# 清空所有用户的限流计数
redis-cli -p 6381 KEYS rate_limit:delete:* | xargs redis-cli -p 6381 DEL
```

---

## 性能优化建议

### 1. 调整窗口大小

- **小窗口**（100-500ms）：更精细的控制，但增加 Redis 压力
- **大窗口**（2000-5000ms）：更平滑的限流，但可能短时突发

### 2. 调整批处理大小

在 `AsyncDirectoryDeleteService.java` 中：

```java
// 当前批处理大小：100
private static final int BATCH_SIZE = 100;

// 如果 IOPS 较低，可以增大批量
private static final int BATCH_SIZE = 200;
```

### 3. Redis 连接池优化

在 `DirectoRedisConfig.java` 中调整连接池参数：

```yaml
directo:
  redis:
    delete:
      lettuce:
        pool:
          max-active: 50    # 增加到 100（高并发场景）
          max-idle: 20      # 增加到 50
          min-idle: 5       # 增加到 10
```

---

## 常见问题

### Q1: 如何动态调整限流阈值？

**A**: 有两种方式：

1. **重启服务**：修改 `DEFAULT_MAX_IOPS` 常量后重启
2. **热更新**：从配置中心（如 Nacos、Apollo）读取配置，无需重启

### Q2: 限流器会影响性能吗？

**A**: 影响极小：

- Lua 脚本在 Redis 中原子执行，耗时 < 1ms
- 滑动窗口自动清理旧数据，不会无限增长
- ZSet 的时间复杂度为 O(log N)

### Q3: 如何禁用限流？

**A**: 修改 `tryAcquire` 方法始终返回 `true`：

```java
@Override
public boolean tryAcquire(String key, int maxIops) {
    return true; // 禁用限流
}
```

### Q4: 分布式环境下限流是否有效？

**A**: 是的，因为：

- 所有实例共享同一个 Redis 实例
- Lua 脚本保证原子性
- 不需要额外的分布式锁

---

## 测试用例

### 单元测试

```java
@Test
public void testRateLimiter() throws InterruptedException {
    String key = "rate_limit:test:" + System.currentTimeMillis();
    int maxIops = 10;
    
    // 前 10 次应该全部通过
    for (int i = 0; i < 10; i++) {
        assertTrue(rateLimiterService.tryAcquire(key, maxIops));
    }
    
    // 第 11 次应该被拒绝
    assertFalse(rateLimiterService.tryAcquire(key, maxIops));
    
    // 等待 1 秒后应该恢复
    Thread.sleep(1100);
    assertTrue(rateLimiterService.tryAcquire(key, maxIops));
}
```

---

## 附录：完整 Lua 脚本

```lua
-- ============================================
-- 滑动窗口限流器 Lua 脚本
-- 文件: sliding_window_rate_limiter.lua
-- 用途: 基于 Redis ZSet 实现滑动窗口限流算法
-- ============================================

-- KEYS[1]: 限流键名 (例如: rate_limit:delete:{userId})
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2]: 窗口大小（毫秒），如 1000ms (1秒)
-- ARGV[3]: 最大请求数，如 1000 (IOPS限制)

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])

-- 参数校验
if not now or not window_size or not max_requests then
    return redis.error_reply("Invalid arguments")
end

-- 计算窗口起始时间
local window_start = now - window_size

-- 清理窗口外的旧记录（原子操作）
redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)

-- 统计当前窗口内的请求数
local current_count = redis.call('ZCARD', key)

-- 判断是否超限
if current_count < max_requests then
    -- 未超限，允许通过
    -- 使用唯一标识作为member（时间戳 + 随机数）
    local member = now .. ':' .. math.random(1000000)
    redis.call('ZADD', key, now, member)
    
    -- 设置过期时间（窗口大小 + 1秒，确保窗口完整）
    local expire_seconds = math.ceil(window_size / 1000) + 1
    redis.call('EXPIRE', key, expire_seconds)
    
    -- 返回 1 表示允许
    return 1
else
    -- 超限，拒绝请求
    -- 返回 0 表示拒绝
    return 0
end
```

---

## 联系方式

如有疑问或需要技术支持，请联系后端开发团队。

**文档版本**: v1.0  
**最后更新**: 2026-06-05
