# 重置密码验证接口文档（第二步到第四步）

## 概述

本文档描述重置密码功能中第二步（选择验证方式）到第四步（设置新密码）的后端接口规范。

---

## 核心设计原则

### 1. SessionId 管理策略

**关键设计**：第三步开始时重新生成 SessionId

**原因**：
- 防止从第四步回退到第三步时，使用旧的 SessionId 导致验证码混乱
- 防止从第二步重新进入第三步时，SessionId 过期或冲突
- 确保每次验证流程都有独立的会话上下文

**实现方案**：
```javascript
// 第三步初始化时
const newSessionId = createNewSessionId()  // 生成新的 UUID
// 有效期：5分钟（300秒）
// 每次成功请求后重置为 295秒
```

### 2. 验证码发送复用

邮箱和手机验证码发送**复用现有接口**：
- 邮箱：`POST /auth/vfcode/email`
- 手机：`POST /auth/vfcode/phone`

**优势**：
- 减少后端接口数量
- 统一的验证码管理逻辑
- 前端已有完整的倒计时和重试机制

### 3. RSA 加密要求

**需要加密的数据**：
- ✅ 安全问题答案（securityAnswer）
- ✅ 新密码（newPassword）
- ✅ 确认密码（confirmPassword）

**不需要加密的数据**：
- ❌ 验证码（verificationCode）- 明文传输
- ❌ SessionId - 明文传输

---

## 接口详细设计

### 接口 1：邮箱验证码验证

#### 基本信息

- **路径**: `POST /auth/reset_password/verify/email`
- **描述**: 验证邮箱验证码是否正确
- **认证**: 不需要（公开接口）
- **前置条件**: 已在第三步发送过邮箱验证码

#### 请求参数

```json
{
  "sessionId": "string",              // 第三步生成的新 SessionId
  "email": "string",                  // 用户邮箱（明文，从第一步获取）
  "verificationCode": "string"        // 6位验证码（明文）
}
```

**字段说明**:
- `sessionId`: 第三步开始时生成的新 SessionId（UUID v4）
- `email`: 用户的邮箱地址，从第一步查找用户时获取
- `verificationCode`: 用户输入的6位数字验证码

#### 响应格式

##### 成功响应 (HTTP 200)

```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "resetToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."  // JWT格式的临时令牌
}
```

**字段说明**:
- `resetToken`: 临时令牌，用于第四步重置密码
  - 有效期：**10分钟**
  - 包含信息：用户ID、验证方式、时间戳
  - 只能使用一次（使用后失效）

##### 失败响应

**验证码错误** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "验证码错误或已过期"
}
```

**验证码已过期** (HTTP 410):
```json
{
  "code": 410,
  "success": false,
  "message": "验证码已过期，请重新发送"
}
```

**尝试次数过多** (HTTP 429):
```json
{
  "code": 429,
  "success": false,
  "message": "验证失败次数过多，请稍后再试"
}
```

#### 业务逻辑

1. **验证 SessionId**
   - 检查 sessionId 是否存在且有效
   - 从 Redis/内存中获取对应的验证上下文

2. **查找验证码记录**
   - 根据 email 查找最近发送的验证码
   - 检查验证码是否过期（通常5分钟）

3. **验证验证码**
   - 比较用户输入的验证码与存储的验证码
   - 不区分大小写

4. **检查尝试次数**
   - 记录验证失败次数
   - 超过限制（如5次）则锁定该邮箱

5. **生成 resetToken**
   - 创建 JWT 令牌，包含：
     ```json
     {
       "userId": 10001,
       "verifiedBy": "email",
       "email": "user@example.com",
       "iat": 1714636800,
       "exp": 1714637400  // 10分钟后过期
     }
     ```
   - 签名密钥使用服务器端的 secret

6. **清理验证码**
   - 验证成功后删除存储的验证码
   - 防止重放攻击

#### 示例代码（Node.js/Express）

```javascript
const jwt = require('jsonwebtoken');
const RESET_TOKEN_SECRET = process.env.RESET_TOKEN_SECRET;

