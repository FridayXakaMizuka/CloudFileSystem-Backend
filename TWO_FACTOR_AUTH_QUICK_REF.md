# 二次验证 - 快速参考

## 🎯 核心流程

```
登录 → 检查 requiresTwoFactor → 
  false: 直接登录成功
  true: 跳转到 /two-factor-auth → 选择验证方式 → 验证 → 登录成功
```

---

## 🔐 登录接口响应

### 需要二次验证

```json
{
  "success": true,
  "code": 200,
  "requiresTwoFactor": true,
  "userId": 123,
  "email": "user@example.com",
  "phone": "13800138000",
  "securityQuestion": "你的出生地是哪里？"
}
```

### 直接登录成功

```json
{
  "success": true,
  "code": 200,
  "requiresTwoFactor": false,
  "token": "eyJhbGc...",
  "userId": 123,
  "userType": "USER",
  "homeDirectory": "/home/user"
}
```

---

## 🔑 验证接口

### 邮箱验证
```http
POST /auth/verify/email
Content-Type: application/json

{
  "sessionId": "xxx",
  "userId": 123,
  "verificationCode": "123456"
}
```

### 手机验证
```http
POST /auth/verify/phone
Content-Type: application/json

{
  "sessionId": "xxx",
  "userId": 123,
  "verificationCode": "123456"
}
```

### 密保问题验证
```http
POST /auth/verify/security_answer
Content-Type: application/json

{
  "sessionId": "xxx",
  "userId": 123,
  "encryptedAnswer": "加密后的答案"
}
```

### 验证成功响应
```json
{
  "success": true,
  "code": 200,
  "token": "eyJhbGc...",
  "userId": 123,
  "userType": "USER",
  "homeDirectory": "/home/user"
}
```

---

## 📱 前端页面

**路由**: `/two-factor-auth`  
**文件**: `src/views/TwoFactorAuthView.vue`

### 两步流程

**第一步**: 选择验证方式
- 显示用户ID
- 显示密保问题（如果有）
- 三个按钮：邮箱、手机、密保问题

**第二步**: 验证身份
- 邮箱/手机：输入验证码 + 重新发送按钮
- 密保问题：输入答案
- 验证按钮

---

## 💻 前端代码示例

### 登录时处理二次验证

```javascript
const result = await response.json()

if (result.requiresTwoFactor === true) {
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
  return
}

// 直接登录成功
saveAuthInfo(result.token, userInfo)
router.push('/')
```

### 验证成功后

```javascript
if (result.success) {
  saveAuthInfo(result.token, {
    userId: userInfo.value.userId,
    userType: result.userType,
    homeDirectory: result.homeDirectory
  })
  
  await fetchAllUserInfo()
  router.push('/')
}
```

---

## ⚙️ 配置清单

### API 配置（已完成）

```javascript
// src/config/api.js
export const AUTH_API = {
  VERIFY_EMAIL: `${BASE_API_URL}/auth/verify/email`,
  VERIFY_PHONE: `${BASE_API_URL}/auth/verify/phone`,
  VERIFY_SECURITY_ANSWER: `${BASE_API_URL}/auth/verify/security_answer`
}
```

### 路由配置（已完成）

```javascript
// src/router/index.js
{
  path: '/two-factor-auth',
  name: 'twoFactorAuth',
  component: TwoFactorAuthView
}
```

---

## 🔍 触发条件建议

1. **新设备登录** - 检测到未记录的设备指纹
2. **异地登录** - IP 地址与常用地点不同
3. **用户设置** - 用户启用了二次验证
4. **高风险操作** - 风险评估为高
5. **定期验证** - 距离上次验证超过30天

---

## 📊 数据库表

### verification_codes
```sql
CREATE TABLE verification_codes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    code VARCHAR(10) NOT NULL,
    expires_at DATETIME NOT NULL
);
```

### security_questions
```sql
CREATE TABLE security_questions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    question_text VARCHAR(500) NOT NULL,
    answer VARCHAR(500) NOT NULL
);
```

---

## ⚠️ 安全要点

- ✅ sessionId 有效期 5 分钟
- ✅ 验证码长度 6 位
- ✅ 最多尝试 3 次
- ✅ 发送间隔 60 秒
- ✅ 答案 RSA 加密传输
- ✅ 记录设备信息
- ✅ 验证成功后清除 sessionId

---

## 📝 后端待实现

- [ ] `/auth/login` - 添加 requiresTwoFactor 判断逻辑
- [ ] `/auth/verify/email` - 邮箱验证接口
- [ ] `/auth/verify/phone` - 手机验证接口
- [ ] `/auth/verify/security_answer` - 密保问题验证接口
- [ ] 设备指纹记录和管理
- [ ] 二次验证触发策略
- [ ] 验证码生成和存储
- [ ] 密保问题管理

---

**详细文档**: [TWO_FACTOR_AUTH_BACKEND_GUIDE.md](./TWO_FACTOR_AUTH_BACKEND_GUIDE.md)  
**更新时间**: 2026-05-02
