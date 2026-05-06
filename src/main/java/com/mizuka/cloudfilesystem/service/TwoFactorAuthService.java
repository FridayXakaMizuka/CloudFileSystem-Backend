package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.entity.TwoFactorVerificationLog;
import com.mizuka.cloudfilesystem.entity.UserTrustedDevice;
import com.mizuka.cloudfilesystem.mapper.SecurityQuestionMapper;
import com.mizuka.cloudfilesystem.mapper.TwoFactorVerificationLogMapper;
import com.mizuka.cloudfilesystem.mapper.UserTrustedDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 二次验证服务类
 */
@Service
public class TwoFactorAuthService {
    
    private static final Logger logger = LoggerFactory.getLogger(TwoFactorAuthService.class);
    
    @Autowired
    private UserTrustedDeviceMapper deviceMapper;
    
    @Autowired
    private TwoFactorVerificationLogMapper logMapper;
    
    @Autowired
    private SecurityQuestionMapper securityQuestionMapper;
    
    @Autowired
    private com.mizuka.cloudfilesystem.mapper.UserMapper userMapper;
    
    // BCrypt密码编码器（用于验证安全答案）
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder = 
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    
    // 注入主RedisTemplate（端口6379）用于存储所有二次验证会话数据
    @Autowired
    @Qualifier("rsaRedisTemplate")
    private RedisTemplate<String, Object> rsaRedisTemplate;
    
    // 注入trustedBrowsersRedisTemplate（端口6378）用于存储浏览器临时信任设备
    @Autowired
    @Qualifier("trustedBrowsersRedisTemplate")
    private RedisTemplate<String, Object> trustedBrowsersRedisTemplate;
    
    @Autowired
    private EmailVerificationService emailVerificationService;
    
    @Autowired
    private SmsVerificationService smsVerificationService;
    
    /**
     * 判断是否需要二次验证
     * 
     * @param userId 用户ID
     * @param clientType 客户端类型 (browser/electron/android/ios)
     * @param deviceFingerprint 设备指纹
     * @param hardwareId 硬件ID（仅客户端可用）
     * @return true-需要二次验证，false-不需要
     */
    public boolean requiresTwoFactor(Long userId, String clientType, 
                                     String deviceFingerprint, String hardwareId) {
        // 1. 浏览器检查Redis中的临时信任设备
        if ("browser".equalsIgnoreCase(clientType)) {
            if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
                Boolean isTrusted = isBrowserTrusted(userId, deviceFingerprint);
                if (Boolean.TRUE.equals(isTrusted)) {
                    logger.debug("[二次验证] 浏览器信任设备（Redis匹配），无需二次验证 - userId: {}, fingerprint: {}", 
                                userId, deviceFingerprint);
                    return false;  // 信任设备，不需要二次验证
                }
            }
            logger.debug("[二次验证] 浏览器登录，需要二次验证 - userId: {}", userId);
            return true;
        }
        
        // 2. 客户端检查是否为信任设备
        if ("electron".equalsIgnoreCase(clientType) || 
            "android".equalsIgnoreCase(clientType) || 
            "ios".equalsIgnoreCase(clientType)) {
            
            // 先查硬件ID（更准确）
            if (hardwareId != null && !hardwareId.isEmpty()) {
                UserTrustedDevice device = deviceMapper.findByUserIdAndHardwareId(userId, hardwareId);
                if (device != null && Boolean.TRUE.equals(device.getIsTrusted())) {
                    logger.debug("[二次验证] 客户端信任设备（硬件ID匹配），无需二次验证 - userId: {}, hardwareId: {}", 
                                userId, hardwareId);
                    
                    // 更新登录统计
                    device.setLastLoginTime(LocalDateTime.now());
                    deviceMapper.updateLoginInfo(device);
                    
                    return false;  // 信任设备，不需要二次验证
                }
            }
            
            // 再查设备指纹
            if (deviceFingerprint != null && !deviceFingerprint.isEmpty()) {
                UserTrustedDevice device = deviceMapper.findByUserIdAndFingerprint(userId, deviceFingerprint);
                if (device != null && Boolean.TRUE.equals(device.getIsTrusted())) {
                    logger.debug("[二次验证] 客户端信任设备（指纹匹配），无需二次验证 - userId: {}, fingerprint: {}", 
                                userId, deviceFingerprint);
                    
                    // 更新登录统计
                    device.setLastLoginTime(LocalDateTime.now());
                    deviceMapper.updateLoginInfo(device);
                    
                    return false;  // 信任设备，不需要二次验证
                }
            }
        }
        
