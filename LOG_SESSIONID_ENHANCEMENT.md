# 修改邮箱和手机号日志增强 - 添加SessionId

## 📋 变更概述

在修改邮箱和修改手机号的功能中，为所有关键日志输出添加了 `SessionId` 字段，便于问题追踪和调试。

---

## 🔍 变更原因

### 问题背景

在之前的实现中，日志只记录了 `UserId`，但在以下场景中难以定位问题：

1. **多个会话并发**：同一用户可能在多个设备或浏览器标签页中操作
2. **密钥不匹配问题**：无法确定是哪个sessionId对应的密钥对出现问题
3. **调试困难**：当出现BadPaddingException时，无法快速定位是哪个会话的问题

### 解决方案

在所有关键日志中添加 `SessionId`，使得：
- ✅ 可以追踪每个会话的完整操作流程
- ✅ 快速定位密钥不匹配的具体会话
- ✅ 便于排查并发问题
- ✅ 提高日志的可读性和可追溯性

---

## ✅ 已实施的修改

### 修改1：修改邮箱功能（changeEmail）

**文件**：`UserService.java` - `changeEmail()` 方法

#### 修改的日志点

| 行号 | 日志级别 | 原日志 | 新日志 |
|------|----------|--------|--------|
| 889 | INFO | `[修改邮箱] 开始处理 - UserId: {}` | `[修改邮箱] 开始处理 - UserId: {}, SessionId: {}` |
| 912 | WARN | `[修改邮箱] 失败 - UserId: {}, 会话已过期或无效` | `[修改邮箱] 失败 - UserId: {}, SessionId: {}, 会话已过期或无效` |
| 915 | DEBUG | `[修改邮箱] RSA密钥对获取成功 - UserId: {}` | `[修改邮箱] RSA密钥对获取成功 - UserId: {}, SessionId: {}` |
| 921 | DEBUG | `[修改邮箱] 开始解密邮箱 - UserId: {}` | `[修改邮箱] 开始解密邮箱 - UserId: {}, SessionId: {}` |
| 927 | DEBUG | `[修改邮箱] 邮箱解密完成 - UserId: {}` | `[修改邮箱] 邮箱解密完成 - UserId: {}, SessionId: {}` |
| 997 | INFO | `[修改邮箱] 成功 - UserId: {}, 新邮箱: {}` | `[修改邮箱] 成功 - UserId: {}, SessionId: {}, 新邮箱: {}` |

**共计**：6处日志修改

---

### 修改2：修改手机号功能（changePhone）

**文件**：`UserService.java` - `changePhone()` 方法

#### 修改的日志点

| 行号 | 日志级别 | 原日志 | 新日志 |
|------|----------|--------|--------|
| 1031 | INFO | `[修改手机号] 开始处理 - UserId: {}` | `[修改手机号] 开始处理 - UserId: {}, SessionId: {}` |
| 1054 | WARN | `[修改手机号] 失败 - UserId: {}, 会话已过期或无效` | `[修改手机号] 失败 - UserId: {}, SessionId: {}, 会话已过期或无效` |
| 1057 | DEBUG | `[修改手机号] RSA密钥对获取成功 - UserId: {}` | `[修改手机号] RSA密钥对获取成功 - UserId: {}, SessionId: {}` |
| 1063 | DEBUG | `[修改手机号] 开始解密手机号 - UserId: {}` | `[修改手机号] 开始解密手机号 - UserId: {}, SessionId: {}` |
| 1069 | DEBUG | `[修改手机号] 手机号解密完成 - UserId: {}` | `[修改手机号] 手机号解密完成 - UserId: {}, SessionId: {}` |
| 1085 | WARN | `[修改手机号] 失败 - UserId: {}, 验证码错误或已过期` | `[修改手机号] 失败 - UserId: {}, SessionId: {}, 验证码错误或已过期` |
| 1088 | DEBUG | `[修改手机号] 验证码验证通过 - UserId: {}` | `[修改手机号] 验证码验证通过 - UserId: {}, SessionId: {}` |
| 1139 | INFO | `[修改手机号] 成功 - UserId: {}, 新手机号: {}` | `[修改手机号] 成功 - UserId: {}, SessionId: {}, 新手机号: {}` |

**共计**：8处日志修改

---

