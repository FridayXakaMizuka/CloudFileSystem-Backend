# SessionId 清理机制 - 快速参考

## 🎯 概述

在邮箱、密码、手机号修改成功后，系统会自动清除对应的 sessionId 相关缓存。

---

## 📋 清理规则

### 1. 密码修改 (`/profile/password/set`)

**清除内容**:
- ✅ `rsa:key:{sessionId}` - RSA 密钥对
- ✅ `email:code:{sessionId}:*` - 所有邮箱验证码（通配符匹配）
- ✅ `sms:code:{sessionId}:*` - 所有短信验证码（通配符匹配）
- ✅ `profile:{userId}` - 个人资料缓存

**特点**: 最严格的清理，确保安全性

---

### 2. 邮箱修改 (`/profile/email/set`)

**清除内容**:
- ✅ `email:code:{sessionId}:{newEmail}` - 邮箱验证码
- ✅ `rsa:key:{sessionId}` - RSA 密钥对（如果存在）
- 🔄 `profile:{userId}` - **更新**而不是删除

**特点**: 保持登录状态，同步更新缓存

---

### 3. 手机号修改 (`/profile/phone/set`)

**清除内容**:
- ✅ `sms:code:{sessionId}:{newPhone}` - 短信验证码
- ✅ `sms:send_time:{newPhone}` - 短信发送时间记录
- ✅ `rsa:key:{sessionId}` - RSA 密钥对（如果存在）
- 🔄 `profile:{userId}` - **更新**而不是删除

**特点**: 防止立即再次发送短信，保持登录状态

---

## 🔍 Redis Key 格式

| 类型 | Key 格式 | 示例 |
|------|---------|------|
| RSA 密钥对 | `rsa:key:{sessionId}` | `rsa:key:abc-123` |
| 邮箱验证码 | `email:code:{sessionId}:{email}` | `email:code:abc-123:user@example.com` |
| 短信验证码 | `sms:code:{sessionId}:{phone}` | `sms:code:abc-123:13800138000` |
| 短信发送时间 | `sms:send_time:{phone}` | `sms:send_time:13800138000` |
| 个人资料缓存 | `profile:{userId}` | `profile:10001` |

---

## 💡 关键代码

### 密码修改
```java
// 批量清除所有相关验证码
String emailCodeKey = "email:code:" + request.getSessionId();
java.util.Set<String> emailKeys = redisTemplate.keys(emailCodeKey + "*");
if (emailKeys != null && !emailKeys.isEmpty()) {
    redisTemplate.delete(emailKeys);
}
```

### 邮箱修改
```java
// 清除指定的邮箱验证码
String emailCodeKey = "email:code:" + request.getSessionId() + ":" + newEmail;
redisTemplate.delete(emailCodeKey);
```

### 手机号修改
```java
// 清除短信验证码和发送时间
String smsCodeKey = "sms:code:" + request.getSessionId() + ":" + newPhone;
redisTemplate.delete(smsCodeKey);

String smsSendTimeKey = "sms:send_time:" + newPhone;
redisTemplate.delete(smsSendTimeKey);
```

---

## ⚠️ 注意事项

### ✅ 必须做的
1. 使用独立的 sessionId 用于每次修改操作
2. 修改成功后前端清除对应的 sessionId
3. 密码修改后前端清除 JWT 令牌并强制重新登录

### ❌ 不要做的
1. 不要在不同功能间共用 sessionId
2. 不要在修改失败后清除 sessionId（可以重试）
3. 不要手动管理 Redis 缓存（由后端自动处理）

---

## 📊 对比表

| 特性 | 密码修改 | 邮箱修改 | 手机号修改 |
|------|---------|---------|-----------|
| 清除 RSA 密钥对 | ✅ | ✅ | ✅ |
| 清除验证码 | ✅ 批量 | ✅ 单个 | ✅ 单个 |
| 清除发送时间 | ❌ | ❌ | ✅ |
| 个人资料缓存 | ❌ 删除 | 🔄 更新 | 🔄 更新 |
| 保持登录状态 | ❌ | ✅ | ✅ |
| 需要重新登录 | ✅ | ❌ | ❌ |

---

## 🧪 测试命令

### 检查缓存是否清除
```bash
# 连接 Redis
redis-cli

# 检查邮箱验证码
GET email:code:test-session:new@example.com
# 应该返回 (nil)

# 检查短信验证码
GET sms:code:test-session:13800138000
# 应该返回 (nil)

# 检查 RSA 密钥对
GET rsa:key:test-session
# 应该返回 (nil)

# 检查短信发送时间
GET sms:send_time:13800138000
# 应该返回 (nil)
```

---

## 📚 完整文档

- **详细实现**: `SESSION_CLEANUP_IMPLEMENTATION.md`
- **API 文档**: `EMAIL_PHONE_CHANGE_API.md`
- **实现总结**: `IMPLEMENTATION_SUMMARY.md`

---

**最后更新**: 2026-05-02  
**版本**: v1.0
