# 后端 Filter 实现指南

## 📋 概述

本文档提供了 Spring Boot 后端 Filter 的实现示例，用于验证和记录前端发送的安全请求头。

---

## 🔧 核心 Filter 实现

### **SecurityHeaderFilter.java**

```java
package com.example.cloudfilesystem.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 安全请求头过滤器
 * 
 * 功能：
 * 1. 验证设备指纹一致性
 * 2. 记录客户端信息
 * 3. 检测 HTTPS 协议
 * 4. 审计日志记录
 */
@Component
public class SecurityHeaderFilter implements Filter {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityHeaderFilter.class);
    
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        logger.info("SecurityHeaderFilter 初始化完成");
    }
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        
        // 1. 提取请求头信息
        String deviceFingerprint = request.getHeader("X-Device-Fingerprint");
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String clientType = request.getHeader("X-Client-Type");
        String clientIdentifier = request.getHeader("X-Client-Identifier");
        String platform = request.getHeader("X-Client-Platform");
        String electronVersion = request.getHeader("X-Electron-Version");
        String browserType = request.getHeader("X-Browser-Type");
        String clientIp = request.getHeader("X-Client-IP");
        
        // 2. 判断是否为 HTTPS
        boolean isHttps = "https".equalsIgnoreCase(forwardedProto);
        
        // 3. 获取请求路径和方法
        String requestURI = request.getRequestURI();
        String method = request.getMethod();
        
        // 4. 记录审计日志
        logger.info("🔐 [{}] {} | 客户端: {} ({}) | 平台: {} | HTTPS: {} | IP: {} | 指纹: {}",
            method,
            requestURI,
            clientType != null ? clientType : "unknown",
            clientIdentifier != null ? clientIdentifier : "unknown",
            platform != null ? platform : "unknown",
            isHttps,
            clientIp != null ? clientIp : "unknown",
            deviceFingerprint != null ? deviceFingerprint.substring(0, Math.min(16, deviceFingerprint.length())) + "..." : "none"
        );
        
        // 5. 验证设备指纹一致性（可选）
        if (deviceFingerprint != null && shouldValidateFingerprint(requestURI)) {
            validateDeviceFingerprint(request, deviceFingerprint);
        }
        
        // 6. 将设备信息存入 request attribute（供 Controller 使用）
        request.setAttribute("deviceFingerprint", deviceFingerprint);
        request.setAttribute("clientType", clientType);
        request.setAttribute("clientIdentifier", clientIdentifier);
        request.setAttribute("platform", platform);
        request.setAttribute("electronVersion", electronVersion);
        request.setAttribute("browserType", browserType);
        request.setAttribute("clientIp", clientIp);
        request.setAttribute("isHttps", isHttps);
        
        // 7. 添加响应头（可选，用于调试）
        response.setHeader("X-Request-Id", generateRequestId());
        
        // 8. 继续过滤链
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            logger.error("请求处理异常: {} {}", method, requestURI, e);
            throw e;
        }
    }
    
    /**
     * 验证设备指纹一致性
     */
    private void validateDeviceFingerprint(HttpServletRequest request, String currentFingerprint) {
        // 从 session 或 token 中获取存储的指纹
        String storedFingerprint = getStoredFingerprint(request);
        
        if (storedFingerprint != null && !storedFingerprint.equals(currentFingerprint)) {
            logger.warn("⚠️ 设备指纹不匹配！存储: {}, 当前: {}", 
                storedFingerprint.substring(0, 16) + "...",
                currentFingerprint.substring(0, 16) + "...");
            
            // 可选：记录安全事件
            logSecurityEvent(request, "DEVICE_FINGERPRINT_MISMATCH");
            
            // 可选：拒绝请求
            // throw new SecurityException("设备验证失败");
        }
    }
    
    /**
     * 获取存储的设备指纹
     */
    private String getStoredFingerprint(HttpServletRequest request) {
        // 方法 1: 从 session 获取
        // return (String) request.getSession().getAttribute("deviceFingerprint");
        
        // 方法 2: 从 JWT token 解析
        // return JwtUtils.getFingerprintFromToken(request.getHeader("Authorization"));
        
        // 方法 3: 从数据库获取
        // return deviceService.getCurrentDeviceFingerprint(getUserId(request));
        
        return null; // 根据实际需求实现
    }
    
    /**
     * 判断是否需要验证设备指纹
     */
    private boolean shouldValidateFingerprint(String requestURI) {
        // 以下接口需要验证设备指纹
        return requestURI.startsWith("/profile/") ||
               requestURI.startsWith("/file/") ||
               requestURI.startsWith("/transfer/");
    }
    
    /**
     * 记录安全事件
     */
    private void logSecurityEvent(HttpServletRequest request, String eventType) {
        logger.warn("🚨 安全事件: {} | IP: {} | URI: {} | 用户代理: {}",
            eventType,
            request.getHeader("X-Client-IP"),
            request.getRequestURI(),
            request.getHeader("User-Agent")
        );
    }
    
    /**
     * 生成请求 ID
     */
    private String generateRequestId() {
        return java.util.UUID.randomUUID().toString();
    }
    
    @Override
    public void destroy() {
        logger.info("SecurityHeaderFilter 销毁");
    }
}
```

