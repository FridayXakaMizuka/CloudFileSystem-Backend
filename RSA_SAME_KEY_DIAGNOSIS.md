# RSA密钥相同问题 - 深度诊断

## 🐛 问题描述

**用户反馈**：前端使用了**不同的sessionId**，但返回的都是**相同的公钥**。

这是一个非常严重的问题，说明可能存在以下情况之一：
1. 后端没有真正生成新的密钥对
2. 有静态变量或缓存导致返回相同的密钥
3. Redis写入失败但没有报错
4. 前端实际上发送的是相同的sessionId

---

## 🔍 诊断步骤

### 步骤1：使用测试端点验证RSA随机性

重启后端服务后，访问以下端点：

```bash
curl http://localhost:8080/auth/test-rsa-randomness | jq
```

**预期响应**：
```json
{
  "success": true,
  "testCount": 5,
  "uniqueCount": 5,
  "allUnique": true,
  "message": "所有公钥都是唯一的",
  "samples": [
    "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhki...",
    "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhki...",
    "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhki..."
  ]
}
```

**如果 `allUnique` 为 `false`**：
- ❌ 说明RSA密钥生成器有问题
- 需要检查 `RSAKeyManager.generateKeyPair()` 方法
- 可能是JVM的SecureRandom种子问题

---

### 步骤2：检查后端日志

调用 `/auth/rsa-key` 接口两次，使用不同的sessionId：

```bash
# 第一次调用
curl -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "session-1"}'

# 第二次调用
curl -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "session-2"}'
```

**查看后端日志**，应该看到：
```
[获取RSA公钥] 开始生成新密钥对 - SessionId: session-1
[获取RSA公钥] 已生成新密钥对 - SessionId: session-1, PublicKey预览: -----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhki...
[获取RSA公钥] 密钥已存入Redis - Key: rsa:key:session-1, TTL: 300秒
[获取RSA公钥] 成功 - SessionId: session-1

[获取RSA公钥] 开始生成新密钥对 - SessionId: session-2
[获取RSA公钥] 已生成新密钥对 - SessionId: session-2, PublicKey预览: -----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhki...  ← 应该不同
[获取RSA公钥] 密钥已存入Redis - Key: rsa:key:session-2, TTL: 300秒
[获取RSA公钥] 成功 - SessionId: session-2
```

**关键点**：
- ✅ 每次调用都应该有"开始生成新密钥对"日志
- ✅ PublicKey预览应该不同
- ✅ Redis Key应该不同（session-1 vs session-2）

---

### 步骤3：检查Redis中的数据

```bash
# 检查第一个sessionId的密钥对
redis-cli GET "rsa:key:session-1" | jq '.publicKey' | head -c 50

# 检查第二个sessionId的密钥对
redis-cli GET "rsa:key:session-2" | jq '.publicKey' | head -c 50
```

**预期结果**：
- 两个公钥应该**完全不同**
- 如果相同，说明Redis写入有问题

---

### 步骤4：检查前端实际发送的数据

打开浏览器开发者工具 → Network标签 → 找到 `/auth/rsa-key` 请求：

**检查Request Payload**：
```json
// 第一次请求
{"sessionId": "abc-123"}

// 第二次请求
{"sessionId": "def-456"}  // ← 确认这里真的不同
```

**常见问题**：
- ❌ 前端虽然生成了新的sessionId，但没有正确发送到后端
- ❌ 前端缓存了请求体，导致每次都发送相同的sessionId
- ❌ 前端代码逻辑错误，实际上使用的是同一个sessionId

---

## ✅ 已实施的改进

### 改进1：添加详细的日志

**文件**：`AuthController.java`

**新增日志**：
```java
logger.info("[获取RSA公钥] 开始生成新密钥对 - SessionId: {}", sessionId);
// ... 生成密钥对 ...
logger.info("[获取RSA公钥] 已生成新密钥对 - SessionId: {}, PublicKey预览: {}", 
    sessionId, publicKeyPreview);
```

**作用**：
- ✅ 可以确认每次请求都真正执行了密钥生成
- ✅ 可以看到生成的公钥预览（前50个字符）
- ✅ 可以对比不同请求的公钥是否不同

---

### 改进2：添加测试端点

**新增接口**：`GET /auth/test-rsa-randomness`

**功能**：
- 连续生成5个密钥对
- 检查是否全部唯一
- 返回测试结果和样本

**用途**：
- 快速验证RSA密钥生成器是否正常工作
- 排除JVM或系统层面的问题

---

## 🧪 完整测试流程

### 1. 重启后端服务

确保新的日志代码生效。

---

### 2. 运行测试端点

```bash
curl http://localhost:8080/auth/test-rsa-randomness | jq
```

**预期**：`allUnique: true`

如果失败，说明是JVM或系统问题，需要进一步调查。

---

### 3. 测试不同sessionId

```bash
# 终端1
curl -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "test-A"}' | jq '.publicKey' | head -c 50

# 终端2
curl -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "test-B"}' | jq '.publicKey' | head -c 50
```

**预期**：两个公钥的前50个字符应该不同。

