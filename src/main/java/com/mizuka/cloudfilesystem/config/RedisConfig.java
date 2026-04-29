package com.mizuka.cloudfilesystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类
 * 配置两个独立的Redis实例：
 * 1. rsaRedisTemplate - RSA临时缓存（端口6379）
 * 2. avatarRedisTemplate - 头像缓存（端口6380）
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String rsaRedisHost;

    @Value("${spring.data.redis.port:6379}")
    private int rsaRedisPort;

    @Value("${avatar.redis.host:localhost}")
    private String avatarRedisHost;

    @Value("${avatar.redis.port:6380}")
    private int avatarRedisPort;

    /**
     * RSA临时缓存的Redis连接工厂（端口6379）
     * 用于存储RSA密钥对等临时数据
     */
    @Bean(name = "rsaRedisConnectionFactory")
    public RedisConnectionFactory rsaRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(rsaRedisHost);
        config.setPort(rsaRedisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * 头像缓存的Redis连接工厂（端口6380）
     * 用于存储用户头像等数据
     */
    @Bean(name = "avatarRedisConnectionFactory")
    public RedisConnectionFactory avatarRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(avatarRedisHost);
        config.setPort(avatarRedisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * RSA临时缓存的RedisTemplate（主模板，默认使用）
     * 使用String序列化器作为key，JSON序列化器作为value
     * 用于存储RSA密钥对、会话信息等
     * 
     * 注意：同时注册为 "redisTemplate" 和 "rsaRedisTemplate" 两个名称
     */
    @Primary
    @Bean(name = {"redisTemplate", "rsaRedisTemplate"})
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        // 设置连接工厂（使用6379端口）
        template.setConnectionFactory(rsaRedisConnectionFactory());

        // 设置key的序列化器为String类型
        template.setKeySerializer(new StringRedisSerializer());

        // 使用RedisSerializer.json()获取JSON序列化器
        RedisSerializer<Object> jsonSerializer = RedisSerializer.json();

        // 设置value的序列化器为JSON类型，支持对象存储
        template.setValueSerializer(jsonSerializer);

        // 设置hash key的序列化器
        template.setHashKeySerializer(new StringRedisSerializer());

        // 设置hash value的序列化器
        template.setHashValueSerializer(jsonSerializer);

        // 初始化模板
        template.afterPropertiesSet();

        return template;
    }

    /**
     * 头像缓存的RedisTemplate
     * 使用String序列化器作为key和value
     * 用于存储头像的Base64编码字符串
     */
    @Bean(name = "avatarRedisTemplate")
    public RedisTemplate<String, String> avatarRedisTemplate() {
        RedisTemplate<String, String> template = new RedisTemplate<>();

        // 设置连接工厂（使用6380端口）
        template.setConnectionFactory(avatarRedisConnectionFactory());

        // 设置key的序列化器为String类型
        template.setKeySerializer(new StringRedisSerializer());

        // 设置value的序列化器也为String类型（因为头像直接存储Base64字符串）
        template.setValueSerializer(new StringRedisSerializer());

        // 设置hash key的序列化器
        template.setHashKeySerializer(new StringRedisSerializer());

        // 设置hash value的序列化器
        template.setHashValueSerializer(new StringRedisSerializer());

        // 初始化模板
        template.afterPropertiesSet();

        return template;
    }
}
