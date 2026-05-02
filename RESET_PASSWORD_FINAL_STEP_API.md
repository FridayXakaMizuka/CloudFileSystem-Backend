# 重置密码最后一步接口文档

## 概述

本文档描述重置密码功能中最后一步（第四步：设置新密码）的后端接口规范。

---

## 核心设计原则

### 1. JWT 令牌管理

**关键设计**：resetToken 在以下情况立即失效

- ✅ 页面刷新
- ✅ 从第四步返回第三步
- ✅ 从第三步返回第二步
- ✅ 重置密码成功后
- ✅ 重置密码失败后

**原因**：
- 防止令牌被重放攻击
- 确保每次重置密码流程都是全新的
- 提高安全性

**实现方案**：
```javascript
// 前端在以下情况清除 resetToken
resetToken.value = ''

// 后端验证令牌后立即标记为已使用
await markTokenAsUsed(resetToken)
```

### 2. SessionId 重新生成

**关键设计**：第四步开始时重新生成 SessionId

**原因**：
- 与第三步的 SessionId 隔离
- 防止会话冲突
- 确保每次请求都有独立的上下文

### 3. RSA 加密要求

**需要加密的数据**：
- ✅ 新密码（encryptedNewPassword）

**不需要加密的数据**：
- ❌ SessionId - 明文传输
- ❌ JWT 令牌 - 放在 Authorization 头

---

## 接口详细设计

### 接口：重置密码

#### 基本信息

- **路径**: `POST /auth/reset_password/reset`
- **描述**: 使用 resetToken 重置用户密码
- **认证**: 使用 resetToken（Bearer Token）
- **前置条件**: 
  - 用户已通过邮箱/手机/密保验证
  - 拥有有效的 resetToken（10分钟有效期）
  - resetToken 未被使用过

#### 请求头

```http
POST /auth/reset_password/reset HTTP/1.1
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

**字段说明**:
- `Authorization`: Bearer 格式的 JWT 令牌（resetToken）

#### 请求参数

```json
{
  "sessionId": "string",                    // 第四步生成的新 SessionId
  "encryptedNewPassword": "string"          // RSA加密的新密码
}
```

**字段说明**:
- `sessionId`: 第四步开始时生成的新 SessionId（UUID v4）
- `encryptedNewPassword`: 使用 RSA 公钥加密的新密码
  - 密码长度：6-14位
  - 可以包含任意字符
  - 加密方法：`encryptPassword(password, publicKey)`

#### 响应格式

##### 成功响应 (HTTP 200)

```json
{
  "code": 200,
  "success": true,
  "message": "密码重置成功"
}
```

##### 失败响应

**令牌无效或过期** (HTTP 401):
```json
{
  "code": 401,
  "success": false,
  "message": "验证令牌无效或已过期"
}
```

**令牌已被使用** (HTTP 410):
```json
{
  "code": 410,
  "success": false,
  "message": "验证令牌已被使用，请重新验证"
}
```

**密码格式错误** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "密码长度必须为6-14位"
}
```

**SessionId 无效** (HTTP 400):
```json
{
  "code": 400,
  "success": false,
  "message": "无效的会话ID"
}
```

**服务器错误** (HTTP 500):
```json
{
  "code": 500,
  "success": false,
  "message": "服务器内部错误"
}
```

#### 业务逻辑

1. **验证 JWT 令牌**
   - 从 Authorization 头提取 token
   - 验证签名是否正确
   - 检查是否过期（10分钟）
   - 检查是否已被使用（一次性令牌）

2. **解析令牌获取用户信息**
   ```json
   {
     "userId": 10001,
     "verifiedBy": "email",  // 或 "phone" / "security"
     "iat": 1714636800,
     "exp": 1714637400
   }
   ```

3. **验证 SessionId**
   - 检查 sessionId 是否存在且有效
   - 从 Redis/内存中获取对应的验证上下文

4. **解密密码**
   - 从 SessionId 关联的上下文中获取 RSA 私钥
   - 解密 `encryptedNewPassword`

5. **验证密码格式**
   - 长度：6-14位
   - 可以包含任意字符

6. **更新密码**
   - 对密码进行哈希处理（bcrypt）
   - 更新数据库中的密码字段
   - 记录更新时间

7. **标记令牌已使用**
   - 将 resetToken 标记为已使用
   - 防止重放攻击

8. **清理敏感数据**
   - 清除 SessionId 中的 RSA 私钥
   - 清除验证码记录
   - 清除临时数据

9. **返回成功响应**

#### 示例代码（Node.js/Express）

