-- 滑动窗口限流器 Lua 脚本
-- KEYS[1]: 限流键 (如: rate_limit:delete:{userId})
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2]: 窗口大小（毫秒）
-- ARGV[3]: 最大请求数（IOPS）

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])

-- 计算窗口起始时间
local window_start = now - window_size

-- 1. 移除过期的请求记录（窗口外的数据）
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- 2. 获取当前窗口内的请求数
local current_count = redis.call('ZCARD', key)

-- 3. 判断是否超过限制
if current_count < max_requests then
    -- 允许请求，记录当前时间戳（使用唯一成员避免冲突）
    local member = now .. '-' .. math.random(1000000)
    redis.call('ZADD', key, now, member)
    
    -- 设置过期时间（窗口大小 + 缓冲时间）
    redis.call('PEXPIRE', key, window_size + 1000)
    
    return 1  -- 允许通过
else
    -- 拒绝请求
    return 0  -- 被限流
end
