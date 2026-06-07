package com.mizuka.cloudfilesystem.service;

import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 基于 Redis 滑动窗口的限流器实现
 * 使用 Lua 脚本保证原子性操作
 */
@Service
public class RedisSlidingWindowRateLimiter implements RateLimiterService {
    
    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);
    
    // 默认窗口大小：1秒
    private static final long DEFAULT_WINDOW_SIZE_MS = 1000L;
    
    // 默认退避时间：10毫秒
    private static final long DEFAULT_BACKOFF_MS = 10L;
    
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisAsyncCommands<String, String> deleteRedisCommands;
    
    private DefaultRedisScript<Long> rateLimiterScript;
    
    public RedisSlidingWindowRateLimiter(
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("deleteRedisCommands") RedisAsyncCommands<String, String> deleteRedisCommands) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.deleteRedisCommands = deleteRedisCommands;
    }
    
    /**
     * 初始化 Lua 脚本
     */
    @PostConstruct
    public void init() {
        rateLimiterScript = new DefaultRedisScript<>();
        rateLimiterScript.setLocation(new ClassPathResource("lua/sliding_window_rate_limiter.lua"));
        rateLimiterScript.setResultType(Long.class);
        
        log.info("[限流器] Lua 脚本加载成功");
    }
    
    @Override
    public boolean tryAcquire(String key, int maxIops) {
        try {
            long now = System.currentTimeMillis();
            
            // 执行 Lua 脚本
            Long result = stringRedisTemplate.execute(
                rateLimiterScript,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(DEFAULT_WINDOW_SIZE_MS),
                String.valueOf(maxIops)
            );
            
            boolean allowed = result != null && result == 1L;
            
            if (!allowed) {
                log.debug("[限流器] 请求被限流 - Key: {}, MaxIops: {}", key, maxIops);
            }
            
            return allowed;
            
        } catch (Exception e) {
            log.error("[限流器] 执行失败，默认允许通过 - Key: {}", key, e);
            // 异常情况下默认允许通过，避免影响业务
            return true;
        }
    }
    
    @Override
    public void acquireWithBackoff(String key, int maxIops) throws InterruptedException {
        int retryCount = 0;
        int maxRetries = 100; // 最多重试100次（约1秒）
        
        while (retryCount < maxRetries) {
            if (tryAcquire(key, maxIops)) {
                return; // 成功获取许可
            }
            
            // 退避等待
            Thread.sleep(DEFAULT_BACKOFF_MS);
            retryCount++;
            
            if (retryCount % 10 == 0) {
                log.warn("[限流器] 持续被限流 - Key: {}, RetryCount: {}", key, retryCount);
            }
        }
        
        log.error("[限流器] 超过最大重试次数 - Key: {}, MaxRetries: {}", key, maxRetries);
        throw new RuntimeException("限流器等待超时，请稍后重试");
    }
    
    @Override
    public long getCurrentCount(String key) {
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - DEFAULT_WINDOW_SIZE_MS;
            
            // 先清理过期数据
            stringRedisTemplate.execute((RedisCallback<Long>) connection -> {
                StringRedisConnection stringConn = (StringRedisConnection) connection;
                stringConn.zRemRangeByScore(key, 0, windowStart);
                return null;
            });
            
            // 统计当前窗口内的请求数
            Long count = stringRedisTemplate.opsForZSet().count(key, windowStart, now);
            
            return count != null ? count : 0L;
            
        } catch (Exception e) {
            log.error("[限流器] 获取计数失败 - Key: {}", key, e);
            return 0L;
        }
    }
}