router.post('/auth/reset_password/verify/email', async (req, res) => {
  try {
    const { sessionId, email, verificationCode } = req.body;
    
    // 1. 验证 sessionId
    if (!sessionId || !sessionStore.has(sessionId)) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '无效的会话ID'
      });
    }
    
    // 2. 查找验证码记录
    const codeRecord = await VerificationCode.findOne({
      where: {
        email: email,
        type: 'reset_password',
        used: false
      },
      order: [['created_at', 'DESC']]
    });
    
    if (!codeRecord) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '未找到验证码记录，请重新发送'
      });
    }
    
    // 3. 检查是否过期
    const now = Date.now();
    const createdAt = new Date(codeRecord.created_at).getTime();
    if (now - createdAt > 5 * 60 * 1000) {  // 5分钟
      return res.status(410).json({
        code: 410,
        success: false,
        message: '验证码已过期，请重新发送'
      });
    }
    
    // 4. 检查尝试次数
    if (codeRecord.attempt_count >= 5) {
      return res.status(429).json({
        code: 429,
        success: false,
        message: '验证失败次数过多，请稍后再试'
      });
    }
    
    // 5. 验证验证码
    if (codeRecord.code !== verificationCode.toUpperCase()) {
      // 增加尝试次数
      await codeRecord.increment('attempt_count');
      
      return res.status(400).json({
        code: 400,
        success: false,
        message: '验证码错误'
      });
    }
    
    // 6. 查找用户
    const user = await User.findByEmail(email);
    if (!user) {
      return res.status(404).json({
        code: 404,
        success: false,
        message: '用户不存在'
      });
    }
    
    // 7. 生成 resetToken
    const resetToken = jwt.sign(
      {
        userId: user.id,
        verifiedBy: 'email',
        email: user.email,
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 600  // 10分钟
      },
      RESET_TOKEN_SECRET
    );
    
    // 8. 标记验证码已使用
    await codeRecord.update({ used: true });
    
    res.json({
      code: 200,
      success: true,
      message: '验证成功',
      resetToken: resetToken
    });
    
  } catch (error) {
    console.error('邮箱验证失败:', error);
    res.status(500).json({
      code: 500,
      success: false,
      message: '服务器内部错误'
    });
  }
});
```

---

### 接口 2：手机验证码验证

#### 基本信息

- **路径**: `POST /auth/reset_password/verify/phone`
- **描述**: 验证手机验证码是否正确
- **认证**: 不需要（公开接口）
- **前置条件**: 已在第三步发送过手机验证码

#### 请求参数

```json
{
  "sessionId": "string",              // 第三步生成的新 SessionId
  "phone": "string",                  // 用户手机号（明文，从第一步获取）
  "verificationCode": "string"        // 6位验证码（明文）
}
```

**字段说明**:
- `sessionId`: 第三步开始时生成的新 SessionId
- `phone`: 用户的手机号，从第一步查找用户时获取
- `verificationCode`: 用户输入的6位数字验证码

#### 响应格式

与邮箱验证相同：

**成功**:
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "resetToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**失败**: 同邮箱验证

#### 业务逻辑

与邮箱验证类似，区别在于：
- 根据 phone 查找验证码记录
- resetToken 中包含 phone 而非 email

**resetToken payload**:
```json
{
  "userId": 10001,
  "verifiedBy": "phone",
  "phone": "13812345678",
  "iat": 1714636800,
  "exp": 1714637400
}
```

---

### 接口 3：密保问题答案验证

#### 基本信息

- **路径**: `POST /auth/reset_password/verify/security_answer`
- **描述**: 验证密保问题答案是否正确
- **认证**: 不需要（公开接口）
- **前置条件**: 用户在第一步查找时返回了密保问题

#### 请求参数

```json
{
  "sessionId": "string",                    // 第三步生成的新 SessionId
  "encryptedSecurityAnswer": "string"       // RSA加密的密保答案
}
```

**字段说明**:
- `sessionId`: 第三步开始时生成的新 SessionId
- `encryptedSecurityAnswer`: 使用 RSA 公钥加密的密保问题答案

**注意**：
- 密保答案需要 RSA 加密传输
- 前端需要在第三步重新获取 RSA 公钥
- 加密方法：`encryptPassword(answer, publicKey)`

#### 响应格式

**成功响应** (HTTP 200):
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "resetToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**失败响应**:

**答案错误** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "密保答案错误"
}
```

**尝试次数过多** (HTTP 429):
```json
{
  "code": 429,
  "success": false,
  "message": "验证失败次数过多，请稍后再试"
}
```

#### 业务逻辑

1. **验证 SessionId**
   - 检查 sessionId 是否存在且有效

2. **解密答案**
   - 从 SessionId 关联的上下文中获取 RSA 私钥
   - 解密 `encryptedSecurityAnswer`

3. **查找用户**
   - 从 SessionId 上下文中获取用户ID（第一步时保存）
   - 查询用户的密保问题ID和答案哈希

4. **验证答案**
   - 对用户输入的答案进行哈希处理
   - 与数据库中存储的答案哈希比较
   - 建议使用 bcrypt 或 SHA-256

5. **检查尝试次数**
   - 记录验证失败次数
   - 超过限制（如5次）则锁定该账户的密保验证

6. **生成 resetToken**
   - 创建 JWT 令牌
   - payload 包含 userId 和 verifiedBy: "security"

7. **清理敏感数据**
   - 清除 SessionId 中的 RSA 私钥
   - 防止后续重用

#### 示例代码

