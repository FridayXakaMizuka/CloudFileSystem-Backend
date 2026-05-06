package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.entity.UserTrustedDevice;
import com.mizuka.cloudfilesystem.mapper.UserTrustedDeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 设备管理服务
 * 
 * 功能：
 * 1. 客户端设备信息保存到MySQL（永久）
 * 2. 浏览器设备信息保存到Redis（24小时）
 * 3. 设备查询和管理
 */
@Service
public class DeviceService {
    
    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);
    
    @Autowired
    private UserTrustedDeviceMapper deviceMapper;
    
    @Autowired
    @Qualifier("trustedBrowsersRedisTemplate")
    private RedisTemplate<String, Object> trustedBrowsersRedisTemplate;
    
    /**
     * 注册或更新设备信息
     * - 客户端：保存到MySQL
     * - 浏览器：保存到Redis（24小时）
     */
    public void registerOrUpdateDevice(Long userId, HttpServletRequest request) {
        String deviceFingerprint = (String) request.getAttribute("deviceFingerprint");
        String clientType = (String) request.getAttribute("clientType");
        String clientIdentifier = (String) request.getAttribute("clientIdentifier");
        String platform = (String) request.getAttribute("platform");
        String electronVersion = (String) request.getAttribute("electronVersion");
        String browserType = (String) request.getAttribute("browserType");
        String clientIp = (String) request.getAttribute("clientIp");
        Boolean isHttps = (Boolean) request.getAttribute("isHttps");
        
        if (deviceFingerprint == null || deviceFingerprint.isEmpty()) {
            logger.warn("[设备管理] 设备指纹为空，跳过设备注册 - userId: {}", userId);
            return;
        }
        
        if (clientType == null) {
            logger.warn("[设备管理] 客户端类型为空，跳过设备注册 - userId: {}", userId);
            return;
        }
        
        try {
            // 判断是浏览器还是客户端
            if ("browser".equalsIgnoreCase(clientType)) {
                // 浏览器设备：保存到Redis（24小时）
                saveBrowserDevice(userId, deviceFingerprint, browserType, clientIp, isHttps);
            } else {
                // 客户端设备：保存到MySQL
                saveClientDevice(userId, deviceFingerprint, clientType, clientIdentifier, 
                    platform, electronVersion, browserType, clientIp, isHttps);
            }
            
            logger.info("[设备管理] 设备信息已更新 - userId={}, type={}, fingerprint={}", 
                userId, clientType, deviceFingerprint.substring(0, Math.min(16, deviceFingerprint.length())) + "...");
                
        } catch (Exception e) {
            logger.error("[设备管理] 设备注册失败 - userId: {}, error: {}", userId, e.getMessage(), e);
        }
    }
    
    /**
     * 保存浏览器设备到Redis
     */
    private void saveBrowserDevice(Long userId, String deviceFingerprint, String browserType, 
                                   String clientIp, Boolean isHttps) {
        String redisKey = "device:browser:" + userId + ":" + deviceFingerprint;
        
        Map<String, Object> deviceData = new HashMap<>();
        deviceData.put("userId", userId);
        deviceData.put("deviceFingerprint", deviceFingerprint);
        deviceData.put("browserType", browserType != null ? browserType : "unknown");
        deviceData.put("clientIp", clientIp != null ? clientIp : "unknown");
        deviceData.put("isHttps", isHttps != null ? isHttps : false);
        deviceData.put("lastSeenAt", System.currentTimeMillis());
        deviceData.put("expiresAt", System.currentTimeMillis() + 24 * 60 * 60 * 1000); // 24小时后过期
        
        trustedBrowsersRedisTemplate.opsForValue().set(redisKey, deviceData, 24, TimeUnit.HOURS);
        
        logger.debug("[设备管理] 浏览器设备已保存到Redis - Key: {}, TTL: 24小时", redisKey);
    }
    
    /**
     * 保存客户端设备到MySQL
     */
    private void saveClientDevice(Long userId, String deviceFingerprint, String clientType,
                                  String clientIdentifier, String platform, String electronVersion,
                                  String browserType, String clientIp, Boolean isHttps) {
        // 查找现有设备
        UserTrustedDevice device = deviceMapper.findByUserIdAndFingerprint(userId, deviceFingerprint);
        
        if (device != null) {
            // 更新现有设备
            device.setLastLoginTime(LocalDateTime.now());
            device.setLastLoginIp(clientIp != null ? clientIp : device.getLastLoginIp());
            device.setLoginCount(device.getLoginCount() + 1);
            
            // 更新设备标识和平台信息（如果有变化）
            if (clientIdentifier != null && !clientIdentifier.equals(device.getClientIdentifier())) {
                device.setClientIdentifier(clientIdentifier);
            }
            if (platform != null && !platform.equals(device.getPlatform())) {
                device.setPlatform(platform);
            }
            
            deviceMapper.updateLoginInfo(device);
            
            logger.debug("[设备管理] 客户端设备已更新 - deviceId: {}, loginCount: {}", 
                device.getId(), device.getLoginCount());
        } else {
            // 创建新设备
            device = new UserTrustedDevice();
            device.setUserId(userId);
            device.setDeviceUuid(java.util.UUID.randomUUID().toString());
            device.setDeviceFingerprint(deviceFingerprint);
            device.setClientType(clientType);
            device.setClientIdentifier(clientIdentifier != null ? clientIdentifier : clientType + "-" + platform);
            device.setPlatform(platform != null ? platform : "unknown");
            device.setDeviceName(generateDefaultDeviceName(clientType, platform));
            device.setIsTrusted(false); // 首次登录不信任，需要二次验证后才信任
            device.setTrustLevel(0);
            device.setLastLoginTime(LocalDateTime.now());
            device.setLastLoginIp(clientIp != null ? clientIp : "unknown");
            device.setLoginCount(1);
            device.setFirstSeenAt(LocalDateTime.now());
            
            deviceMapper.insert(device);
            
            logger.info("[设备管理] 新客户端设备已创建 - deviceId: {}, uuid: {}", 
                device.getId(), device.getDeviceUuid());
        }
    }
    
    /**
     * 获取用户的所有客户端设备（从MySQL）
     */
    public List<UserTrustedDevice> getUserClientDevices(Long userId) {
        return deviceMapper.findByUserId(userId);
    }
    
    /**
     * 获取用户的浏览器设备（从Redis）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getUserBrowserDevices(Long userId) {
        // Redis中存储的key格式: device:browser:{userId}:{fingerprint}
        // 由于Redis不支持直接按pattern查询value，这里需要改进
        // 暂时返回空列表，后续可以优化为使用Redis Set存储所有浏览器设备指纹
        return List.of();
    }
    
    /**
     * 移除客户端设备
     */
    public void removeClientDevice(Long userId, Long deviceId) {
        UserTrustedDevice device = deviceMapper.findById(deviceId);
        
        if (device == null) {
            throw new RuntimeException("设备不存在");
        }
        
        if (!device.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作该设备");
        }
        
        deviceMapper.deleteById(deviceId);
        logger.info("[设备管理] 客户端设备已移除 - deviceId: {}, userId: {}", deviceId, userId);
    }
    
    /**
     * 标记设备为信任设备
     */
    public void markDeviceAsTrusted(Long userId, String deviceFingerprint) {
        UserTrustedDevice device = deviceMapper.findByUserIdAndFingerprint(userId, deviceFingerprint);
        
        if (device != null) {
            device.setIsTrusted(true);
            device.setTrustLevel(1);
            deviceMapper.update(device);
            
            logger.info("[设备管理] 设备已标记为信任 - deviceId: {}, userId: {}", device.getId(), userId);
        }
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
        
        String platformName = switch (platform != null ? platform.toLowerCase() : "") {
            case "windows" -> "Windows";
            case "macos" -> "macOS";
            case "linux" -> "Linux";
            case "android" -> "Android";
            case "ios" -> "iOS";
            default -> platform != null ? platform : "Unknown";
        };
        
        return typeName + " - " + platformName;
    }
}
