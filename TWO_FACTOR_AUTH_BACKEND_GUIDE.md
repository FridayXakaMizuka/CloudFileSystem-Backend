# 二次验证功能 - 后端实现指南

## 📋 概述

本文档说明了前端二次验证功能的实现逻辑和后端需要提供的接口规范。当用户登录时，如果后端判断需要二次验证，会返回特定响应，前端将跳转到二次验证页面进行身份确认。

---

## 🎯 业务流程

### 完整流程

```
1. 用户输入账号密码登录
   ↓
2. POST /auth/login
   ↓
3. 后端验证账号密码
   ↓
4a. 不需要二次验证 → 返回 JWT + 用户信息 → 登录成功
4b. 需要二次验证 → 返回 requiresTwoFactor=true + 用户基本信息 + 密保问题
   ↓
5. 前端跳转到 /two-factor-auth 页面
   ↓
6. 用户选择验证方式（邮箱/手机/密保问题）
   ↓
7. 前端生成新的 sessionId，获取 RSA 公钥
   ↓
8. 发送验证码或验证答案
   ↓
9. POST /auth/verify/{email|phone|security_answer}
   ↓
10. 后端验证通过 → 返回 JWT + 用户信息 → 登录成功
```

---

## 🔐 登录接口变更

### POST /auth/login

#### 请求体

```json
{
  "sessionId": "xxx",
  "userIdOrEmail": "user@example.com",
  "encryptedPassword": "...",
  "tokenExpiration": 604800
}
```

**新增请求头**（设备标识）:
```http
X-Client-Type: browser
X-Client-Identifier: browser-chrome-windows
X-Client-Platform: windows
X-Browser-Type: chrome
X-Device-Fingerprint: abc123def456  # 可选
```

#### 响应 - 情况 A：直接登录成功

```json
{
  "success": true,
  "code": 200,
  "message": "登录成功",
  "requiresTwoFactor": false,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 123,
  "userType": "USER",
  "homeDirectory": "/home/user"
}
```

#### 响应 - 情况 B：需要二次验证

```json
{
  "success": true,
  "code": 200,
  "message": "需要二次验证",
  "requiresTwoFactor": true,
  "userId": 123,
  "email": "user@example.com",
  "phone": "13800138000",
  "securityQuestion": "你的出生地是哪里？",
  "securityQuestionId": 1
}
```

**字段说明**:
- `requiresTwoFactor`: 是否需要二次验证
- `userId`: 用户ID
- `email`: 邮箱地址（用于显示打码后的邮箱）
- `phone`: 手机号（用于显示打码后的手机号）
- `securityQuestion`: 密保问题文本
- `securityQuestionId`: 密保问题序号（可选）

---

## 🔑 二次验证接口

### 1. 邮箱验证

**接口**: `POST /auth/verify/email`

**请求头**:
```http
Content-Type: application/json
X-Client-Type: browser
X-Client-Identifier: browser-chrome-windows
X-Client-Platform: windows
X-Browser-Type: chrome
```

**请求体**:
```json
{
  "sessionId": "xxx",
  "userId": 123,
  "verificationCode": "123456"
}
```

**成功响应**:
```json
{
  "success": true,
  "code": 200,
  "message": "验证成功",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 123,
  "userType": "USER",
  "homeDirectory": "/home/user"
}
```

**失败响应**:
```json
{
  "success": false,
  "code": 400,
  "message": "验证码错误或已过期"
}
```

---

### 2. 手机验证

**接口**: `POST /auth/verify/phone`

**请求头**:
```http
Content-Type: application/json
X-Client-Type: android
X-Client-Identifier: android-app
X-Client-Platform: android
```

**请求体**:
```json
{
  "sessionId": "xxx",
  "userId": 123,
  "verificationCode": "123456"
}
```

**响应**: 同邮箱验证

---

### 3. 密保问题验证

**接口**: `POST /auth/verify/security_answer`

**请求头**:
```http
Content-Type: application/json
X-Client-Type: electron
X-Client-Identifier: electron-windows-x64
X-Client-Platform: windows
X-Electron-Version: 28.0.0
```

**请求体**:
```json
{
  "sessionId": "xxx",
  "userId": 123,
  "encryptedAnswer": "加密后的答案"
}
```

**响应**: 同邮箱验证

---

## 💻 后端实现示例（Spring Boot）

### Controller 层

