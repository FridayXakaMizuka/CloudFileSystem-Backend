# 邮箱和手机号修改接口文档

## 概述

本文档描述了 CloudFileSystem 系统中用于修改用户邮箱和手机号的两个接口。

**核心特点**：
- ✅ 使用 JWT 令牌进行身份认证
- ✅ 需要验证码验证（邮箱/短信）
- ✅ 成功后 **保留** JWT 令牌（用户保持登录状态）
- ✅ 自动同步更新 Redis 缓存
- ✅ 唯一性检查（防止与其他用户冲突）

---

## 接口详情

### 1. 修改邮箱接口

#### 接口信息
- **URL**: `POST /profile/email/set`
- **Content-Type**: `application/json`
- **认证方式**: Bearer Token (JWT)

#### 请求头
```http
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

#### 请求体
```json
{
  "sessionId": "邮箱修改专用会话ID",
  "email": "newemail@example.com",
  "verificationCode": "123456"
}
```

**参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | String | 是 | 邮箱修改专用的会话 ID（从发送验证码时获得） |
| `email` | String | 是 | 新的邮箱地址 |
| `verificationCode` | String | 是 | 发送到新邮箱的 6 位验证码 |

#### 成功响应 (HTTP 200)
```json
{
  "code": 200,
  "success": true,
  "message": "邮箱修改成功"
}
```

#### 失败响应示例

**验证码错误** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "验证码错误或已过期"
}
```

**邮箱已被使用** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "该邮箱已被其他用户使用"
}
```

**无效令牌** (HTTP 401):
```json
{
  "code": 401,
  "success": false,
  "message": "未提供有效的认证令牌"
}
```

---

### 2. 修改手机号接口

#### 接口信息
- **URL**: `POST /profile/phone/set`
- **Content-Type**: `application/json`
- **认证方式**: Bearer Token (JWT)

#### 请求头
```http
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

#### 请求体
```json
{
  "sessionId": "手机号修改专用会话ID",
  "phone": "13800138000",
  "verificationCode": "123456"
}
```

**参数说明**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | String | 是 | 手机号修改专用的会话 ID（从发送验证码时获得） |
| `phone` | String | 是 | 新的手机号码（中国大陆 11 位） |
| `verificationCode` | String | 是 | 发送到新手机号的 6 位验证码 |

#### 成功响应 (HTTP 200)
```json
{
  "code": 200,
  "success": true,
  "message": "手机号修改成功"
}
```

#### 失败响应示例

**验证码错误** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "验证码错误或已过期"
}
```

**手机号已被使用** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "该手机号已被其他用户使用"
}
```

**手机号格式错误** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "手机号格式不正确"
}
```

**无效令牌** (HTTP 401):
```json
{
  "code": 401,
  "success": false,
  "message": "未提供有效的认证令牌"
}
```

---

## 完整使用流程

### 修改邮箱流程

```mermaid
sequenceDiagram
    participant Client as 前端客户端
    participant Auth as AuthController
    participant Profile as ProfileController
    participant UserService as UserService
    participant EmailService as EmailVerificationService
    participant Redis as Redis
    participant DB as MySQL Database

    Client->>Auth: 1. POST /auth/vfcode/email<br/>(发送验证码到新邮箱)
    Auth->>EmailService: 发送邮箱验证码
    EmailService->>Redis: 存储验证码<br/>Key: email:code:{sessionId}:{email}
    EmailService-->>Client: 返回成功
    
    Client->>Profile: 2. POST /profile/email/set<br/>(提交修改)
    Profile->>UserService: changeEmail(token, request)
    UserService->>UserService: 验证 JWT 令牌
    UserService->>EmailService: verifyCode(sessionId, email, code)
    EmailService->>Redis: 获取并验证验证码
    EmailService->>Redis: 删除验证码（一次性使用）
    EmailService-->>UserService: 验证结果
    UserService->>DB: 检查邮箱唯一性
    UserService->>DB: UPDATE users SET email = ? WHERE id = ?
    UserService->>Redis: 更新个人资料缓存<br/>Key: profile:{userId}
    UserService-->>Profile: 返回成功响应
    Profile-->>Client: 邮箱修改成功
```

#### 步骤详解

**步骤 1: 发送验证码到新邮箱**

```bash
curl -X POST http://localhost:8080/auth/vfcode/email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newemail@example.com",
    "sessionId": "email-change-session-uuid"
  }'