```javascript
const jwt = require('jsonwebtoken');
const bcrypt = require('bcrypt');
const crypto = require('crypto');

const RESET_TOKEN_SECRET = process.env.RESET_TOKEN_SECRET;
const SALT_ROUNDS = 10;

router.post('/auth/reset_password/reset', async (req, res) => {
  try {
    // 1. 提取 JWT 令牌
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({
        code: 401,
        success: false,
        message: '未提供验证令牌'
      });
    }
    
    const resetToken = authHeader.substring(7); // 移除 "Bearer " 前缀
    
    // 2. 验证 JWT 令牌
    let decoded;
    try {
      decoded = jwt.verify(resetToken, RESET_TOKEN_SECRET);
    } catch (error) {
      if (error.name === 'TokenExpiredError') {
        return res.status(401).json({
          code: 401,
          success: false,
          message: '验证令牌已过期'
        });
      }
      return res.status(401).json({
        code: 401,
        success: false,
        message: '验证令牌无效'
      });
    }
    
    // 3. 检查令牌是否已被使用
    const isTokenUsed = await checkIfTokenUsed(resetToken);
    if (isTokenUsed) {
      return res.status(410).json({
        code: 410,
        success: false,
        message: '验证令牌已被使用，请重新验证'
      });
    }
    
    // 4. 获取请求参数
    const { sessionId, encryptedNewPassword } = req.body;
    
    if (!sessionId || !encryptedNewPassword) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '缺少必要参数'
      });
    }
    
    // 5. 验证 SessionId
    const sessionContext = sessionStore.get(sessionId);
    if (!sessionContext) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '无效的会话ID'
      });
    }
    
    // 6. 解密密码
    let newPassword;
    try {
      newPassword = crypto.privateDecrypt(
        {
          key: sessionContext.privateKey,
          padding: crypto.constants.RSA_PKCS1_OAEP_PADDING
        },
        Buffer.from(encryptedNewPassword, 'base64')
      ).toString('utf-8');
    } catch (error) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '密码解密失败'
      });
    }
    
    // 7. 验证密码格式
    if (newPassword.length < 6 || newPassword.length > 14) {
      return res.status(400).json({
        code: 400,
        success: false,
        message: '密码长度必须为6-14位'
      });
    }
    
    // 8. 查找用户
    const userId = decoded.userId;
    const user = await User.findById(userId);
    
    if (!user) {
      return res.status(404).json({
        code: 404,
        success: false,
        message: '用户不存在'
      });
    }
    
    // 9. 哈希密码
    const hashedPassword = await bcrypt.hash(newPassword, SALT_ROUNDS);
    
    // 10. 更新密码
    await user.update({
      password_hash: hashedPassword,
      updated_at: new Date()
    });
    
    // 11. 标记令牌已使用
    await markTokenAsUsed(resetToken);
    
    // 12. 清理敏感数据
    sessionStore.delete(sessionId);
    
    logger.info(`用户 ${userId} 密码重置成功`);
    
    res.json({
      code: 200,
      success: true,
      message: '密码重置成功'
    });
    
  } catch (error) {
    console.error('重置密码失败:', error);
    res.status(500).json({
      code: 500,
      success: false,
      message: '服务器内部错误'
    });
  }
});

/**
 * 检查令牌是否已被使用
 */
async function checkIfTokenUsed(token) {
  // 可以从 Redis 或数据库中查询
  // 例如：Redis key: "used_reset_token:{token}"
  const used = await redis.get(`used_reset_token:${token}`);
  return used === '1';
}

/**
 * 标记令牌已使用
 */
async function markTokenAsUsed(token) {
  // 设置过期时间为 24 小时（防止无限增长）
  await redis.setex(`used_reset_token:${token}`, 86400, '1');
}
```

---

## 前端调用流程详解

### 第四步初始化

```javascript
// 当用户从第三步进入第四步时
const handleVerify = async () => {
  // ... 验证成功后
  
  if (result.success) {
    // 保存 resetToken
    resetToken.value = result.resetToken
    logger.info('验证成功，获取到 resetToken')
    
    // 跳转到第四步
    currentStep.value = 4
    showSuccess('验证成功')
  }
}
```

### 第四步界面

```vue
<!-- 第四步：设置新密码 -->
<div v-else-if="currentStep === 4" class="reset-form step4">
  <h2 class="step-title">设置新密码</h2>
  
  <div class="form-group">
    <label for="new-password">
      <span class="label-icon">🔒</span>
      新密码
    </label>
    <input
      type="password"
      id="new-password"
      v-model="resetForm.newPassword"
      placeholder="请输入新密码（6-14位）"
      minlength="6"
      maxlength="14"
    />
    <p v-if="passwordError" class="error-message">{{ passwordError }}</p>
  </div>
  
  <div class="form-group">
    <label for="confirm-password">
      <span class="label-icon">🔒</span>
      确认密码
    </label>
    <input
      type="password"
      id="confirm-password"
      v-model="resetForm.confirmPassword"
      placeholder="请再次输入新密码"
    />
    <p v-if="confirmPasswordError" class="error-message">{{ confirmPasswordError }}</p>
  </div>
  
  <div class="button-group button-group-vertical">
    <button class="btn btn-prev" @click="handlePrevStepFromReset">
      上一步
    </button>
    <button 
      class="btn btn-next" 
      @click="handleResetPassword"
      :disabled="!canResetPassword || isLoading"
    >
      {{ isLoading ? '重置中...' : '确认重置' }}
    </button>
  </div>
</div>
```