```java
@RestController
@RequestMapping("/auth")
public class AuthController {
    
    @Autowired
    private AuthService authService;
    
    @Autowired
    private DeviceService deviceService;
    
    /**
     * 登录接口
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
        @RequestHeader(value = "X-Client-Type", required = false) String clientType,
        @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
        @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
        @RequestHeader(value = "X-Electron-Version", required = false) String electronVersion,
        @RequestHeader(value = "X-Browser-Type", required = false) String browserType,
        @RequestHeader(value = "X-Device-Fingerprint", required = false) String deviceFingerprint,
        @RequestBody LoginRequest request
    ) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 1. 验证账号密码
            User user = authService.authenticate(request.getUserIdOrEmail(), 
                                                  request.getEncryptedPassword(),
                                                  request.getSessionId());
            
            if (user == null) {
                response.put("success", false);
                response.put("code", 401);
                response.put("message", "用户名或密码错误");
                return ResponseEntity.status(401).body(response);
            }
            
            // 2. 记录设备信息
            if (deviceFingerprint != null) {
                deviceService.registerDevice(
                    user.getId(),
                    deviceFingerprint,
                    clientType,
                    clientIdentifier,
                    clientPlatform
                );
            }
            
            // 3. 判断是否需要二次验证
            boolean requiresTwoFactor = authService.requiresTwoFactor(user);
            
            if (requiresTwoFactor) {
                // 需要二次验证
                response.put("success", true);
                response.put("code", 200);
                response.put("message", "需要二次验证");
                response.put("requiresTwoFactor", true);
                response.put("userId", user.getId());
                response.put("email", user.getEmail());
                response.put("phone", user.getPhone());
                
                // 获取密保问题
                SecurityQuestion question = authService.getSecurityQuestion(user.getId());
                if (question != null) {
                    response.put("securityQuestion", question.getQuestionText());
                    response.put("securityQuestionId", question.getId());
                }
                
                return ResponseEntity.ok(response);
            } else {
                // 直接登录成功
                String token = jwtUtil.generateToken(user);
                
                response.put("success", true);
                response.put("code", 200);
                response.put("message", "登录成功");
                response.put("requiresTwoFactor", false);
                response.put("token", token);
                response.put("userId", user.getId());
                response.put("userType", user.getUserType());
                response.put("homeDirectory", user.getHomeDirectory());
                
                return ResponseEntity.ok(response);
            }
            
        } catch (Exception e) {
            log.error("登录失败", e);
            response.put("success", false);
            response.put("code", 500);
            response.put("message", "系统错误");
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 邮箱验证
     */
    @PostMapping("/verify/email")
    public ResponseEntity<Map<String, Object>> verifyEmail(
        @RequestHeader(value = "X-Client-Type", required = false) String clientType,
        @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
        @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
        @RequestBody VerifyEmailRequest request
    ) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 1. 验证 sessionId 和验证码
            boolean valid = authService.verifyEmailCode(
                request.getSessionId(),
                request.getUserId(),
                request.getVerificationCode()
            );
            
            if (!valid) {
                response.put("success", false);
                response.put("code", 400);
                response.put("message", "验证码错误或已过期");
                return ResponseEntity.badRequest().body(response);
            }
            
            // 2. 生成 JWT
            User user = userService.findById(request.getUserId());
            String token = jwtUtil.generateToken(user);
            
            // 3. 返回成功响应
            response.put("success", true);
            response.put("code", 200);
            response.put("message", "验证成功");
            response.put("token", token);
            response.put("userId", user.getId());
            response.put("userType", user.getUserType());
            response.put("homeDirectory", user.getHomeDirectory());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("邮箱验证失败", e);
            response.put("success", false);
            response.put("code", 500);
            response.put("message", "系统错误");
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 手机验证
     */
    @PostMapping("/verify/phone")
    public ResponseEntity<Map<String, Object>> verifyPhone(
        @RequestHeader(value = "X-Client-Type", required = false) String clientType,
        @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
        @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
        @RequestBody VerifyPhoneRequest request
    ) {
        // 实现逻辑与邮箱验证类似
        // ...
    }
    
    /**
     * 密保问题验证
     */
    @PostMapping("/verify/security_answer")
    public ResponseEntity<Map<String, Object>> verifySecurityAnswer(
        @RequestHeader(value = "X-Client-Type", required = false) String clientType,
        @RequestHeader(value = "X-Client-Identifier", required = false) String clientIdentifier,
        @RequestHeader(value = "X-Client-Platform", required = false) String clientPlatform,
        @RequestBody VerifySecurityAnswerRequest request
    ) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 1. 解密答案
            String answer = rsaUtil.decrypt(request.getEncryptedAnswer(), 
                                            request.getSessionId());
            
            // 2. 验证答案
            boolean valid = authService.verifySecurityAnswer(
                request.getSessionId(),
                request.getUserId(),
                answer
            );
            
            if (!valid) {
                response.put("success", false);
                response.put("code", 400);
                response.put("message", "答案错误");
                return ResponseEntity.badRequest().body(response);
            }
            
            // 3. 生成 JWT
            User user = userService.findById(request.getUserId());
            String token = jwtUtil.generateToken(user);
            
            // 4. 返回成功响应
            response.put("success", true);
            response.put("code", 200);
            response.put("message", "验证成功");
            response.put("token", token);
            response.put("userId", user.getId());
            response.put("userType", user.getUserType());
            response.put("homeDirectory", user.getHomeDirectory());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("密保问题验证失败", e);
            response.put("success", false);
            response.put("code", 500);
            response.put("message", "系统错误");
            return ResponseEntity.status(500).body(response);
        }
    }
}
```

