# 登录设备标识规范 - 后端参考文档

## 📋 概述

本文档说明了前端在登录时会发送到后端的设备标识和客户端/浏览器标识信息，供后端开发和日志记录参考。

---

## 🔐 登录请求格式

### HTTP 请求头

所有登录请求都会包含以下自定义请求头：

```http
POST /auth/login HTTP/1.1
Host: api.example.com
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>  # 登录成功后返回

# 设备标识相关
X-Client-Type: <client_type>
X-Client-Identifier: <client_identifier>
X-Client-Platform: <platform>
X-Electron-Version: <version>  # 仅 Electron 环境
X-Browser-Type: <browser>      # 仅浏览器环境
X-Device-Fingerprint: <fingerprint>  # 设备指纹（可选）
```

---

## 📊 字段详细说明

### 1. X-Client-Type（客户端类型）

**说明：** 标识应用运行的环境类型

**可能的值：**

| 值 | 说明 | 示例场景 |
|---|------|---------|
| `browser` | Web 浏览器 | Chrome、Firefox、Safari 等访问 |
| `electron` | Electron PC 客户端 | Windows/Linux/macOS 桌面应用 |
| `android` | Android 移动应用 | Android 手机/平板应用 |
| `ios` | iOS 移动应用 | iPhone/iPad 应用 |

**后端用途：**
- 区分不同平台的用户行为
- 统计各平台用户数量
- 针对不同平台推送不同的更新通知

---

### 2. X-Client-Identifier（客户端标识符）

**说明：** 更详细的客户端标识，包含平台和版本信息

**格式：** `{type}-{detail}-{platform}`

**可能的值：**

#### 浏览器环境
```
browser-chrome-windows
browser-firefox-linux
browser-safari-macos
browser-edge-windows
browser-opera-windows
browser-unknown-unknown
```

#### Electron 环境
```
electron-windows-x64
electron-windows-arm64
electron-linux-x64
electron-linux-arm64
electron-macos-x64
electron-macos-arm64
```

#### 移动应用
```
android-app
ios-app
```

**后端用途：**
- 精确识别客户端类型和架构
- 针对特定版本推送修复
- 分析用户使用的主流配置

---

### 3. X-Client-Platform（操作系统平台）

**说明：** 用户设备的操作系统

**可能的值：**

| 值 | 说明 |
|---|------|
| `windows` | Windows 系统 |
| `macos` | macOS 系统 |
| `linux` | Linux 系统 |
| `android` | Android 系统 |
| `ios` | iOS 系统 |
| `unknown` | 未知系统 |

**后端用途：**
- 统计各操作系统用户分布
- 针对特定系统优化服务
- 系统兼容性分析

---

### 4. X-Electron-Version（Electron 版本）

**说明：** 仅在 Electron 环境下提供，标识 Electron 框架版本

**示例值：**
```
28.0.0
27.1.3
26.2.4
```

**后端用途：**
- 追踪 Electron 版本分布
- 提醒用户升级旧版本
- 排查版本相关的 bug

---

### 5. X-Browser-Type（浏览器类型）

**说明：** 仅在浏览器环境下提供，标识浏览器类型

**可能的值：**

| 值 | 说明 |
|---|------|
| `chrome` | Google Chrome |
| `firefox` | Mozilla Firefox |
| `safari` | Apple Safari |
| `edge` | Microsoft Edge |
| `opera` | Opera Browser |
| `unknown` | 未知浏览器 |

**后端用途：**
- 统计浏览器使用情况
- 针对特定浏览器优化
- 浏览器兼容性问题追踪

---

### 6. X-Device-Fingerprint（设备指纹）

**说明：** （可选）设备的唯一标识符，用于设备管理

**生成方式：**
- 浏览器：基于 User Agent + 屏幕分辨率 + 时区等
- Electron：基于机器硬件信息
- Mobile：基于设备 ID（需要权限）

**示例值：**
```
a1b2c3d4e5f6g7h8i9j0
```

**后端用途：**
- 设备管理和限制
- 异常登录检测
- 多设备登录控制

---

## 🎯 完整示例

### 示例 1：Chrome 浏览器（Windows）

```http
POST /auth/login HTTP/1.1
Host: api.example.com
Content-Type: application/json

X-Client-Type: browser
X-Client-Identifier: browser-chrome-windows
X-Client-Platform: windows
X-Browser-Type: chrome
X-Device-Fingerprint: abc123def456

{
  "sessionId": "...",
  "userIdOrEmail": "user@example.com",
  "encryptedPassword": "...",
  "tokenExpiration": 604800
}
```

