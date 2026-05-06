package com.mizuka.cloudfilesystem.filter;

import com.mizuka.cloudfilesystem.service.DeviceService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 安全请求头过滤器
 * 
 * 功能：
 * 1. 提取并记录所有前端发送的安全请求头
 * 2. 将设备信息存入 request attribute（供后续 Filter 和 Controller 使用）
 * 3. 生成请求ID用于追踪
 * 4. 审计日志记录
 * 
 * 注意：通过 SecurityConfig 注册到 Spring Security Filter 链，在 JwtAuthenticationFilter 之前执行
 */
@Component
public class SecurityHeaderFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityHeaderFilter.class);
    
    @Autowired(required = false)
    private DeviceService deviceService;
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("SecurityHeaderFilter 初始化完成");
    }
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        
        // 1. 提取所有安全请求头
        String deviceFingerprint = request.getHeader("X-Device-Fingerprint");
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String clientType = request.getHeader("X-Client-Type");
        String clientIdentifier = request.getHeader("X-Client-Identifier");
        String platform = request.getHeader("X-Client-Platform");
        String electronVersion = request.getHeader("X-Electron-Version");
        String browserType = request.getHeader("X-Browser-Type");
        String clientIp = request.getHeader("X-Client-IP");
        String appVersion = request.getHeader("X-App-Version");
        
        // 调试日志：记录原始请求头
        if (logger.isDebugEnabled()) {
            logger.debug("[SecurityHeaderFilter] 原始请求头 - X-Device-Fingerprint: {}, X-Client-Type: {}, X-Client-Platform: {}",
                deviceFingerprint != null ? deviceFingerprint.substring(0, Math.min(16, deviceFingerprint.length())) + "..." : "null",
                clientType,
                platform
            );
        }
        
        // 2. 判断是否为 HTTPS
        boolean isHttps = "https".equalsIgnoreCase(forwardedProto);
        
        // 3. 获取请求路径和方法
        String requestURI = request.getRequestURI();
        String method = request.getMethod();
        
        // 4. 记录详细的审计日志
        if (deviceFingerprint != null || clientType != null) {
            logger.info("🔐 [{}] {} | 客户端: {} ({}) | 平台: {} | HTTPS: {} | IP: {} | 指纹: {}",
                method,
                requestURI,
                clientType != null ? clientType : "unknown",
                clientIdentifier != null ? clientIdentifier : "unknown",
                platform != null ? platform : "unknown",
                isHttps,
                clientIp != null ? clientIp : getClientIpFromRequest(request),
                deviceFingerprint != null ? 
                    deviceFingerprint.substring(0, Math.min(16, deviceFingerprint.length())) + "..." : "none"
            );
        }
        
        // 5. 将所有设备信息存入 request attribute（供后续 Filter 和 Controller 使用）
        request.setAttribute("deviceFingerprint", deviceFingerprint);
        request.setAttribute("clientType", clientType);
        request.setAttribute("clientIdentifier", clientIdentifier);
        request.setAttribute("platform", platform);
        request.setAttribute("electronVersion", electronVersion);
        request.setAttribute("browserType", browserType);
        request.setAttribute("clientIp", clientIp != null ? clientIp : getClientIpFromRequest(request));
        request.setAttribute("appVersion", appVersion);
        request.setAttribute("isHttps", isHttps);
        
        // 调试日志：确认 attribute 已设置
        if (logger.isDebugEnabled()) {
            logger.debug("[SecurityHeaderFilter] 已设置 request attribute - deviceFingerprint: {}",
                deviceFingerprint != null ? deviceFingerprint.substring(0, Math.min(16, deviceFingerprint.length())) + "..." : "null"
            );
        }
        
        // 6. 添加响应头（用于调试和追踪）
        response.setHeader("X-Request-Id", generateRequestId());
        
        // 7. 继续过滤链
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            logger.error("请求处理异常: {} {}", method, requestURI, e);
            throw e;
        }
    }
    
    /**
     * 从请求中获取客户端IP
     */
    private String getClientIpFromRequest(HttpServletRequest request) {
        // 优先从 X-Real-IP 获取（Nginx代理）
        String ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }
        
        // 其次从 X-Forwarded-For 获取
        ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            // X-Forwarded-For 可能包含多个IP，取第一个
            return ip.split(",")[0].trim();
        }
        
        // 最后从 remoteAddr 获取
        return request.getRemoteAddr();
    }
    
    /**
     * 生成请求 ID
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
    
    @Override
    public void destroy() {
        logger.info("SecurityHeaderFilter 销毁");
    }
}