---

## 🛡️ 设备管理服务

### **DeviceService.java**

```java
package com.example.cloudfilesystem.service;

import com.example.cloudfilesystem.entity.UserDevice;
import com.example.cloudfilesystem.repository.UserDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DeviceService {
    
    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);
    
    @Autowired
    private UserDeviceRepository deviceRepository;
    
    /**
     * 注册或更新设备信息
     */
    public void registerOrUpdateDevice(Long userId, HttpServletRequest request) {
        String fingerprint = (String) request.getAttribute("deviceFingerprint");
        String clientType = (String) request.getAttribute("clientType");
        String clientIdentifier = (String) request.getAttribute("clientIdentifier");
        String platform = (String) request.getAttribute("platform");
        String electronVersion = (String) request.getAttribute("electronVersion");
        String browserType = (String) request.getAttribute("browserType");
        String clientIp = (String) request.getAttribute("clientIp");
        Boolean isHttps = (Boolean) request.getAttribute("isHttps");
        
        if (fingerprint == null) {
            logger.warn("设备指纹为空，跳过设备注册");
            return;
        }
        
        // 查找现有设备
        UserDevice device = deviceRepository
            .findByUserIdAndDeviceFingerprint(userId, fingerprint)
            .orElse(new UserDevice());
        
        // 更新设备信息
        device.setUserId(userId);
        device.setDeviceFingerprint(fingerprint);
        device.setClientType(clientType);
        device.setClientIdentifier(clientIdentifier);
        device.setPlatform(platform);
        device.setElectronVersion(electronVersion);
        device.setBrowserType(browserType);
        device.setClientIp(clientIp);
        device.setIsHttps(isHttps);
        device.setLastLoginTime(LocalDateTime.now());
        device.setLastLoginIp(clientIp);
        device.setLoginCount(device.getLoginCount() + 1);
        
        // 保存
        deviceRepository.save(device);
        
        logger.info("✅ 设备信息已更新: userId={}, type={}, platform={}", 
            userId, clientType, platform);
    }
    
    /**
     * 获取用户的所有设备
     */
    public List<UserDevice> getUserDevices(Long userId) {
        return deviceRepository.findByUserIdOrderByLastLoginTimeDesc(userId);
    }
    
    /**
     * 移除设备
     */
    public void removeDevice(Long userId, Long deviceId) {
        UserDevice device = deviceRepository.findById(deviceId)
            .orElseThrow(() -> new RuntimeException("设备不存在"));
        
        if (!device.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作该设备");
        }
        
        deviceRepository.delete(device);
        logger.info("🗑️ 设备已移除: deviceId={}, userId={}", deviceId, userId);
    }
    
    /**
     * 检查设备是否存在
     */
    public boolean isDeviceExists(Long userId, String fingerprint) {
        return deviceRepository.existsByUserIdAndDeviceFingerprint(userId, fingerprint);
    }
}
```

---

## 💾 数据库实体

### **UserDevice.java**

```java
package com.example.cloudfilesystem.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_devices")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDevice {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long userId;
    
    @Column(nullable = false, length = 64)
    private String deviceFingerprint;
    
    @Column(nullable = false, length = 50)
    private String clientType;
    
    @Column(length = 100)
    private String clientIdentifier;
    
    @Column(length = 50)
    private String platform;
    
    @Column(length = 50)
    private String browserType;
    
    @Column(length = 50)
    private String electronVersion;
    
    @Column(length = 45)
    private String clientIp;
    
    @Column(nullable = false)
    private Boolean isHttps;
    
    @Column(nullable = false)
    private LocalDateTime lastLoginTime;
    
    @Column(length = 45)
    private String lastLoginIp;
    
    @Column(nullable = false)
    private Integer loginCount = 0;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

---

## 📊 Repository

### **UserDeviceRepository.java**

```java
package com.example.cloudfilesystem.repository;

