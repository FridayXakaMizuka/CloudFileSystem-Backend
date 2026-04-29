package com.mizuka.cloudfilesystem.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试控制器
 * 用于测试JWT认证功能
 */
@RestController
@RequestMapping("/test")
public class TestController {

    private static final Logger logger = LoggerFactory.getLogger(TestController.class);

    /**
     * 测试接口 - 需要JWT认证
     * @param request HTTP请求对象
     * @return 用户信息
     */
    @GetMapping("/protected")
    public ResponseEntity<Map<String, Object>> getProtectedResource(HttpServletRequest request) {
        // 从请求属性中获取用户ID（由JWT过滤器设置）
        Long userId = (Long) request.getAttribute("userId");

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("success", true);
        response.put("message", "访问成功");
        response.put("data", Map.of(
            "userId", userId,
            "info", "这是受保护的资源，只有携带有效JWT令牌的请求才能访问"
        ));

        logger.info("[测试接口] 访问成功 - UserId: {}", userId);

        return ResponseEntity.ok(response);
    }

    /**
     * 获取当前用户信息
     * @param request HTTP请求对象
     * @return 用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("success", true);
        response.put("message", "获取成功");
        response.put("data", Map.of(
            "userId", userId
        ));

        return ResponseEntity.ok(response);
    }
}
