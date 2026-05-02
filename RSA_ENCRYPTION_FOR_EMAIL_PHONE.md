# RSA加密支持 - 邮箱和手机号修改功能

## 📋 概述

本次更新为邮箱和手机号修改功能添加了RSA加密支持，使其与密码修改、登录和注册功能保持一致的安全机制。

---

## 🔐 安全改进

### 改进前
- **密码修改**：✅ 已使用RSA加密（`oldPassword`, `newPassword`）
- **邮箱修改**：❌ 明文传输（`email`）
- **手机号修改**：❌ 明文传输（`phone`）

### 改进后
- **密码修改**：✅ 使用RSA加密
- **邮箱修改**：✅ 使用RSA加密（`encryptedEmail`）
- **手机号修改**：✅ 使用RSA加密（`encryptedPhone`）

---

## 🔄 修改内容

### 1. DTO类修改

#### EmailChangeRequest.java
**变更**：
- `email` → `encryptedEmail`
- Getter/Setter方法相应更新

**修改前**：
```java
private String email;

public String getEmail() {
    return email;
}

public void setEmail(String email) {
    this.email = email;
}
```

**修改后**：
```java
private String encryptedEmail;

public String getEncryptedEmail() {
    return encryptedEmail;
}

public void setEncryptedEmail(String encryptedEmail) {
    this.encryptedEmail = encryptedEmail;
}
```

---

#### PhoneChangeRequest.java
**变更**：
- `phone` → `encryptedPhone`
- Getter/Setter方法相应更新

**修改前**：
```java
private String phone;

public String getPhone() {
    return phone;
}

public void setPhone(String phone) {
    this.phone = phone;
}
```

**修改后**：
```java
private String encryptedPhone;

public String getEncryptedPhone() {
    return encryptedPhone;
}

public void setEncryptedPhone(String encryptedPhone) {
    this.encryptedPhone = encryptedPhone;
}
```

---

### 2. Service层修改

#### UserService.changeEmail()
**新增逻辑**：
1. 从Redis获取RSA密钥对（使用sessionId）
2. 验证密钥对是否存在且有效
3. 重置密钥对过期时间（延长5分钟）
4. 使用私钥解密邮箱地址
5. 继续后续验证和更新流程
6. 成功后清除RSA密钥对（一次性使用）

**关键代码**：
```java
// 3. 从Redis获取RSA密钥对
String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

if (keyPairDTO == null) {
    logger.warn("[修改邮箱] 失败 - UserId: {}, 会话已过期或无效", userId);
    return new EmailChangeResponse(400, false, "会话已过期或无效，请重新获取公钥");
}

// 重置RSA密钥对的过期时间（延长5分钟）
redisTemplate.expire(rsaKey, 5, TimeUnit.MINUTES);

// 4. 使用私钥解密邮箱
String newEmail = RSAKeyManager.decryptWithPrivateKey(
        request.getEncryptedEmail(),
        keyPairDTO.getPrivateKey()
);
newEmail = newEmail.trim();

// ... 后续验证和更新逻辑 ...

// 11. 清除RSA密钥对（一次性使用）
redisTemplate.delete(rsaKey);
```

---

#### UserService.changePhone()
**新增逻辑**：
1. 从Redis获取RSA密钥对（使用sessionId）
2. 验证密钥对是否存在且有效
3. 重置密钥对过期时间（延长5分钟）
4. 使用私钥解密手机号
5. 继续后续验证和更新流程
6. 成功后清除RSA密钥对（一次性使用）

**关键代码**：
```java
// 3. 从Redis获取RSA密钥对
String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

if (keyPairDTO == null) {
    logger.warn("[修改手机号] 失败 - UserId: {}, 会话已过期或无效", userId);
    return new PhoneChangeResponse(400, false, "会话已过期或无效，请重新获取公钥");
}

// 重置RSA密钥对的过期时间（延长5分钟）
redisTemplate.expire(rsaKey, 5, TimeUnit.MINUTES);

// 4. 使用私钥解密手机号
String newPhone = RSAKeyManager.decryptWithPrivateKey(
        request.getEncryptedPhone(),
        keyPairDTO.getPrivateKey()
);
newPhone = newPhone.trim();

// ... 后续验证和更新流程 ...

// 12. 清除RSA密钥对（一次性使用）
redisTemplate.delete(rsaKey);
```

---

## 📊 请求流程对比

### 修改前（明文传输）
```mermaid
sequenceDiagram
    participant Client as 前端
    participant Controller as ProfileController
    participant Service as UserService
    participant Redis as Redis
    participant DB as MySQL

    Client->>Controller: POST /profile/email/set<br/>{email: "test@example.com"}
    Controller->>Service: changeEmail(token, request)
    Service->>DB: UPDATE users SET email='test@example.com'
    Service-->>Client: 成功
```

### 修改后（RSA加密传输）
```mermaid
sequenceDiagram
    participant Client as 前端
    participant Controller as ProfileController
    participant Service as UserService
    participant Redis as Redis
    participant DB as MySQL

    Note over Client,Redis: 前置步骤：获取公钥并加密
    Client->>Redis: GET rsa:key:{sessionId}
    Redis-->>Client: 返回公钥
    Client->>Client: 使用公钥加密邮箱
    
    Note over Client,DB: 正式请求
    Client->>Controller: POST /profile/email/set<br/>{encryptedEmail: "RSA加密数据"}
    Controller->>Service: changeEmail(token, request)
    Service->>Redis: GET rsa:key:{sessionId}
    Redis-->>Service: 返回密钥对
    Service->>Service: 使用私钥解密邮箱
    Service->>DB: UPDATE users SET email='test@example.com'
    Service->>Redis: DELETE rsa:key:{sessionId}
    Service-->>Client: 成功
```

