# RSA密钥存储与SessionId使用说明

## 📋 问题描述

用户反馈："RSA密钥存入Redis应该根据前端传递的sessionID存储，但是重新生成密钥对后疑似换了新的sessionID"

## 🔍 问题分析

### 当前实现检查

经过代码审查，**后端实现是正确的**：

#### 1. `/auth/rsa-key` 接口（获取公钥）
```java
// AuthController.java 第87-148行
@PostMapping("/rsa-key")
public ResponseEntity<Map<String, Object>> getRSAPublicKey(
        @RequestBody Map<String, String> request) {
    // 1. 接收前端传来的sessionId
    String sessionId = request.get("sessionId");
    
    // 2. 生成RSA密钥对
    KeyPair keyPair = RSAKeyManager.generateKeyPair();
    
    // 3. 存储到Redis，key为 "rsa:key:{sessionId}"
    String redisKey = RSA_KEY_PREFIX + sessionId;
    redisTemplate.opsForValue().set(redisKey, keyPairDTO, 300, TimeUnit.SECONDS);
    
    // 4. 返回响应（只返回公钥，不返回sessionId）
    Map<String, Object> response = new HashMap<>();
    response.put("code", 200);
    response.put("success", true);
    response.put("publicKey", publicKeyBase64);
    // ❌ 注意：这里没有返回 sessionId
}
```

**✅ 正确**：使用前端传递的sessionId作为Redis key

---

#### 2. `/auth/is_rsa_valid` 接口（验证密钥有效性）

##### 情况A：密钥有效
```java
// AuthController.java 第299-325行
if (storedPublicKey.equals(cleanProvidedPublicKey)) {
    // 密钥对有效，重置有效期为300秒
    redisTemplate.expire(redisKey, 300, TimeUnit.SECONDS);
    
    // 创建Cookie，更新sessionId（保持不变）
    ResponseCookie sessionCookie = ResponseCookie.from("rsa_session_id", sessionId)
        .httpOnly(false)
        .secure(false)
        .path("/")
        .maxAge(300)
        .sameSite("Lax")
        .build();
    
    // 返回有效响应，使用原有的sessionId和公钥
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
        .body(new RSAValidationResponse(
                200, true, "RSA密钥对有效", true,
                keyPairDTO.getPublicKey(),
                sessionId,  // ✅ 使用原始sessionId
                System.currentTimeMillis()
        ));
}
```

##### 情况B：公钥不匹配，重新生成
```java
// AuthController.java 第326-369行
else {
    // 密钥对无效，重新生成密钥对但使用原sessionId
    KeyPair newKeyPair = RSAKeyManager.generateKeyPair();
    String newPublicKey = RSAKeyManager.getPublicKeyBase64(newKeyPair);
    String newPrivateKey = RSAKeyManager.getPrivateKeyBase64(newKeyPair);
    
    RSAKeyPairDTO newKeyPairDTO = new RSAKeyPairDTO(
            newPublicKey, newPrivateKey, System.currentTimeMillis()
    );
    
    // 存储到Redis中，覆盖原有数据，设置300秒过期
    redisTemplate.opsForValue().set(redisKey, newKeyPairDTO, 300, TimeUnit.SECONDS);
    
    // 创建Cookie，更新sessionId（保持原sessionId）
    ResponseCookie sessionCookie = ResponseCookie.from("rsa_session_id", sessionId)
        .httpOnly(false)
        .secure(false)
        .path("/")
        .maxAge(300)
        .sameSite("Lax")
        .build();
    
    // 返回无效响应，附带新的公钥和原sessionId
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
        .body(new RSAValidationResponse(
                200, true, "RSA密钥对已失效，已生成新的密钥对", false,
                newPublicKey,
                sessionId,  // ✅ 使用原始sessionId
                System.currentTimeMillis()
        ));
}
```

