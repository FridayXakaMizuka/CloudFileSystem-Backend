package com.mizuka.cloudfilesystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 二次验证Redis配置
 * 使用独立的Redis实例（端口6378）存储临时会话信息
 */
@Configuration
public class TrustedBrowsersRedisConfig {
    
    @Value("${trustedBrowsers.redis.host:localhost}")
    private String host;
    
    @Value("${trustedBrowsers.redis.port:6378}")
    private int port;
    
    @Value("${trustedBrowsers.redis.database:0}")
    private int database;
    
    @Value("${trustedBrowsers.redis.timeout:3000ms}")
    private String timeout;
    
    /**
     * 创建二次验证专用的Redis连接工厂
     */
    @Bean(name = "trustedBrowsersRedisConnectionFactory")
    public RedisConnectionFactory trustedBrowsersRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(host);
        config.setPort(port);
        config.setDatabase(database);
        
        return new LettuceConnectionFactory(config);
    }
    
    /**
     * 创建二次验证专用的RedisTemplate
     */
    @Bean(name = "trustedBrowsersRedisTemplate")
    public RedisTemplate<String, Object> trustedBrowsersRedisTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("trustedBrowsersRedisConnectionFactory")
            RedisConnectionFactory connectionFactory) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // 使用String序列化器处理key
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        
        // 使用JSON序列化器处理value（使用新的API）
        RedisSerializer<Object> jsonSerializer = RedisSerializer.json();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
