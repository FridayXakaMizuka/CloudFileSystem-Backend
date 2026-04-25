package com.mizuka.cloudfilesystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类
 * 配置RedisTemplate以支持字符串键和JSON格式的值
 */
@Configuration
public class RedisConfig {

    /**
     * 创建并配置RedisTemplate
     * 使用String序列化器作为key，JSON序列化器作为value
     * @param connectionFactory Redis连接工厂
     * @return 配置好的RedisTemplate实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        // 设置连接工厂
        template.setConnectionFactory(connectionFactory);

        // 设置key的序列化器为String类型
        template.setKeySerializer(new StringRedisSerializer());

        // 使用RedisSerializer.json()获取JSON序列化器
        // 这是Spring Boot 4.0推荐的JSON序列化方式
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
}