```

**步骤 2: 提交邮箱修改**

```bash
curl -X POST http://localhost:8080/profile/email/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "sessionId": "email-change-session-uuid",
    "email": "newemail@example.com",
    "verificationCode": "123456"
  }'
```

---

### 修改手机号流程

```mermaid
sequenceDiagram
    participant Client as 前端客户端
    participant Auth as AuthController
    participant Profile as ProfileController
    participant UserService as UserService
    participant SmsService as SmsVerificationService
    participant Redis as Redis
    participant DB as MySQL Database

    Client->>Auth: 1. POST /auth/vfcode/phone<br/>(发送验证码到新手机号)
    Auth->>SmsService: 发送短信验证码
    SmsService->>阿里云: 调用短信服务
    SmsService->>Redis: 存储验证码<br/>Key: sms:code:{sessionId}:{phone}
    SmsService-->>Client: 返回成功
    
    Client->>Profile: 2. POST /profile/phone/set<br/>(提交修改)
    Profile->>UserService: changePhone(token, request)
    UserService->>UserService: 验证 JWT 令牌
    UserService->>SmsService: verifyCode(sessionId, phone, code)
    SmsService->>Redis: 获取并验证验证码
    SmsService->>Redis: 删除验证码（一次性使用）
    SmsService-->>UserService: 验证结果
    UserService->>DB: 检查手机号唯一性
    UserService->>DB: UPDATE users SET phone = ? WHERE id = ?
    UserService->>Redis: 更新个人资料缓存<br/>Key: profile:{userId}
    UserService-->>Profile: 返回成功响应
    Profile-->>Client: 手机号修改成功
```

#### 步骤详解

**步骤 1: 发送验证码到新手机号**

```bash
curl -X POST http://localhost:8080/auth/vfcode/phone \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "13800138000",
    "sessionId": "phone-change-session-uuid"
  }'
```

**步骤 2: 提交手机号修改**

```bash
curl -X POST http://localhost:8080/profile/phone/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "sessionId": "phone-change-session-uuid",
    "phone": "13800138000",
    "verificationCode": "123456"
  }'
