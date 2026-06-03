# 用户目录ID API更新说明

## 📋 概述

本次更新在登录和二次验证相关接口中添加了 `homeDirectoryId`（用户根目录ID）和 `recycleBinId`（用户回收站ID）字段，方便前端直接获取用户的目录结构信息。

---

## 🔑 核心变更

### 1. 新增字段说明

| 字段名 | 类型 | 说明 | 数据来源 |
|--------|------|------|---------|
| `homeDirectoryId` | Long | 用户根目录ID | `folder_nodes`表中 `user_id={userId}` 且 `parent_id` 对应 `_root/_files` 的节点ID |
| `recycleBinId` | Long | 用户回收站ID | `folder_nodes`表中 `user_id={userId}` 且 `parent_id` 对应 `_root/_recycle_bin` 的节点ID |

**数据库查询逻辑：**
```sql
-- 查询用户根目录ID
SELECT id FROM folder_nodes 
WHERE user_id = #{userId} 
AND parent_id = (SELECT id FROM folder_nodes WHERE path = '_root/_files') 
LIMIT 1;

-- 查询用户回收站ID
SELECT id FROM folder_nodes 
WHERE user_id = #{userId} 
AND parent_id = (SELECT id FROM folder_nodes WHERE path = '_root/_recycle_bin') 
LIMIT 1;
```

---

## 📡 受影响的接口

### 1. 登录接口 - POST /auth/login

#### 响应体变更

**信任设备登录成功（无需二次验证）：**
```json
{
  "code": 200,
  "success": true,
  "message": "登录成功",
  "userId": "123",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userType": "user",
  "homeDirectoryId": 456,      // ✅ 新增：用户根目录ID
  "recycleBinId": 789,         // ✅ 新增：用户回收站ID
  "expiresAt": "2024-01-08T00:00:00"
}
```

**需要二次验证：**
```json
{
  "code": 200,
  "success": true,
  "message": "需要二次验证",
  "requiresTwoFactor": true,
  "sessionId": "session-uuid-xxx",
  "userId": "123",
  "email": "user@example.com",
  "phone": "13800138000",
  "securityQuestion": "你的出生地是？",
  "securityQuestionId": 1
}
```
> ⚠️ **注意：** 需要二次验证时，不返回 `homeDirectoryId` 和 `recycleBinId`，需在二次验证成功后获取。

---

### 2. 二次验证接口

#### 2.1 邮箱验证 - POST /auth/verify/email

**成功响应：**
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 123,
  "userType": "user",
  "homeDirectoryId": 456,      // ✅ 新增：用户根目录ID
  "recycleBinId": 789,         // ✅ 新增：用户回收站ID
  "expiresAt": "2024-01-08T00:00:00"  // ✅ 新增：Token过期时间
}
```

#### 2.2 手机验证 - POST /auth/verify/phone

**成功响应：**
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 123,
  "userType": "user",
  "homeDirectoryId": 456,      // ✅ 新增：用户根目录ID
  "recycleBinId": 789,         // ✅ 新增：用户回收站ID
  "expiresAt": "2024-01-08T00:00:00"  // ✅ 新增：Token过期时间
}
```

#### 2.3 密保问题验证 - POST /auth/verify/security_answer

**成功响应：**
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 123,
  "userType": "user",
  "homeDirectoryId": 456,      // ✅ 新增：用户根目录ID
  "recycleBinId": 789,         // ✅ 新增：用户回收站ID
  "expiresAt": "2024-01-08T00:00:00"  // ✅ 新增：Token过期时间
}
```

---

### 3. 获取个人资料接口 - POST /profile/get_all

#### 响应体变更

```json
{
  "code": 200,
  "success": true,
  "message": "获取成功（来自缓存）",
  "data": {
    "avatar": "/avatars/123.jpg",
    "email": "u***@example.com",
    "nickname": "用户昵称",
    "phone": "138****8000",
    "securityQuestion": "你的出生地是？",
    "storageQuota": 10737418240,
    "storageUsed": 1073741824,
    "homeDirectoryId": 456,      // ✅ 新增：用户根目录ID
    "recycleBinId": 789          // ✅ 新增：用户回收站ID
  }
}
```

---

## 💾 Redis缓存策略

### 缓存Key
```
profile:{userId}
```

### 缓存内容
包含用户的所有个人资料信息，包括新增的 `homeDirectoryId` 和 `recycleBinId`。

### 缓存有效期
- 与JWT令牌的剩余有效期一致
- 如果无法获取JWT剩余时间，默认7天

### 缓存更新时机
1. **登录成功时**：自动查询并写入缓存
2. **二次验证成功时**：自动查询并写入缓存
3. **获取个人资料时**：如果缓存不存在，从数据库查询并写入缓存

### Redis实例
- **端口**: 6380
- **数据库**: 0

---

## 🔍 使用场景

### 场景1：登录后直接进入文件管理页面

```javascript
// 1. 用户登录
const loginResponse = await fetch('/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    sessionId: 'xxx',
    userIdOrEmail: 'user@example.com',
    encryptedPassword: '...'
  })
});

const data = await loginResponse.json();