        // 3. 其他情况需要二次验证
        logger.debug("[二次验证] 新设备或未信任设备，需要二次验证 - userId: {}, clientType: {}", userId, clientType);
        return true;
    }
    
    /**
     * 发送邮箱验证码
     */
    public boolean sendEmailVerificationCode(String sessionId, Long userId, String email) {
        try {
            // 直接调用EmailVerificationService，让它负责生成和存储验证码
            boolean sent = emailVerificationService.sendVerificationCode(email, sessionId);
            
            if (sent) {
                logger.info("[二次验证] 邮箱验证码发送成功 - userId: {}, email: {}", userId, maskEmail(email));
            } else {
                logger.error("[二次验证] 邮箱验证码发送失败 - userId: {}, email: {}", userId, email);
            }
            
            return sent;
            
        } catch (Exception e) {
            logger.error("[二次验证] 发送邮箱验证码异常 - userId: {}, error: {}", userId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 发送手机验证码
     */
    public boolean sendPhoneVerificationCode(String sessionId, Long userId, String phone) {
        try {
            // 调用SmsVerificationService发送短信（它会自动存储验证码到Redis端口6379）
            boolean sent = smsVerificationService.sendSmsVerificationCode(phone, sessionId);
            
            if (sent) {
                logger.info("[二次验证] 手机验证码发送成功 - userId: {}, phone: {}", userId, maskPhone(phone));
            } else {
                logger.error("[二次验证] 手机验证码发送失败 - userId: {}, phone: {}", userId, phone);
            }
            
            return sent;
            
        } catch (Exception e) {
            logger.error("[二次验证] 发送手机验证码异常 - userId: {}, error: {}", userId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 验证邮箱验证码
     */
    public boolean verifyEmailCode(String sessionId, String code) {
        // 从会话数据中获取邮箱地址
        Map<String, Object> sessionData = getSessionData(sessionId, null);
        if (sessionData == null) {
            logger.warn("[二次验证] 会话不存在 - sessionId: {}", sessionId);
            return false;
        }
        
        String email = (String) sessionData.get("email");
        if (email == null || email.isEmpty()) {
            logger.warn("[二次验证] 会话中无邮箱信息 - sessionId: {}", sessionId);
            return false;
        }
        
        // 调用EmailVerificationService验证（它会从Redis端口6379读取并删除验证码）
        return emailVerificationService.verifyCode(sessionId, email, code);
    }
    
    /**
     * 验证手机验证码
     */
    public boolean verifyPhoneCode(String sessionId, String code) {
        // 从会话数据中获取手机号
        Map<String, Object> sessionData = getSessionData(sessionId, null);
        if (sessionData == null) {
            logger.warn("[二次验证] 会话不存在 - sessionId: {}", sessionId);
            return false;
        }
        
        String phone = (String) sessionData.get("phone");
        if (phone == null || phone.isEmpty()) {
            logger.warn("[二次验证] 会话中无手机信息 - sessionId: {}", sessionId);
            return false;
        }
        
        // 调用SmsVerificationService验证（它会从Redis端口6379读取并删除验证码）
        return smsVerificationService.verifyCode(sessionId, phone, code);
    }
    
    /**
     * 验证密保答案（兼容明文和BCrypt，并自动迁移）
     */
    public boolean verifySecurityAnswer(Long userId, String answer) {
        try {
            // 从数据库查询用户信息
            var user = userMapper.findById(userId);
            
            if (user == null) {
                logger.warn("[二次验证] 用户不存在 - userId: {}", userId);
                return false;
            }
            
            if (user.getSecurityAnswer() == null || user.getSecurityAnswer().isEmpty()) {
                logger.warn("[二次验证] 用户未设置密保答案 - userId: {}", userId);
                return false;
            }
            
            // 验证答案（兼容明文和BCrypt）
            boolean valid = verifySecurityAnswerInternal(answer.trim(), user.getSecurityAnswer());
            
            if (valid) {
                logger.info("[二次验证] 密保问题验证成功 - userId: {}", userId);
                
                // 如果是明文存储，自动迁移为BCrypt
                migrateSecurityAnswerToBCrypt(userId, user.getSecurityAnswer());
            } else {
                logger.warn("[二次验证] 密保问题答案错误 - userId: {}", userId);
            }
            
            return valid;
            
        } catch (Exception e) {
            logger.error("[二次验证] 验证密保答案异常 - userId: {}, error: {}", userId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 内部方法：验证安全答案（兼容明文和BCrypt）
     * @param plainAnswer 明文答案
     * @param storedAnswer 数据库中存储的答案（可能是明文或BCrypt）
     * @return 是否匹配
     */
    private boolean verifySecurityAnswerInternal(String plainAnswer, String storedAnswer) {
        if (plainAnswer == null || storedAnswer == null) {
            return false;
        }
        
        // 判断是否为 BCrypt 加密（BCrypt 哈希以 $2a$, $2b$, 或 $2y$ 开头）
        if (storedAnswer.startsWith("$2a$") || storedAnswer.startsWith("$2b$") || storedAnswer.startsWith("$2y$")) {
            // BCrypt 加密，使用 matches 比较
            return passwordEncoder.matches(plainAnswer, storedAnswer);
        } else {
            // 明文存储，直接比较（不区分大小写）
            return plainAnswer.equalsIgnoreCase(storedAnswer);
        }
    }
    
    /**
     * 检查并迁移安全答案为 BCrypt 格式
     * @param userId 用户ID
     * @param storedAnswer 数据库中存储的答案
     * @return 如果已迁移返回 true，否则返回 false
     */
    private boolean migrateSecurityAnswerToBCrypt(Long userId, String storedAnswer) {
        if (storedAnswer == null || storedAnswer.isEmpty()) {
            return false;
        }
        
        // 如果已经是 BCrypt 格式，不需要迁移
        if (storedAnswer.startsWith("$2a$") || storedAnswer.startsWith("$2b$") || storedAnswer.startsWith("$2y$")) {
            return false; // 已经迁移
        }
        
        // 明文存储，需要迁移为 BCrypt
        try {
            String bcryptAnswer = passwordEncoder.encode(storedAnswer);
            int result = userMapper.updateSecurityQuestion(userId, null, bcryptAnswer); // 只更新答案，不改变问题ID
            
            if (result > 0) {
                logger.info("[二次验证-安全答案迁移] 成功 - UserId: {}, 从明文迁移到BCrypt", userId);
                return true;
            } else {
                logger.error("[二次验证-安全答案迁移] 失败 - UserId: {}", userId);
                return false;
            }
        } catch (Exception e) {
            logger.error("[二次验证-安全答案迁移] 异常 - UserId: {}, 错误: {}", userId, e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存信任设备到MySQL（仅客户端）
     */
    public void saveTrustedDevice(Long userId, String clientType, String deviceFingerprint,
                                  String hardwareId, String clientIdentifier, String platform,
                                  String ipAddress) {
        // 仅客户端设备保存到MySQL
        if ("browser".equalsIgnoreCase(clientType)) {
            logger.debug("[二次验证] 浏览器设备不保存到信任设备表 - userId: {}", userId);
            return;
        }
        
        try {
            UserTrustedDevice device = new UserTrustedDevice();
            device.setUserId(userId);
            device.setDeviceUuid(UUID.randomUUID().toString());
            // 处理deviceFingerprint为null的情况，使用默认值
            device.setDeviceFingerprint(deviceFingerprint != null ? deviceFingerprint : "unknown-fingerprint");
            device.setHardwareId(hardwareId);
            device.setClientType(clientType);
            device.setClientIdentifier(clientIdentifier != null ? clientIdentifier : clientType + "-" + platform);
            device.setPlatform(platform != null ? platform : "unknown");
            device.setDeviceName(generateDefaultDeviceName(clientType, platform));
            device.setIsTrusted(true);
            device.setTrustLevel(1);  // 普通信任
            device.setLastLoginTime(LocalDateTime.now());
            device.setLastLoginIp(ipAddress != null ? ipAddress : "unknown");
            device.setLoginCount(1);
            device.setFirstSeenAt(LocalDateTime.now());
            
            deviceMapper.insert(device);
            
            logger.info("[二次验证] 信任设备保存成功 - userId: {}, deviceUuid: {}, clientType: {}, fingerprint: {}", 
                       userId, device.getDeviceUuid(), clientType, device.getDeviceFingerprint());
            
        } catch (Exception e) {
            logger.error("[二次验证] 保存信任设备失败 - userId: {}, error: {}", userId, e.getMessage(), e);
        }
    }
    
    /**
     * 记录二次验证日志
     */
    public void logVerification(Long userId, String deviceUuid, String deviceFingerprint,
                               String verifyMethod, boolean success, String failureReason,
                               String clientType, String platform, String ipAddress, String userAgent) {
        try {
            TwoFactorVerificationLog log = new TwoFactorVerificationLog();
            log.setUserId(userId);
            log.setDeviceUuid(deviceUuid);
            log.setDeviceFingerprint(deviceFingerprint);
            log.setVerifyMethod(verifyMethod);
            log.setVerifyResult(success ? "success" : "failed");
            log.setFailureReason(failureReason);
            log.setClientType(clientType);
            log.setClientPlatform(platform);
            log.setIpAddress(ipAddress);
            log.setUserAgent(userAgent);
            log.setCreatedAt(LocalDateTime.now());
            
            logMapper.insert(log);
            
            logger.debug("[二次验证] 验证日志记录成功 - userId: {}, method: {}, result: {}", 
                        userId, verifyMethod, success ? "success" : "failed");
            
        } catch (Exception e) {
            logger.error("[二次验证] 记录验证日志失败 - userId: {}, error: {}", userId, e.getMessage(), e);
        }
    }
    
    /**
     * 存储会话信息到Redis（端口6379）
     */
    public void storeSessionData(String sessionId, Map<String, Object> sessionData, String clientType) {
        // 所有二次验证会话都存储到端口6379
        String redisKey = "twofactor:session:" + sessionId;
        
        logger.info("[二次验证] 接收到sessionId: {}, 准备存储到Redis Key: {}", sessionId, redisKey);
        
        rsaRedisTemplate.opsForValue().set(redisKey, sessionData, 300, TimeUnit.SECONDS);
        
        logger.debug("[二次验证] 会话数据存储成功 - sessionId: {}, clientType: {}, key: {}", sessionId, clientType, redisKey);
    }
    
    /**
     * 从Redis获取会话信息（端口6379）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSessionData(String sessionId, String clientType) {
        // 所有二次验证会话都从端口6379读取
        String redisKey = "twofactor:session:" + sessionId;
        
        Map<String, Object> data = (Map<String, Object>) rsaRedisTemplate.opsForValue().get(redisKey);
        
        if (data == null) {
            logger.warn("[二次验证] 会话数据不存在或已过期 - sessionId: {}, key: {}", sessionId, redisKey);
        }
        
        return data;
    }
    
    /**
     * 清除会话数据（端口6379）
     */
    public void clearSessionData(String sessionId, String clientType) {
        // 清除端口6379中的会话数据
        String redisKey = "twofactor:session:" + sessionId;
        
        rsaRedisTemplate.delete(redisKey);
        
        // 从会话数据中获取邮箱和手机，清除端口6379中的验证码
        Map<String, Object> sessionData = getSessionData(sessionId, clientType);
        if (sessionData != null) {
            String email = (String) sessionData.get("email");
            String phone = (String) sessionData.get("phone");
            
            // 清除邮箱验证码（EmailVerificationService使用email:code:{sessionId}:{email}格式）
            if (email != null && !email.isEmpty()) {
                String emailCodeKey = "email:code:" + sessionId + ":" + email;
                rsaRedisTemplate.delete(emailCodeKey);
                logger.debug("[二次验证] 已清除邮箱验证码 - Key: {}", emailCodeKey);
            }
            
            // 清除手机验证码（SmsVerificationService使用sms:code:{sessionId}:{phone}格式）
            if (phone != null && !phone.isEmpty()) {
                String phoneCodeKey = "sms:code:" + sessionId + ":" + phone;
                rsaRedisTemplate.delete(phoneCodeKey);
                logger.debug("[二次验证] 已清除手机验证码 - Key: {}", phoneCodeKey);
            }
        }
        
        logger.debug("[二次验证] 会话数据清除成功 - sessionId: {}", sessionId);
    }
    
    /**
     * 生成6位随机验证码
     */
    private String generateVerificationCode() {
        return String.format("%06d", (int)(Math.random() * 1000000));
    }
    
    /**
     * 生成默认设备名称
     */
    private String generateDefaultDeviceName(String clientType, String platform) {
        String typeName = switch (clientType.toLowerCase()) {
            case "electron" -> "PC客户端";
            case "android" -> "Android设备";
            case "ios" -> "iOS设备";
            default -> "未知设备";
        };
        
        String platformName = switch (platform.toLowerCase()) {
            case "windows" -> "Windows";
            case "macos" -> "macOS";
            case "linux" -> "Linux";
            case "android" -> "Android";
            case "ios" -> "iOS";
            default -> platform;
        };
        
        return typeName + " - " + platformName;
    }
    
    /**
     * 邮箱打码
     */
    private String maskEmail(String email) {
        if (email == null || email.length() < 5) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 2) {
            return "***" + email.substring(atIndex);
        }
        return email.substring(0, 2) + "***" + email.substring(atIndex);
    }
    
    /**
     * 手机号打码
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    
    /**
     * 保存浏览器临时信任设备到Redis（端口6378）
     * Key格式: trusted_browser:{userId}:{deviceFingerprint}
     * TTL: 24小时
     */
    public void saveBrowserTrustedDevice(Long userId, String deviceFingerprint, String browserType, String ipAddress) {
        if (deviceFingerprint == null || deviceFingerprint.isEmpty()) {
            logger.warn("[浏览器信任] 设备指纹为空，无法保存 - userId: {}", userId);
            return;
        }
        
        try {
            String redisKey = "trusted_browser:" + userId + ":" + deviceFingerprint;
            
            Map<String, Object> trustedData = new HashMap<>();
            trustedData.put("userId", userId);
            trustedData.put("deviceFingerprint", deviceFingerprint);
            trustedData.put("browserType", browserType != null ? browserType : "unknown");
            trustedData.put("ipAddress", ipAddress != null ? ipAddress : "unknown");
            trustedData.put("trustedAt", System.currentTimeMillis());
            trustedData.put("expiresAt", System.currentTimeMillis() + 24 * 60 * 60 * 1000); // 24小时后过期
            
            trustedBrowsersRedisTemplate.opsForValue().set(redisKey, trustedData, 24, TimeUnit.HOURS);
            
            logger.info("[浏览器信任] 保存成功 - userId: {}, fingerprint: {}, browser: {}, TTL: 24小时", 
                       userId, deviceFingerprint, browserType);
            
        } catch (Exception e) {
            logger.error("[浏览器信任] 保存失败 - userId: {}, error: {}", userId, e.getMessage(), e);
        }
    }
    
    /**
     * 检查浏览器是否为信任设备（从Redis端口6378查询）
     */
    public Boolean isBrowserTrusted(Long userId, String deviceFingerprint) {
        if (deviceFingerprint == null || deviceFingerprint.isEmpty()) {
            return false;
        }
        
        try {
            String redisKey = "trusted_browser:" + userId + ":" + deviceFingerprint;
            Object data = trustedBrowsersRedisTemplate.opsForValue().get(redisKey);
            
            if (data != null) {
                logger.debug("[浏览器信任] 找到信任设备 - userId: {}, fingerprint: {}", userId, deviceFingerprint);
                return true;
            } else {
                logger.debug("[浏览器信任] 未找到信任设备 - userId: {}, fingerprint: {}", userId, deviceFingerprint);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("[浏览器信任] 查询失败 - userId: {}, error: {}", userId, e.getMessage(), e);
            return false;
        }
    }
}