### 示例 2：Electron 客户端（Windows x64）

```http
POST /auth/login HTTP/1.1
Host: api.example.com
Content-Type: application/json

X-Client-Type: electron
X-Client-Identifier: electron-windows-x64
X-Client-Platform: windows
X-Electron-Version: 28.0.0
X-Device-Fingerprint: xyz789uvw012

{
  "sessionId": "...",
  "userIdOrEmail": "user@example.com",
  "encryptedPassword": "...",
  "tokenExpiration": 604800
}
```

### 示例 3：Android 应用

```http
POST /auth/login HTTP/1.1
Host: api.example.com
Content-Type: application/json

X-Client-Type: android
X-Client-Identifier: android-app
X-Client-Platform: android
X-Device-Fingerprint: mobile123abc

{
  "sessionId": "...",
  "userIdOrEmail": "user@example.com",
  "encryptedPassword": "...",
  "tokenExpiration": 604800
}
```

### 示例 4：iOS 应用

```http
POST /auth/login HTTP/1.1
Host: api.example.com
Content-Type: application/json

X-Client-Type: ios
X-Client-Identifier: ios-app
X-Client-Platform: ios
X-Device-Fingerprint: mobile456def

{
  "sessionId": "...",
  "userIdOrEmail": "user@example.com",
  "encryptedPassword": "...",
  "tokenExpiration": 604800
}
```

---

## 💻 后端接收示例（Spring Boot）

### Controller 层

```java
@PostMapping("/auth/login")
public ResponseEntity<Map<String, Object>> login(
    @RequestHeader(value = "X-Client-Type", required = false) String clientType,
    @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
    @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
    @RequestHeader(value = "X-Electron-Version", required = false) String electronVersion,
    @RequestHeader(value = "X-Browser-Type", required = false) String browserType,
    @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
    @RequestBody LoginRequest request
) {
    // 记录登录设备信息
    logger.info("Login attempt from: {} ({}) on {}", 
        clientType, clientIdentifier, clientPlatform);
    
    if ("electron".equals(clientType)) {
        logger.info("Electron version: {}", electronVersion);
    } else if ("browser".equals(clientType)) {
        logger.info("Browser type: {}", browserType);
    }
    
    // 执行登录逻辑
    LoginResponse response = authService.login(request);
    
    // 保存设备信息到数据库（可选）
    if (deviceFingerprint != null) {
        deviceService.registerDevice(
            response.getUserId(),
            deviceFingerprint,
            clientType,
            clientIdentifier,
            clientPlatform
        );
    }
    
    return ResponseEntity.ok(response.toMap());
}
```

### 实体类

```java
@Entity
@Table(name = "user_devices")
public class UserDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long userId;
    
    @Column(nullable = false)
    private String deviceFingerprint;
    
    @Column(nullable = false)
    private String clientType;  // browser, electron, android, ios
    
    @Column(nullable = false)
    private String clientIdentifier;  // browser-chrome-windows, etc.
    
    @Column(nullable = false)
    private String platform;  // windows, macos, linux, android, ios
    
    private String electronVersion;  // 仅 Electron
    private String browserType;      // 仅 browser
    
    @Column(nullable = false)
    private LocalDateTime lastLoginTime;
    
    @Column(nullable = false)
    private String lastLoginIp;
    
    private Integer loginCount = 0;
    
    // getters and setters
}
```

### Service 层

```java
@Service
public class DeviceService {
    
    @Autowired
    private UserDeviceRepository deviceRepository;
    
    public void registerDevice(Long userId, String fingerprint, 
                               String clientType, String identifier, 
                               String platform) {
        UserDevice device = deviceRepository
            .findByUserIdAndFingerprint(userId, fingerprint)
            .orElse(new UserDevice());
        
        device.setUserId(userId);
        device.setDeviceFingerprint(fingerprint);
        device.setClientType(clientType);
        device.setClientIdentifier(identifier);
        device.setPlatform(platform);
        device.setLastLoginTime(LocalDateTime.now());
        device.setLastLoginIp(getCurrentRequestIp());
        device.setLoginCount(device.getLoginCount() + 1);
        
        deviceRepository.save(device);
    }
    
    public List<UserDevice> getUserDevices(Long userId) {
        return deviceRepository.findByUserIdOrderByLastLoginTimeDesc(userId);
    }
    
    public void removeDevice(Long userId, Long deviceId) {
        deviceRepository.deleteByIdAndUserId(deviceId, userId);
    }
}
```