## 📊 修改前后对比

### 修改前

```
[修改邮箱] 开始处理 - UserId: 123
[修改邮箱] RSA密钥对获取成功 - UserId: 123
[修改邮箱] 开始解密邮箱 - UserId: 123
[修改邮箱] 邮箱解密完成 - UserId: 123
[修改邮箱] 成功 - UserId: 123, 新邮箱: new@example.com
```

**问题**：
- ❌ 无法区分是哪个会话的操作
- ❌ 当有多个并发请求时，日志混乱
- ❌ 难以追踪密钥不匹配的具体会话

---

### 修改后

```
[修改邮箱] 开始处理 - UserId: 123, SessionId: abc-123-def
[修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: abc-123-def
[修改邮箱] 开始解密邮箱 - UserId: 123, SessionId: abc-123-def
[修改邮箱] 邮箱解密完成 - UserId: 123, SessionId: abc-123-def
[修改邮箱] 成功 - UserId: 123, SessionId: abc-123-def, 新邮箱: new@example.com
```

**优势**：
- ✅ 可以清晰看到每个会话的完整流程
- ✅ 便于追踪特定sessionId的操作
- ✅ 快速定位问题会话
- ✅ 支持并发场景下的日志分析

---

## 🎯 使用场景

### 场景1：追踪特定会话的操作

```bash
# 在日志中搜索特定sessionId
grep "SessionId: abc-123-def" application.log
```

**输出示例**：
```
2026-05-02 10:30:15 [修改邮箱] 开始处理 - UserId: 123, SessionId: abc-123-def
2026-05-02 10:30:15 [修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: abc-123-def
2026-05-02 10:30:16 [修改邮箱] 开始解密邮箱 - UserId: 123, SessionId: abc-123-def
2026-05-02 10:30:16 [修改邮箱] 邮箱解密完成 - UserId: 123, SessionId: abc-123-def
2026-05-02 10:30:16 [修改邮箱] 成功 - UserId: 123, SessionId: abc-123-def, 新邮箱: new@example.com
```

---

### 场景2：排查BadPaddingException错误

**修改前的日志**：
```
[修改邮箱] 开始处理 - UserId: 123
[修改邮箱] RSA密钥对获取成功 - UserId: 123
[修改邮箱] 开始解密邮箱 - UserId: 123
ERROR: BadPaddingException: Padding error in decryption
```

**问题**：不知道是哪个sessionId导致的错误。

**修改后的日志**：
```
[修改邮箱] 开始处理 - UserId: 123, SessionId: abc-123-def
[修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: abc-123-def
[修改邮箱] 开始解密邮箱 - UserId: 123, SessionId: abc-123-def
ERROR: BadPaddingException: Padding error in decryption
```

**优势**：
- ✅ 可以立即知道是 `abc-123-def` 这个会话的问题
- ✅ 可以检查Redis中该sessionId对应的密钥对
- ✅ 可以追溯前端是否使用了正确的公钥

---

### 场景3：并发场景分析

当同一用户在多个设备或浏览器标签页中操作时：

```
[修改邮箱] 开始处理 - UserId: 123, SessionId: session-A
[修改邮箱] 开始处理 - UserId: 123, SessionId: session-B
[修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: session-A
[修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: session-B
[修改邮箱] 成功 - UserId: 123, SessionId: session-A, 新邮箱: email-a@example.com
[修改邮箱] 成功 - UserId: 123, SessionId: session-B, 新邮箱: email-b@example.com
```

**优势**：
- ✅ 清晰区分不同会话的操作
- ✅ 不会混淆不同会话的数据
- ✅ 便于分析并发问题

---

## 🧪 测试验证

### 测试1：正常修改邮箱

```bash
# 1. 调用修改邮箱接口
curl -X POST http://localhost:8080/profile/email/set \
  -H "Authorization: Bearer {jwt_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-session-123",
    "encryptedEmail": "{encrypted_email}",
    "verificationCode": "123456"
  }'

# 2. 查看日志
tail -f logs/application.log | grep "test-session-123"
```

**预期日志**：
```
[修改邮箱] 开始处理 - UserId: 123, SessionId: test-session-123
[修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: test-session-123
[修改邮箱] 开始解密邮箱 - UserId: 123, SessionId: test-session-123
[修改邮箱] 邮箱解密完成 - UserId: 123, SessionId: test-session-123
[修改邮箱] 成功 - UserId: 123, SessionId: test-session-123, 新邮箱: new@example.com
```

