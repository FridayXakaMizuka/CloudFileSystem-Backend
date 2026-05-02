# 前端会话 ID 改造 - 后端接口参考文档

## 📋 概述

本文档描述前端会话 ID（sessionId）改造后的接口规范，供后端开发参考。

### 核心变化

| 项目 | 改造前 | 改造后 |
|------|--------|--------|
| **sessionId 来源** | 后端生成并返回 | 前端生成（UUID v4） |
| **存储位置** | Cookie（后端设置） | Cookie + 组件 state（前端设置） |
| **有效期** | 7天 | 5分钟（前端控制，刷新页面时检查） |
| **验证机制** | `/auth/is_rsa_valid` 验证 | 无需验证（前端生成即有效） |
| **共享方式** | 每个接口独立 sessionId | RSA、邮箱、手机验证码共用同一 sessionId |

---

## 🔧 接口规范

### 1. 获取 RSA 公钥

#### 请求
```
POST /auth/rsa-key
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

#### 响应
```json
{
  "code": 200,
  "success": true,
  "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A..."
}
```

#### 后端处理逻辑
1. 接收前端传来的 `sessionId`
2. 生成 RSA 密钥对
3. 将 `sessionId` 作为 key，RSA 私钥等信息存入 Redis 缓存（有效期建议 10 分钟）
4. 返回公钥给前端
5. **不再返回 sessionId**（由前端生成）

---

### 2. 验证 RSA 密钥有效性（可选）

> ⚠️ **注意**：如果前端不再需要验证 sessionId 有效性，此接口可以移除或简化

#### 请求
```
POST /auth/is_rsa_valid
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "publicKey": "-----BEGIN PUBLIC KEY-----\n..."
}
```

#### 响应
```json
{
  "valid": true
}
```

#### 后端处理逻辑
1. 从 Redis 中查询 `sessionId` 是否存在
2. 如果存在，返回 `{ valid: true }`
3. 如果不存在，返回 `{ valid: false }`

---

### 3. 发送邮箱验证码

#### 请求
```
POST /auth/vfcode/email
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "email": "user@example.com"
}
```

#### 响应
```json
{
  "code": 200,
  "success": true,
  "message": "验证码已发送，请查收邮件"
}
```

#### 后端处理逻辑
1. 验证 `sessionId` 是否在 Redis 中存在（确保 RSA 密钥已生成）
2. 生成 6 位数字验证码
3. 将验证码与 `sessionId` + `email` 关联存储到 Redis（有效期 5 分钟）
4. 发送邮件
5. **不再返回 sessionId**（前端已生成并传入）

---

### 4. 发送手机验证码

#### 请求
```
POST /auth/vfcode/phone
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "phoneNumber": "13800138000"
}
```

#### 响应
```json
{
  "code": 200,
  "success": true,
  "message": "验证码已发送，请注意查收"
}
```

#### 后端处理逻辑
1. 验证 `sessionId` 是否在 Redis 中存在
2. 生成 6 位数字验证码
3. 将验证码与 `sessionId` + `phoneNumber` 关联存储到 Redis（有效期 5 分钟）
4. 发送短信
5. **不再返回 sessionId**

---

### 5. 注册

#### 请求
```
POST /auth/register
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "data": [
    {
      "nickname": "zhangsan_123",
      "email": "zhangsan@example.com",
      "emailVfCode": "123456",
      "phone": "13800138000",
      "phoneVfCode": "654321",
      "encryptedPassword": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...",
      "securityQuestion": 1,
      "securityAnswer": "北京"
    }
  ]
}
```

#### 响应
```json
{
  "code": 200,
  "success": true,
  "data": [
    {
      "id": "user_123456",
      "nickname": "zhangsan_123"
    }
  ],
  "message": "注册成功"
}
```

#### 后端处理逻辑

**重要变化：**
1. **手机号为非必填项**
   - 如果 `phone` 为空字符串或 null，跳过手机验证码验证
   - 如果 `phone` 有值，则必须验证 `phoneVfCode`

2. **昵称格式验证（后端不验证，仅前端验证）**
   - 前端规则：只含数字、字母和下划线，必须以字母开头
   - 正则：`/^[a-zA-Z][a-zA-Z0-9_]*$/`
   - 后端可选择性验证或不验证

3. **密码验证**
   - 长度限制：6-14 位
   - **无字符限制**（可以是任何字符）
   - 使用 RSA 私钥解密 `encryptedPassword`

4. **验证码验证**
   - 从 Redis 中根据 `sessionId` + `email` 查找邮箱验证码
   - 如果 `phone` 有值，从 Redis 中根据 `sessionId` + `phone` 查找手机验证码
   - 验证通过后删除验证码（防止重复使用）

5. **sessionId 清理**
   - 注册成功后，从 Redis 中删除该 `sessionId` 相关的缓存

---

### 6. 登录（接口形式不变）

#### 请求
```
POST /auth/login
Content-Type: application/json

