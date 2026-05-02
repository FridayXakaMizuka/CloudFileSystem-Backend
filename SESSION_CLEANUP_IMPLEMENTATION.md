# SessionId 清理机制实现文档

## 📋 概述

本文档描述了在邮箱、密码、手机号修改成功后清除对应 sessionId 相关缓存的实现机制。

---

## 🎯 实现目标

确保在用户成功修改邮箱、密码或手机号后，及时清理 Redis 中与该 sessionId 相关的所有缓存数据，包括：
- 验证码缓存
- RSA 密钥对缓存
- 短信发送时间记录

---

## 🔧 实现细节

### 1. 密码修改 (`changePassword`)

#### 清除的缓存

**位置**: `UserService.changePassword()` 方法

**清除内容**:
1. ✅ RSA 密钥对：`rsa:key:{sessionId}`
2. ✅ 个人资料缓存：`profile:{userId}`
3. ✅ 所有以该 sessionId 开头的邮箱验证码：`email:code:{sessionId}:*`
4. ✅ 所有以该 sessionId 开头的短信验证码：`sms:code:{sessionId}:*`

**代码实现**:
```java
// 10. 修改成功后，删除Redis中的密钥对（一次性使用）
redisTemplate.delete(redisKey);
logger.debug("[修改密码] RSA密钥对已删除 - UserId: {}", userId);

// 11. 清除个人资料缓存（因为密码已更改）
String profileCacheKey = "profile:" + userId;
profileRedisTemplate.delete(profileCacheKey);

// 12. 清除sessionId相关的验证码缓存（如果存在）
String emailCodeKey = "email:code:" + request.getSessionId();
String smsCodeKey = "sms:code:" + request.getSessionId();

// 删除所有以该sessionId开头的邮箱验证码键
java.util.Set<String> emailKeys = redisTemplate.keys(emailCodeKey + "*");
if (emailKeys != null && !emailKeys.isEmpty()) {
    redisTemplate.delete(emailKeys);
    logger.debug("[修改密码] 已清除邮箱验证码缓存 - UserId: {}, 清除数量: {}", userId, emailKeys.size());
}

// 删除所有以该sessionId开头的短信验证码键
java.util.Set<String> smsKeys = redisTemplate.keys(smsCodeKey + "*");
if (smsKeys != null && !smsKeys.isEmpty()) {
    redisTemplate.delete(smsKeys);
    logger.debug("[修改密码] 已清除短信验证码缓存 - UserId: {}, 清除数量: {}", userId, smsKeys.size());
}

logger.info("[修改密码] 成功 - UserId: {}, 所有缓存已清除", userId);
```

**特点**:
- 使用通配符 `*` 匹配所有相关的验证码键
- 批量删除，提高效率
- 详细的日志记录

---

### 2. 邮箱修改 (`changeEmail`)

#### 清除的缓存

**位置**: `UserService.changeEmail()` 方法

**清除内容**:
1. ✅ 邮箱验证码：`email:code:{sessionId}:{newEmail}`
2. ✅ RSA 密钥对（如果存在）：`rsa:key:{sessionId}`

**代码实现**:
```java
// 8. 清除sessionId相关的验证码缓存
String emailCodeKey = "email:code:" + request.getSessionId() + ":" + newEmail;
redisTemplate.delete(emailCodeKey);
logger.debug("[修改邮箱] 已清除邮箱验证码缓存 - UserId: {}, Key: {}", userId, emailCodeKey);

// 9. 清除RSA密钥对（如果存在）
String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
Boolean rsaExists = redisTemplate.hasKey(rsaKey);
if (rsaExists != null && rsaExists) {
    redisTemplate.delete(rsaKey);
    logger.debug("[修改邮箱] 已清除RSA密钥对 - UserId: {}", userId);
}
```

**特点**:
- 精确删除指定的邮箱验证码键
- 检查 RSA 密钥对是否存在，避免不必要的操作
- 注意：不清除个人资料缓存，而是同步更新

---

### 3. 手机号修改 (`changePhone`)

#### 清除的缓存

**位置**: `UserService.changePhone()` 方法

**清除内容**:
1. ✅ 短信验证码：`sms:code:{sessionId}:{newPhone}`
2. ✅ 短信发送时间记录：`sms:send_time:{newPhone}`
3. ✅ RSA 密钥对（如果存在）：`rsa:key:{sessionId}`

