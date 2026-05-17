package com.mizuka.cloudfilesystem.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 安全上下文工具类
 * 用于从JWT认证过滤器中获取当前登录用户信息
 */
public class SecurityUtils {

    /**
     * 获取当前请求的HttpServletRequest
     * 
     * @return HttpServletRequest对象，如果不在请求上下文中则返回null
     */
    public static HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes = 
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return attributes.getRequest();
    }

    /**
     * 从JWT令牌中获取当前用户ID
     * JWT认证过滤器已将用户ID设置到request attribute中
     * 
     * @return 用户ID，如果未认证则返回null
     */
    public static Long getCurrentUserId() {
        HttpServletRequest request = getCurrentRequest();
        if (request == null) {
            return null;
        }
        
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj instanceof Long) {
            return (Long) userIdObj;
        } else if (userIdObj instanceof Integer) {
            // 兼容Integer类型
            return ((Integer) userIdObj).longValue();
        }
        return null;
    }

    /**
     * 获取当前用户ID，如果未认证则抛出异常
     * 
     * @return 用户ID
     * @throws IllegalStateException 如果用户未认证
     */
    public static Long getRequiredUserId() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new IllegalStateException("用户未认证或会话已过期");
        }
        return userId;
    }

    /**
     * 检查当前用户是否已认证
     * 
     * @return true-已认证，false-未认证
     */
    public static boolean isAuthenticated() {
        return getCurrentUserId() != null;
    }
}
