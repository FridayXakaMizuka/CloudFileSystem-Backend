package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.AvatarResponse;
import com.mizuka.cloudfilesystem.service.AvatarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户资料控制器
 * 处理用户头像、个人信息等资料的获取和更新
 */
@RestController
@RequestMapping("/profile")
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    @Autowired
    private AvatarService avatarService;

    /**
     * 获取用户头像
     * GET /api/profile/avatar/get
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @return 头像响应对象
     */
    @GetMapping("/avatar/get")
    public ResponseEntity<AvatarResponse> getAvatar(@RequestHeader("Authorization") String authHeader) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[获取头像] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new AvatarResponse(401, false, "未提供有效的认证令牌", null)
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[获取头像] 请求收到");

            // 调用服务层获取头像
            AvatarResponse response = avatarService.getAvatar(token);

            // 返回响应
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[获取头像] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new AvatarResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 设置用户头像
     * GET /api/profile/avatar/set?avatar={avatarUrl}
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param avatarUrl 头像URL或Base64编码
     * @return 操作结果
     */
    @GetMapping("/avatar/set")
    public ResponseEntity<Map<String, Object>> setAvatar(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("avatar") String avatarUrl) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[设置头像] 失败 - 未提供有效的认证令牌");
                Map<String, Object> error = new HashMap<>();
                error.put("code", 401);
                error.put("success", false);
                error.put("message", "未提供有效的认证令牌");
                return ResponseEntity.status(401).body(error);
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            // 验证avatarUrl
            if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
                logger.warn("[设置头像] 失败 - 头像URL为空");
                Map<String, Object> error = new HashMap<>();
                error.put("code", 400);
                error.put("success", false);
                error.put("message", "头像URL不能为空");
                return ResponseEntity.badRequest().body(error);
            }

            logger.info("[设置头像] 请求收到 - AvatarUrl: {}", avatarUrl);

            // 调用服务层设置头像
            String message = avatarService.setAvatar(token, avatarUrl);

            // 返回成功响应
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("success", true);
            response.put("message", message);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("[设置头像] 异常 - {}", e.getMessage());
            e.printStackTrace();
            
            Map<String, Object> error = new HashMap<>();
            error.put("code", 500);
            error.put("success", false);
            error.put("message", "服务器内部错误：" + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
