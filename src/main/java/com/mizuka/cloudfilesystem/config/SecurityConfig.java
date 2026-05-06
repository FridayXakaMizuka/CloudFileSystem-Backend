package com.mizuka.cloudfilesystem.config;

import com.mizuka.cloudfilesystem.filter.SecurityHeaderFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * Spring Security 配置类
 * 配置哪些接口需要认证，哪些接口可以公开访问
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    
    @Autowired
    private SecurityHeaderFilter securityHeaderFilter;

    /**
     * 配置 CORS
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 开发环境允许所有来源，生产环境应限制具体域名
        configuration.setAllowedOriginPatterns(Arrays.asList("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With"));
        configuration.setExposedHeaders(Arrays.asList("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
    
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
            // 启用 CORS 支持（必须在 csrf 之前）
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // 禁用CSRF保护（前后端分离项目通常不需要）
            .csrf(csrf -> csrf.disable())
            
            // 配置会话管理为无状态（使用JWT不需要session）
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // 配置授权规则
            .authorizeHttpRequests(auth -> auth
                // 放行以下公开接口（无需认证）
                .requestMatchers(
                    "/auth/**",              // 所有认证相关接口都不需要JWT
                    "/file/download/**"      // 文件下载接口不需要JWT（因为<img>标签无法携带Authorization头）
                ).permitAll()
                
                // 其他所有请求都需要JWT认证
                .anyRequest().authenticated()
            )
            
            // 添加安全请求头过滤器（最先执行，提取设备指纹等信息）
            .addFilterBefore(securityHeaderFilter, UsernamePasswordAuthenticationFilter.class)
            // 添加JWT认证过滤器（在SecurityHeaderFilter之后执行）
            .addFilterAfter(jwtAuthenticationFilter, SecurityHeaderFilter.class);
        
        return http.build();
    }
}
