package com.mizuka.cloudfilesystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务执行器配置
 * 用于目录删除、恢复等后台异步操作
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    
    /**
     * 删除任务专用线程池
     * 核心线程数: 10
     * 最大线程数: 20
     * 队列容量: 100
     */
    @Bean(name = "deleteTaskExecutor")
    public Executor deleteTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // 核心线程数：保持活跃的线程数
        executor.setCorePoolSize(10);
        
        // 最大线程数：高峰期允许的最大线程数
        executor.setMaxPoolSize(20);
        
        // 队列容量：等待执行的任务数量
        executor.setQueueCapacity(100);
        
        // 线程空闲时间（秒）：超过核心线程数的线程在空闲多久后销毁
        executor.setKeepAliveSeconds(60);
        
        // 线程名称前缀：便于日志追踪
        executor.setThreadNamePrefix("async-delete-");
        
        // 拒绝策略：队列满时由调用线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        
        // 等待时间（秒）
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        
        return executor;
    }
    
    /**
     * 恢复任务专用线程池
     */
    @Bean(name = "restoreTaskExecutor")
    public Executor restoreTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-restore-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        
        return executor;
    }
}
