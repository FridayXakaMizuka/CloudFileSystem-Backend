package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.AvatarResponse;
import com.mizuka.cloudfilesystem.dto.EmailChangeRequest;
import com.mizuka.cloudfilesystem.dto.EmailChangeResponse;
import com.mizuka.cloudfilesystem.dto.NicknameChangeRequest;
import com.mizuka.cloudfilesystem.dto.NicknameChangeResponse;
import com.mizuka.cloudfilesystem.dto.PasswordChangeRequest;
import com.mizuka.cloudfilesystem.dto.PasswordChangeResponse;
import com.mizuka.cloudfilesystem.dto.PasswordVerificationRequest;
import com.mizuka.cloudfilesystem.dto.PasswordVerificationResponse;
import com.mizuka.cloudfilesystem.dto.PhoneChangeRequest;
import com.mizuka.cloudfilesystem.dto.PhoneChangeResponse;
import com.mizuka.cloudfilesystem.dto.SecurityQuestionChangeRequest;
import com.mizuka.cloudfilesystem.dto.SecurityQuestionChangeResponse;
import com.mizuka.cloudfilesystem.dto.UserProfileResponse;
import com.mizuka.cloudfilesystem.service.AvatarService;
import com.mizuka.cloudfilesystem.service.UserService;
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

    @Autowired
    private UserService userService;

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

    /**
     * 验证用户原密码是否正确
     * POST /api/profile/password/is_initial_correct
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param request 密码验证请求对象
     * @return 密码验证响应对象
     */
    @PostMapping("/password/is_initial_correct")
    public ResponseEntity<PasswordVerificationResponse> verifyInitialPassword(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PasswordVerificationRequest request) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[验证原密码] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new PasswordVerificationResponse(401, false, "未提供有效的认证令牌")
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[验证原密码] 请求收到");

            // 调用服务层验证密码
            PasswordVerificationResponse response = userService.verifyInitialPassword(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[验证原密码] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[验证原密码] 失败 - {}", response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[验证原密码] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new PasswordVerificationResponse(500, false, "服务器内部错误：" + e.getMessage())
            );
        }
    }

    /**
     * 获取用户所有个人信息
     * POST /api/profile/get_all
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @return 用户个人信息响应对象
     */
    @PostMapping("/get_all")
    public ResponseEntity<UserProfileResponse> getAllProfile(
            @RequestHeader("Authorization") String authHeader) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[获取个人资料] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new UserProfileResponse(401, false, "未提供有效的认证令牌", null)
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[获取个人资料] 请求收到");

            // 调用服务层获取个人资料
            UserProfileResponse response = userService.getAllProfile(token);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[获取个人资料] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[获取个人资料] 失败 - {}", response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[获取个人资料] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new UserProfileResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 修改用户密码
     * POST /api/profile/password/set
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param request 修改密码请求对象
     * @return 修改密码响应对象
     */
    @PostMapping("/password/set")
    public ResponseEntity<PasswordChangeResponse> changePassword(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PasswordChangeRequest request) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[修改密码] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new PasswordChangeResponse(401, false, "未提供有效的认证令牌")
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[修改密码] 请求收到");

            // 调用服务层修改密码
            PasswordChangeResponse response = userService.changePassword(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[修改密码] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[修改密码] 失败 - {}", response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[修改密码] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new PasswordChangeResponse(500, false, "服务器内部错误：" + e.getMessage())
            );
        }
    }

    /**
     * 修改用户昵称
     * POST /api/profile/nickname/set
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param request 修改昵称请求对象
     * @return 修改昵称响应对象
     */
    @PostMapping("/nickname/set")
    public ResponseEntity<NicknameChangeResponse> changeNickname(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody NicknameChangeRequest request) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[修改昵称] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new NicknameChangeResponse(401, false, "未提供有效的认证令牌", null)
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[修改昵称] 请求收到");

            // 调用服务层修改昵称
            NicknameChangeResponse response = userService.changeNickname(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[修改昵称] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[修改昵称] 失败 - {}", response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[修改昵称] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new NicknameChangeResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 修改用户邮箱
     * POST /profile/email/set
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param request 修改邮箱请求对象
     * @return 修改邮箱响应对象
     */
    @PostMapping("/email/set")
    public ResponseEntity<EmailChangeResponse> changeEmail(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody EmailChangeRequest request) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[修改邮箱] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new EmailChangeResponse(401, false, "未提供有效的认证令牌")
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[修改邮箱] 请求收到");

            // 调用服务层修改邮箱
            EmailChangeResponse response = userService.changeEmail(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[修改邮箱] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[修改邮箱] 失败 - {}", response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[修改邮箱] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new EmailChangeResponse(500, false, "服务器内部错误：" + e.getMessage())
            );
        }
    }

    /**
     * 修改用户手机号
     * POST /profile/phone/set
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param request 修改手机号请求对象
     * @return 修改手机号响应对象
     */
    @PostMapping("/phone/set")
    public ResponseEntity<PhoneChangeResponse> changePhone(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PhoneChangeRequest request) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[修改手机号] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new PhoneChangeResponse(401, false, "未提供有效的认证令牌")
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[修改手机号] 请求收到");

            // 调用服务层修改手机号
            PhoneChangeResponse response = userService.changePhone(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[修改手机号] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[修改手机号] 失败 - {}", response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[修改手机号] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new PhoneChangeResponse(500, false, "服务器内部错误：" + e.getMessage())
            );
        }
    }

    /**
     * 修改用户密保问题
     * POST /profile/security_question/set
     * 
     * @param authHeader Authorization头，格式：Bearer {token}
     * @param request 修改密保问题请求对象
     * @return 修改密保问题响应对象
     */
    @PostMapping("/security_question/set")
    public ResponseEntity<SecurityQuestionChangeResponse> changeSecurityQuestion(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody SecurityQuestionChangeRequest request) {
        try {
            // 验证Authorization头
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("[修改密保问题] 失败 - 未提供有效的认证令牌");
                return ResponseEntity.status(401).body(
                    new SecurityQuestionChangeResponse(false, "未提供有效的认证令牌")
                );
            }

            // 提取JWT令牌
            String token = authHeader.substring(7);

            logger.info("[修改密保问题] 请求收到");

            // 调用服务层修改密保问题
            SecurityQuestionChangeResponse response = userService.changeSecurityQuestion(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[修改密保问题] 成功");
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[修改密保问题] 失败 - {}", response.getMessage());
                return ResponseEntity.status(400).body(response);
            }

        } catch (Exception e) {
            logger.error("[修改密保问题] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new SecurityQuestionChangeResponse(false, "服务器内部错误：" + e.getMessage())
            );
        }
    }
}
