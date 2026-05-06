# CloudFileSystem 前端对接快速恢复指南

> **文档版本**: v1.0  
> **最后更新**: 2026-05-06  
> **适用环境**: 生产环境/开发环境

---

## 📋 目录

- [1. 项目概述](#1-项目概述)
- [2. 技术栈](#2-技术栈)
- [3. 环境配置](#3-环境配置)
- [4. 核心功能模块](#4-核心功能模块)
- [5. API接口详解](#5-api接口详解)
- [6. 认证流程](#6-认证流程)
- [7. 文件上传下载](#7-文件上传下载)
- [8. 用户资料管理](#8-用户资料管理)
- [9. 安全机制](#9-安全机制)
- [10. 常见问题](#10-常见问题)

---

## 1. 项目概述

CloudFileSystem 是一个基于 Spring Boot 的云文件系统后端服务，提供用户认证、文件管理、个人资料管理等功能。

### 核心特性

- ✅ RSA加密传输（密码等敏感信息）
- ✅ JWT身份认证
- ✅ 二次验证（邮箱/手机/密保问题）
- ✅ 分片上传、秒传、断点续传
- ✅ 设备指纹识别与信任设备管理
- ✅ 多Redis实例隔离存储

---

## 2. 技术栈

### 后端技术

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.x | 核心框架 |
| MyBatis | - | ORM框架 |
| MySQL | 8.0+ | 主数据库 |
| Redis | 6.0+ | 缓存（3个实例） |
| JWT (jjwt) | - | Token认证 |
| Spring Security | - | 安全框架 |
| Spring Mail | - | 邮件发送 |

### 前端需要支持的库

```javascript
// 必需库
- axios (HTTP请求)
- crypto-js 或 jsencrypt (RSA加密)
- jwt-decode (JWT解析，可选)
- spark-md5 (文件MD5计算，用于秒传)
```

---

## 3. 环境配置

### 3.1 后端服务地址

```yaml
开发环境: http://127.0.0.1:8835
生产环境: https://your-domain.com (通过Nginx代理)
```

### 3.2 Redis实例配置

| 实例 | 端口 | 用途 | Key前缀 |
|------|------|------|---------|
| Redis 1 | 6379 | RSA密钥对、会话数据 | `rsa:key:` |
| Redis 2 | 6380 | 用户资料缓存 | `profile:` |
| Redis 3 | 6378 | 信任浏览器设备 | `trusted:browser:` |

### 3.3 数据库配置

```yaml
数据库: cloud_file_database
主机: localhost:3306
字符集: utf8mb4
```

### 3.4 文件存储路径

```yaml
本地存储: D:/CloudFileSystem/files (Windows)
         /data/files (Linux)
```

---

## 4. 核心功能模块

### 4.1 用户认证模块 (`/auth`)

- 获取RSA公钥
- 用户登录
- 用户注册
- 发送验证码（邮箱/手机）
- 重置密码（4步流程）
- 二次验证（邮箱/手机/密保）

### 4.2 文件管理模块 (`/file`)

- 初始化上传（检查秒传）
- 分片上传
- 合并分片
- 查询上传进度
- 文件下载

### 4.3 用户资料模块 (`/profile`)

- 获取/设置头像
- 获取所有个人信息
- 修改密码
- 修改昵称
- 修改邮箱
- 修改手机号
- 修改密保问题

---

## 5. API接口详解

### 🔐 5.1 认证相关接口

#### 5.1.1 健康检查

```http
GET /auth/health
```

**响应:**
```
Backend is running
```

**用途:** 检测后端是否启动

---

#### 5.1.2 获取RSA公钥

```http
POST /auth/rsa-key
Content-Type: application/json
```

**请求体:**
```json
{
  "sessionId": "uuid-generated-by-frontend"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
}
```

**前端实现要点:**
1. 生成唯一sessionId（UUID）
2. 调用此接口获取公钥
3. 使用公钥加密敏感信息（密码、答案等）
4. sessionId有效期5分钟，每次使用后会自动续期

**RSA加密示例 (JavaScript):**
```javascript
import JSEncrypt from 'jsencrypt';

const encrypt = new JSEncrypt();
encrypt.setPublicKey(publicKey);
const encryptedPassword = encrypt.encrypt(password);
```

---

#### 5.1.3 用户登录

```http
POST /auth/login
Content-Type: application/json
X-Client-Type: browser | electron | mobile
X-Device-Fingerprint: device-fingerprint-hash
X-Hardware-Id: hardware-id (optional)
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "userIdOrEmail": "user@example.com",
  "encryptedPassword": "RSA-encrypted-password"
}
```

**响应场景1 - 直接登录成功:**
```json
{
  "code": 200,
  "success": true,
  "message": "登录成功",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "123",
  "email": "user@example.com",
  "phone": "13800138000",
  "userType": "user",
  "homeDirectory": "/users/123",
  "requiresTwoFactor": false
}
```

**响应场景2 - 需要二次验证:**
```json
{
  "code": 200,
  "success": true,
  "message": "需要二次验证",
  "requiresTwoFactor": true,
  "sessionId": "same-session-id-as-login",
  "userId": "123",
  "email": "user@example.com",
  "phone": "138****8000",
  "securityQuestion": "您的出生地是？",
  "securityQuestionId": 1
}
```

**前端实现要点:**
1. 使用登录时的同一个sessionId
2. 如果`requiresTwoFactor=true`，跳转到二次验证页面
3. 保存返回的token到localStorage/sessionStorage
4. 后续请求在Header中携带`Authorization: Bearer {token}`

---

#### 5.1.4 用户注册

```http
POST /auth/register
Content-Type: application/json
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "data": [
    {
      "nickname": "张三",
      "email": "user@example.com",
      "phone": "13800138000",
      "encryptedPassword": "RSA-encrypted-password",
      "securityQuestionId": 1,
      "encryptedSecurityAnswer": "RSA-encrypted-answer",
      "emailVerificationCode": "123456",
      "phoneVerificationCode": "654321"
    }
  ]
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "注册成功"
}
```

**前置条件:**
1. 必须先发送邮箱验证码和手机验证码
2. 验证码有效期5分钟

---

#### 5.1.5 发送邮箱验证码

```http
POST /auth/vfcode/email
Content-Type: application/json
```

**请求体:**
```json
{
  "email": "user@example.com",
  "sessionId": "uuid-from-rsa-key-request"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "验证码已发送，请查收邮件",
  "expireTime": 300
}
```

**限制:**
- 同一邮箱60秒内只能发送一次
- 验证码有效期5分钟

---

#### 5.1.6 发送手机验证码

```http
POST /auth/vfcode/phone
Content-Type: application/json
```

**请求体:**
```json
{
  "phoneNumber": "13800138000",
  "sessionId": "uuid-from-rsa-key-request"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "验证码已发送，请注意查收",
  "expireTime": 300
}
```

**限制:**
- 同一手机号60秒内只能发送一次
- 验证码有效期5分钟

---

#### 5.1.7 获取安全问题列表

```http
GET /auth/security-questions
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "questions": [
    {
      "id": 1,
      "question": "您的出生地是？"
    },
    {
      "id": 2,
      "question": "您第一所学校的名字是？"
    },
    {
      "id": 3,
      "question": "您最喜欢的颜色是？"
    }
  ]
}
```

---

### 🔄 5.2 重置密码流程（4步）

#### 步骤1: 查找用户

```http
POST /auth/reset_password/find_user
Content-Type: application/json
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "encryptedUserIdOrEmail": "RSA-encrypted-userId-or-email"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "userId": "123",
  "maskedEmail": "us***@example.com",
  "maskedPhone": "138****8000",
  "hasEmail": true,
  "hasPhone": true,
  "hasSecurityQuestion": true,
  "securityQuestion": "您的出生地是？"
}
```

---

#### 步骤2: 验证身份（三选一）

**选项A - 邮箱验证:**
```http
POST /auth/reset_password/verify/email
Content-Type: application/json
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "email": "user@example.com",
  "verificationCode": "123456"
}
```

**选项B - 手机验证:**
```http
POST /auth/reset_password/verify/phone
Content-Type: application/json
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "phone": "13800138000",
  "verificationCode": "123456"
}
```

**选项C - 密保问题验证:**
```http
POST /auth/reset_password/verify/security_answer
Content-Type: application/json
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "userId": "123",
  "encryptedSecurityAnswer": "RSA-encrypted-answer"
}
```

**响应 (三种方式相同):**
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "resetToken": "temp-token-for-password-reset"
}
```

---

#### 步骤3: 重置密码

```http
POST /auth/reset_password/reset
Content-Type: application/json
Authorization: Bearer {resetToken}
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "uuid-from-rsa-key-request",
  "encryptedNewPassword": "RSA-encrypted-new-password"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "密码重置成功"
}
```

---

### 🔐 5.3 二次验证接口

#### 5.3.1 邮箱验证码验证

```http
POST /auth/verify/email
Content-Type: application/json
X-Client-Type: browser | electron | mobile
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "same-session-id-as-login",
  "userId": 123,
  "verificationCode": "123456"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "验证成功",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": "123",
  "userType": "user",
  "homeDirectory": "/users/123"
}
```

---

#### 5.3.2 手机验证码验证

```http
POST /auth/verify/phone
Content-Type: application/json
X-Client-Type: browser | electron | mobile
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "same-session-id-as-login",
  "userId": 123,
  "verificationCode": "123456"
}
```

**响应:** 同邮箱验证

---

#### 5.3.3 密保问题验证

```http
POST /auth/verify/security_answer
Content-Type: application/json
X-Client-Type: browser | electron | mobile
X-Device-Fingerprint: device-fingerprint-hash
```

**请求体:**
```json
{
  "sessionId": "same-session-id-as-login",
  "userId": 123,
  "encryptedAnswer": "RSA-encrypted-answer"
}
```

**响应:** 同邮箱验证

---

### 📁 5.4 文件上传下载接口

#### 5.4.1 初始化上传（检查秒传）

```http
POST /file/upload/init
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "fileName": "example.pdf",
  "fileSize": 10485760,
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "totalChunks": 10,
  "chunkSize": 1048576,
  "targetDirectory": "/documents"
}
```

**响应场景1 - 秒传成功:**
```json
{
  "code": 200,
  "success": true,
  "message": "秒传成功",
  "uploadId": null,
  "needUpload": false,
  "fileUrl": "/file/download/example_abc123.pdf"
}
```

**响应场景2 - 需要上传:**
```json
{
  "code": 200,
  "success": true,
  "message": "初始化成功",
  "uploadId": "upload-uuid-123456",
  "needUpload": true,
  "uploadedChunks": [0, 1, 2],
  "remainingChunks": [3, 4, 5, 6, 7, 8, 9]
}
```

---

#### 5.4.2 上传文件分片

```http
POST /file/upload/chunk
Content-Type: multipart/form-data
Authorization: Bearer {jwt-token}
```

**表单参数:**
- `uploadId`: upload-uuid-123456
- `chunkIndex`: 3
- `file`: [Binary chunk data]

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "分片上传成功",
  "chunkIndex": 3,
  "progress": 40
}
```

---

#### 5.4.3 合并文件分片

```http
POST /file/upload/merge
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "uploadId": "upload-uuid-123456",
  "fileName": "example.pdf",
  "totalChunks": 10,
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "fileSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "文件合并成功",
  "fileUrl": "/file/download/example_abc123.pdf"
}
```

---

#### 5.4.4 查询上传进度

```http
GET /file/upload/progress/{uploadId}
Authorization: Bearer {jwt-token}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "uploadId": "upload-uuid-123456",
  "uploadedChunks": [0, 1, 2, 3, 4],
  "totalChunks": 10,
  "progress": 50
}
```

---

#### 5.4.5 文件下载

```http
GET /file/download/{fileName}
或
GET /api/file/download/{fileName}
```

**注意:** 此接口不需要Authorization头（因为`<img>`标签无法携带）

**响应:** 文件流

---

### 👤 5.5 用户资料接口

**所有Profile接口都需要在Header中携带:**
```
Authorization: Bearer {jwt-token}
```

#### 5.5.1 获取头像

```http
GET /profile/avatar/get
Authorization: Bearer {jwt-token}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "avatar": "https://example.com/avatar.jpg"
}
```

---

#### 5.5.2 设置头像

```http
GET /profile/avatar/set?avatar={avatarUrl}
Authorization: Bearer {jwt-token}
```

**参数:**
- `avatar`: 图片URL或Base64编码

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "头像设置成功"
}
```

---

#### 5.5.3 获取所有个人信息

```http
POST /profile/get_all
Authorization: Bearer {jwt-token}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "profile": {
    "userId": "123",
    "nickname": "张三",
    "email": "user@example.com",
    "phone": "13800138000",
    "avatar": "https://example.com/avatar.jpg",
    "securityQuestionId": 1,
    "registeredAt": "2026-01-01T00:00:00"
  }
}
```

---

#### 5.5.4 验证原密码

```http
POST /profile/password/is_initial_correct
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "encryptedPassword": "RSA-encrypted-current-password"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "密码正确"
}
```

---

#### 5.5.5 修改密码

```http
POST /profile/password/set
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "encryptedOldPassword": "RSA-encrypted-old-password",
  "encryptedNewPassword": "RSA-encrypted-new-password"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "密码修改成功"
}
```

---

#### 5.5.6 修改昵称

```http
POST /profile/nickname/set
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "nickname": "新昵称"
}
```

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "昵称修改成功",
  "newNickname": "新昵称"
}
```