##### 情况C：密钥不存在，重新生成
```java
// AuthController.java 第370-413行
else {
    // 密钥对不存在，使用原sessionId生成新的密钥对
    KeyPair newKeyPair = RSAKeyManager.generateKeyPair();
    String newPublicKey = RSAKeyManager.getPublicKeyBase64(newKeyPair);
    String newPrivateKey = RSAKeyManager.getPrivateKeyBase64(newKeyPair);
    
    RSAKeyPairDTO newKeyPairDTO = new RSAKeyPairDTO(
            newPublicKey, newPrivateKey, System.currentTimeMillis()
    );
    
    // 存储到Redis中，设置300秒过期
    redisTemplate.opsForValue().set(redisKey, newKeyPairDTO, 300, TimeUnit.SECONDS);
    
    // 创建Cookie，保持原sessionId
    ResponseCookie sessionCookie = ResponseCookie.from("rsa_session_id", sessionId)
        .httpOnly(false)
        .secure(false)
        .path("/")
        .maxAge(300)
        .sameSite("Lax")
        .build();
    
    // 返回无效响应，附带新的公钥和原sessionId
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
        .body(new RSAValidationResponse(
                200, true, "RSA密钥对已失效，已生成新的密钥对", false,
                newPublicKey,
                sessionId,  // ✅ 使用原始sessionId
                System.currentTimeMillis()
        ));
}
```

**✅ 正确**：所有情况下都使用前端传递的原始sessionId，**没有生成新的sessionId**

---

## 🎯 结论

### 后端实现状态
- ✅ **完全正确**：始终使用前端传递的sessionId
- ✅ **不会生成新sessionId**：即使重新生成密钥对，也使用原sessionId
- ✅ **Redis Key一致**：始终为 `rsa:key:{前端传递的sessionId}`

### 可能的问题来源

#### 1. 前端误解响应
**问题**：前端可能从响应的 `sessionId` 字段读取并覆盖了本地的sessionId

**响应示例**：
```json
{
  "code": 200,
  "success": true,
  "message": "RSA密钥对已失效，已生成新的密钥对",
  "valid": false,
  "publicKey": "-----BEGIN PUBLIC KEY-----...",
  "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",  // ⚠️ 这是原始的sessionId
  "timestamp": 1234567890
}
```

**说明**：
- 响应中的 `sessionId` 是**回显**前端传递的值
- **不是新生成的sessionId**
- 前端不应该用这个值覆盖本地的sessionId

#### 2. Cookie中的sessionId
**问题**：后端通过 `Set-Cookie` 头返回 `rsa_session_id` Cookie

**Cookie示例**：
```
Set-Cookie: rsa_session_id=a1b2c3d4-e5f6-7890-abcd-ef1234567890; Path=/; Max-Age=300; SameSite=Lax
```

**说明**：
- Cookie中的sessionId也是**原始的sessionId**
- 前端如果从Cookie读取并覆盖本地sessionId，会导致混淆

#### 3. 前端生成了新的sessionId
**问题**：前端在调用 `/auth/is_rsa_valid` 之前，可能生成了新的sessionId

**错误流程**：
```javascript
// ❌ 错误做法
let sessionId = generateUUID();  // 第一次生成
await fetch('/auth/rsa-key', { body: { sessionId } });

// ... 一段时间后 ...

sessionId = generateUUID();  // ⚠️ 第二次生成（错误！）
await fetch('/auth/is_rsa_valid', { body: { sessionId, publicKey } });
```

**正确流程**：
```javascript
// ✅ 正确做法
let sessionId = generateUUID();  // 只生成一次
await fetch('/auth/rsa-key', { body: { sessionId } });

// ... 一段时间后 ...

// 使用同一个sessionId
await fetch('/auth/is_rsa_valid', { body: { sessionId, publicKey } });
```

---

## 📝 正确使用指南

### 前端实现规范

#### 1. SessionId生成规则
```javascript
// ✅ 正确：只在需要时生成一次
const sessionId = crypto.randomUUID();  // 或 UUID v4

// ❌ 错误：每次请求都生成新的
const sessionId1 = crypto.randomUUID();  // 用于 /auth/rsa-key
const sessionId2 = crypto.randomUUID();  // ❌ 用于 /auth/is_rsa_valid（错误！）
```

#### 2. SessionId存储
```javascript
// ✅ 推荐：存储在组件state或全局store中
const [sessionId, setSessionId] = useState(crypto.randomUUID());

// ✅ 也可以：存储在localStorage（持久化）
localStorage.setItem('rsa_session_id', crypto.randomUUID());

// ⚠️ 注意：不要从响应中覆盖sessionId
```

#### 3. API调用示例

