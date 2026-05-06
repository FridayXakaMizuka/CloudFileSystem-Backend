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
import com.mizuka.cloudfilesystem.dto.TwoFactorVerifyRequest;
import com.mizuka.cloudfilesystem.dto.TwoFactorVerifyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    // 注入二次验证服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.TwoFactorAuthService twoFactorAuthService;

    // 注入JWT工具类
    @Autowired
    private com.mizuka.cloudfilesystem.util.JwtUtil jwtUtil;

    // 注入二次验证RedisTemplate
    //@Autowired
    //@Qualifier("twoFactorRedisTemplate")
    //private RedisTemplate<String, Object> twoFactorRedisTemplate;


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
     * 接收前端发送的登录请求，从 Redis中获取私钥，解密密码
     * @param loginRequest 登录请求对象，包含会话ID、用户ID和加密密码
     * @return 登录响应对象
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest loginRequest,
            @RequestHeader(value = "X-Client-Type", required = false) String clientType,
            @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
            @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
            @RequestHeader(value = "X-Electron-Version", required = false) String electronVersion,
            @RequestHeader(value = "X-Browser-Type", required = false) String browserType,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @RequestHeader(value = "X-Hardware-Id", required = false) String hardwareId,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        try {
            logger.info("[用户登录] 请求 - UserIdOrEmail: {}, SessionId: {}, ClientType: {}",
                    loginRequest.getUserIdOrEmail(), loginRequest.getSessionId(), clientType);
    
            // 1. 验证用户凭证
            LoginResponse response = userService.login(loginRequest);
    
            if (!response.isSuccess()) {
                logger.warn("[用户登录] 失败 - UserIdOrEmail: {}, 原因: {}",
                        loginRequest.getUserIdOrEmail(), response.getMessage());
    
                ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
                        .httpOnly(true)
                        .secure(true)  // HTTPS环境下必须设置为true
                        .path("/")
                        .maxAge(0)
                        .sameSite("Lax")
                        .build();
    
                return ResponseEntity.status(response.getCode())
                        .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                        .body(response);
            }
    
            // 2. 登录成功，判断是否需要二次验证
            Long userId = Long.parseLong(response.getUserId());
            String ipAddress = getClientIp(httpRequest);
                
            boolean requiresTwoFactor = twoFactorAuthService.requiresTwoFactor(
                userId, 
                clientType != null ? clientType : "browser",
                deviceFingerprint,
                hardwareId
            );
                
            if (requiresTwoFactor) {
                logger.info("[用户登录] 需要二次验证 - UserId: {}, ClientType: {}", userId, clientType);
                    
                // 2.1 使用前端传来的sessionId用于二次验证
                String twoFactorSessionId = loginRequest.getSessionId();
                logger.info("[用户登录] 前端传来的sessionId: {}", twoFactorSessionId);
                    
                // 2.2 存储会话信息到Redis（端口6379）
                Map<String, Object> sessionData = new HashMap<>();
                sessionData.put("userId", userId);
                sessionData.put("email", response.getEmail() != null ? response.getEmail() : "");
                sessionData.put("phone", response.getPhone() != null ? response.getPhone() : "");
                sessionData.put("deviceFingerprint", deviceFingerprint);
                sessionData.put("clientType", clientType != null ? clientType : "browser");
                sessionData.put("clientIdentifier", clientIdentifier);
                sessionData.put("platform", clientPlatform);
                sessionData.put("hardwareId", hardwareId);
                sessionData.put("createdAt", System.currentTimeMillis());
                    
                logger.info("[用户登录] 准备存储二次验证会话 - sessionId: {}, userId: {}", twoFactorSessionId, userId);
                twoFactorAuthService.storeSessionData(twoFactorSessionId, sessionData, 
                    clientType != null ? clientType : "browser");
                    
                // 2.3 返回需要二次验证的响应
                LoginResponse twoFactorResponse = new LoginResponse();
                twoFactorResponse.setCode(200);
                twoFactorResponse.setSuccess(true);
                twoFactorResponse.setMessage("需要二次验证");
                twoFactorResponse.setRequiresTwoFactor(true);
                twoFactorResponse.setSessionId(twoFactorSessionId);  // ✅ 使用前端传来的sessionId
                twoFactorResponse.setUserId(response.getUserId());
                twoFactorResponse.setEmail(response.getEmail());
                twoFactorResponse.setPhone(response.getPhone());
                    
                // 获取密保问题
                var user = userService.getUserById(userId);
                if (user != null && user.getSecurityQuestionId() != null) {
                    var questionText = securityQuestionService.getQuestionTextById(user.getSecurityQuestionId());
                    if (questionText != null) {
                        twoFactorResponse.setSecurityQuestion(questionText);
                        twoFactorResponse.setSecurityQuestionId(user.getSecurityQuestionId());
                    }
                }
                    
                // 清除RSA Cookie
                ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
                        .httpOnly(true)
                        .secure(true)  // HTTPS环境下必须设置为true
                        .path("/")
                        .maxAge(0)
                        .sameSite("Lax")
                        .build();
                    
                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                        .body(twoFactorResponse);
                    
            } else {
                // 3. 不需要二次验证，直接返回登录成功
                logger.info("[用户登录] 成功（信任设备） - UserIdOrEmail: {}, UserType: {}",
                        loginRequest.getUserIdOrEmail(), response.getUserType());
    
                // ✅ 不需要二次验证时，删除RSA密钥对（一次性使用）
                String redisKey = RSA_KEY_PREFIX + loginRequest.getSessionId();
                redisTemplate.delete(redisKey);
                logger.debug("[用户登录] 已删除RSA密钥对 - SessionId: {}", loginRequest.getSessionId());
    
                ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
                        .httpOnly(true)
                        .secure(true)  // HTTPS环境下必须设置为true
                        .path("/")
                        .maxAge(0)
                        .sameSite("Lax")
                        .build();
    
                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                        .body(response);
            }
    
        } catch (Exception e) {
            logger.error("[用户登录] 异常 - {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    new LoginResponse(500, false, "登录失败：" + e.getMessage())
            );
        }
    }
        
    /**
     * 获取客户端IP地址
     * 优先级：X-Client-IP > X-Forwarded-For > X-Real-IP > RemoteAddr
     */
    private String getClientIp(jakarta.servlet.http.HttpServletRequest request) {
        // 1. 优先从 X-Client-IP 获取（某些 CDN 或代理使用）
        String ip = request.getHeader("X-Client-IP");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // 2. 从 X-Forwarded-For 获取（经过代理/负载均衡）
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // 3. 从 X-Real-IP 获取（Nginx 等反向代理常用）
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            // 4. 直接获取远程地址
            ip = request.getRemoteAddr();
        }
        // 如果是多个IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
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
            .secure(true)  // HTTPS环境下必须设置为true
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
    public ResponseEntity<ResetPasswordVerifyResponse> verifyEmailForReset(
            @RequestBody ResetPasswordEmailVerifyRequest request,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint) {
        try {
            logger.info("[重置密码-邮箱验证] 请求收到 - SessionId: {}, Email: {}, DeviceFingerprint: {}", 
                request != null ? request.getSessionId() : "null",
                request != null ? request.getEmail() : "null",
                deviceFingerprint != null ? "已提供" : "未提供");

            // 调用服务层验证邮箱
            ResetPasswordVerifyResponse response = userService.verifyEmailForReset(request, deviceFingerprint);

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
    public ResponseEntity<ResetPasswordVerifyResponse> verifyPhoneForReset(
            @RequestBody ResetPasswordPhoneVerifyRequest request,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint) {
        try {
            logger.info("[重置密码-手机验证] 请求收到 - SessionId: {}, Phone: {}, DeviceFingerprint: {}", 
                request != null ? request.getSessionId() : "null",
                request != null ? request.getPhone() : "null",
                deviceFingerprint != null ? "已提供" : "未提供");

            // 调用服务层验证手机
            ResetPasswordVerifyResponse response = userService.verifyPhoneForReset(request, deviceFingerprint);

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
    public ResponseEntity<ResetPasswordVerifyResponse> verifySecurityAnswerForReset(
            @RequestBody ResetPasswordSecurityVerifyRequest request,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint) {
        try {
            logger.info("[重置密码-密保验证] 请求收到 - SessionId: {}, UserId: {}, DeviceFingerprint: {}", 
                request != null ? request.getSessionId() : "null",
                request != null ? request.getUserId() : "null",
                deviceFingerprint != null ? "已提供" : "未提供");

            // 调用服务层验证密保答案
            ResetPasswordVerifyResponse response = userService.verifySecurityAnswerForReset(request, deviceFingerprint);

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
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @RequestBody ResetPasswordRequest request) {
        try {
            logger.info("[重置密码] 请求收到 - SessionId: {}, DeviceFingerprint: {}", 
                request != null ? request.getSessionId() : "null",
                deviceFingerprint != null ? "已提供" : "未提供");

            // 提取Bearer Token
            String token = null;
            if (authorization != null && authorization.startsWith("Bearer ")) {
                token = authorization.substring(7); // 移除 "Bearer " 前缀
                logger.debug("[重置密码] 已提取Token");
            } else {
                logger.warn("[重置密码] 失败 - 未提供Authorization头或格式不正确");
            }

            // 调用服务层重置密码
            ResetPasswordResponse response = userService.resetPassword(token, request, deviceFingerprint);

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

    /**
     * 密保问题二次验证接口
     * POST /auth/verify/security_answer
     */
    @PostMapping("/verify/security_answer")
    public ResponseEntity<TwoFactorVerifyResponse> verifySecurityAnswer(
            @RequestBody TwoFactorVerifyRequest verifyRequest,
            @RequestHeader(value = "X-Client-Type", required = false) String clientType,
            @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
            @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @RequestHeader(value = "X-Hardware-Id", required = false) String hardwareId,
            @RequestHeader(value = "X-Electron-Version", required = false) String electronVersion,
            @RequestHeader(value = "X-Browser-Type", required = false) String browserType,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        try {
            String sessionId = verifyRequest.getSessionId();
            Long userId = verifyRequest.getUserId();
            String encryptedAnswer = verifyRequest.getEncryptedAnswer();
            
            logger.info("[密保验证] 请求 - SessionId: {}, UserId: {}, ClientType: {}", 
                       sessionId, userId, clientType);
            
            // 1. 验证参数
            if (sessionId == null || sessionId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话ID不能为空", null, null, null, null)
                );
            }
            
            if (userId == null) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "用户ID不能为空", null, null, null, null)
                );
            }
            
            if (encryptedAnswer == null || encryptedAnswer.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "答案不能为空", null, null, null, null)
                );
            }
            
            // 2. 从Redis获取会话信息（端口6378）
            Map<String, Object> sessionData = twoFactorAuthService.getSessionData(sessionId, null);
            if (sessionData == null) {
                logger.warn("[密保验证] 会话不存在或已过期 - SessionId: {}", sessionId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话已过期，请重新登录", null, null, null, null)
                );
            }
            
            // 3. 从Redis获取RSA密钥对（端口6379）
            String redisKey = RSA_KEY_PREFIX + sessionId;
            com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO keyPairDTO = 
                (com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);
            
            if (keyPairDTO == null) {
                logger.warn("[密保验证] RSA密钥不存在或已过期 - SessionId: {}", sessionId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话已过期，请重新获取公钥", null, null, null, null)
                );
            }
            
            // 4. 使用私钥解密答案
            String decryptedAnswer;
            try {
                decryptedAnswer = com.mizuka.cloudfilesystem.util.RSAKeyManager.decryptWithPrivateKey(
                    encryptedAnswer,
                    keyPairDTO.getPrivateKey()
                );
                logger.debug("[密保验证] 答案解密成功 - SessionId: {}", sessionId);
            } catch (Exception e) {
                logger.error("[密保验证] 答案解密失败 - SessionId: {}, Error: {}", sessionId, e.getMessage());
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "答案解密失败：" + e.getMessage(), null, null, null, null)
                );
            }
            
            // 5. 验证答案
            boolean verified = twoFactorAuthService.verifySecurityAnswer(userId, decryptedAnswer);
            
            // 6. 记录日志
            String ipAddress = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");
            
            twoFactorAuthService.logVerification(
                userId, null, deviceFingerprint, "security_answer", verified,
                verified ? null : "答案错误",
                clientType != null ? clientType : "browser",
                clientPlatform, ipAddress, userAgent
            );
            
            if (!verified) {
                logger.warn("[密保验证] 验证失败 - UserId: {}", userId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "答案错误", null, null, null, null)
                );
            }
            
            // 7. 验证成功，生成JWT
            String nickname = ""; // 不再在 JWT中包含nickname
            String userType = getUserTypeFromUserId(userId);
            java.time.LocalDateTime registeredAt = getRegisteredAtFromUserId(userId);
            
            // 确定JWT有效期
            Long tokenExpiration;
            if ("browser".equalsIgnoreCase(clientType)) {
                tokenExpiration = 86400L;  // 24小时
            } else {
                tokenExpiration = 2592000L;  // 30天
            }
            
            // 生成包含设备指纹的JWT令牌
            String token = jwtUtil.generateTokenWithDeviceFingerprint(
                userId, nickname, userType, registeredAt, tokenExpiration, deviceFingerprint
            );
            
            // 8. 如果是客户端且验证成功，保存为信任设备
            if (!"browser".equalsIgnoreCase(clientType)) {
                twoFactorAuthService.saveTrustedDevice(
                    userId, clientType, deviceFingerprint, hardwareId,
                    clientIdentifier, clientPlatform, ipAddress
                );
            }

            // 9. 如果是浏览器且验证成功，保存到Redis（端口6378）作为临时信任设备
            if ("browser".equalsIgnoreCase(clientType)) {
                browserType = httpRequest.getHeader("X-Browser-Type");
                twoFactorAuthService.saveBrowserTrustedDevice(
                    userId, deviceFingerprint, browserType, ipAddress
                );
                logger.info("[密保验证] 浏览器已保存为临时信任设备 - UserId: {}, Browser: {}", userId, browserType);
            }
            
            // 10. 清除Redis中的会话数据
            twoFactorAuthService.clearSessionData(sessionId, clientType);
            
            // 11. 删除RSA密钥对（一次性使用）
            redisTemplate.delete(redisKey);
            
            // 12. 返回成功响应
            logger.info("[密保验证] 验证成功 - UserId: {}, ClientType: {}", userId, clientType);
            
            TwoFactorVerifyResponse response = new TwoFactorVerifyResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("验证成功");
            response.setToken(token);
            response.setUserId(userId);
            response.setUserType(userType);
            response.setHomeDirectory("user".equals(userType) ? "/users/" + userId : "/admin/");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("[密保验证] 异常 - {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                new TwoFactorVerifyResponse(500, false, "服务器内部错误", null, null, null, null)
            );
        }
    }
    
    /**
     * 根据用户ID获取用户类型
     */
    private String getUserTypeFromUserId(Long userId) {
        // User表中的用户都是普通用户，返回"user"
        // 如果是管理员，应该在administrators表中查询
        return "user";
    }
    
    /**
     * 根据用户ID获取注册时间
     */
    private java.time.LocalDateTime getRegisteredAtFromUserId(Long userId) {
        var user = userService.getUserById(userId);
        return user != null ? user.getRegisteredAt() : java.time.LocalDateTime.now();
    }
    
    /**
     * 邮箱验证码二次验证接口
     * POST /auth/verify/email
     */
    @PostMapping("/verify/email")
    public ResponseEntity<TwoFactorVerifyResponse> verifyEmail(
            @RequestBody TwoFactorVerifyRequest verifyRequest,
            @RequestHeader(value = "X-Client-Type", required = false) String clientType,
            @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
            @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @RequestHeader(value = "X-Hardware-Id", required = false) String hardwareId,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        try {
            String sessionId = verifyRequest.getSessionId();
            Long userId = verifyRequest.getUserId();
            String verificationCode = verifyRequest.getVerificationCode();
            
            logger.info("[邮箱验证] 请求 - SessionId: {}, UserId: {}, ClientType: {}", 
                       sessionId, userId, clientType);
            
            // 1. 验证参数
            if (sessionId == null || sessionId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话ID不能为空", null, null, null, null)
                );
            }
            
            if (userId == null) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "用户ID不能为空", null, null, null, null)
                );
            }
            
            if (verificationCode == null || verificationCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "验证码不能为空", null, null, null, null)
                );
            }
            
            // 2. 从Redis获取会话信息（端口6379）
            Map<String, Object> sessionData = twoFactorAuthService.getSessionData(sessionId, null);
            if (sessionData == null) {
                logger.warn("[邮箱验证] 会话不存在或已过期 - SessionId: {}", sessionId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话已过期，请重新登录", null, null, null, null)
                );
            }
            
            // 3. 验证邮箱验证码
            boolean verified = twoFactorAuthService.verifyEmailCode(sessionId, verificationCode);
            
            // 4. 记录日志
            String ipAddress = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");
            
            twoFactorAuthService.logVerification(
                userId, null, deviceFingerprint, "email", verified,
                verified ? null : "验证码错误或已过期",
                clientType != null ? clientType : "browser",
                clientPlatform, ipAddress, userAgent
            );
            
            if (!verified) {
                logger.warn("[邮箱验证] 验证失败 - UserId: {}", userId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "验证码错误或已过期", null, null, null, null)
                );
            }
            
            // 5. 验证成功，生成JWT
            String nickname = ""; // 不再在 JWT中包含nickname
            String userType = getUserTypeFromUserId(userId);
            java.time.LocalDateTime registeredAt = getRegisteredAtFromUserId(userId);
            
            // 确定JWT有效期
            Long tokenExpiration;
            if ("browser".equalsIgnoreCase(clientType)) {
                tokenExpiration = 86400L;  // 24小时
            } else {
                tokenExpiration = 2592000L;  // 30天
            }
            
            // 生成包含设备指纹的JWT令牌
            String token = jwtUtil.generateTokenWithDeviceFingerprint(
                userId, nickname, userType, registeredAt, tokenExpiration, deviceFingerprint
            );
            
            // 6. 如果是浏览器且验证成功，保存到Redis（端口6378）作为临时信任设备
            if ("browser".equalsIgnoreCase(clientType)) {
                String browserType = httpRequest.getHeader("X-Browser-Type");
                twoFactorAuthService.saveBrowserTrustedDevice(
                    userId, deviceFingerprint, browserType, ipAddress
                );
                logger.info("[邮箱验证] 浏览器已保存为临时信任设备 - UserId: {}, Browser: {}", userId, browserType);
            }
            
            // 7. 如果是客户端且验证成功，保存为信任设备到MySQL
            if (!"browser".equalsIgnoreCase(clientType)) {
                twoFactorAuthService.saveTrustedDevice(
                    userId, clientType, deviceFingerprint, hardwareId,
                    clientIdentifier, clientPlatform, ipAddress
                );
            }
            
            // 8. 清除Redis中的会话数据
            twoFactorAuthService.clearSessionData(sessionId, clientType);
            
            // 9. 返回成功响应
            logger.info("[邮箱验证] 验证成功 - UserId: {}, ClientType: {}", userId, clientType);
            
            TwoFactorVerifyResponse response = new TwoFactorVerifyResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("验证成功");
            response.setToken(token);
            response.setUserId(userId);
            response.setUserType(userType);
            response.setHomeDirectory("user".equals(userType) ? "/users/" + userId : "/admin/");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("[邮箱验证] 异常 - {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                new TwoFactorVerifyResponse(500, false, "服务器内部错误", null, null, null, null)
            );
        }
    }
    
    /**
     * 手机验证码二次验证接口
     * POST /auth/verify/phone
     */
    @PostMapping("/verify/phone")
    public ResponseEntity<TwoFactorVerifyResponse> verifyPhone(
            @RequestBody TwoFactorVerifyRequest verifyRequest,
            @RequestHeader(value = "X-Client-Type", required = false) String clientType,
            @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
            @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
            @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
            @RequestHeader(value = "X-Hardware-Id", required = false) String hardwareId,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        try {
            String sessionId = verifyRequest.getSessionId();
            Long userId = verifyRequest.getUserId();
            String verificationCode = verifyRequest.getVerificationCode();
            
            logger.info("[手机验证] 请求 - SessionId: {}, UserId: {}, ClientType: {}", 
                       sessionId, userId, clientType);
            
            // 1. 验证参数
            if (sessionId == null || sessionId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话ID不能为空", null, null, null, null)
                );
            }
            
            if (userId == null) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "用户ID不能为空", null, null, null, null)
                );
            }
            
            if (verificationCode == null || verificationCode.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "验证码不能为空", null, null, null, null)
                );
            }
            
            // 2. 从Redis获取会话信息（端口6379）
            Map<String, Object> sessionData = twoFactorAuthService.getSessionData(sessionId, null);
            if (sessionData == null) {
                logger.warn("[手机验证] 会话不存在或已过期 - SessionId: {}", sessionId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "会话已过期，请重新登录", null, null, null, null)
                );
            }
            
            // 3. 验证手机验证码
            boolean verified = twoFactorAuthService.verifyPhoneCode(sessionId, verificationCode);
            
            // 4. 记录日志
            String ipAddress = getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");
            
            twoFactorAuthService.logVerification(
                userId, null, deviceFingerprint, "phone", verified,
                verified ? null : "验证码错误或已过期",
                clientType != null ? clientType : "browser",
                clientPlatform, ipAddress, userAgent
            );
            
            if (!verified) {
                logger.warn("[手机验证] 验证失败 - UserId: {}", userId);
                return ResponseEntity.badRequest().body(
                    new TwoFactorVerifyResponse(400, false, "验证码错误或已过期", null, null, null, null)
                );
            }
            
            // 5. 验证成功，生成JWT
            String nickname = ""; // 不再在 JWT中包含nickname
            String userType = getUserTypeFromUserId(userId);
            java.time.LocalDateTime registeredAt = getRegisteredAtFromUserId(userId);
            
            // 确定JWT有效期
            Long tokenExpiration;
            if ("browser".equalsIgnoreCase(clientType)) {
                tokenExpiration = 86400L;  // 24小时
            } else {
                tokenExpiration = 2592000L;  // 30天
            }
            
            // 生成包含设备指纹的JWT令牌
            String token = jwtUtil.generateTokenWithDeviceFingerprint(
                userId, nickname, userType, registeredAt, tokenExpiration, deviceFingerprint
            );
            
            // 6. 如果是浏览器且验证成功，保存到Redis（端口6378）作为临时信任设备
            if ("browser".equalsIgnoreCase(clientType)) {
                String browserType = httpRequest.getHeader("X-Browser-Type");
                twoFactorAuthService.saveBrowserTrustedDevice(
                    userId, deviceFingerprint, browserType, ipAddress
                );
                logger.info("[手机验证] 浏览器已保存为临时信任设备 - UserId: {}, Browser: {}", userId, browserType);
            }
            
            // 7. 如果是客户端且验证成功，保存为信任设备到MySQL
            if (!"browser".equalsIgnoreCase(clientType)) {
                twoFactorAuthService.saveTrustedDevice(
                    userId, clientType, deviceFingerprint, hardwareId,
                    clientIdentifier, clientPlatform, ipAddress
                );
            }
            
            // 8. 清除Redis中的会话数据
            twoFactorAuthService.clearSessionData(sessionId, clientType);
            
            // 9. 返回成功响应
            logger.info("[手机验证] 验证成功 - UserId: {}, ClientType: {}", userId, clientType);
            
            TwoFactorVerifyResponse response = new TwoFactorVerifyResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("验证成功");
            response.setToken(token);
            response.setUserId(userId);
            response.setUserType(userType);
            response.setHomeDirectory("user".equals(userType) ? "/users/" + userId : "/admin/");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("[手机验证] 异常 - {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                new TwoFactorVerifyResponse(500, false, "服务器内部错误", null, null, null, null)
            );
        }
    }



}