**代码实现**:
```java
// 8. 清除sessionId相关的验证码缓存
String smsCodeKey = "sms:code:" + request.getSessionId() + ":" + newPhone;
redisTemplate.delete(smsCodeKey);
logger.debug("[修改手机号] 已清除短信验证码缓存 - UserId: {}, Key: {}", userId, smsCodeKey);

// 9. 清除手机号发送时间记录
String smsSendTimeKey = "sms:send_time:" + newPhone;
redisTemplate.delete(smsSendTimeKey);
logger.debug("[修改手机号] 已清除短信发送时间记录 - UserId: {}, Key: {}", userId, smsSendTimeKey);

// 10. 清除RSA密钥对（如果存在）
String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
Boolean rsaExists = redisTemplate.hasKey(rsaKey);
if (rsaExists != null && rsaExists) {
    redisTemplate.delete(rsaKey);
    logger.debug("[修改手机号] 已清除RSA密钥对 - UserId: {}", userId);
}
```

**特点**:
- 删除短信验证码和发送时间记录
- 防止用户在修改后立即再次发送短信
- 检查 RSA 密钥对是否存在

---

## 📊 缓存清理对比

| 修改类型 | RSA密钥对 | 邮箱验证码 | 短信验证码 | 短信发送时间 | 个人资料缓存 |
|---------|----------|-----------|-----------|------------|------------|
| **密码修改** | ✅ 删除 | ✅ 批量删除 | ✅ 批量删除 | ❌ 保留 | ✅ 删除 |
| **邮箱修改** | ✅ 删除（如存在） | ✅ 删除 | ❌ 不处理 | ❌ 不处理 | 🔄 更新 |
| **手机号修改** | ✅ 删除（如存在） | ❌ 不处理 | ✅ 删除 | ✅ 删除 | 🔄 更新 |

**说明**:
- ✅ 删除：主动清除缓存
- ❌ 不处理：不涉及该缓存
- 🔄 更新：同步更新缓存内容（不删除）

---

## 🔍 技术要点

### 1. 通配符匹配

在密码修改中，使用通配符 `*` 匹配所有相关的验证码键：

```java
String emailCodeKey = "email:code:" + request.getSessionId();
java.util.Set<String> emailKeys = redisTemplate.keys(emailCodeKey + "*");
```

**优点**:
- 可以一次性清除多个验证码（如果用户多次请求）
- 确保不会遗漏任何相关缓存

**注意事项**:
- `keys()` 操作在大容量 Redis 中可能影响性能
- 建议在开发环境或小规模系统中使用
- 生产环境可以考虑使用 `SCAN` 命令替代

### 2. 存在性检查

在删除 RSA 密钥对时，先检查是否存在：

```java
Boolean rsaExists = redisTemplate.hasKey(rsaKey);
if (rsaExists != null && rsaExists) {
    redisTemplate.delete(rsaKey);
}
```

**优点**:
- 避免不必要的删除操作
- 减少 Redis 负载
- 提高代码健壮性

### 3. 日志记录

每个清理操作都有详细的日志记录：

```java
logger.debug("[修改邮箱] 已清除邮箱验证码缓存 - UserId: {}, Key: {}", userId, emailCodeKey);
logger.info("[修改密码] 成功 - UserId: {}, 所有缓存已清除", userId);
```

**日志级别**:
- `DEBUG`: 详细的清理操作信息
- `INFO`: 重要的成功信息

---

## 🎯 设计原则

### 1. 安全性优先

- 验证码一次性使用，验证后立即删除
- RSA 密钥对使用后删除，防止重放攻击
- 密码修改后清除所有相关缓存，强制重新认证

### 2. 资源管理

- 及时清理不再需要的缓存
- 避免内存泄漏
- 提高 Redis 利用率

### 3. 用户体验

- 邮箱/手机号修改后保持登录状态（不清除 JWT）
- 同步更新个人资料缓存，避免频繁查询数据库
- 密码修改后清除缓存，提示用户重新登录

### 4. 一致性保证

- 数据库更新成功后才清除缓存
- 缓存清理失败不影响主流程
- 详细的错误日志便于排查问题

---

## 📝 使用示例

### 密码修改流程

```bash
# 1. 获取 RSA 公钥
POST /auth/rsa-key
{
  "sessionId": "password-change-session"
}

# 2. 提交密码修改
POST /profile/password/set
Authorization: Bearer YOUR_JWT_TOKEN
{
  "sessionId": "password-change-session",
  "oldPassword": "encrypted_old_password",
  "newPassword": "encrypted_new_password"
}

# 成功后自动清除：
# - rsa:key:password-change-session
# - email:code:password-change-session:*
# - sms:code:password-change-session:*
# - profile:{userId}
```

### 邮箱修改流程