---

## 📈 后端使用场景

### 1. 安全审计

```java
// 检测异常登录
if (isNewDevice(deviceFingerprint)) {
    sendSecurityAlert(userId, "新设备登录", clientIdentifier);
}

// 检测多地登录
if (isDifferentLocation(userId, currentIp)) {
    logSecurityEvent(userId, "异地登录", clientPlatform);
}
```

### 2. 统计分析

```sql
-- 统计各平台用户数
SELECT client_type, COUNT(DISTINCT user_id) as user_count
FROM user_devices
GROUP BY client_type;

-- 统计浏览器分布
SELECT browser_type, COUNT(*) as count
FROM user_devices
WHERE client_type = 'browser'
GROUP BY browser_type;

-- 统计 Electron 版本分布
SELECT electron_version, COUNT(*) as count
FROM user_devices
WHERE client_type = 'electron'
GROUP BY electron_version;
```

### 3. 设备管理

```java
// 获取用户的设备列表
@GetMapping("/devices")
public List<UserDevice> getUserDevices(@AuthenticationPrincipal Long userId) {
    return deviceService.getUserDevices(userId);
}

// 删除设备（强制下线）
@DeleteMapping("/devices/{deviceId}")
public void removeDevice(@AuthenticationPrincipal Long userId, 
                         @PathVariable Long deviceId) {
    deviceService.removeDevice(userId, deviceId);
}

// 限制登录设备数量
if (getUserDeviceCount(userId) >= MAX_DEVICES) {
    throw new BusinessException("已达到最大设备数量限制");
}
```

### 4. 定向推送

```java
// 向特定平台推送更新通知
if ("electron".equals(clientType)) {
    if (isOldElectronVersion(electronVersion)) {
        sendUpdateNotification(userId, "请升级到最新版客户端");
    }
}

// 向特定浏览器推送兼容性提示
if ("browser".equals(clientType) && "ie".equals(browserType)) {
    sendCompatibilityWarning(userId, "建议使用现代浏览器");
}
```

---

## 🔍 设备指纹生成策略

### 浏览器环境

```javascript
import FingerprintJS from '@fingerprintjs/fingerprintjs'

const generateBrowserFingerprint = async () => {
  const fp = await FingerprintJS.load()
  const result = await fp.get()
  return result.visitorId
}
```

### Electron 环境

```javascript
// 在 preload.js 中
const { machineIdSync } = require('node-machine-id')

ipcMain.handle('get-device-fingerprint', () => {
  return machineIdSync()
})
```

### 移动应用

```javascript
import { Device } from '@capacitor/device'

const generateMobileFingerprint = async () => {
  const info = await Device.getId()
  return info.identifier
}
```

---

## ⚠️ 注意事项

### 1. 隐私保护

- 设备指纹可能涉及隐私问题
- 需要在隐私政策中说明
- 提供用户删除设备信息的选项
- 遵守 GDPR 等数据保护法规

### 2. 安全性

- 不要仅依赖设备指纹进行身份验证
- 结合 IP 地址、时间等多维度判断
- 定期清理过期设备记录
- 对敏感操作要求二次验证

### 3. 性能考虑

- 设备指纹生成可能耗时，建议异步
- 缓存设备信息，减少数据库查询
- 定期归档历史数据
- 设置合理的设备数量限制

### 4. 兼容性

- 某些浏览器可能阻止指纹收集
- 移动设备 ID 可能需要用户授权
- 准备降级方案（使用 sessionId）

---

## 📝 总结

### 核心字段

| 字段 | 必填 | 说明 |
|------|------|------|
| `X-Client-Type` | ✅ | 客户端类型 |
| `X-Client-Identifier` | ✅ | 详细客户端标识 |
| `X-Client-Platform` | ✅ | 操作系统平台 |
| `X-Electron-Version` | ❌ | Electron 版本（仅 Electron） |
| `X-Browser-Type` | ❌ | 浏览器类型（仅 browser） |
| `X-Device-Fingerprint` | ❌ | 设备指纹（可选） |

### 后端建议

1. **记录日志**：保存所有登录设备信息
2. **设备管理**：允许用户查看和管理已登录设备
3. **安全监控**：检测异常登录行为
4. **数据分析**：统计平台分布和用户行为
5. **定向服务**：根据平台提供差异化服务

---

**文档版本**: 1.0  
**最后更新**: 2026-05-02  
**适用平台**: Web / Electron / Android / iOS
