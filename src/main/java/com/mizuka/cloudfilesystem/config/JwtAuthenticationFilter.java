package com.mizuka.cloudfilesystem.config;

import com.mizuka.cloudfilesystem.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器
 * 拦截所有请求，验证 JWT 令牌的有效性和设备指纹一致性
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain)
            throws ServletException, IOException {

        // 获取 Authorization 头
        String authHeader = request.getHeader("Authorization");

        // 如果存在 Authorization 头且以 "Bearer " 开头
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                // 验证 JWT 令牌
                if (jwtUtil.validateToken(token)) {
                    // 从令牌中获取用户ID
                    Long userId = jwtUtil.getUserIdFromToken(token);
                    
                    // 从JWT中获取存储的设备指纹
                    String jwtDeviceFingerprint = jwtUtil.getDeviceFingerprintFromToken(token);
                    
                    // 从请求头中获取当前设备指纹（由 SecurityHeaderFilter 设置）
                    String requestDeviceFingerprint = (String) request.getAttribute("deviceFingerprint");
                    
                    // 调试日志：记录从 request attribute 读取的值
                    if (logger.isDebugEnabled()) {
                        logger.debug("[JWT认证] 从 request attribute 读取 - deviceFingerprint: {}", 
                            requestDeviceFingerprint != null ? 
                                requestDeviceFingerprint.substring(0, Math.min(16, requestDeviceFingerprint.length())) + "..." : "null"
                        );
                    }
                    
                    // 如果JWT中包含设备指纹，则验证一致性
                    if (jwtDeviceFingerprint != null && !jwtDeviceFingerprint.isEmpty()) {
                        if (requestDeviceFingerprint == null || !jwtDeviceFingerprint.equals(requestDeviceFingerprint)) {
                            logger.warn("[JWT认证] 设备指纹不匹配 - UserId: {}, JWT指纹: {}, 请求指纹: {}", 
                                userId, 
                                jwtDeviceFingerprint.substring(0, Math.min(16, jwtDeviceFingerprint.length())) + "...",
                                requestDeviceFingerprint != null ? 
                                    requestDeviceFingerprint.substring(0, Math.min(16, requestDeviceFingerprint.length())) + "..." : "null"
                            );
                            
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"success\":false,\"message\":\"设备验证失败，请重新登录\"}");
                            return;
                        }
                        
                        logger.debug("[JWT认证] 设备指纹验证通过 - UserId: {}", userId);
                    } else {
                        logger.debug("[JWT认证] JWT中无设备指纹，跳过验证 - UserId: {}", userId);
                    }

                    // 创建认证对象
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            Collections.emptyList()
                        );

                    // 将认证信息设置到 SecurityContext
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // 将用户ID和设备信息设置到请求属性中，方便后续使用
                    request.setAttribute("userId", userId);

                    logger.debug("[JWT认证] 成功 - UserId: {}, URI: {}", userId, request.getRequestURI());
                } else {
                    logger.warn("[JWT认证] 失败 - 无效的令牌, URI: {}", request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"success\":false,\"message\":\"无效的JWT令牌\"}");
                    return;
                }
            } catch (Exception e) {
                logger.error("[JWT认证] 异常 - {}, URI: {}", e.getMessage(), request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"success\":false,\"message\":\"认证失败：" + e.getMessage() + "\"}");
                return;
            }
        }

        // 继续过滤链
        filterChain.doFilter(request, response);
    }

    /**
     * 排除不需要 JWT 认证的路径
     * 所有 /auth/** 路径都不需要 JWT 认证（包括登录、注册、二次验证等）
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/auth/rsa-key")
                || path.startsWith("/auth/login")
                || path.startsWith("/auth/register")
                || path.startsWith("/auth/security-questions")
                || path.startsWith("/auth/vfcode/email")
                || path.startsWith("/auth/vfcode/phone")
                || path.startsWith("/auth/reset_password/find_user")
                || path.startsWith("/auth/reset_password/verify/email")
                || path.startsWith("/auth/reset_password/verify/phone")
                || path.startsWith("/auth/reset_password/verify/security_answer")
                || path.startsWith("/auth/verify/email")
                || path.startsWith("/auth/verify/phone")
                || path.startsWith("/auth/verify/security_answer");
    }
}