### Service 层

```java
@Service
public class AuthService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;
    
    @Autowired
    private SecurityQuestionRepository securityQuestionRepository;
    
    @Autowired
    private JwtUtil jwtUtil;
    
    /**
     * 判断是否需要二次验证
     */
    public boolean requiresTwoFactor(User user) {
        // 可以根据以下因素判断：
        // 1. 新设备登录
        // 2. 异地登录
        // 3. 用户设置了二次验证
        // 4. 敏感操作
        
        // 示例：检查是否是新设备
        return deviceService.isNewDevice(user.getId(), getCurrentDeviceFingerprint());
    }
    
    /**
     * 获取用户的密保问题
     */
    public SecurityQuestion getSecurityQuestion(Long userId) {
        return securityQuestionRepository.findByUserId(userId);
    }
    
    /**
     * 验证邮箱验证码
     */
    public boolean verifyEmailCode(String sessionId, Long userId, String code) {
        VerificationCode verificationCode = verificationCodeRepository
            .findBySessionIdAndUserIdAndType(sessionId, userId, "EMAIL");
        
        if (verificationCode == null) {
            return false;
        }
        
        // 检查是否过期
        if (verificationCode.isExpired()) {
            verificationCodeRepository.delete(verificationCode);
            return false;
        }
        
        // 验证验证码
        boolean valid = verificationCode.getCode().equals(code);
        
        if (valid) {
            verificationCodeRepository.delete(verificationCode);
        }
        
        return valid;
    }
    
    /**
     * 验证密保问题答案
     */
    public boolean verifySecurityAnswer(String sessionId, Long userId, String answer) {
        SecurityQuestion question = securityQuestionRepository.findByUserId(userId);
        
        if (question == null) {
            return false;
        }
        
        // 比较答案（不区分大小写）
        return question.getAnswer().equalsIgnoreCase(answer.trim());
    }
}
```

### 实体类

```java
@Entity
@Table(name = "verification_codes")
public class VerificationCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String sessionId;
    
    @Column(nullable = false)
    private Long userId;
    
    @Column(nullable = false)
    private String type;  // EMAIL, PHONE
    
    @Column(nullable = false)
    private String code;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    // getters and setters
}

@Entity
@Table(name = "security_questions")
public class SecurityQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long userId;
    
    @Column(nullable = false)
    private Integer questionId;
    
    @Column(nullable = false, length = 500)
    private String questionText;
    
    @Column(nullable = false, length = 500)
    private String answer;
    
    // getters and setters
}
```

---

## 📊 数据库设计

### 验证码表

```sql
CREATE TABLE verification_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,  -- 'EMAIL' or 'PHONE'
    code VARCHAR(10) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NOT NULL,
    INDEX idx_session_user (session_id, user_id),
    INDEX idx_expires (expires_at)
);
```

### 密保问题表

