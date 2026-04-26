package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.LoginRequest;
import com.mizuka.cloudfilesystem.dto.LoginResponse;
import com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO;
import com.mizuka.cloudfilesystem.dto.RSAPublicKeyResponse;
import com.mizuka.cloudfilesystem.util.RSAKeyManager;
import com.mizuka.cloudfilesystem.dto.RSAValidationRequest;
import com.mizuka.cloudfilesystem.dto.RSAValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 认证控制器
 * 处理用户认证相关的请求，包括获取RSA公钥和用户登录
 */
@RestController
@RequestMapping("/api/auth")
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
    
    // 注入RedisTemplate用于操作Redis缓存
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 注入安全问题服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.SecurityQuestionService securityQuestionService;

    // 注入用户服务
    @Autowired
    private com.mizuka.cloudfilesystem.service.UserService userService;


    // Redis中存储密钥对的key前缀
    private static final String RSA_KEY_PREFIX = "rsa:key:";
    
    // 密钥对在Redis中的过期时间（单位：分钟）
    private static final long KEY_EXPIRE_MINUTES = 5;
    
    /**
     * 获取RSA公钥接口
     * 生成新的密钥对，将公钥返回给前端，私钥存储到Redis中
     * @return 包含公钥和会话ID的响应对象
     */
    @GetMapping("/rsa-key")
    public ResponseEntity<RSAPublicKeyResponse> getRSAPublicKey() {
        try {
            // 生成唯一的会话ID
            String sessionId = UUID.randomUUID().toString();
            
            // 生成RSA密钥对（不需要传递参数）
            KeyPair keyPair = RSAKeyManager.generateKeyPair();
            
            // 获取Base64编码的公钥
            String publicKeyBase64 = RSAKeyManager.getPublicKeyBase64(keyPair);
            
            // 获取Base64编码的私钥
            String privateKeyBase64 = RSAKeyManager.getPrivateKeyBase64(keyPair);
            
            // 创建密钥对DTO对象
            RSAKeyPairDTO keyPairDTO = new RSAKeyPairDTO(
                publicKeyBase64,
                privateKeyBase64,
                System.currentTimeMillis()
            );
            
            // 将密钥对存储到Redis中，key为"rsa:key:{sessionId}"，设置5分钟过期
            String redisKey = RSA_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(redisKey, keyPairDTO, KEY_EXPIRE_MINUTES, TimeUnit.MINUTES);
            
            // 创建响应对象，只返回公钥和会话ID（不返回私钥）
            RSAPublicKeyResponse response = new RSAPublicKeyResponse(
                publicKeyBase64,
                sessionId,
                System.currentTimeMillis()
            );
            
            // 创建Cookie，存储sessionId
            ResponseCookie sessionCookie = ResponseCookie.from("rsa_session_id", sessionId)
                .httpOnly(false)  // 允许前端JavaScript读取
                .secure(false)   // 开发环境使用false，生产环境使用true
                .path("/")       // Cookie作用路径
                .maxAge(KEY_EXPIRE_MINUTES * 60)  // 过期时间（秒）
                .sameSite("Lax") // CSRF保护
                .build();
            
            // 返回响应并设置Cookie
            logger.info("[获取RSA公钥] 成功 - SessionId: {}", sessionId);
            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
                .body(response);
            
        } catch (NoSuchAlgorithmException e) {
            // 如果RSA算法不可用，返回500错误
            logger.error("[获取RSA公钥] 失败 - RSA算法不可用: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
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
            // 从请求中获取会话ID
            String sessionId = loginRequest.getSessionId();
            
            // 构建Redis中的key
            String redisKey = RSA_KEY_PREFIX + sessionId;
            
            // 从Redis中获取密钥对对象
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);
            
            // 检查密钥对是否存在或已过期
            if (keyPairDTO == null) {
                logger.warn("[用户登录] 失败 - 会话已过期或无效, SessionId: {}", sessionId);
                // 返回400错误码，表示请求参数错误或会话过期
                return ResponseEntity.badRequest().body(
                    new LoginResponse(400, false, "会话已过期或无效，请重新获取公钥")
                );
            }
            
            // 获取加密的密码
            String encryptedPassword = loginRequest.getEncryptedPassword();
            
            // 获取私钥
            String privateKey = keyPairDTO.getPrivateKey();
            
            // 使用私钥解密密码
            String decryptedPassword = RSAKeyManager.decryptWithPrivateKey(encryptedPassword, privateKey);
            
            // 打印解密后的密码（实际应用中应该与数据库中的密码进行比对）
            System.out.println("用户ID: " + loginRequest.getUserId());
            System.out.println("解密后的密码: " + decryptedPassword);
            
            // TODO: 在这里添加实际的登录验证逻辑
            // 例如：查询数据库，验证用户ID和密码是否正确
            
            // 登录成功后，可以选择从Redis中删除该密钥对（一次性使用）
            redisTemplate.delete(redisKey);
            
            logger.info("[用户登录] 成功 - UserId: {}", loginRequest.getUserId());
            
            // 创建清除Cookie的响应（因为密钥对已删除）
            ResponseCookie clearCookie = ResponseCookie.from("rsa_session_id", "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(0)  // 立即过期，清除Cookie
                .sameSite("Lax")
                .build();
            
            // 返回200成功码和登录成功响应
            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
                .body(new LoginResponse(200, true, "登录成功", loginRequest.getUserId()));
            
        } catch (Exception e) {
            // 捕获解密异常或其他异常
            logger.error("[用户登录] 失败 - {}", e.getMessage());
            e.printStackTrace();
            // 返回500错误码，表示服务器内部错误
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
     * 验证RSA密钥对有效性接口
     * 检查Redis中是否存在有效的密钥对，如果有效则重置有效期，否则生成新的密钥对
     * @param validationRequest 验证请求对象
     * @return 验证响应对象
     */
    @PostMapping("/is_rsa_valid")
    public ResponseEntity<RSAValidationResponse> validateRsaKey(
            @RequestBody RSAValidationRequest validationRequest) {

        try {
            String sessionId = validationRequest.getSessionId();
            String providedPublicKey = validationRequest.getPublicKey();

            // 构建Redis中的key
            String redisKey = RSA_KEY_PREFIX + sessionId;

            // 从Redis中获取密钥对对象
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            // 检查密钥对是否存在且有效
            // 去除空格和换行符后进行比较，避免格式差异导致的验证失败
            if (keyPairDTO != null) {
                String storedPublicKey = keyPairDTO.getPublicKey().replaceAll("\\s+", "");
                String cleanProvidedPublicKey = providedPublicKey != null ? providedPublicKey.replaceAll("\\s+", "") : "";
                
                if (storedPublicKey.equals(cleanProvidedPublicKey)) {
                    // 密钥对有效，重置有效期（再延长5分钟）
                    redisTemplate.expire(redisKey, KEY_EXPIRE_MINUTES, TimeUnit.MINUTES);

                    logger.info("[RSA验证] 成功 - SessionId: {}, 状态: 有效", sessionId);

                    // 创建Cookie，更新sessionId（保持不变）
                ResponseCookie sessionCookie = ResponseCookie.from("rsa_session_id", sessionId)
                    .httpOnly(false)  // 允许前端JavaScript读取
                    .secure(false)
                    .path("/")
                    .maxAge(KEY_EXPIRE_MINUTES * 60)
                    .sameSite("Lax")
                    .build();

                    // 返回有效响应，使用原有的sessionId和公钥
                    return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
                        .body(new RSAValidationResponse(
                                200,
                                true,
                                "RSA密钥对有效",
                                true,
                                keyPairDTO.getPublicKey(),
                                sessionId,
                                System.currentTimeMillis()
                        ));
                } else {
                    logger.warn("[RSA验证] 失败 - SessionId: {}, 原因: 公钥不匹配", sessionId);
                    // 密钥对无效或不存在，生成新的密钥对
                    String newSessionId = UUID.randomUUID().toString();
                    KeyPair newKeyPair = RSAKeyManager.generateKeyPair();
                    String newPublicKey = RSAKeyManager.getPublicKeyBase64(newKeyPair);
                    String newPrivateKey = RSAKeyManager.getPrivateKeyBase64(newKeyPair);

                    // 创建新的密钥对DTO
                    RSAKeyPairDTO newKeyPairDTO = new RSAKeyPairDTO(
                            newPublicKey,
                            newPrivateKey,
                            System.currentTimeMillis()
                    );

                    // 存储到Redis中
                    String newRedisKey = RSA_KEY_PREFIX + newSessionId;
                    redisTemplate.opsForValue().set(newRedisKey, newKeyPairDTO, KEY_EXPIRE_MINUTES, TimeUnit.MINUTES);

                    logger.info("[RSA验证] 成功 - 原SessionId: {}, 新SessionId: {}, 状态: 已生成新密钥对", sessionId, newSessionId);

                    // 创建新的Cookie，存储新的sessionId
                ResponseCookie newSessionCookie = ResponseCookie.from("rsa_session_id", newSessionId)
                    .httpOnly(false)  // 允许前端JavaScript读取
                    .secure(false)
                    .path("/")
                    .maxAge(KEY_EXPIRE_MINUTES * 60)
                    .sameSite("Lax")
                    .build();

                    // 返回无效响应，附带新的公钥和sessionId
                    return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, newSessionCookie.toString())
                        .body(new RSAValidationResponse(
                                200,
                                true,
                                "RSA密钥对已失效，已生成新的密钥对",
                                false,
                                newPublicKey,
                                newSessionId,
                                System.currentTimeMillis()
                        ));
                }
            } else {
                logger.warn("[RSA验证] 失败 - SessionId: {}, 原因: Redis中未找到密钥对", sessionId);

                // 密钥对无效或不存在，生成新的密钥对
                String newSessionId = UUID.randomUUID().toString();
                KeyPair newKeyPair = RSAKeyManager.generateKeyPair();
                String newPublicKey = RSAKeyManager.getPublicKeyBase64(newKeyPair);
                String newPrivateKey = RSAKeyManager.getPrivateKeyBase64(newKeyPair);

                // 创建新的密钥对DTO
                RSAKeyPairDTO newKeyPairDTO = new RSAKeyPairDTO(
                        newPublicKey,
                        newPrivateKey,
                        System.currentTimeMillis()
                );

                // 存储到Redis中
                String newRedisKey = RSA_KEY_PREFIX + newSessionId;
                redisTemplate.opsForValue().set(newRedisKey, newKeyPairDTO, KEY_EXPIRE_MINUTES, TimeUnit.MINUTES);

                logger.info("[RSA验证] 成功 - 原SessionId: {}, 新SessionId: {}, 状态: 已生成新密钥对", sessionId, newSessionId);

                // 创建新的Cookie，存储新的sessionId
                ResponseCookie newSessionCookie = ResponseCookie.from("rsa_session_id", newSessionId)
                    .httpOnly(false)  // 允许前端JavaScript读取
                    .secure(false)
                    .path("/")
                    .maxAge(KEY_EXPIRE_MINUTES * 60)
                    .sameSite("Lax")
                    .build();

                // 返回无效响应，附带新的公钥和sessionId
                return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, newSessionCookie.toString())
                    .body(new RSAValidationResponse(
                            200,
                            true,
                            "RSA密钥对已失效，已生成新的密钥对",
                            false,
                            newPublicKey,
                            newSessionId,
                            System.currentTimeMillis()
                    ));
            }
        } catch (Exception e) {
            logger.error("[RSA验证] 失败 - SessionId: {}, 错误: {}", validationRequest.getSessionId(), e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(new RSAValidationResponse(
                    500,
                    false,
                    "验证失败：" + e.getMessage(),
                    false,
                    null,
                    null,
                    System.currentTimeMillis()
            ));
        }
    }



}