```javascript
const crypto = require('crypto');
const bcrypt = require('bcrypt');

router.post('/auth/reset_password/verify/security_answer', async (req, res) => {
  try {
    const { sessionId, encryptedSecurityAnswer } = req.body;
    
    // 1. 验证 sessionId
    const sessionContext = sessionStore.get(sessionId);
    if (!sessionContext) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '无效的会话ID'
      });
    }
    
    // 2. 解密答案
    let securityAnswer;
    try {
      securityAnswer = crypto.privateDecrypt(
        {
          key: sessionContext.privateKey,
          padding: crypto.constants.RSA_PKCS1_OAEP_PADDING
        },
        Buffer.from(encryptedSecurityAnswer, 'base64')
      ).toString('utf-8');
    } catch (error) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '解密失败'
      });
    }
    
    // 3. 查找用户
    const userId = sessionContext.userId;  // 第一步时保存
    const user = await User.findById(userId);
    
    if (!user || !user.security_question_id) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '用户未设置密保问题'
      });
    }
    
    // 4. 检查尝试次数
    if (user.security_attempt_count >= 5) {
      return res.status(429).json({
        code: 429,
        success: false,
        message: '验证失败次数过多，请稍后再试'
      });
    }
    
    // 5. 验证答案
    const isMatch = await bcrypt.compare(securityAnswer, user.security_answer_hash);
    
    if (!isMatch) {
      // 增加尝试次数
      await user.increment('security_attempt_count');
      
      return res.status(400).json({
        code: 400,
        success: false,
        message: '密保答案错误'
      });
    }
    
    // 6. 生成 resetToken
    const resetToken = jwt.sign(
      {
        userId: user.id,
        verifiedBy: 'security',
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 600  // 10分钟
      },
      RESET_TOKEN_SECRET
    );
    
    // 7. 重置尝试次数
    await user.update({ security_attempt_count: 0 });
    
    res.json({
      code: 200,
      success: true,
      message: '验证成功',
      resetToken: resetToken
    });
    
  } catch (error) {
    console.error('密保验证失败:', error);
    res.status(500).json({
      code: 500,
      success: false,
      message: '服务器内部错误'
    });
  }
});
```

---

## 前端调用流程详解

### 第二步 → 第三步

```javascript
// 用户点击验证方式按钮
const selectVerifyMethod = async (method) => {
  logger.info('选择验证方式:', method)
  
  // 1. 清除旧的 SessionId 和公钥
  clearSessionId()
  deleteCookie('rsaPublicKey')
  
  // 2. 生成新的 SessionId（有效期5分钟）
  const newSessionId = createNewSessionId()
  sessionId.value = newSessionId
  
  // 3. 获取新的 RSA 公钥（仅密保需要，但统一获取）
  const keyData = await fetchRSAKey()
  rsaPublicKey.value = keyData.publicKey
  
  // 4. 根据验证方式显示不同界面
  currentStep.value = 3
  verifyMethod.value = method
  
  // 5. 如果是邮箱或手机，自动发送验证码
  if (method === 'email' || method === 'phone') {
    await sendVerificationCode()
  }
}
```

### 第三步界面

**邮箱验证**:
```vue
<div class="verify-section">
  <h3>邮箱验证</h3>
  <p>验证码已发送至 {{ maskEmail(userInfo.email) }}</p>
  
  <div class="form-group">
    <label>验证码</label>
    <input 
      v-model="verificationCode" 
      maxlength="6"
      placeholder="请输入6位验证码"
    />
    <button 
      @click="sendVerificationCode"
      :disabled="countdownTimer.isRunning()"
    >
      {{ countdownTimer.isRunning() ? `${remaining}s` : '重新发送' }}
    </button>
  </div>
  
  <div class="button-group">
    <button @click="handlePrevStep">上一步</button>
    <button @click="handleVerifyEmail" :disabled="!verificationCode">
      下一步
    </button>
  </div>
</div>
```

**手机验证**: 类似邮箱，调用 `/auth/vfcode/phone`

**密保验证**:
```vue
<div class="verify-section">
  <h3>密保问题验证</h3>
  <p>{{ userInfo.securityQuestionText }}</p>
  
  <div class="form-group">
    <label>答案</label>
    <input 
      v-model="securityAnswer" 
      type="text"
      placeholder="请输入答案"
    />
  </div>
  
  <div class="button-group">
    <button @click="handlePrevStep">上一步</button>
    <button @click="handleVerifySecurity" :disabled="!securityAnswer">
      下一步
    </button>
  </div>
</div>
```

### 第三步 → 第四步