```sql
CREATE TABLE security_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    question_id INT NOT NULL,
    question_text VARCHAR(500) NOT NULL,
    answer VARCHAR(500) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

### 用户设备表

```sql
CREATE TABLE user_devices (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_fingerprint VARCHAR(255) NOT NULL,
    client_type VARCHAR(50),
    client_identifier VARCHAR(100),
    platform VARCHAR(50),
    last_login_time DATETIME NOT NULL,
    last_login_ip VARCHAR(45),
    login_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_fingerprint (user_id, device_fingerprint),
    FOREIGN KEY (user_id) REFERENCES users(id)
);
```

---

## 🔍 二次验证触发条件

### 建议的触发策略

1. **新设备登录**
   ```java
   if (deviceService.isNewDevice(userId, deviceFingerprint)) {
       return true;  // 需要二次验证
   }
   ```

2. **异地登录**
   ```java
   if (locationService.isDifferentLocation(userId, currentIp)) {
       return true;
   }
   ```

3. **用户设置**
   ```java
   if (user.isTwoFactorEnabled()) {
       return true;
   }
   ```

4. **高风险操作**
   ```java
   if (riskAssessment.isHighRisk(userId, loginContext)) {
       return true;
   }
   ```

5. **定期验证**
   ```java
   if (lastVerifiedTime.isBefore(LocalDateTime.now().minusDays(30))) {
       return true;
   }
   ```

---

## ⚠️ 安全注意事项

### 1. SessionId 管理

- 每次验证前生成新的 sessionId
- sessionId 有效期 5 分钟
- 验证成功后立即清除

### 2. 验证码安全

- 验证码长度：6 位数字
- 有效期：5 分钟
- 最多尝试次数：3 次
- 发送频率限制：60 秒一次

### 3. 密保问题安全

- 答案需要加密存储
- 验证时使用 RSA 加密传输
- 不区分大小写比较
- 去除首尾空格

### 4. JWT 令牌

- 验证成功后才生成 JWT
- JWT 中包含 userId、userType 等基本信息
- 不包含 nickname 等个人信息
- 设置合理的过期时间

### 5. 设备指纹

- 记录登录设备信息
- 用于异常检测
- 允许用户管理已信任设备

---

## 📈 统计分析

### 可以统计的数据

1. **二次验证使用率**
   ```sql
   SELECT 
       COUNT(CASE WHEN requires_two_factor = true THEN 1 END) * 100.0 / COUNT(*) as two_factor_rate
   FROM login_logs
   WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY);
   ```

2. **验证方式分布**
   ```sql
   SELECT 
       verify_method,
       COUNT(*) as count
   FROM two_factor_verifications
   WHERE created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
   GROUP BY verify_method;
   ```

3. **验证成功率**
   ```sql
   SELECT 
       verify_method,
       SUM(CASE WHEN success = true THEN 1 ELSE 0 END) * 100.0 / COUNT(*) as success_rate
   FROM two_factor_verifications
   GROUP BY verify_method;
   ```

---

## 🎯 前端集成要点

### 1. 登录响应处理

```javascript
if (result.requiresTwoFactor === true) {
  // 跳转到二次验证页面
  router.push({
    path: '/two-factor-auth',
    state: {
      userInfo: {
        userId: result.userId,
        email: result.email,
        phone: result.phone
      },
      securityQuestion: result.securityQuestion || ''
    }
  })
}
```

### 2. 设备标识发送

```javascript
import { addClientInfoToHeaders } from '@/utils/clientDetector'

const headers = new Headers({
  'Content-Type': 'application/json'
})

// 自动添加设备信息
addClientInfoToHeaders(headers)

// 发送请求
fetch('/auth/login', {
  method: 'POST',
  headers: headers,
  body: JSON.stringify(loginData)
})
```

### 3. 验证成功后处理

```javascript
// 保存 JWT 和用户信息
saveAuthInfo(result.token, {
  userId: userInfo.value.userId,
  userType: result.userType,
  homeDirectory: result.homeDirectory
})

// 获取完整个人信息
await fetchAllUserInfo()

// 跳转到首页
router.push('/')
```

---

## 📝 总结

### 核心接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/auth/login` | POST | 登录（可能返回需要二次验证） |
| `/auth/verify/email` | POST | 邮箱验证 |
| `/auth/verify/phone` | POST | 手机验证 |
| `/auth/verify/security_answer` | POST | 密保问题验证 |

### 关键特性

- ✅ 支持三种验证方式：邮箱、手机、密保问题
- ✅ 自动发送验证码
- ✅ 倒计时控制
- ✅ 设备信息记录
- ✅ 安全的密钥管理
- ✅ 完善的错误处理

### 安全建议

1. 实施速率限制
2. 记录所有验证尝试
3. 监控异常行为
4. 定期清理过期数据
5. 提供用户设备管理功能

---

**文档版本**: 1.0  
**最后更新**: 2026-05-02  
**适用平台**: Web / Electron / Android / iOS