```

---

## 后端实现逻辑

### 邮箱修改 (`UserService.changeEmail`)

```java
public EmailChangeResponse changeEmail(String token, EmailChangeRequest request) {
    // 1. 从 JWT 令牌中获取用户 ID
    Long userId = jwtUtil.getUserIdFromToken(token);
    
    // 2. 验证请求参数（sessionId, email, verificationCode）
    
    // 3. 验证邮箱格式（正则表达式）
    String emailRegex = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    
    // 4. 验证邮箱验证码
    boolean emailVerified = emailVerificationService.verifyCode(
        request.getSessionId(), 
        newEmail, 
        request.getVerificationCode()
    );
    
    // 5. 检查邮箱是否已被其他用户使用
    User existingUser = userMapper.findByEmail(newEmail);
    if (existingUser != null && !existingUser.getId().equals(userId)) {
        return error("该邮箱已被其他用户使用");
    }
    
    // 6. 更新数据库中的邮箱
    int affectedRows = userMapper.updateEmail(userId, newEmail);
    
    // 7. 同步更新 Redis 缓存中的邮箱
    String profileCacheKey = "profile:" + userId;
    // 解析缓存 -> 更新邮箱 -> 重新存入
    
    // 8. 返回成功响应
    return new EmailChangeResponse(200, true, "邮箱修改成功");
}
```

### 手机号修改 (`UserService.changePhone`)

```java
public PhoneChangeResponse changePhone(String token, PhoneChangeRequest request) {
    // 1. 从 JWT 令牌中获取用户 ID
    Long userId = jwtUtil.getUserIdFromToken(token);
    
    // 2. 验证请求参数（sessionId, phone, verificationCode）
    
    // 3. 验证手机号格式（中国大陆：^1[3-9]\\d{9}$）
    String phoneRegex = "^1[3-9]\\d{9}$";
    
    // 4. 验证手机验证码
    boolean phoneVerified = smsVerificationService.verifyCode(
        request.getSessionId(), 
        newPhone, 
        request.getVerificationCode()
    );
    
    // 5. 检查手机号是否已被其他用户使用
    User existingUser = userMapper.findByPhone(newPhone);
    if (existingUser != null && !existingUser.getId().equals(userId)) {
        return error("该手机号已被其他用户使用");
    }
    
    // 6. 更新数据库中的手机号
    int affectedRows = userMapper.updatePhone(userId, newPhone);
    
    // 7. 同步更新 Redis 缓存中的手机号
    String profileCacheKey = "profile:" + userId;
    // 解析缓存 -> 更新手机号 -> 重新存入
    
    // 8. 返回成功响应
    return new PhoneChangeResponse(200, true, "手机号修改成功");
}
```

---

## Redis 缓存变化

### 修改邮箱时的 Redis 操作

**步骤 1: 发送验证码**
```redis
SET email:code:{sessionId}:{newEmail} = "123456"
EXPIRE email:code:{sessionId}:{newEmail} 300
```

**步骤 2: 验证并修改**
```redis
GET email:code:{sessionId}:{newEmail}          # 获取验证码
DEL email:code:{sessionId}:{newEmail}          # 删除验证码（一次性使用）
GET profile:{userId}                           # 获取缓存的个人资料
SET profile:{userId} = updated_json_data       # 更新缓存（保持原 TTL）
```

### 修改手机号时的 Redis 操作

**步骤 1: 发送验证码**
```redis
SET sms:code:{sessionId}:{newPhone} = "123456"
EXPIRE sms:code:{sessionId}:{newPhone} 300
SET sms:send_time:{newPhone} = timestamp
EXPIRE sms:send_time:{newPhone} 60
```

**步骤 2: 验证并修改**
```redis
GET sms:code:{sessionId}:{newPhone}            # 获取验证码
DEL sms:code:{sessionId}:{newPhone}            # 删除验证码（一次性使用）
GET profile:{userId}                           # 获取缓存的个人资料
SET profile:{userId} = updated_json_data       # 更新缓存（保持原 TTL）
```

---

## 安全机制

### 1. JWT 认证
- 所有请求必须携带有效的 JWT 令牌
- 后端验证令牌的签名和有效期
- 从令牌中提取用户 ID，确保操作的是当前用户的账户

### 2. 验证码验证
- 验证码与 sessionId 和新邮箱/手机号绑定
- 验证码有有效期限制（5 分钟）
- 验证后立即清除，防止重复使用（一次性使用）

### 3. 唯一性检查
- 检查新邮箱/手机号是否已被其他用户使用
- 排除当前用户自己的邮箱/手机号
- 避免账户冲突和数据泄露

### 4. 格式验证
- **邮箱格式**：`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`
- **手机号格式**：`^1[3-9]\d{9}$`（中国大陆标准）

### 5. SessionId 管理
- 每个修改操作使用独立的 sessionId
- sessionId 在发送验证码时生成
- 验证成功后自动清除验证码

---

## 与密码修改的区别

| 特性 | 密码修改 | 邮箱/手机号修改 |
|------|---------|----------------|
| 接口 URL | `/profile/password/set` | `/profile/email/set`<br>`/profile/phone/set` |
| 请求方法 | POST | POST |
| 认证方式 | JWT + RSA 加密 | JWT |
| 密码加密 | RSA 加密 | 不需要 |
| 验证码 | 不需要 | **需要** |
| SessionId 用途 | 密码修改专用 | 邮箱/手机号修改专用 |
| **成功后处理** | **清除 JWT**<br>**强制重登录** | **保留 JWT**<br>**保持登录** |
| 安全性要求 | 最高（涉及账户安全） | 高（需要验证码） |
| 缓存更新 | 清除缓存 | **同步更新缓存** |

---

## 测试用例

### 邮箱修改测试

```bash
# 测试 1: 正常修改邮箱
curl -X POST http://localhost:8080/profile/email/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "email": "newemail@example.com",
    "verificationCode": "123456"
  }'

# 预期响应: {"code":200,"success":true,"message":"邮箱修改成功"}


# 测试 2: 验证码错误
curl -X POST http://localhost:8080/profile/email/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "email": "newemail@example.com",
    "verificationCode": "wrong_code"
  }'

# 预期响应: {"code":400,"success":false,"message":"验证码错误或已过期"}


# 测试 3: 邮箱已被使用
curl -X POST http://localhost:8080/profile/email/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "email": "existing@example.com",
    "verificationCode": "123456"
  }'

