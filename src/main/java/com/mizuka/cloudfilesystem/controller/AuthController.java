package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.LoginRequest;
import com.mizuka.cloudfilesystem.dto.LoginResponse;
import com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO;
import com.mizuka.cloudfilesystem.dto.RSAPublicKeyResponse;
import com.mizuka.cloudfilesystem.util.RSAKeyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
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
    
    // 注入RedisTemplate用于操作Redis缓存
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
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
            
            return ResponseEntity.ok(response);
            
        } catch (NoSuchAlgorithmException e) {
            // 如果RSA算法不可用，返回500错误
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
            
            // 返回200成功码和登录成功响应
            return ResponseEntity.ok(
                new LoginResponse(200, true, "登录成功", loginRequest.getUserId())
            );
            
        } catch (Exception e) {
            // 捕获解密异常或其他异常
            e.printStackTrace();
            // 返回500错误码，表示服务器内部错误
            return ResponseEntity.internalServerError().body(
                new LoginResponse(500, false, "登录失败：" + e.getMessage())
            );
        }
    }
}