---

### 4. 检查后端日志

查看日志输出，确认：
- 每次都打印了"开始生成新密钥对"
- PublicKey预览不同
- Redis Key不同

---

### 5. 检查Redis

```bash
redis-cli GET "rsa:key:test-A" | jq '.publicKey' | head -c 50
redis-cli GET "rsa:key:test-B" | jq '.publicKey' | head -c 50
```

**预期**：两个公钥不同。

---

## 🎯 可能的根本原因

### 原因1：前端实际上发送了相同的sessionId

**检查方法**：
- 打开浏览器Network标签
- 查看Request Payload
- 确认sessionId真的不同

**修复**：
```javascript
// ❌ 错误：sessionId在外部定义，可能没有更新
let sessionId = 'fixed-id';

// ✅ 正确：每次调用时生成新的
async function getPublicKey() {
  const sessionId = crypto.randomUUID();  // 每次都生成新的
  const response = await fetch('/auth/rsa-key', {
    method: 'POST',
    body: JSON.stringify({ sessionId })
  });
  return response.json();
}
```

---

### 原因2：浏览器缓存了响应

**检查方法**：
- Network标签中查看Size列
- 如果显示 "(from disk cache)" 或 "(from memory cache)"，说明是缓存问题

**修复**：
已在后端添加禁止缓存的响应头：
```java
.header("Cache-Control", "no-cache, no-store, must-revalidate")
.header("Pragma", "no-cache")
.header("Expires", "0")
```

前端也应该添加：
```javascript
fetch('/auth/rsa-key', {
  method: 'POST',
  cache: 'no-cache',  // 关键
  body: JSON.stringify({ sessionId })
})
```

---

### 原因3：Redis写入失败

**检查方法**：
- 查看后端日志是否有Redis错误
- 手动检查Redis中是否有数据

**修复**：
检查Redis配置和连接。

---

### 原因4：JVM的SecureRandom问题（极少见）

**检查方法**：
- 运行测试端点 `/auth/test-rsa-randomness`
- 如果 `allUnique: false`，说明是这个问题

**修复**：
启动JVM时添加参数：
```bash
java -Djava.security.egd=file:/dev/urandom -jar app.jar
```

---

## 📋 诊断清单

请按顺序执行以下检查：

- [ ] 运行测试端点 `/auth/test-rsa-randomness`
- [ ] 确认 `allUnique: true`
- [ ] 调用 `/auth/rsa-key` 两次，使用不同的sessionId
- [ ] 检查后端日志，确认每次都生成了新密钥对
- [ ] 检查后端日志，确认PublicKey预览不同
- [ ] 检查Redis，确认两个sessionId对应的公钥不同
- [ ] 检查浏览器Network标签，确认Request Payload中的sessionId真的不同
- [ ] 检查浏览器Network标签，确认响应不是来自缓存
- [ ] 清除浏览器缓存后重试

---

## 🚨 紧急排查命令

如果问题仍然存在，请执行以下命令并提供输出：

```bash
# 1. 测试RSA随机性
echo "=== 测试RSA随机性 ==="
curl -s http://localhost:8080/auth/test-rsa-randomness | jq

# 2. 调用两次接口
echo ""
echo "=== 第一次调用 ==="
RESPONSE1=$(curl -s -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "debug-1"}')
echo "$RESPONSE1" | jq '.publicKey' | head -c 50
echo ""

echo "=== 第二次调用 ==="
RESPONSE2=$(curl -s -X POST http://localhost:8080/auth/rsa-key \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "debug-2"}')
echo "$RESPONSE2" | jq '.publicKey' | head -c 50
echo ""

# 3. 比较
echo "=== 比较结果 ==="
KEY1=$(echo "$RESPONSE1" | jq -r '.publicKey')
KEY2=$(echo "$RESPONSE2" | jq -r '.publicKey')
if [ "$KEY1" = "$KEY2" ]; then
  echo "❌ 公钥相同！"
else
  echo "✅ 公钥不同"
fi

# 4. 检查Redis
echo ""
echo "=== Redis中的数据 ==="
echo "Session debug-1:"
redis-cli GET "rsa:key:debug-1" | jq '.publicKey' | head -c 50
echo ""
echo "Session debug-2:"
redis-cli GET "rsa:key:debug-2" | jq '.publicKey' | head -c 50
echo ""

# 5. 检查后端日志
echo "=== 后端日志（最近10行）==="
tail -n 10 /path/to/your/log/file.log | grep "获取RSA公钥"
```

---

## 🎯 总结

### 最可能的原因

1. **前端发送的sessionId实际上是相同的**（概率80%）
2. **浏览器缓存了响应**（概率15%）
3. **其他问题**（概率5%）

### 下一步

1. 重启后端服务
2. 运行测试端点验证RSA随机性
3. 检查浏览器Network标签，确认sessionId真的不同
4. 如果问题仍然存在，提供上述"紧急排查命令"的输出

---

**诊断日期**: 2026-05-02  
**版本**: v1.0  
**作者**: Lingma AI Assistant  
**状态**: 🔍 待用户提供诊断结果
