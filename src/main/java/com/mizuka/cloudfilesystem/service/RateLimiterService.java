package com.mizuka.cloudfilesystem.service;

/**
 * 限流器服务接口
 * 基于滑动窗口算法实现分布式限流
 */
public interface RateLimiterService {
    
    /**
     * 尝试获取许可（非阻塞）
     * 
     * @param key 限流键（如: rate_limit:delete:{userId}）
     * @param maxIops 每秒最大操作数
     * @return true-允许通过，false-被限流
     */
    boolean tryAcquire(String key, int maxIops);
    
    /**
     * 带退避策略的获取许可（阻塞直到成功）
     * 
     * @param key 限流键
     * @param maxIops 每秒最大操作数
     * @throws InterruptedException 如果线程被中断
     */
    void acquireWithBackoff(String key, int maxIops) throws InterruptedException;
    
    /**
     * 获取当前窗口的请求计数
     * 
     * @param key 限流键
     * @return 当前窗口内的请求数
     */
    long getCurrentCount(String key);
}