{
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "zhangsan",
  "encryptedPassword": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...",
  "tokenExpiration": 604800
}
```

#### 响应
```json
{
  "code": 200,
  "success": true,
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "user_123456",
  "nickname": "张三",
  "userType": "normal",
  "homeDirectory": "/home/zhangsan",
  "message": "登录成功"
}
```

#### 后端处理逻辑
1. 从 Redis 中根据 `sessionId` 查找 RSA 私钥
2. 使用私钥解密 `encryptedPassword`
3. 验证用户名和密码
4. 生成 JWT 令牌
5. 登录成功后，可选择从 Redis 中删除该 `sessionId`

---

## 🗄️ Redis 缓存设计

### 键值结构

```
Key: rsa_session:{sessionId}
Value: {
  "privateKey": "-----BEGIN PRIVATE KEY-----\n...",
  "publicKey": "-----BEGIN PUBLIC KEY-----\n...",
  "createdAt": 1234567890,
  "expireAt": 1234567890 + 600  // 10分钟后过期
}
TTL: 600秒（10分钟）
```

```
Key: email_code:{sessionId}:{email}
Value: "123456"
TTL: 300秒（5分钟）
```

```
Key: phone_code:{sessionId}:{phone}
Value: "654321"
TTL: 300秒（5分钟）
```

### 缓存策略

1. **RSA 密钥缓存**
   - TTL: 10 分钟
   - 用途：存储 RSA 私钥，用于解密密码
   - 清理时机：登录/注册成功后手动删除，或自动过期

2. **邮箱验证码缓存**
   - TTL: 5 分钟
   - 用途：存储邮箱验证码
   - 清理时机：验证成功后删除，或自动过期

3. **手机验证码缓存**
   - TTL: 5 分钟
   - 用途：存储手机验证码
   - 清理时机：验证成功后删除，或自动过期

---

## ✅ 前端验证规则（后端参考）

### 1. 昵称格式
- **规则**：只含数字、字母和下划线，必须以字母开头
- **正则**：`/^[a-zA-Z][a-zA-Z0-9_]*$/`
- **示例**：
  - ✅ `zhangsan`
  - ✅ `zhang_san`
  - ✅ `zhang123`
  - ❌ `123zhang`（数字开头）
  - ❌ `张san`（含中文）
  - ❌ `zhang-san`（含连字符）

### 2. 密码规则
- **长度**：6-14 位
- **字符**：无任何限制（可以是中文、特殊字符等）
- **示例**：
  - ✅ `123456`
  - ✅ `password`
  - ✅ `密码123`
  - ✅ `p@ss#word!`
  - ❌ `12345`（太短）
  - ❌ `123456789012345`（太长）

### 3. 手机号规则
- **必填性**：非必填（可以为空）
- **格式**：如果不填，跳过验证；如果填写，必须符合中国大陆手机号格式
- **正则**：`/^1[3-9]\d{9}$/`
- **示例**：
  - ✅ ``（空，允许）
  - ✅ `13800138000`
  - ✅ `19912345678`
  - ❌ `12345678901`（第二位必须是 3-9）
  - ❌ `1380013800`（少一位）

