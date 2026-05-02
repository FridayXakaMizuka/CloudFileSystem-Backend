# 邮箱和手机号修改接口 - 快速参考

## 🚀 快速开始

### 修改邮箱

#### 步骤 1: 发送验证码
```bash
POST /auth/vfcode/email
Content-Type: application/json

{
  "email": "newemail@example.com",
  "sessionId": "your-session-id"
}
```

#### 步骤 2: 提交修改
```bash
POST /profile/email/set
Content-Type: application/json
Authorization: Bearer YOUR_JWT_TOKEN

{
  "sessionId": "your-session-id",
  "email": "newemail@example.com",
  "verificationCode": "123456"
}
```

---

### 修改手机号

#### 步骤 1: 发送验证码
```bash
POST /auth/vfcode/phone
Content-Type: application/json

{
  "phoneNumber": "13800138000",
  "sessionId": "your-session-id"
}
```

#### 步骤 2: 提交修改
```bash
POST /profile/phone/set
Content-Type: application/json
Authorization: Bearer YOUR_JWT_TOKEN

{
  "sessionId": "your-session-id",
  "phone": "13800138000",
  "verificationCode": "123456"
}
```

---

## 📋 响应格式

### 成功响应
```json
{
  "code": 200,
  "success": true,
  "message": "邮箱修改成功"  // 或 "手机号修改成功"
}
```

### 失败响应
```json
{
  "code": 400,
  "success": false,
  "message": "错误描述信息"
}
```

---

## ⚠️ 常见错误码

| 代码 | 说明 | 解决方案 |
|------|------|----------|
| 400 | 验证码错误或已过期 | 重新获取验证码 |
| 400 | 邮箱/手机号已被使用 | 使用其他邮箱/手机号 |
| 400 | 格式不正确 | 检查邮箱/手机号格式 |
| 401 | 无效的认证令牌 | 重新登录获取新令牌 |
| 500 | 服务器内部错误 | 联系管理员 |

---

## 🔍 格式要求

### 邮箱格式
- 正则表达式: `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`
- 示例: `user@example.com`, `test.user@domain.org`

### 手机号格式（中国大陆）
- 正则表达式: `^1[3-9]\d{9}$`
- 示例: `13800138000`, `13912345678`
- 必须以 1 开头，第二位是 3-9，总共 11 位数字

---

## 💡 重要提示

### ✅ 必须做的
1. 先发送验证码，再提交修改
2. 使用同一个 sessionId
3. 携带有效的 JWT 令牌
4. 验证码 5 分钟内有效
5. 验证码一次性使用

### ❌ 不要做的
1. 不要重复使用验证码
2. 不要共用 sessionId
3. 不要在 60 秒内重复发送短信
4. 不要使用已被其他用户使用的邮箱/手机号

---

## 🔗 相关接口

### 获取安全问题列表
```bash
GET /auth/security-questions
```

### 获取个人资料
```bash
POST /profile/get_all
Authorization: Bearer YOUR_JWT_TOKEN
```

### 修改密码
```bash
POST /profile/password/set
Authorization: Bearer YOUR_JWT_TOKEN
```

### 修改昵称
```bash
POST /profile/nickname/set
Authorization: Bearer YOUR_JWT_TOKEN
```

---

## 📖 完整文档

- **详细文档**: `EMAIL_PHONE_CHANGE_API.md`
- **实现总结**: `IMPLEMENTATION_SUMMARY.md`
- **设计指南**: `EMAIL_PHONE_SET_API_GUIDE.md`

---

**最后更新**: 2026-05-02  
**版本**: v1.0