---

#### 5.5.7 修改邮箱

```http
POST /profile/email/set
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "encryptedNewEmail": "RSA-encrypted-new-email",
  "verificationCode": "123456"
}
```

**前置条件:** 需要先发送验证码到新邮箱

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "邮箱修改成功"
}
```

---

#### 5.5.8 修改手机号

```http
POST /profile/phone/set
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "encryptedNewPhone": "RSA-encrypted-new-phone",
  "verificationCode": "123456"
}
```

**前置条件:** 需要先发送验证码到新手机号

**响应:**
```json
{
  "code": 200,
  "success": true,
  "message": "手机号修改成功"
}
```

---

#### 5.5.9 修改密保问题

```http
POST /profile/security_question/set
Content-Type: application/json
Authorization: Bearer {jwt-token}
```

**请求体:**
```json
{
  "securityQuestionId": 2,
  "encryptedSecurityAnswer": "RSA-encrypted-answer"
}
```

**响应:**
```json
{
  "success": true,
  "message": "密保问题修改成功"
}
```

---

## 6. 认证流程

### 6.1 完整登录流程图

```
┌─────────────┐
│   前端启动   │
└──────┬──────┘
       │
       ▼
┌─────────────────────┐
│ 1. 生成UUID sessionId│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ 2. POST /auth/rsa-key│
│    获取RSA公钥       │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ 3. 用户输入账号密码  │
└──────┬──────────────┘
       │
       ▼