### 点击"确认重置"时的逻辑

```javascript
const handleResetPassword = async () => {
  if (!canResetPassword.value) {
    showError('请检查密码格式')
    return
  }
  
  // 检查 resetToken 是否存在
  if (!resetToken.value) {
    showError('验证令牌已失效，请重新验证')
    currentStep.value = 2
    resetToken.value = ''
    return
  }
  
  isLoading.value = true
  
  try {
    logger.info('开始重置密码...')
    
    // 1. 生成新的 SessionId
    clearSessionId()
    deleteCookie('rsaPublicKey')
    sessionId.value = createNewSessionId()
    
    // 2. 获取新的 RSA 公钥
    const keyData = await fetchRSAKey()
    rsaPublicKey.value = keyData.publicKey
    sessionId.value = keyData.sessionId
    
    // 3. 加密新密码
    const encryptedPassword = encryptPassword(
      resetForm.value.newPassword,
      rsaPublicKey.value
    )
    
    // 4. 发送重置密码请求
    const response = await fetch(AUTH_API.RESET_PASSWORD_RESET, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${resetToken.value}`  // 携带 JWT 令牌
      },
      body: JSON.stringify({
        sessionId: sessionId.value,
        encryptedNewPassword: encryptedPassword
      })
    })
    
    const result = await response.json()
    
    // 5. 处理响应
    if (response.ok && result.success === true) {
      showSuccess('密码重置成功！即将跳转到登录页面...')
      
      // 清除所有敏感数据
      clearSessionId()
      deleteCookie('rsaPublicKey')
      resetToken.value = ''
      rsaPublicKey.value = ''
      sessionId.value = ''
      
      // 延迟 2 秒后跳转到登录页面
      setTimeout(() => {
        router.push('/login')
      }, 2000)
    } else {
      showError(result.message || '密码重置失败，请稍后重试')
      
      // 重置失败，清除 resetToken
      resetToken.value = ''
    }
  } catch (error) {
    logger.error('重置密码失败:', error)
    showError('网络错误，请稍后重试')
    
    // 出错时也清除 resetToken
    resetToken.value = ''
  } finally {
    isLoading.value = false
  }
}
```

### 回退时清除 resetToken

```javascript
/**
 * 从第四步返回第三步
 */
const handlePrevStepFromReset = () => {
  // 清空密码相关数据
  resetForm.value.newPassword = ''
  resetForm.value.confirmPassword = ''
  
  // 清除 resetToken（回退时失效）
  resetToken.value = ''
  
  // 清除 SessionId 和公钥
  clearSessionId()
  deleteCookie('rsaPublicKey')
  rsaPublicKey.value = ''
  sessionId.value = ''
  
  // 返回第三步
  currentStep.value = 3
  
  logger.info('从第四步返回第三步，resetToken已失效')
}
```

### 页面刷新时清除 resetToken

```javascript
onMounted(() => {
  getRandomImage()
  
  // 页面加载时清除可能存在的旧 resetToken
  // （因为刷新页面后 JWT 令牌应该失效）
  if (resetToken.value) {
    logger.info('检测到旧的 resetToken，已清除')
    resetToken.value = ''
  }
})
```

---

## API 配置

在 `src/config/api.js` 中添加：

```javascript
export const AUTH_API = {
  // ... 现有接口
  
  // 重置密码 - 重置密码（最后一步）
  RESET_PASSWORD_RESET: `${BASE_API_URL}/auth/reset_password/reset`
}
```

---

## 安全注意事项

### 1. resetToken 一次性使用

- **使用后立即可失效**：防止重放攻击
- **存储已使用的令牌**：Redis 或数据库
- **设置过期时间**：24小时后自动清理

### 2. JWT 令牌验证

- **验证签名**：确保令牌未被篡改
- **检查过期时间**：10分钟有效期
- **验证用户ID**：确保令牌属于当前用户

### 3. 密码安全

- **RSA 加密传输**：防止中间人攻击
- **bcrypt 哈希存储**：防止数据库泄露
- **盐值随机**：每个密码使用不同的盐

### 4. SessionId 隔离

- **独立生成**：第四步使用新的 SessionId
- **及时清理**：重置成功后清除所有敏感数据
- **防止冲突**：不与前面的步骤混用

### 5. 防暴力破解

- **IP限流**：同一IP每分钟最多请求3次
- **账户锁定**：连续失败10次锁定30分钟
- **日志记录**：记录所有重置密码尝试

---

## 数据库设计补充

### 已使用的令牌表 (used_reset_tokens)

```sql
CREATE TABLE used_reset_tokens (
  id INT PRIMARY KEY AUTO_INCREMENT,
  token_hash VARCHAR(64) NOT NULL,      -- token 的哈希值（不存储明文）
  user_id INT NOT NULL,
  used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,        -- 24小时后过期
  
  INDEX idx_token_hash (token_hash),
  INDEX idx_expires (expires_at)
);
```

或者使用 Redis：

```redis
# Key: used_reset_token:{token}
# Value: 1
# TTL: 86400 (24小时)