### 4. 邮箱规则
- **必填性**：必填
- **格式**：标准邮箱格式
- **正则**：`/^[^\s@]+@[^\s@]+\.[^\s@]+$/`
- **唯一性**：不能与已有用户重复

---

## 🔒 安全性建议

### 1. SessionId 防重放攻击
- 前端生成的 sessionId 具有足够的随机性（UUID v4）
- 后端应记录已使用的 sessionId，防止重放
- sessionId 有效期不宜过长（建议 5-10 分钟）

### 2. 验证码防暴力破解
- 限制同一 sessionId + 邮箱/手机的验证次数（如 5 次）
- 验证失败后增加等待时间或要求重新发送验证码
- 验证成功后立即删除验证码

### 3. RSA 密钥安全
- 私钥仅存储在 Redis 中，不返回给前端
- 私钥随 sessionId 一起过期
- 使用后立即删除，减少暴露时间

### 4. 速率限制
- 对 `/auth/rsa-key` 接口进行速率限制（如同一 IP 每分钟最多 10 次）
- 对验证码发送接口进行速率限制（如同一邮箱每分钟最多 1 次）
- 对注册接口进行速率限制（如同一 IP 每小时最多 5 次）

---

## 📊 接口调用流程

### 注册流程

```
1. 前端生成 sessionId (UUID v4)
   ↓
2. POST /auth/rsa-key (携带 sessionId)
   → 后端生成 RSA 密钥对，存入 Redis
   ← 返回 publicKey
   ↓
3. POST /auth/vfcode/email (携带 sessionId + email)
   → 后端生成邮箱验证码，存入 Redis
   ← 发送邮箱
   ↓
4. POST /auth/vfcode/phone (携带 sessionId + phone，可选)
   → 后端生成手机验证码，存入 Redis
   ← 发送短信
   ↓
5. 用户填写表单，点击注册
   ↓
6. POST /auth/register (携带 sessionId + 表单数据)
   → 后端验证：
     - sessionId 是否存在
     - 邮箱验证码是否正确
     - 手机验证码是否正确（如果提供了手机号）
     - 昵称格式（可选）
     - 密码长度
     - 邮箱是否重复
   → 解密密码，创建用户
   → 删除 Redis 中的 sessionId 和验证码
   ← 返回注册结果
```

### 登录流程

```
1. 前端生成或读取 sessionId
   ↓
2. POST /auth/rsa-key (如果 Cookie 中没有公钥)
   → 后端生成 RSA 密钥对，存入 Redis
   ← 返回 publicKey
   ↓
3. 用户输入用户名和密码
   ↓
4. 前端使用 publicKey 加密密码
   ↓
5. POST /auth/login (携带 sessionId + 加密密码)
   → 后端从 Redis 获取私钥
   → 解密密码
   → 验证用户名和密码
   → 生成 JWT
   → 删除 Redis 中的 sessionId
   ← 返回 JWT 和用户信息
```

---

## 🎯 实施建议

### 第一阶段：基础改造
1. 修改 `/auth/rsa-key` 接口，接受 POST 请求和 sessionId 参数
2. 修改 Redis 缓存逻辑，使用前端传来的 sessionId 作为 key
3. 移除响应中的 sessionId 字段

### 第二阶段：验证码改造
1. 修改 `/auth/vfcode/email` 接口，接受 sessionId 参数
2. 修改 `/auth/vfcode/phone` 接口，接受 sessionId 参数
3. 移除响应中的 sessionId 字段
4. 调整验证码存储逻辑，关联 sessionId

### 第三阶段：注册改造
1. 修改 `/auth/register` 接口的验证逻辑
2. 支持手机号为非必填项
3. 调整昵称和密码验证规则
4. 使用 sessionId 关联验证码

### 第四阶段：测试和优化
1. 完整测试注册和登录流程
2. 测试 sessionId 过期场景
3. 测试并发请求场景
4. 优化 Redis 缓存策略

---

## 📞 联系方式

如有问题，请联系前端开发团队。

**文档版本**: v1.0  
**创建时间**: 2026-05-01  
**最后更新**: 2026-05-01
