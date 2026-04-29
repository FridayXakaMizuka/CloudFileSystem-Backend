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
 * 拦截所有请求，验证 JWT 令牌的有效性
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

                    // 创建认证对象
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            Collections.emptyList()
                        );

                    // 将认证信息设置到 SecurityContext
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // 将用户ID设置到请求属性中，方便后续使用
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
     * 所有 /api/auth/** 路径都不需要 JWT 认证
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/auth/");
    }
}