##### 获取公钥
```javascript
// 1. 生成sessionId（如果还没有）
if (!sessionId) {
  sessionId = crypto.randomUUID();
}

// 2. 调用接口
const response = await fetch('/auth/rsa-key', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ sessionId })
});

const data = await response.json();
const publicKey = data.publicKey;

// ❌ 不要这样做：
// sessionId = data.sessionId;  // 响应中没有这个字段
```

##### 验证密钥有效性
```javascript
// 使用同一个sessionId
const response = await fetch('/auth/is_rsa_valid', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ 
    sessionId,      // ✅ 使用之前的sessionId
    publicKey 
  })
});

const data = await response.json();

// ❌ 不要这样做：
// sessionId = data.sessionId;  // 这会覆盖原始sessionId（虽然值相同，但不必要）

// ✅ 如果需要更新公钥：
if (!data.valid) {
  publicKey = data.publicKey;  // 使用新的公钥
}
```

##### 修改邮箱/手机号
```javascript
// 使用同一个sessionId
const encryptedEmail = encryptWithPublicKey(email, publicKey);

const response = await fetch('/profile/email/set', {
  method: 'POST',
  headers: { 
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${jwtToken}`
  },
  body: JSON.stringify({ 
    sessionId,           // ✅ 使用之前的sessionId
    encryptedEmail,      // RSA加密后的邮箱
    verificationCode: '123456'
  })
});
```

---

## 🔑 Redis存储结构

### Key格式
```
rsa:key:{sessionId}
```

### 示例
```
sessionId: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Redis Key: rsa:key:a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

### Value结构
```json
{
  "publicKey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...\n-----END PUBLIC KEY-----",
  "privateKey": "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw...\n-----END PRIVATE KEY-----",
  "createdAt": 1234567890123
}
```

### TTL
- **默认**: 300秒（5分钟）
- **续期**: 每次使用时重置为300秒

---

## 🧪 测试验证

### 测试1：验证sessionId一致性

```bash
# 1. 生成sessionId
SESSION_ID=$(uuidgen)
echo "SessionId: $SESSION_ID"

# 2. 获取公钥
curl -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\": \"$SESSION_ID\"}"

# 3. 验证密钥（使用同一个sessionId）
curl -X POST http://localhost:8080/auth/is_rsa_valid \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"publicKey\": \"$(从步骤2获取的公钥)\"
  }"

# 4. 检查Redis
redis-cli GET "rsa:key:$SESSION_ID"
```

**预期结果**：
- ✅ Redis中存在key `rsa:key:{SESSION_ID}`
- ✅ 响应中的sessionId与请求中的sessionId相同
- ✅ 没有生成新的sessionId

---

### 测试2：验证密钥重新生成

```bash
# 1. 获取公钥
SESSION_ID="test-session-123"
curl -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\": \"$SESSION_ID\"}"

# 记录公钥1
PUBLIC_KEY_1="..."

# 2. 使用错误的公钥验证（触发重新生成）
curl -X POST http://localhost:8080/auth/is_rsa_valid \
  -H "Content-Type: application/json" \
  -d "{
    \"sessionId\": \"$SESSION_ID\",
    \"publicKey\": \"WRONG_PUBLIC_KEY\"
  }"

# 3. 检查Redis（应该还是同一个key）
redis-cli GET "rsa:key:$SESSION_ID"

# 4. 响应中会包含新的公钥
PUBLIC_KEY_2="..."  # 从响应中获取

# 5. 验证PUBLIC_KEY_1 != PUBLIC_KEY_2
# 6. 验证Redis key仍然是 rsa:key:$SESSION_ID（没有变化）
```

**预期结果**：
- ✅ Redis key不变：`rsa:key:test-session-123`
- ✅ 公钥改变：`PUBLIC_KEY_1 != PUBLIC_KEY_2`
- ✅ sessionId不变：始终是 `test-session-123`

---

## ⚠️ 常见错误

### 错误1：前端从响应中读取sessionId并覆盖
```javascript
// ❌ 错误
const response = await fetch('/auth/is_rsa_valid', {...});
const data = await response.json();
sessionId = data.sessionId;  // 不必要且容易混淆
```

**修正**：
```javascript
// ✅ 正确
const response = await fetch('/auth/is_rsa_valid', {...});
const data = await response.json();
// 不需要更新sessionId，它永远不会改变
```

---

