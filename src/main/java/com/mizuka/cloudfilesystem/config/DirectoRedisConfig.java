package com.mizuka.cloudfilesystem.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 目录删除、恢复与彻底删除Redis配置类
 * 使用独立的Redis实例（端口6381）存储目录删除、恢复与彻底删除信息
 * - Delete Session: database 0
 * - Restore Session: database 1
 */
@Configuration
public class DirectoRedisConfig {
    
    @Value("${recycle.redis.host}")
    private String host;
    
    @Value("${recycle.redis.port}")
    private int port;
    
    @Value("${recycle.redis.database:0}")
    private int defaultDatabase;
    
    @Value("${recycle.redis.lettuce.pool.max-active:50}")
    private int maxActive;
    
    @Value("${recycle.redis.lettuce.pool.max-idle:20}")
    private int maxIdle;
    
    @Value("${recycle.redis.lettuce.pool.min-idle:5}")
    private int minIdle;
    
    /**
     * 创建删除操作的Redis连接（database 0）
     */
    @Bean(name = "deleteRedisConnection")
    public StatefulRedisConnection<String, String> deleteRedisConnection() {
        RedisURI redisUri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(0)  // 删除操作使用database 0
                .build();
        
        RedisClient redisClient = RedisClient.create(redisUri);
        return redisClient.connect();
    }
    
    /**
     * 创建恢复操作的Redis连接（database 1）
     */
    @Bean(name = "restoreRedisConnection")
    public StatefulRedisConnection<String, String> restoreRedisConnection() {
        RedisURI redisUri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(1)  // 恢复操作使用database 1
                .build();
        
        RedisClient redisClient = RedisClient.create(redisUri);
        return redisClient.connect();
    }
    
    /**
     * 获取删除操作的异步命令接口
     */
    @Bean(name = "deleteRedisCommands")
    public RedisAsyncCommands<String, String> deleteRedisCommands() {
        return deleteRedisConnection().async();
    }
    
    /**
     * 获取恢复操作的异步命令接口
     */
    @Bean(name = "restoreRedisCommands")
    public RedisAsyncCommands<String, String> restoreRedisCommands() {
        return restoreRedisConnection().async();
    }
    
    /**
     * 创建回收站专用的StringRedisTemplate（用于限流器等场景）
     * 使用6381端口的Redis实例，database 0
     */
    @Bean(name = "recycleStringRedisTemplate")
    public StringRedisTemplate recycleStringRedisTemplate() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(0);  // 删除操作使用database 0
        
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        
        return template;
    }
}