```bash
# 1. 发送验证码到新邮箱
POST /auth/vfcode/email
{
  "email": "newemail@example.com",
  "sessionId": "email-change-session"
}

# 2. 提交邮箱修改
POST /profile/email/set
Authorization: Bearer YOUR_JWT_TOKEN
{
  "sessionId": "email-change-session",
  "email": "newemail@example.com",
  "verificationCode": "123456"
}

# 成功后自动清除：
# - email:code:email-change-session:newemail@example.com
# - rsa:key:email-change-session (如果存在)
# 注意：profile:{userId} 会被更新而不是删除
```

### 手机号修改流程

```bash
# 1. 发送验证码到新手机号
POST /auth/vfcode/phone
{
  "phoneNumber": "13800138000",
  "sessionId": "phone-change-session"
}

# 2. 提交手机号修改
POST /profile/phone/set
Authorization: Bearer YOUR_JWT_TOKEN
{
  "sessionId": "phone-change-session",
  "phone": "13800138000",
  "verificationCode": "123456"
}

# 成功后自动清除：
# - sms:code:phone-change-session:13800138000
# - sms:send_time:13800138000
# - rsa:key:phone-change-session (如果存在)
# 注意：profile:{userId} 会被更新而不是删除
```

---

## ⚠️ 注意事项

### 1. 密码修改的特殊性

密码修改是最敏感的操作，因此：
- ✅ 清除所有相关缓存
- ✅ 清除个人资料缓存
- ✅ 前端需要清除 JWT 令牌
- ✅ 用户需要重新登录

### 2. 邮箱/手机号修改的区别

邮箱和手机号修改相对安全：
- ✅ 保留 JWT 令牌
- ✅ 保持登录状态
- ✅ 同步更新个人资料缓存
- ✅ 用户可以继续操作

### 3. Redis 性能考虑

- 通配符匹配（`keys`）在小规模系统中没问题
- 大规模系统建议使用 `SCAN` 命令
- 定期监控 Redis 内存使用情况

### 4. 异常处理

- 缓存清理失败不应影响主流程
- 详细的日志记录便于排查问题
- 必要时可以添加重试机制

---

## 🧪 测试建议

### 单元测试

```java
@Test
public void testChangeEmail_ClearsSessionCache() {
    // 1. 设置测试数据
    String sessionId = "test-session";
    String email = "test@example.com";
    
    // 2. 模拟 Redis 中存在验证码
    redisTemplate.opsForValue().set(
        "email:code:" + sessionId + ":" + email, 
        "123456", 
        300, 
        TimeUnit.SECONDS
    );
    
    // 3. 执行邮箱修改
    EmailChangeResponse response = userService.changeEmail(token, request);
    
    // 4. 验证缓存已清除
    assertNull(redisTemplate.opsForValue().get("email:code:" + sessionId + ":" + email));
    assertTrue(response.isSuccess());
}
```

### 集成测试

```bash
# 1. 修改邮箱
curl -X POST http://localhost:8080/profile/email/set \
  -H "Authorization: Bearer TOKEN" \
  -d '{
    "sessionId": "test-session",
    "email": "new@example.com",
    "verificationCode": "123456"
  }'

# 2. 检查 Redis
redis-cli
> GET email:code:test-session:new@example.com
(nil)  # 应该返回 nil，表示已清除

> GET rsa:key:test-session
(nil)  # 应该返回 nil，表示已清除
```

---

## 📚 相关文件

- **Service 实现**: `src/main/java/com/mizuka/cloudfilesystem/service/UserService.java`
  - `changePassword()` 方法（第 610-737 行）
  - `changeEmail()` 方法（第 859-964 行）
  - `changePhone()` 方法（第 972-1123 行）

- **验证码服务**:
  - `EmailVerificationService.java`
  - `SmsVerificationService.java`

- **Redis 配置**:
  - `RedisConfig.java`

---

## ✅ 完成清单

- [x] 密码修改后清除 RSA 密钥对
- [x] 密码修改后清除个人资料缓存
- [x] 密码修改后批量清除邮箱验证码
- [x] 密码修改后批量清除短信验证码
- [x] 邮箱修改后清除邮箱验证码
- [x] 邮箱修改后清除 RSA 密钥对（如存在）
- [x] 邮箱修改后同步更新个人资料缓存
- [x] 手机号修改后清除短信验证码
- [x] 手机号修改后清除短信发送时间记录
- [x] 手机号修改后清除 RSA 密钥对（如存在）
- [x] 手机号修改后同步更新个人资料缓存
- [x] 添加详细的日志记录
- [x] 无编译错误

---

**文档版本**: v1.0  
**创建日期**: 2026-05-02  
**最后更新**: 2026-05-02  
**维护者**: CloudFileSystem 开发团队