┌──────────────────────────┐
│ 4. 使用公钥加密密码      │
│ 5. POST /auth/login      │
│    携带sessionId和加密密码│
└──────┬───────────────────┘
       │
       ├─ requiresTwoFactor=false ──► 登录成功，保存token
       │
       └─ requiresTwoFactor=true ──┐
                                    │
                                    ▼
                          ┌──────────────────────┐
                          │ 6. 显示二次验证界面   │
                          │    (邮箱/手机/密保)   │
                          └──────┬───────────────┘
                                 │
                                 ▼
                          ┌────────────────────────┐
                          │ 7. 发送验证码(如需)     │
                          │    POST /auth/vfcode/*  │
                          └──────┬─────────────────┘
                                 │
                                 ▼
                          ┌──────────────────────────┐
                          │ 8. POST /auth/verify/*   │
                          │    提交验证码或密保答案   │
                          └──────┬───────────────────┘
                                 │
                                 ▼
                          ┌──────────────────────┐
                          │ 9. 验证成功，保存token│
                          └──────────────────────┘
```

### 6.2 二次验证触发条件

系统会在以下情况要求二次验证：

1. **新设备登录** - 设备指纹未记录
2. **异地登录** - IP地址变化较大
3. **长时间未登录** - 超过信任期限
4. **浏览器登录** - 默认需要二次验证（除非已信任）

**信任设备规则:**
- 客户端应用（Electron/Mobile）验证成功后自动信任
- 浏览器验证成功后保存为临时信任（Redis，有效期7天）

---

## 7. 文件上传下载

### 7.1 文件上传流程

```
┌──────────────────────┐
│ 1. 选择文件          │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ 2. 计算文件MD5/SHA256│
│    (使用spark-md5)   │
└──────┬───────────────┘
       │
       ▼
┌──────────────────────────┐
│ 3. POST /file/upload/init│
│    检查是否可秒传        │
└──────┬───────────────────┘
       │
       ├─ needUpload=false ──► 秒传成功，结束
       │
       └─ needUpload=true ───┐
                              │
                              ▼
                     ┌──────────────────────┐
                     │ 4. 分割文件为多个分片 │
                     │    (建议5MB/分片)     │
                     └──────┬───────────────┘
                            │
                            ▼
                     ┌────────────────────────┐
                     │ 5. 循环上传每个分片     │
                     │    POST /file/upload/   │
                     │         chunk           │
                     └──────┬─────────────────┘
                            │
                            ▼
                     ┌──────────────────────┐
                     │ 6. 所有分片上传完成后 │
                     │    POST /file/upload/ │
                     │         merge         │
                     └──────┬───────────────┘
                            │
                            ▼
                     ┌──────────────────────┐
                     │ 7. 上传成功，获取     │
                     │    文件URL            │
                     └──────────────────────┘
```

### 7.2 前端实现示例

```javascript
import SparkMD5 from 'spark-md5';

// 计算文件MD5
async function calculateFileMD5(file) {
  return new Promise((resolve) => {
    const spark = new SparkMD5.ArrayBuffer();
    const reader = new FileReader();
    const chunkSize = 2 * 1024 * 1024; // 2MB
    let currentChunk = 0;
    
    reader.onload = (e) => {
      spark.append(e.target.result);
      currentChunk++;
      
      if (currentChunk < Math.ceil(file.size / chunkSize)) {
        loadNext();
      } else {
        resolve(spark.end());
      }
    };
    
    function loadNext() {
      const start = currentChunk * chunkSize;
      const end = start + chunkSize >= file.size ? file.size : start + chunkSize;
      reader.readAsArrayBuffer(file.slice(start, end));
    }
    
    loadNext();
  });
}

// 分片上传
async function uploadFile(file, token) {
  const fileMd5 = await calculateFileMD5(file);
  const chunkSize = 5 * 1024 * 1024; // 5MB
  const totalChunks = Math.ceil(file.size / chunkSize);
  
  // 1. 初始化上传
  const initResponse = await axios.post('/file/upload/init', {
    fileName: file.name,
    fileSize: file.size,
    fileMd5: fileMd5,
    totalChunks: totalChunks,
    chunkSize: chunkSize
  }, {
    headers: { Authorization: `Bearer ${token}` }
  });
  
  if (!initResponse.data.needUpload) {
    console.log('秒传成功');
    return initResponse.data.fileUrl;
  }
  
  const uploadId = initResponse.data.uploadId;
  
  // 2. 上传分片
  for (let i = 0; i < totalChunks; i++) {
    const start = i * chunkSize;
    const end = Math.min(start + chunkSize, file.size);
    const chunk = file.slice(start, end);
    
    const formData = new FormData();
    formData.append('uploadId', uploadId);
    formData.append('chunkIndex', i);
    formData.append('file', chunk);
    
    await axios.post('/file/upload/chunk', formData, {
      headers: { 
        Authorization: `Bearer ${token}`,
        'Content-Type': 'multipart/form-data'
      }
    });
    
    console.log(`分片 ${i + 1}/${totalChunks} 上传完成`);
  }
  
  // 3. 合并分片
  const mergeResponse = await axios.post('/file/upload/merge', {
    uploadId: uploadId,
    fileName: file.name,
    totalChunks: totalChunks,
    fileMd5: fileMd5
  }, {
    headers: { Authorization: `Bearer ${token}` }
  });
  
  return mergeResponse.data.fileUrl;
}
```

---

## 8. 用户资料管理

### 8.1 获取个人资料流程

```javascript
// 获取所有个人信息
async function getUserProfile(token) {
  const response = await axios.post('/profile/get_all', {}, {
    headers: { Authorization: `Bearer ${token}` }
  });
  
  return response.data.profile;
}

// 获取头像
async function getAvatar(token) {
  const response = await axios.get('/profile/avatar/get', {
    headers: { Authorization: `Bearer ${token}` }
  });
  
  return response.data.avatar;
}
```

### 8.2 修改个人信息流程

```javascript
// 修改密码示例
async function changePassword(token, oldPassword, newPassword, publicKey) {
  // 1. 验证原密码
  const encrypt = new JSEncrypt();
  encrypt.setPublicKey(publicKey);
  
  const verifyResponse = await axios.post('/profile/password/is_initial_correct', {
    encryptedPassword: encrypt.encrypt(oldPassword)
  }, {
    headers: { Authorization: `Bearer ${token}` }
  });
  
  if (!verifyResponse.data.success) {
    throw new Error('原密码错误');
  }
  
  // 2. 修改密码
  const changeResponse = await axios.post('/profile/password/set', {
    encryptedOldPassword: encrypt.encrypt(oldPassword),
    encryptedNewPassword: encrypt.encrypt(newPassword)
  }, {
    headers: { Authorization: `Bearer ${token}` }
  });
  
  return changeResponse.data;
}
```

---

## 9. 安全机制

### 9.1 RSA加密

**使用场景:**
- 密码传输（登录、注册、修改密码）
- 密保答案传输
- 邮箱/手机号传输（修改时）

**注意事项:**
1. 每次获取新的sessionId后必须重新获取公钥
2. 公钥有效期5分钟
3. 私钥存储在Redis中，使用后自动删除
4. RSA加密后的数据长度会增加，注意字段长度限制

### 9.2 JWT Token

**Token包含的信息:**
```json
{
  "userId": 123,
  "nickname": "",  // 不再包含nickname
  "userType": "user",
  "registeredAt": "2026-01-01T00:00:00",
  "deviceFingerprint": "hash-value",
  "exp": 1735689600
}
```

**Token有效期:**
- 浏览器: 24小时
- 客户端(Electron/Mobile): 30天

**Token刷新策略:**
- Token过期后需要重新登录
- 无自动刷新机制

### 9.3 设备指纹

**生成方式 (前端):**
```javascript
import FingerprintJS from '@fingerprintjs/fingerprintjs';

async function getDeviceFingerprint() {
  const fp = await FingerprintJS.load();
  const result = await fp.get();
  return result.visitorId;
}
```

**用途:**
- 识别新设备
- 信任设备管理
- 安全审计日志

### 9.4 CORS配置

后端已配置允许跨域:
```yaml
允许来源: * (开发环境)
允许方法: GET, POST, PUT, DELETE, OPTIONS, PATCH
允许头部: Authorization, Content-Type, Accept, Origin, X-Requested-With
暴露头部: Authorization, Content-Type
允许凭证: true
```

**生产环境建议:** 将`allowedOriginPatterns`改为具体域名

---

## 10. 常见问题

### Q1: 登录后提示"会话已过期"

**原因:** sessionId对应的RSA密钥对已过期（5分钟）

**解决方案:**
1. 重新调用`POST /auth/rsa-key`获取新公钥
2. 使用新公钥重新加密密码
3. 重新登录

---

### Q2: 文件上传失败，提示"分片不完整"

**原因:** 某些分片上传失败或顺序错误

**解决方案:**
1. 调用`GET /file/upload/progress/{uploadId}`查询已上传分片
2. 只上传缺失的分片
3. 确保所有分片上传完成后再调用merge接口

---

### Q3: 二次验证一直触发

**原因:** 设备指纹不一致或未保存信任设备

**解决方案:**
1. 确保每次请求都携带相同的`X-Device-Fingerprint`
2. 检查客户端类型是否正确设置（`X-Client-Type`）
3. 客户端应用验证成功后会自动信任，浏览器需要手动信任

---

### Q4: 修改邮箱/手机号收不到验证码

**原因:** 
1. 邮箱配置错误
2. 短信服务未配置
3. 频率限制

**解决方案:**
1. 检查后端日志确认验证码是否发送
2. 检查垃圾邮件箱
3. 等待60秒后重试

---

### Q5: Token无效或过期

**原因:** 
1. Token已过期
2. Token格式错误
3. 设备指纹不匹配

**解决方案:**
1. 检查Token是否在有效期内
2. 确保Header格式为`Authorization: Bearer {token}`
3. 重新登录获取新Token

---

### Q6: 跨域请求被阻止

**原因:** CORS配置问题

**解决方案:**
1. 检查后端CORS配置
2. 确保请求携带正确的Header
3. 开发环境可使用代理解决

---

## 📞 技术支持

如有问题，请查看后端日志或联系开发团队。

**日志级别配置:**
```yaml
logging:
  level:
    com.mizuka.cloudfilesystem: DEBUG
```

---

**文档结束**
