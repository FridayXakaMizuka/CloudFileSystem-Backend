package com.mizuka.cloudfilesystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
    
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        
        // 允许的前端域名 - 开发环境允许所有来源，生产环境应限制具体域名
        // 注意：当 allowCredentials 为 true 时，不能使用 "*"，需要使用 allowedOriginPatterns
        config.addAllowedOriginPattern("http://*");
        config.addAllowedOriginPattern("https://*");
        
        // 允许的HTTP方法（包括OPTIONS预检请求）
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("OPTIONS");
        config.addAllowedMethod("PATCH");
        
        // 允许的请求头
        config.addAllowedHeader("Authorization");
        config.addAllowedHeader("Content-Type");
        config.addAllowedHeader("Accept");
        config.addAllowedHeader("Origin");
        config.addAllowedHeader("Cookie");
        config.addAllowedHeader("X-Requested-With");
        
        // 安全请求头（设备指纹和客户端信息）
        config.addAllowedHeader("X-Device-Fingerprint");
        config.addAllowedHeader("X-Forwarded-Proto");
        config.addAllowedHeader("X-Forwarded-For");
        config.addAllowedHeader("X-Real-IP");
        config.addAllowedHeader("X-Client-Type");
        config.addAllowedHeader("X-Client-Identifier");
        config.addAllowedHeader("X-Client-Platform");
        config.addAllowedHeader("X-Electron-Version");
        config.addAllowedHeader("X-Browser-Type");
        config.addAllowedHeader("X-App-Version");
        config.addAllowedHeader("X-Client-IP");
        config.addAllowedHeader("X-Real-IP");
        config.addAllowedHeader("X-Hardware-Id");


        // 允许携带凭证（Cookie、Authorization头等）
        config.setAllowCredentials(true);
        
        // 预检请求的缓存时间（秒）
        config.setMaxAge(3600L);
        
        // 暴露给前端的响应头（如果需要访问自定义响应头）
        config.addExposedHeader("Authorization");
        config.addExposedHeader("Content-Type");
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
}
