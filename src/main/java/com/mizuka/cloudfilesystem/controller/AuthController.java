package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.LoginRequest;
import com.mizuka.cloudfilesystem.dto.LoginResponse;
import com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO;
import com.mizuka.cloudfilesystem.dto.RSAPublicKeyResponse;
import com.mizuka.cloudfilesystem.util.RSAKeyManager;
import com.mizuka.cloudfilesystem.dto.RSAValidationRequest;
import com.mizuka.cloudfilesystem.dto.RSAValidationResponse;
import com.mizuka.cloudfilesystem.dto.EmailVerificationRequest;
import com.mizuka.cloudfilesystem.dto.EmailVerificationResponse;
import com.mizuka.cloudfilesystem.dto.SmsVerificationRequest;
import com.mizuka.cloudfilesystem.dto.SmsVerificationResponse;
import com.mizuka.cloudfilesystem.dto.FindUserRequest;
import com.mizuka.cloudfilesystem.dto.FindUserResponse;
import com.mizuka.cloudfilesystem.dto.ResetPasswordEmailVerifyRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordPhoneVerifyRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordSecurityVerifyRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordVerifyResponse;
import com.mizuka.cloudfilesystem.dto.ResetPasswordRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 认证控制器
 * 处理用户认证相关的请求，包括获取RSA公钥和用户登录
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    
    /**
     * 健康检查接口
     * 用于前端检测后端是否启动
     * @return 简单的响应对象
     */
    @GetMapping("/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Backend is running");
    }

    // 注入RSA RedisTemplate用于操作RSA密钥对缓存（端口6379）
    @Autowired
    @Qualifier("rsaRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;


    // 注入安全问题服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.SecurityQuestionService securityQuestionService;

    // 注入用户服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.UserService userService;

    // 注入邮箱验证码服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.EmailVerificationService emailVerificationService;

    // 注入短信验证码服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.SmsVerificationService smsVerificationService;


    // Redis中存储密钥对的key前缀
    private static final String RSA_KEY_PREFIX = "rsa:key:";
    
    // 密钥对在Redis中的过期时间（单位：秒）- 300秒 = 5分钟
    private static final long KEY_EXPIRE_SECONDS = 300;
    
    /**
     * 获取RSA公钥接口
     * 接收前端传来的sessionId，生成RSA密钥对并存储到Redis
     * POST /auth/rsa-key
     * @param request 包含sessionId的请求对象
     * @return 包含公钥的响应对象
     */
    @PostMapping("/rsa-key")
    public ResponseEntity<Map<String, Object>> getRSAPublicKey(
            @RequestBody Map<String, String> request) {
        try {
            // 1. 接收前端传来的sessionId
            String sessionId = request.get("sessionId");
            
            if (sessionId == null || sessionId.trim().isEmpty()) {
                logger.warn("[获取RSA公钥] 失败 - sessionId不能为空");
                Map<String, Object> error = new HashMap<>();
                error.put("code", 400);
                error.put("success", false);
                error.put("message", "sessionId不能为空");
                return ResponseEntity.badRequest().body(error);
            }
            
            // 2. 生成RSA密钥对
            logger.info("[获取RSA公钥] 开始生成新密钥对 - SessionId: {}", sessionId);
            KeyPair keyPair = RSAKeyManager.generateKeyPair();
            
            // 3. 获取Base64编码的公钥和私钥
            String publicKeyBase64 = RSAKeyManager.getPublicKeyBase64(keyPair);
            String privateKeyBase64 = RSAKeyManager.getPrivateKeyBase64(keyPair);
            
            // 记录公钥的前50个字符用于调试
            String publicKeyPreview = publicKeyBase64.length() > 50 ? 
                publicKeyBase64.substring(0, 50) + "..." : publicKeyBase64;
            logger.info("[获取RSA公钥] 已生成新密钥对 - SessionId: {}, PublicKey预览: {}", 
                sessionId, publicKeyPreview);
            
            // 4. 创建密钥对DTO对象
            RSAKeyPairDTO keyPairDTO = new RSAKeyPairDTO(
                publicKeyBase64,
                privateKeyBase64,
                System.currentTimeMillis()
            );
            
            // 5. 将密钥对存储到Redis中，key为"rsa:key:{sessionId}"，设置300秒（5分钟）过期
            String redisKey = RSA_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(redisKey, keyPairDTO, 300, TimeUnit.SECONDS);
            
            logger.info("[获取RSA公钥] 密钥已存入Redis - Key: {}, TTL: 300秒", redisKey);
            
            // 6. 返回响应（只返回公钥，不返回sessionId）
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("success", true);
            response.put("publicKey", publicKeyBase64);
            
            logger.info("[获取RSA公钥] 成功 - SessionId: {}", sessionId);
            
            // 添加禁止缓存的头，确保前端不会缓存响应
            return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .body(response);
            
        } catch (NoSuchAlgorithmException e) {
            // 如果RSA算法不可用，返回500错误
            logger.error("[获取RSA公钥] 失败 - RSA算法不可用: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("code", 500);
            error.put("success", false);
            error.put("message", "RSA算法不可用");
            return ResponseEntity.internalServerError().body(error);
        } catch (Exception e) {
            logger.error("[获取RSA公钥] 异常 - {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("code", 500);
            error.put("success", false);
            error.put("message", "服务器内部错误：" + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 测试RSA密钥随机性接口
     * 用于调试：连续生成多个密钥对，验证是否真的随机
     * GET /auth/test-rsa-randomness
     * @return 测试结果
     */
    @GetMapping("/test-rsa-randomness")
    public ResponseEntity<Map<String, Object>> testRSARandomness() {
        try {
            int testCount = 5;
            java.util.List<String> publicKeys = new java.util.ArrayList<>();
            
            logger.info("[RSA随机性测试] 开始测试，生成{}个密钥对", testCount);
            
            for (int i = 0; i < testCount; i++) {
                KeyPair keyPair = RSAKeyManager.generateKeyPair();
                String publicKey = RSAKeyManager.getPublicKeyBase64(keyPair);
                publicKeys.add(publicKey);
                
                String preview = publicKey.length() > 30 ? 
                    publicKey.substring(0, 30) + "..." : publicKey;
                logger.info("[RSA随机性测试] 第{}个公钥: {}", i + 1, preview);
            }
            
            // 检查是否有重复
            java.util.Set<String> uniqueKeys = new java.util.HashSet<>(publicKeys);
            boolean allUnique = uniqueKeys.size() == testCount;
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("testCount", testCount);
            result.put("uniqueCount", uniqueKeys.size());
            result.put("allUnique", allUnique);
            result.put("message", allUnique ? "所有公钥都是唯一的" : "存在重复的公钥");
            
            // 只返回前3个公钥的前50个字符作为示例
            java.util.List<String> samples = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(3, publicKeys.size()); i++) {
                String pk = publicKeys.get(i);
                samples.add(pk.length() > 50 ? pk.substring(0, 50) + "..." : pk);
            }
            result.put("samples", samples);
            
            logger.info("[RSA随机性测试] 完成 - 唯一性: {}", allUnique);
            
            return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(result);
                
        } catch (Exception e) {
            logger.error("[RSA随机性测试] 失败 - {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "测试失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 用户登录接口
     * 接收前端发送的登录请求，从Redis中获取私钥，解密密码
     * @param loginRequest 登录请求对象，包含会话ID、用户ID和加密密码
     * @return 登录响应对象
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest loginRequest) {
        try {
            logger.info("[用户登录] 请求 - UserIdOrEmail: {}, SessionId: {}",
                    loginRequest.getUserIdOrEmail(), loginRequest.getSessionId());

            LoginResponse response = userService.login(loginRequest);

            if (response.isSuccess()) {
                logger.info("[用户登录] 成功 - UserIdOrEmail: {}, UserType: {}",
                        loginRequest.getUserIdOrEmail(), response.getUserType());

                // 重置sessionId的有效期为300秒
                String redisKey = RSA_KEY_PREFIX + loginRequest.getSessionId();
                redisTemplate.expire(redisKey, 300, TimeUnit.SECONDS);
                logger.debug("[用户登录] 已重置SessionId有效期 - SessionId: {}, TTL: 300秒", loginRequest.getSessionId());

                ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
                        .httpOnly(true)
                        .secure(false)
                        .path("/")
                        .maxAge(0)
                        .sameSite("Lax")
                        .build();

                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                        .body(response);
            } else {
                logger.warn("[用户登录] 失败 - UserIdOrEmail: {}, 原因: {}",
                        loginRequest.getUserIdOrEmail(), response.getMessage());

                ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
                        .httpOnly(true)
                        .secure(false)
                        .path("/")
                        .maxAge(0)
                        .sameSite("Lax")
                        .build();

                return ResponseEntity.status(response.getCode())
                        .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                        .body(response);
            }

        } catch (Exception e) {
            logger.error("[用户登录] 异常 - {}", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                    new LoginResponse(500, false, "登录失败：" + e.getMessage())
            );
        }
    }



    /**
     * 获取安全问题接口
     * 返回所有安全问题的ID和内容，用于注册时选择
     * @return 包含安全问题列表的响应对象
     */
    @GetMapping("/security-questions")
    public ResponseEntity<com.mizuka.cloudfilesystem.dto.SecurityQuestionResponse> getSecurityQuestions() {
        // 调用服务层获取所有安全问题
        com.mizuka.cloudfilesystem.dto.SecurityQuestionResponse response = securityQuestionService.getAllQuestions();

        // 根据响应结果返回相应的HTTP状态码
        if (response.isSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 用户注册接口
     * 接收前端发送的注册请求，解密密码并存入数据库
     * @param registerRequest 注册请求对象
     * @return 注册响应对象
     */
    @PostMapping("/register")
    public ResponseEntity<com.mizuka.cloudfilesystem.dto.RegisterResponse> register(
            @RequestBody com.mizuka.cloudfilesystem.dto.RegisterRequest registerRequest) {

        // 调用服务层处理注册逻辑
        com.mizuka.cloudfilesystem.dto.RegisterResponse response = userService.register(registerRequest);

        // 创建清除Cookie的响应（因为注册成功后密钥对已删除）
        ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
            .httpOnly(true)
            .secure(false)
            .path("/")
            .maxAge(0)  // 立即过期，清除Cookie
            .sameSite("Lax")
            .build();

        // 根据响应结果返回相应的HTTP状态码
        if (response.isSuccess()) {
            logger.info("[用户注册] 成功 - Nickname: {}", 
                registerRequest.getData() != null && registerRequest.getData().length > 0 
                    ? registerRequest.getData()[0].getNickname() : "Unknown");
            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(response);
        } else if (response.getCode() == 400) {
            logger.warn("[用户注册] 失败 - {}", response.getMessage());
            return ResponseEntity.badRequest()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(response);
        } else {
            logger.error("[用户注册] 失败 - {}", response.getMessage());
            return ResponseEntity.internalServerError()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(response);
        }
    }

    /**
     * 发送邮箱验证码接口
     * POST /auth/vfcode/email
     * 
     * @param request 包含邮箱地址和sessionId的请求对象
     * @return 发送结果响应对象
     */
    @PostMapping("/vfcode/email")
    public ResponseEntity<EmailVerificationResponse> sendEmailVerificationCode(
            @RequestBody EmailVerificationRequest request) {
        try {
            // 1. 验证请求参数
            if (request == null || request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                logger.warn("[发送邮箱验证码] 失败 - 邮箱地址不能为空");
                return ResponseEntity.badRequest().body(
                    new EmailVerificationResponse(400, false, "邮箱地址不能为空", null)
                );
            }

            String email = request.getEmail().trim();
            String sessionId = request.getSessionId();
            
            // 2. 验证sessionId
            if (sessionId == null || sessionId.trim().isEmpty()) {
                logger.warn("[发送邮箱验证码] 失败 - sessionId不能为空");
                return ResponseEntity.badRequest().body(
                    new EmailVerificationResponse(400, false, "sessionId不能为空", null)
                );
            }
            
            // 3. 验证RSA密钥是否已生成（检查sessionId是否存在于Redis中）
            String redisKey = RSA_KEY_PREFIX + sessionId;
            Object keyPairDTO = redisTemplate.opsForValue().get(redisKey);
            
            logger.debug("[发送邮箱验证码] 查找Redis Key: {}, 结果: {}", redisKey, keyPairDTO != null ? "找到" : "未找到");
            
            if (keyPairDTO == null) {
                logger.warn("[发送邮箱验证码] 失败 - RSA密钥未生成或已过期 - SessionId: {}, RedisKey: {}", sessionId, redisKey);
                return ResponseEntity.badRequest().body(
                    new EmailVerificationResponse(400, false, "会话已过期，请重新获取公钥", null)
                );
            }
            
            // 重置sessionId的有效期为300秒
            redisTemplate.expire(redisKey, 300, TimeUnit.SECONDS);
            logger.debug("[发送邮箱验证码] 已重置SessionId有效期 - SessionId: {}, TTL: 300秒", sessionId);
            
            logger.info("[发送邮箱验证码] 请求收到 - Email: {}, SessionId: {}", email, sessionId);

            // 4. 发送验证码（传入sessionId）
            boolean success = emailVerificationService.sendVerificationCode(email, sessionId);

            if (success) {
                logger.info("[发送邮箱验证码] 成功 - Email: {}, SessionId: {}", email, sessionId);
                return ResponseEntity.ok(
                    new EmailVerificationResponse(200, true, "验证码已发送，请查收邮件", null)
                );
            } else {
                logger.error("[发送邮箱验证码] 失败 - Email: {}", email);
                return ResponseEntity.internalServerError().body(
                    new EmailVerificationResponse(500, false, "验证码发送失败，请稍后重试", null)
                );
            }

        } catch (Exception e) {
            logger.error("[发送邮箱验证码] 异常 - {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                new EmailVerificationResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 发送短信验证码接口
     * POST /auth/vfcode/phone
     * 
     * @param request 包含手机号和sessionId的请求对象
     * @return 发送结果响应对象
     */
    @PostMapping("/vfcode/phone")
    public ResponseEntity<SmsVerificationResponse> sendSmsVerificationCode(
            @RequestBody SmsVerificationRequest request) {
        try {
            // 1. 验证请求参数
            if (request == null || request.getPhoneNumber() == null || request.getPhoneNumber().trim().isEmpty()) {
                logger.warn("[发送短信验证码] 失败 - 手机号不能为空");
                return ResponseEntity.badRequest().body(
                    new SmsVerificationResponse(400, false, "手机号不能为空", null)
                );
            }

            String phoneNumber = request.getPhoneNumber().trim();
            String sessionId = request.getSessionId();
            
            // 2. 验证sessionId
            if (sessionId == null || sessionId.trim().isEmpty()) {
                logger.warn("[发送短信验证码] 失败 - sessionId不能为空");
                return ResponseEntity.badRequest().body(
                    new SmsVerificationResponse(400, false, "sessionId不能为空", null)
                );
            }
            
            // 3. 验证RSA密钥是否已生成（检查sessionId是否存在于Redis中）
            String redisKey = RSA_KEY_PREFIX + sessionId;
            Object keyPairDTO = redisTemplate.opsForValue().get(redisKey);
            if (keyPairDTO == null) {
                logger.warn("[发送短信验证码] 失败 - RSA密钥未生成或已过期 - SessionId: {}", sessionId);
                return ResponseEntity.badRequest().body(
                    new SmsVerificationResponse(400, false, "会话已过期，请重新获取公钥", null)
                );
            }
            
            // 重置sessionId的有效期为300秒
            redisTemplate.expire(redisKey, 300, TimeUnit.SECONDS);
            logger.debug("[发送短信验证码] 已重置SessionId有效期 - SessionId: {}, TTL: 300秒", sessionId);
            
            logger.info("[发送短信验证码] 请求收到 - Phone: {}, SessionId: {}", phoneNumber, sessionId);

            // 4. 发送短信验证码（传入sessionId）
            boolean success = smsVerificationService.sendSmsVerificationCode(phoneNumber, sessionId);

            if (success) {
                logger.info("[发送短信验证码] 成功 - Phone: {}, SessionId: {}", phoneNumber, sessionId);
                return ResponseEntity.ok(
                    new SmsVerificationResponse(200, true, "验证码已发送，请注意查收", null)
                );
            } else {
                logger.warn("[发送短信验证码] 失败 - Phone: {} (可能是频率限制或发送失败)", phoneNumber);
                return ResponseEntity.status(429).body(
                    new SmsVerificationResponse(429, false, "发送过于频繁，请60秒后再试", null)
                );
            }

        } catch (Exception e) {
            logger.error("[发送短信验证码] 异常 - {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                new SmsVerificationResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 查找用户接口（重置密码第一步）
     * 根据用户ID或邮箱查找用户，返回可用的验证方式
     * POST /auth/reset_password/find_user
     * @param request 包含sessionId和加密数据的请求对象
     * @return 包含用户信息和验证方式的响应对象
     */
    @PostMapping("/reset_password/find_user")
    public ResponseEntity<FindUserResponse> findUser(@RequestBody FindUserRequest request) {
        try {
            logger.info("[查找用户] 请求收到 - SessionId: {}", 
                request != null ? request.getSessionId() : "null");

            // 调用服务层查找用户
            FindUserResponse response = userService.findUser(request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[查找用户] 成功 - SessionId: {}", request.getSessionId());
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[查找用户] 失败 - SessionId: {}, 原因: {}", 
                    request.getSessionId(), response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[查找用户] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new FindUserResponse(500, false, "服务器内部错误：" + e.getMessage(), null, null, null, null, null)
            );
        }
    }

    /**
     * 邮箱验证码验证接口（重置密码第二步）
     * 验证邮箱验证码是否正确，成功后返回resetToken
     * POST /auth/reset_password/verify/email
     * @param request 包含sessionId、email和verificationCode的请求对象
     * @return 包含resetToken的响应对象
     */
    @PostMapping("/reset_password/verify/email")
    public ResponseEntity<ResetPasswordVerifyResponse> verifyEmailForReset(@RequestBody ResetPasswordEmailVerifyRequest request) {
        try {
            logger.info("[重置密码-邮箱验证] 请求收到 - SessionId: {}, Email: {}", 
                request != null ? request.getSessionId() : "null",
                request != null ? request.getEmail() : "null");

            // 调用服务层验证邮箱
            ResetPasswordVerifyResponse response = userService.verifyEmailForReset(request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[重置密码-邮箱验证] 成功 - SessionId: {}", request.getSessionId());
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[重置密码-邮箱验证] 失败 - SessionId: {}, 原因: {}", 
                    request.getSessionId(), response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[重置密码-邮箱验证] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new ResetPasswordVerifyResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 手机验证码验证接口（重置密码第二步）
     * 验证手机验证码是否正确，成功后返回resetToken
     * POST /auth/reset_password/verify/phone
     * @param request 包含sessionId、phone和verificationCode的请求对象
     * @return 包含resetToken的响应对象
     */
    @PostMapping("/reset_password/verify/phone")
    public ResponseEntity<ResetPasswordVerifyResponse> verifyPhoneForReset(@RequestBody ResetPasswordPhoneVerifyRequest request) {
        try {
            logger.info("[重置密码-手机验证] 请求收到 - SessionId: {}, Phone: {}", 
                request != null ? request.getSessionId() : "null",
                request != null ? request.getPhone() : "null");

            // 调用服务层验证手机
            ResetPasswordVerifyResponse response = userService.verifyPhoneForReset(request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[重置密码-手机验证] 成功 - SessionId: {}", request.getSessionId());
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[重置密码-手机验证] 失败 - SessionId: {}, 原因: {}", 
                    request.getSessionId(), response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[重置密码-手机验证] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new ResetPasswordVerifyResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 密保问题答案验证接口（重置密码第二步）
     * 验证密保问题答案是否正确，成功后返回resetToken
     * POST /auth/reset_password/verify/security_answer
     * @param request 包含sessionId、userId和encryptedSecurityAnswer的请求对象
     * @return 包含resetToken的响应对象
     */
    @PostMapping("/reset_password/verify/security_answer")
    public ResponseEntity<ResetPasswordVerifyResponse> verifySecurityAnswerForReset(@RequestBody ResetPasswordSecurityVerifyRequest request) {
        try {
            logger.info("[重置密码-密保验证] 请求收到 - SessionId: {}, UserId: {}", 
                request != null ? request.getSessionId() : "null",
                request != null ? request.getUserId() : "null");

            // 调用服务层验证密保答案
            ResetPasswordVerifyResponse response = userService.verifySecurityAnswerForReset(request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[重置密码-密保验证] 成功 - SessionId: {}, UserId: {}", 
                    request.getSessionId(), request.getUserId());
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[重置密码-密保验证] 失败 - SessionId: {}, UserId: {}, 原因: {}", 
                    request.getSessionId(), request.getUserId(), response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[重置密码-密保验证] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new ResetPasswordVerifyResponse(500, false, "服务器内部错误：" + e.getMessage(), null)
            );
        }
    }

    /**
     * 重置密码接口（最后一步）
     * 使用resetToken验证身份，设置新密码
     * POST /auth/reset_password/reset
     * @param authorization Authorization头，格式：Bearer {resetToken}
     * @param request 包含sessionId和encryptedNewPassword的请求对象
     * @return 重置密码响应对象
     */
    @PostMapping("/reset_password/reset")
    public ResponseEntity<ResetPasswordResponse> resetPassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ResetPasswordRequest request) {
        try {
            logger.info("[重置密码] 请求收到 - SessionId: {}", 
                request != null ? request.getSessionId() : "null");

            // 提取Bearer Token
            String token = null;
            if (authorization != null && authorization.startsWith("Bearer ")) {
                token = authorization.substring(7); // 移除 "Bearer " 前缀
                logger.debug("[重置密码] 已提取Token");
            } else {
                logger.warn("[重置密码] 失败 - 未提供Authorization头或格式不正确");
            }

            // 调用服务层重置密码
            ResetPasswordResponse response = userService.resetPassword(token, request);

            // 返回响应
            if (response.isSuccess()) {
                logger.info("[重置密码] 成功 - SessionId: {}", request.getSessionId());
                return ResponseEntity.ok(response);
            } else {
                logger.warn("[重置密码] 失败 - SessionId: {}, 原因: {}", 
                    request.getSessionId(), response.getMessage());
                return ResponseEntity.status(response.getCode()).body(response);
            }

        } catch (Exception e) {
            logger.error("[重置密码] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                new ResetPasswordResponse(500, false, "服务器内部错误：" + e.getMessage())
            );
        }
    }



}