SET used_reset_token:eyJhbGci... 1 EX 86400
```

---

## 测试用例

### 测试场景 1：重置密码成功

**请求**:
```bash
curl -X POST http://localhost:8835/auth/reset_password/reset \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -d '{
    "sessionId": "new-uuid-here",
    "encryptedNewPassword": "BASE64_ENCRYPTED_PASSWORD"
  }'
```

**预期响应**:
```json
{
  "code": 200,
  "success": true,
  "message": "密码重置成功"
}
```

### 测试场景 2：令牌已过期

**预期响应**:
```json
{
  "code": 401,
  "success": false,
  "message": "验证令牌已过期"
}
```

### 测试场景 3：令牌已被使用

**预期响应**:
```json
{
  "code": 410,
  "success": false,
  "message": "验证令牌已被使用，请重新验证"
}
```

### 测试场景 4：密码格式错误

**预期响应**:
```json
{
  "code": 400,
  "success": false,
  "message": "密码长度必须为6-14位"
}
```

---

## 完整流程图

```
┌─────────────────────────────────┐
│  第三步：验证身份                │
│  [邮箱/手机/密保]                │
│  [上一步] [下一步]               │
└────────────┬────────────────────┘
             ↓ 验证成功
┌─────────────────────────────────┐
│  获取 resetToken (JWT)           │
│  有效期：10分钟                  │
│  只能使用一次                    │
└────────────┬────────────────────┘
             ↓
┌─────────────────────────────────┐
│  第四步：设置新密码              │
│                                  │
│  初始化时：                      │
│  1. createNewSessionId()        │
│  2. fetchRSAKey()               │
│                                  │
│  [新密码输入框]                  │
│  [确认密码输入框]                │
│  [上一步] [确认重置]             │
└────────────┬────────────────────┘
             ↓ 点击"确认重置"
┌─────────────────────────────────┐
│  1. 检查 resetToken 是否存在     │
│  2. createNewSessionId()        │
│  3. fetchRSAKey()               │
│  4. encryptPassword()           │
│  5. POST /auth/reset_password/  │
│     reset                       │
│     Headers:                    │
│       Authorization: Bearer     │
│       {resetToken}              │
│     Body:                       │
│       { sessionId,              │
│         encryptedNewPassword }  │
└────────────┬────────────────────┘
             ↓
┌─────────────────────────────────┐
│  后端验证：                      │
│  1. 验证 JWT 令牌               │
│  2. 检查是否已使用               │
│  3. 验证 SessionId              │
│  4. 解密密码                    │
│  5. 验证密码格式                │
│  6. 哈希密码 (bcrypt)           │
│  7. 更新数据库                  │
│  8. 标记令牌已使用               │
│  9. 清理敏感数据                │
└────────────┬────────────────────┘
             ↓ 成功
┌─────────────────────────────────┐
│  前端处理：                      │
│  1. 显示成功消息                 │
│  2. 清除所有敏感数据             │
│     - clearSessionId()          │
│     - resetToken = ''           │
│     - rsaPublicKey = ''         │
│  3. 延迟 2 秒跳转登录页          │
└─────────────────────────────────┘
```

---

## 常见问题

### Q1: 为什么 resetToken 要在回退时失效？

**A**: 防止用户回退后重新提交相同的令牌，导致重放攻击。每次重置密码流程都应该是全新的。

### Q2: 为什么第四步要重新生成 SessionId？

**A**: 与第三步的 SessionId 隔离，防止会话冲突，提高安全性。

### Q3: JWT 令牌如何防止被窃取？

**A**: 
- 使用 HTTPS 传输
- 令牌只在内存中存储（不存入 Cookie 或 localStorage）
- 令牌有效期短（10分钟）
- 令牌只能使用一次

### Q4: 如果用户刷新页面怎么办？

**A**: 页面刷新时，resetToken 会被清除（因为在内存中），用户需要重新从第二步开始验证。

---

## 更新日志

| 版本 | 日期 | 更新内容 |
|------|------|---------|
| 1.0 | 2026-05-02 | 初始版本，定义重置密码接口 |

---

**文档维护者**: Frontend Team  
**最后更新**: 2026-05-02
