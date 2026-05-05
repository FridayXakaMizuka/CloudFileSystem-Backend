# 登录设备标识 - 快速参考表

## 📋 请求头总览

```http
X-Client-Type: <类型>
X-Client-Identifier: <标识符>
X-Client-Platform: <平台>
X-Electron-Version: <版本>  # 可选，仅 Electron
X-Browser-Type: <浏览器>    # 可选，仅 browser
X-Device-Fingerprint: <指纹> # 可选
```

---

## 🔢 字段值对照表

### X-Client-Type（客户端类型）

| 值 | 说明 |
|---|------|
| `browser` | Web 浏览器 |
| `electron` | Electron PC 客户端 |
| `android` | Android 应用 |
| `ios` | iOS 应用 |

---

### X-Client-Identifier（客户端标识符）

#### 浏览器
```
browser-chrome-windows
browser-firefox-linux
browser-safari-macos
browser-edge-windows
browser-opera-windows
```

#### Electron
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

---

### X-Client-Platform（操作系统）

| 值 | 说明 |
|---|------|
| `windows` | Windows |
| `macos` | macOS |
| `linux` | Linux |
| `android` | Android |
| `ios` | iOS |

---

### X-Browser-Type（浏览器类型）

| 值 | 说明 |
|---|------|
| `chrome` | Chrome |
| `firefox` | Firefox |
| `safari` | Safari |
| `edge` | Edge |
| `opera` | Opera |

---

## 🎯 完整示例

### 场景 1：Chrome on Windows
```http
X-Client-Type: browser
X-Client-Identifier: browser-chrome-windows
X-Client-Platform: windows
X-Browser-Type: chrome
```

### 场景 2：Electron on Windows x64
```http
X-Client-Type: electron
X-Client-Identifier: electron-windows-x64
X-Client-Platform: windows
X-Electron-Version: 28.0.0
```

### 场景 3：Android App
```http
X-Client-Type: android
X-Client-Identifier: android-app
X-Client-Platform: android
```

### 场景 4：iOS App
```http
X-Client-Type: ios
X-Client-Identifier: ios-app
X-Client-Platform: ios
```

---

## 💻 Spring Boot 接收代码

```java
@PostMapping("/auth/login")
public ResponseEntity<?> login(
    @RequestHeader("X-Client-Type") String clientType,
    @RequestHeader("X-Client-Identifier") String clientIdentifier,
    @RequestHeader("X-Client-Platform") String clientPlatform,
    @RequestHeader(value = "X-Electron-Version", required = false) String electronVersion,
    @RequestHeader(value = "X-Browser-Type", required = false) String browserType,
    @RequestBody LoginRequest request
) {
    // 记录日志
    log.info("Login from: {} ({}) on {}", 
        clientType, clientIdentifier, clientPlatform);
    
    // 处理登录...
}
```

---

## 📊 数据库表结构建议

```sql
CREATE TABLE user_devices (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_fingerprint VARCHAR(255),
    client_type VARCHAR(50) NOT NULL,      -- browser/electron/android/ios
    client_identifier VARCHAR(100) NOT NULL, -- browser-chrome-windows etc.
    platform VARCHAR(50) NOT NULL,          -- windows/macos/linux/android/ios
    electron_version VARCHAR(50),           -- 仅 Electron
    browser_type VARCHAR(50),               -- 仅 browser
    last_login_time DATETIME NOT NULL,
    last_login_ip VARCHAR(45),
    login_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_fingerprint (device_fingerprint)
);
```

---

## 🔍 常用查询

```sql
-- 统计各平台用户数
SELECT client_type, COUNT(DISTINCT user_id) as users
FROM user_devices
GROUP BY client_type;

-- 获取用户的设备列表
SELECT * FROM user_devices
WHERE user_id = ?
ORDER BY last_login_time DESC;

-- 检测新设备登录
SELECT * FROM user_devices
WHERE user_id = ? AND device_fingerprint = ?;

-- 清理90天未使用的设备
DELETE FROM user_devices
WHERE last_login_time < DATE_SUB(NOW(), INTERVAL 90 DAY);
```

---

## ⚡ 前端使用示例

```javascript
import { addClientInfoToHeaders } from '@/utils/clientDetector'

// 登录时
const headers = new Headers({
  'Content-Type': 'application/json'
})

// 自动添加所有设备标识
addClientInfoToHeaders(headers)

// 发送登录请求
const response = await fetch('/auth/login', {
  method: 'POST',
  credentials: 'include',
  headers: headers,
  body: JSON.stringify(loginData)
})
```

---

**详细文档**: [LOGIN_DEVICE_IDENTIFIERS.md](./LOGIN_DEVICE_IDENTIFIERS.md)  
**更新时间**: 2026-05-02