---

## 🔑 RSA密钥管理

### 密钥生命周期
1. **生成**：前端调用 `/auth/rsa/key` 获取公钥和sessionId
2. **存储**：后端将密钥对存储在Redis中，Key格式：`rsa:key:{sessionId}`
3. **使用**：修改邮箱/手机号时使用私钥解密
4. **续期**：每次使用时重置过期时间为5分钟
5. **销毁**：操作成功后立即删除密钥对（一次性使用）

### Redis键值结构
```
Key: rsa:key:{sessionId}
Value: RSAKeyPairDTO {
    publicKey: "-----BEGIN PUBLIC KEY-----...",
    privateKey: "-----BEGIN PRIVATE KEY-----..."
}
TTL: 5分钟（可续期）
```

---

## 📝 API调用示例

### 1. 获取RSA公钥
```bash
curl -X GET http://localhost:8080/auth/rsa/key?sessionId=email-change-session-uuid
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": {
    "sessionId": "email-change-session-uuid",
    "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...\n-----END PUBLIC KEY-----"
  }
}
```

### 2. 发送邮箱验证码
```bash
curl -X POST http://localhost:8080/auth/vfcode/email \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newemail@example.com",
    "sessionId": "email-change-session-uuid"
  }'
```

### 3. 修改邮箱（加密）
```bash
curl -X POST http://localhost:8080/profile/email/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "sessionId": "email-change-session-uuid",
    "encryptedEmail": "RSA加密后的邮箱数据",
    "verificationCode": "123456"
  }'
```

### 4. 修改手机号（加密）
```bash
curl -X POST http://localhost:8080/profile/phone/set \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "sessionId": "phone-change-session-uuid",
    "encryptedPhone": "RSA加密后的手机号数据",
    "verificationCode": "123456"
  }'
```

---

## ✅ 优势

### 1. 安全性提升
- ✅ 敏感数据（邮箱、手机号）在传输过程中加密
- ✅ 防止中间人攻击和网络嗅探
- ✅ 与密码修改保持一致的安全级别

### 2. 一致性
- ✅ 所有敏感信息修改都使用相同的加密机制
- ✅ 统一的密钥管理流程
- ✅ 相似的代码结构和日志记录

### 3. 一次性使用
- ✅ 密钥对在使用后立即销毁
- ✅ 防止重放攻击
- ✅ 减少密钥泄露风险

### 4. 灵活的过期机制
- ✅ 默认5分钟过期
- ✅ 每次使用时自动续期
- ✅ 平衡安全性和用户体验

---

## ⚠️ 注意事项

### 前端实现要求
1. **必须先获取公钥**：在发送修改请求前，先调用 `/auth/rsa/key` 获取公钥
2. **使用公钥加密**：使用获取的公钥加密邮箱/手机号
3. **传递sessionId**：请求体中必须包含正确的sessionId
4. **处理错误**：如果返回"会话已过期或无效"，需要重新获取公钥

### 后端处理
1. **密钥验证**：每次请求都会验证密钥对是否存在
2. **自动续期**：使用时自动重置过期时间
3. **立即销毁**：操作成功后立即删除密钥对
4. **详细日志**：记录密钥获取、解密、销毁等关键步骤

### 错误处理
- **密钥不存在**：返回400错误，提示"会话已过期或无效，请重新获取公钥"
- **解密失败**：抛出异常，返回500错误
- **格式错误**：在解密前验证参数是否为空

---

## 🧪 测试建议

### 单元测试
1. 测试RSA加密和解密的正确性
2. 测试密钥对过期和续期逻辑
3. 测试密钥对一次性使用机制
4. 测试无效密钥的处理

### 集成测试
1. 完整的邮箱修改流程（获取公钥 → 加密 → 发送请求）
2. 完整的手机号修改流程
3. 密钥过期后的重试流程
4. 并发请求的密钥管理

### 安全测试
1. 尝试使用过期的密钥对
2. 尝试重放旧的加密数据
3. 尝试使用错误的密钥解密
4. 网络抓包验证数据是否加密

---

## 📚 相关文件

### DTO类
- `src/main/java/com/mizuka/cloudfilesystem/dto/EmailChangeRequest.java`
- `src/main/java/com/mizuka/cloudfilesystem/dto/PhoneChangeRequest.java`

### Service类
- `src/main/java/com/mizuka/cloudfilesystem/service/UserService.java`

### 工具类
- `src/main/java/com/mizuka/cloudfilesystem/util/RSAKeyManager.java`
- `src/main/java/com/mizuka/cloudfilesystem/util/JwtUtil.java`

### 控制器
- `src/main/java/com/mizuka/cloudfilesystem/controller/ProfileController.java`
- `src/main/java/com/mizuka/cloudfilesystem/controller/AuthController.java`

---

## 🎯 完成清单

- [x] 修改 EmailChangeRequest DTO（email → encryptedEmail）
- [x] 修改 PhoneChangeRequest DTO（phone → encryptedPhone）
- [x] 更新 UserService.changeEmail() 添加RSA解密逻辑
- [x] 更新 UserService.changePhone() 添加RSA解密逻辑
- [x] 添加密钥对验证和续期逻辑
- [x] 添加密钥对销毁逻辑
- [x] 添加详细的日志记录
- [x] 验证代码无编译错误
- [ ] 前端适配（使用公钥加密数据）
- [ ] 单元测试
- [ ] 集成测试
- [ ] 安全测试

---

**更新日期**: 2026-05-02  
**版本**: v1.0  
**作者**: Lingma AI Assistant  
**状态**: ✅ 后端已完成，待前端适配