if (data.success && !data.requiresTwoFactor) {
  // 2. 保存Token和用户信息
  localStorage.setItem('jwt_token', data.token);
  
  // 3. 直接使用 homeDirectoryId 和 recycleBinId
  const homeDirId = data.homeDirectoryId;  // 用户根目录ID
  const recycleBinId = data.recycleBinId;  // 用户回收站ID
  
  // 4. 跳转到文件管理页面，传入目录ID
  navigateToFileManager(homeDirId);
}
```

### 场景2：二次验证后进入文件管理页面

```javascript
// 1. 完成邮箱验证
const verifyResponse = await fetch('/auth/verify/email', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    sessionId: 'xxx',
    userId: 123,
    verificationCode: '123456'
  })
});

const data = await verifyResponse.json();

if (data.success) {
  // 2. 保存Token和用户信息
  localStorage.setItem('jwt_token', data.token);
  
  // 3. 使用 homeDirectoryId 和 recycleBinId
  const homeDirId = data.homeDirectoryId;  // 用户根目录ID
  const recycleBinId = data.recycleBinId;  // 用户回收站ID
  
  // 4. 跳转到文件管理页面
  navigateToFileManager(homeDirId);
}
```

### 场景3：从个人资料中获取目录ID

```javascript
// 1. 获取个人资料
const profileResponse = await fetch('/profile/get_all', {
  method: 'POST',
  headers: {
    'Authorization': 'Bearer ' + token
  }
});

const data = await profileResponse.json();

if (data.success) {
  // 2. 从个人资料中获取目录ID
  const homeDirId = data.data.homeDirectoryId;   // 用户根目录ID
  const recycleBinId = data.data.recycleBinId;   // 用户回收站ID
  
  console.log('用户根目录ID:', homeDirId);
  console.log('用户回收站ID:', recycleBinId);
}
```

---

## ⚠️ 注意事项

### 1. 字段可能为null的情况

以下情况 `homeDirectoryId` 和 `recycleBinId` 可能为 `null`：
- 管理员用户（userId < 10001）
- 新用户尚未创建目录结构
- 数据库中没有对应的目录记录

**前端处理建议：**
```javascript
if (response.homeDirectoryId) {
  // 正常使用
  navigateToFileManager(response.homeDirectoryId);
} else {
  // 提示用户或调用初始化接口
  showWarning('您的账户尚未初始化，请联系管理员');
}
```

### 2. 缓存一致性

- 目录ID在用户注册时创建，之后不会改变
- 如果删除了用户的目录结构，需要重新创建并更新缓存
- 缓存会在JWT过期时自动失效

### 3. 性能优化

- 优先从登录/二次验证响应中获取目录ID（避免额外请求）
- 如果需要刷新个人资料，调用 `/profile/get_all` 接口
- 目录ID变化频率极低，可以长期缓存在前端

---

## 🧪 测试用例

### 测试1：普通用户登录（信任设备）

```bash
curl -X POST http://localhost:8835/auth/login \
  -H "Content-Type: application/json" \
  -H "X-Client-Type: electron" \
  -d '{
    "sessionId": "test-session-id",
    "userIdOrEmail": "user@example.com",
    "encryptedPassword": "Base64EncodedEncryptedPassword"
  }'
```

**预期结果：**
```json
{
  "code": 200,
  "success": true,
  "message": "登录成功",
  "userId": "10001",
  "token": "eyJhbGc...",
  "homeDirectoryId": 456,
  "recycleBinId": 789
}
```

### 测试2：邮箱二次验证

```bash
curl -X POST http://localhost:8835/auth/verify/email \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "test-session-id",
    "userId": 10001,
    "verificationCode": "123456"
  }'
```

**预期结果：**
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "token": "eyJhbGc...",
  "userId": 10001,
  "homeDirectoryId": 456,
  "recycleBinId": 789,
  "expiresAt": "2024-01-08T00:00:00"
}
```

### 测试3：获取个人资料

```bash
curl -X POST http://localhost:8835/profile/get_all \
  -H "Authorization: Bearer eyJhbGc..."
```

**预期结果：**
```json
{
  "code": 200,
  "success": true,
  "message": "获取成功（来自缓存）",
  "data": {
    "homeDirectoryId": 456,
    "recycleBinId": 789,
    ...
  }
}
```

---

## 📝 总结

### 主要变更
1. ✅ 登录接口返回 `homeDirectoryId`、`recycleBinId` 和 `expiresAt`
2. ✅ 二次验证接口返回 `homeDirectoryId`、`recycleBinId` 和 `expiresAt`
3. ✅ 个人资料接口返回 `homeDirectoryId` 和 `recycleBinId`
4. ✅ 所有目录ID自动缓存到Redis（端口6380）

### 前端适配建议
1. 优先从登录/二次验证响应中获取目录ID
2. 将目录ID保存到本地状态管理（Vuex/Pinia/Redux等）
3. 文件浏览、回收站浏览等接口直接使用这些ID
4. 处理 `null` 值的边界情况

### 优势
- 🚀 减少前端请求次数（无需单独查询目录ID）
- 💾 后端自动缓存，提升性能
- 🎯 统一的目录ID来源，避免不一致
- 📦 响应体结构清晰，易于使用

---

**文档版本**: v1.0  
**更新日期**: 2026-05-20  
**联系人**: 后端开发团队