import com.example.cloudfilesystem.entity.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {
    
    Optional<UserDevice> findByUserIdAndDeviceFingerprint(Long userId, String fingerprint);
    
    List<UserDevice> findByUserIdOrderByLastLoginTimeDesc(Long userId);
    
    boolean existsByUserIdAndDeviceFingerprint(Long userId, String fingerprint);
    
    void deleteByIdAndUserId(Long id, Long userId);
}
```

---

## 🎯 Controller 中使用

### **ProfileController.java**

```java
package com.example.cloudfilesystem.controller;

import com.example.cloudfilesystem.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/profile")
public class ProfileController {
    
    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);
    
    @Autowired
    private DeviceService deviceService;
    
    @PostMapping("/get_all")
    public Map<String, Object> getAllProfile(
            HttpServletRequest request,
            @RequestHeader("Authorization") String authHeader) {
        
        // 从 request attribute 获取设备信息
        String deviceFingerprint = (String) request.getAttribute("deviceFingerprint");
        String clientType = (String) request.getAttribute("clientType");
        String platform = (String) request.getAttribute("platform");
        String clientIp = (String) request.getAttribute("clientIp");
        
        logger.info("📱 用户访问个人信息 - 设备: {} ({}) | IP: {}", 
            clientType, platform, clientIp);
        
        // 注册或更新设备信息
        Long userId = getUserIdFromToken(authHeader);
        deviceService.registerOrUpdateDevice(userId, request);
        
        // 业务逻辑
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("success", true);
        response.put("data", profileService.getAllProfile(userId));
        
        return response;
    }
    
    @GetMapping("/devices")
    public Map<String, Object> getUserDevices(
            @RequestHeader("Authorization") String authHeader) {
        
        Long userId = getUserIdFromToken(authHeader);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("success", true);
        response.put("data", deviceService.getUserDevices(userId));
        
        return response;
    }
    
    @DeleteMapping("/devices/{deviceId}")
    public Map<String, Object> removeDevice(
            @PathVariable Long deviceId,
            @RequestHeader("Authorization") String authHeader) {
        
        Long userId = getUserIdFromToken(authHeader);
        deviceService.removeDevice(userId, deviceId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("success", true);
        response.put("message", "设备已移除");
        
        return response;
    }
    
    private Long getUserIdFromToken(String authHeader) {
        // 从 JWT token 解析用户 ID
        // 这里简化处理
        return 1L;
    }
}
```

---

## 📝 数据库迁移脚本

### **schema.sql**

```sql
CREATE TABLE IF NOT EXISTS user_devices (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_fingerprint VARCHAR(64) NOT NULL,
    client_type VARCHAR(50) NOT NULL,
    client_identifier VARCHAR(100),
    platform VARCHAR(50),
    browser_type VARCHAR(50),
    electron_version VARCHAR(50),
    client_ip VARCHAR(45),
    is_https BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_time DATETIME NOT NULL,
    last_login_ip VARCHAR(45),
    login_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_user_id (user_id),
    INDEX idx_fingerprint (device_fingerprint),
    INDEX idx_last_login (last_login_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设备表';
```

---

## 🔍 日志配置

### **application.yml**

```yaml
logging:
  level:
    com.example.cloudfilesystem.filter.SecurityHeaderFilter: INFO
    com.example.cloudfilesystem.service.DeviceService: INFO
  
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

---

## ✅ 测试示例

### **SecurityHeaderFilterTest.java**

```java
package com.example.cloudfilesystem.filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class SecurityHeaderFilterTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    public void testRequestWithSecurityHeaders() throws Exception {
        mockMvc.perform(post("/profile/get_all")
                .header("Authorization", "Bearer test-token")
                .header("X-Device-Fingerprint", "test-fingerprint-123")
                .header("X-Forwarded-Proto", "https")
                .header("X-Client-Type", "browser")
                .header("X-Client-Platform", "windows")
                .header("X-Browser-Type", "chrome"))
            .andExpect(status().isOk());
    }
}
```

---

## 🎯 总结

### **关键点**

1. ✅ **Filter 自动提取所有安全请求头**
2. ✅ **验证设备指纹一致性（可选）**
3. ✅ **记录详细的审计日志**
4. ✅ **将设备信息存入 request attribute**
5. ✅ **DeviceService 管理设备生命周期**
6. ✅ **支持设备查询和移除**

### **下一步**

1. 实现 JWT token 解析，提取用户 ID
2. 完善设备指纹验证逻辑
3. 添加设备数量限制（如最多 10 个设备）
4. 实现异常登录检测
5. 添加设备活动历史记录

---

**最后更新**: 2026-05-02  
**版本**: v1.0