# 预期响应: {"code":400,"success":false,"message":"该邮箱已被其他用户使用"}


# 测试 4: 无效 JWT 令牌
curl -X POST http://localhost:8080/profile/email/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer INVALID_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "email": "newemail@example.com",
    "verificationCode": "123456"
  }'

# 预期响应: HTTP 401
```

### 手机号修改测试

```bash
# 测试 1: 正常修改手机号
curl -X POST http://localhost:8080/profile/phone/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "phone": "13800138000",
    "verificationCode": "123456"
  }'

# 预期响应: {"code":200,"success":true,"message":"手机号修改成功"}


# 测试 2: 手机号格式错误
curl -X POST http://localhost:8080/profile/phone/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "phone": "12345",
    "verificationCode": "123456"
  }'

# 预期响应: {"code":400,"success":false,"message":"手机号格式不正确"}


# 测试 3: 手机号已被使用
curl -X POST http://localhost:8080/profile/phone/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "sessionId": "test-session-id",
    "phone": "13800138000",
    "verificationCode": "123456"
  }'

# 预期响应: {"code":400,"success":false,"message":"该手机号已被其他用户使用"}
```

---

## 注意事项

### 1. SessionId 管理
- ✅ 使用独立的 sessionId 用于邮箱/手机号修改
- ✅ 从 `/auth/vfcode/email` 或 `/auth/vfcode/phone` 获取 sessionId
- ❌ 不要在不同功能间共用 sessionId

### 2. JWT 令牌
- ✅ 邮箱/手机号修改成功后 **保留** JWT 令牌
- ✅ 用户可以继续操作，无需重新登录
- ❌ 不要像密码修改那样清除 JWT

### 3. 验证码
- ✅ 验证码与 sessionId 和新邮箱/手机号绑定
- ✅ 验证后立即清除（一次性使用）
- ❌ 不要允许验证码重复使用

### 4. 唯一性检查
- ✅ 检查新邮箱/手机号是否已被其他用户使用
- ✅ 排除当前用户自己的邮箱/手机号
- ❌ 不要允许两个账户使用相同的邮箱/手机号

### 5. 缓存同步
- ✅ 修改成功后同步更新 Redis 缓存
- ✅ 保持原有的 TTL（不重置）
- ❌ 如果更新失败，删除缓存以保证数据一致性

---

## 相关文件

### 后端文件
- **DTO 类**:
  - `src/main/java/com/mizuka/cloudfilesystem/dto/EmailChangeRequest.java`
  - `src/main/java/com/mizuka/cloudfilesystem/dto/EmailChangeResponse.java`
  - `src/main/java/com/mizuka/cloudfilesystem/dto/PhoneChangeRequest.java`
  - `src/main/java/com/mizuka/cloudfilesystem/dto/PhoneChangeResponse.java`

- **Controller**:
  - `src/main/java/com/mizuka/cloudfilesystem/controller/ProfileController.java`
    - `changeEmail()` 方法
    - `changePhone()` 方法

- **Service**:
  - `src/main/java/com/mizuka/cloudfilesystem/service/UserService.java`
    - `changeEmail()` 方法
    - `changePhone()` 方法

- **Mapper**:
  - `src/main/java/com/mizuka/cloudfilesystem/mapper/UserMapper.java`
    - `updateEmail()` 方法
    - `updatePhone()` 方法

### 前端参考
- **API 配置**: `src/config/api.js`
- **前端视图**: `src/views/ProfileEditView.vue`
- **SessionId 工具**: `src/utils/sessionId.js`

---

## 完成清单

- [x] 创建 DTO 类（EmailChangeRequest, EmailChangeResponse, PhoneChangeRequest, PhoneChangeResponse）
- [x] 添加 UserMapper 方法（updateEmail, updatePhone）
- [x] 实现 UserService 方法（changeEmail, changePhone）
- [x] 实现 ProfileController 接口（/profile/email/set, /profile/phone/set）
- [x] 验证 JWT 令牌
- [x] 验证验证码
- [x] 检查唯一性
- [x] 更新数据库
- [x] 同步更新 Redis 缓存
- [x] 编写接口文档

---

**文档版本**: v1.0  
**创建日期**: 2026-05-02  
**最后更新**: 2026-05-02  
**维护者**: CloudFileSystem 开发团队
