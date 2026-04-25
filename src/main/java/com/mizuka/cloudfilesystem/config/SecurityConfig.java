package com.mizuka.cloudfilesystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置类
 * 配置哪些接口需要认证，哪些接口可以公开访问
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    /**
     * 配置安全过滤链
     * 定义接口的访问权限规则
     * @param http HttpSecurity对象
     * @return SecurityFilterChain
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用CSRF保护（前后端分离项目通常不需要）
            .csrf(csrf -> csrf.disable())
            
            // 配置授权规则
            .authorizeHttpRequests(auth -> auth
                // 放行以下公开接口（无需认证）
                .requestMatchers(
                    "/api/auth/health",              // 健康检查
                    "/api/auth/rsa-key",             // 获取RSA公钥
                    "/api/auth/register",            // 用户注册
                    "/api/auth/security-questions"   // 获取安全问题列表
                ).permitAll()
                
                // 其他所有请求都需要认证（后续可以添加登录接口后修改）
                .anyRequest().permitAll()  // 暂时全部放行，后续可以根据需要修改
            );
        
        return http.build();
    }
}