**邮箱验证**:
```javascript
const handleVerifyEmail = async () => {
  try {
    const response = await fetch(AUTH_API.VERIFY_EMAIL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: sessionId.value,
        email: userInfo.value.email,
        verificationCode: verificationCode.value
      })
    })
    
    const result = await response.json()
    
    if (result.success) {
      // 保存 resetToken
      resetToken.value = result.resetToken
      
      // 跳转到第四步
      currentStep.value = 4
    } else {
      showError(result.message)
    }
  } catch (error) {
    showError('网络错误，请稍后重试')
  }
}
```

**手机验证**: 类似，调用 `/auth/reset_password/verify/phone`

**密保验证**:
```javascript
const handleVerifySecurity = async () => {
  try {
    // 1. 加密答案
    const encryptedAnswer = encryptPassword(
      securityAnswer.value, 
      rsaPublicKey.value
    )
    
    // 2. 发送请求
    const response = await fetch(AUTH_API.VERIFY_SECURITY, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: sessionId.value,
        encryptedSecurityAnswer: encryptedAnswer
      })
    })
    
    const result = await response.json()
    
    if (result.success) {
      resetToken.value = result.resetToken
      currentStep.value = 4
    } else {
      showError(result.message)
    }
  } catch (error) {
    showError('网络错误，请稍后重试')
  }
}
```

---

## API 配置更新

需要在 `src/config/api.js` 中添加：

```javascript
export const AUTH_API = {
  // ... 现有接口
  
  // 重置密码 - 查找用户
  RESET_PASSWORD_FIND_USER: `${BASE_API_URL}/auth/reset_password/find_user`,
  
  // 重置密码 - 邮箱验证
  RESET_PASSWORD_VERIFY_EMAIL: `${BASE_API_URL}/auth/reset_password/verify/email`,
  
  // 重置密码 - 手机验证
  RESET_PASSWORD_VERIFY_PHONE: `${BASE_API_URL}/auth/reset_password/verify/phone`,
  
  // 重置密码 - 密保验证
  RESET_PASSWORD_VERIFY_SECURITY: `${BASE_API_URL}/auth/reset_password/verify/security_answer`,
  
  // 重置密码 - 设置新密码
  RESET_PASSWORD_SET_NEW: `${BASE_API_URL}/auth/reset_password/set_new_password`
}
```

---

## 安全注意事项

### 1. resetToken 安全

- **有效期短**：仅10分钟
- **一次性使用**：使用后立即可失效
- **绑定验证方式**：token 中包含 verifiedBy 字段
- **JWT 签名**：使用服务器端密钥签名，防止篡改

### 2. 防暴力破解

- **验证码限制**：最多尝试5次
- **IP限流**：同一IP每分钟最多请求3次
- **账户锁定**：连续失败10次锁定30分钟

### 3. SessionId 隔离

- **独立生成**：第三步使用新的 SessionId
- **上下文分离**：不与登录/注册的 SessionId 混用
- **及时清理**：验证成功后清除敏感数据

### 4. 数据传输安全

- **HTTPS**：所有接口必须使用 HTTPS
- **RSA加密**：敏感数据（答案、密码）必须加密
- **验证码明文**：验证码可以明文传输（短期有效）

---

## 数据库设计补充

### 验证码记录表 (verification_codes)

```sql
CREATE TABLE verification_codes (
  id INT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(100),
  phone VARCHAR(20),
  code VARCHAR(10) NOT NULL,          -- 6位验证码
  type ENUM('register', 'reset_password', 'change_email', 'change_phone') NOT NULL,
  used BOOLEAN DEFAULT FALSE,
  attempt_count INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  
  INDEX idx_email (email),
  INDEX idx_phone (phone),
  INDEX idx_expires (expires_at)
);
```

### 用户表补充字段

```sql
ALTER TABLE users ADD COLUMN security_attempt_count INT DEFAULT 0;
ALTER TABLE users ADD COLUMN last_security_attempt_at TIMESTAMP NULL;
```

---

## 测试用例

### 测试场景 1：邮箱验证成功

**请求**:
```bash
curl -X POST http://localhost:8835/auth/reset_password/verify/email \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "new-uuid-here",
    "email": "test@example.com",
    "verificationCode": "123456"
  }'
```

**预期响应**:
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "resetToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

### 测试场景 2：密保验证成功

**请求**:
```bash
curl -X POST http://localhost:8835/auth/reset_password/verify/security_answer \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "new-uuid-here",
    "encryptedSecurityAnswer": "BASE64_ENCRYPTED_ANSWER"
  }'
```

**预期响应**: 同上

### 测试场景 3：验证码错误

**预期响应**:
```json
{
  "code": 400,
  "success": false,
  "message": "验证码错误"
}
```

---

## 更新日志

| 版本 | 日期 | 更新内容 |
|------|------|---------|
| 1.0 | 2026-05-02 | 初始版本，定义三个验证接口 |

---

**文档维护者**: Frontend Team  
**最后更新**: 2026-05-02