### 错误2：每次请求都生成新的sessionId
```javascript
// ❌ 错误
async function getPublicKey() {
  const sessionId = crypto.randomUUID();  // 每次都生成新的
  await fetch('/auth/rsa-key', { body: { sessionId } });
}

async function validateKey() {
  const sessionId = crypto.randomUUID();  // ❌ 又生成新的
  await fetch('/auth/is_rsa_valid', { body: { sessionId } });
}
```

**修正**：
```javascript
// ✅ 正确
let sessionId = null;

async function getPublicKey() {
  if (!sessionId) {
    sessionId = crypto.randomUUID();  // 只生成一次
  }
  await fetch('/auth/rsa-key', { body: { sessionId } });
}

async function validateKey() {
  // 使用同一个sessionId
  await fetch('/auth/is_rsa_valid', { body: { sessionId } });
}
```

---

### 错误3：混淆Cookie中的sessionId和请求体中的sessionId
```javascript
// ❌ 错误：从Cookie读取sessionId
function getSessionIdFromCookie() {
  return document.cookie
    .split('; ')
    .find(row => row.startsWith('rsa_session_id='))
    ?.split('=')[1];
}

const sessionId = getSessionIdFromCookie();  // 可能与实际使用的不同步
```

**修正**：
```javascript
// ✅ 正确：使用自己生成的sessionId
const sessionId = localStorage.getItem('my_session_id') || crypto.randomUUID();
localStorage.setItem('my_session_id', sessionId);
```

---

## 📊 流程图

### 正确的流程
```mermaid
sequenceDiagram
    participant Frontend as 前端
    participant Backend as 后端
    participant Redis as Redis

    Note over Frontend: 1. 生成sessionId（只一次）
    Frontend->>Frontend: sessionId = UUID()
    
    Note over Frontend,Backend: 2. 获取公钥
    Frontend->>Backend: POST /auth/rsa-key<br/>{sessionId}
    Backend->>Redis: SET rsa:key:{sessionId} = 密钥对
    Backend-->>Frontend: {publicKey}
    
    Note over Frontend,Backend: 3. 验证密钥（可选）
    Frontend->>Backend: POST /auth/is_rsa_valid<br/>{sessionId, publicKey}
    Backend->>Redis: GET rsa:key:{sessionId}
    alt 密钥有效
        Backend->>Redis: EXPIRE rsa:key:{sessionId} 300
        Backend-->>Frontend: {valid: true, sessionId}
    else 密钥无效
        Backend->>Backend: 生成新密钥对
        Backend->>Redis: SET rsa:key:{sessionId} = 新密钥对
        Backend-->>Frontend: {valid: false, newPublicKey, sessionId}
    end
    
    Note over Frontend,Backend: 4. 修改邮箱/手机号
    Frontend->>Frontend: 加密敏感数据
    Frontend->>Backend: POST /profile/email/set<br/>{sessionId, encryptedEmail}
    Backend->>Redis: GET rsa:key:{sessionId}
    Backend->>Backend: 解密数据
    Backend->>Backend: 更新数据库
    Backend->>Redis: DEL rsa:key:{sessionId}
    Backend-->>Frontend: {success: true}
```

### 关键点
- ✅ sessionId只在第一步生成
- ✅ 所有后续请求都使用同一个sessionId
- ✅ Redis key始终是 `rsa:key:{sessionId}`
- ✅ 即使重新生成密钥对，sessionId也不变

---

## 🎯 总结

### 后端实现
- ✅ **完全正确**：始终使用前端传递的sessionId
- ✅ **不会生成新sessionId**：即使重新生成密钥对
- ✅ **Redis key一致**：`rsa:key:{前端传递的sessionId}`

### 前端注意事项
- ⚠️ **只生成一次sessionId**：不要在每次请求时生成新的
- ⚠️ **不要从响应中覆盖sessionId**：响应中的sessionId只是回显
- ⚠️ **不要依赖Cookie中的sessionId**：使用自己生成的
- ✅ **保持一致性**：所有请求都使用同一个sessionId

### 如果仍有问题
请检查：
1. 前端是否在某处重新生成了sessionId
2. 前端是否从响应中读取并覆盖了sessionId
3. 前端是否正确地在所有请求中使用同一个sessionId
4. 浏览器控制台日志，查看实际发送的sessionId值

---

**文档版本**: v1.0  
**创建日期**: 2026-05-02  
**作者**: Lingma AI Assistant  
**状态**: ✅ 后端实现正确，待前端检查