---

### 测试2：会话过期

```bash
# 使用过期的sessionId
curl -X POST http://localhost:8080/profile/email/set \
  -H "Authorization: Bearer {jwt_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "expired-session-456",
    "encryptedEmail": "{encrypted_email}",
    "verificationCode": "123456"
  }'
```

**预期日志**：
```
[修改邮箱] 开始处理 - UserId: 123, SessionId: expired-session-456
[修改邮箱] 失败 - UserId: 123, SessionId: expired-session-456, 会话已过期或无效
```

---

### 测试3：BadPaddingException错误

```bash
# 使用错误的加密数据
curl -X POST http://localhost:8080/profile/email/set \
  -H "Authorization: Bearer {jwt_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "bad-key-session-789",
    "encryptedEmail": "invalid_encrypted_data",
    "verificationCode": "123456"
  }'
```

**预期日志**：
```
[修改邮箱] 开始处理 - UserId: 123, SessionId: bad-key-session-789
[修改邮箱] RSA密钥对获取成功 - UserId: 123, SessionId: bad-key-session-789
[修改邮箱] 开始解密邮箱 - UserId: 123, SessionId: bad-key-session-789
ERROR: RSA解密失败：密钥不匹配或数据已损坏
```

**优势**：可以立即知道是 `bad-key-session-789` 这个会话的问题。

---

## 📝 日志格式规范

### 标准格式

```
[操作名称] 状态 - UserId: {userId}, SessionId: {sessionId}, 其他信息
```

### 示例

```
[修改邮箱] 开始处理 - UserId: 123, SessionId: abc-123-def
[修改邮箱] 成功 - UserId: 123, SessionId: abc-123-def, 新邮箱: new@example.com
[修改邮箱] 失败 - UserId: 123, SessionId: abc-123-def, 验证码错误或已过期
```

### 日志级别

| 级别 | 使用场景 | 示例 |
|------|----------|------|
| INFO | 操作开始、操作成功 | `[修改邮箱] 开始处理`, `[修改邮箱] 成功` |
| WARN | 业务逻辑失败 | `[修改邮箱] 失败 - 验证码错误` |
| ERROR | 系统异常 | `[修改邮箱] 异常` |
| DEBUG | 详细步骤 | `[修改邮箱] RSA密钥对获取成功`, `[修改邮箱] 开始解密邮箱` |

---

## ⚠️ 注意事项

### 1. SessionId的安全性

- ✅ SessionId可以在日志中记录（不是敏感信息）
- ✅ SessionId用于追踪会话，不包含用户隐私
- ❌ 不要在日志中记录私钥、密码等敏感信息

---

### 2. 日志性能

- ✅ 添加SessionId对性能影响极小（只是字符串拼接）
- ✅ INFO和WARN级别的日志在生产环境中应该开启
- ⚠️ DEBUG级别的日志在生产环境中可以考虑关闭

---

### 3. 日志检索

建议使用以下命令检索日志：

```bash
# 按SessionId检索
grep "SessionId: abc-123-def" application.log

# 按UserId和SessionId检索
grep "UserId: 123.*SessionId: abc-123-def" application.log

# 检索特定操作的完整流程
grep "SessionId: abc-123-def" application.log | grep "修改邮箱"
```

---

## 🎯 总结

### 修改内容

- ✅ 修改邮箱功能：6处日志添加SessionId
- ✅ 修改手机号功能：8处日志添加SessionId
- ✅ 共计14处日志修改

### 优势

1. **可追溯性**：可以追踪每个会话的完整操作流程
2. **易调试**：快速定位问题会话
3. **并发支持**：清晰区分不同会话的操作
4. **问题分析**：便于排查BadPaddingException等错误

### 下一步

1. **重启服务**：使修改生效
2. **观察日志**：确认日志格式正确
3. **测试验证**：测试各种场景下的日志输出
4. **前端配合**：确保前端正确传递sessionId

---

**修改日期**: 2026-05-02  
**版本**: v1.0  
**作者**: Lingma AI Assistant  
**状态**: ✅ 已完成
