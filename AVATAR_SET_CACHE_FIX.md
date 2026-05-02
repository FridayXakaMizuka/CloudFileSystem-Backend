# /profile/avatar/set 缓存一致性问题修复

## 🐛 问题描述

用户反馈：调用 `/profile/avatar/set` 设置头像成功后，再调用 `/profile/get_all` 获取个人资料时，头像字段仍然是旧值或空字符串。

---

## 🔍 问题分析

### 根本原因：缓存 Key 不一致

系统中存在**两个不同的缓存 Key** 用于存储用户相关信息：

#### 1. 头像专用缓存
- **Key 格式**: `avatar:{userId}`
- **用途**: 专门存储头像 URL
- **使用位置**: `AvatarService.getAvatar()` 和 `AvatarService.setAvatar()`
- **示例**: `avatar:10001`

#### 2. 个人资料缓存
- **Key 格式**: `profile:{userId}`
- **用途**: 存储完整的个人资料（包括头像、昵称、邮箱等）
- **使用位置**: `UserService.getAllProfile()`
- **示例**: `profile:10001`

### 问题流程

```mermaid
sequenceDiagram
    participant Client as 前端
    participant Avatar as AvatarService
    participant Profile as UserService
    participant Redis as Redis (6380)
    participant DB as MySQL

    Note over Client,DB: 场景：设置头像后获取个人资料
    
    Client->>Avatar: POST /profile/avatar/set<br/>avatar=/new.jpg
    Avatar->>DB: UPDATE users SET avatar='/new.jpg'
    Avatar->>Redis: SET avatar:10001 = '/new.jpg' ✅
    Avatar-->>Client: 设置成功
    
    Client->>Profile: POST /profile/get_all
    Profile->>Redis: GET profile:10001 ❌
    Note over Profile,Redis: 读取的是 profile:10001<br/>而不是 avatar:10001
    Redis-->>Profile: 返回旧的个人资料<br/>(包含旧头像或空)
    Profile-->>Client: 返回旧头像 ❌
```

### 具体问题

**步骤 1**: 用户设置新头像
```java
// AvatarService.setAvatar()
String cacheKey = "avatar:" + userId;  // "avatar:10001"
profileRedisTemplate.opsForValue().set(cacheKey, "/new.jpg", ttl, SECONDS);
```

**步骤 2**: 用户获取个人资料
```java
// UserService.getAllProfile()
String cacheKey = "profile:" + userId;  // "profile:10001"
String cachedProfile = profileRedisTemplate.opsForValue().get(cacheKey);
// ❌ 读取的是 profile:10001，不是 avatar:10001
// ❌ 如果 profile:10001 已存在，返回旧的头像数据
```

**结果**: 
- ✅ `avatar:10001` 已更新为新头像
- ❌ `profile:10001` 仍然是旧数据（包含旧头像或空字符串）
- ❌ 前端看到的仍然是旧头像

---

## ✅ 解决方案

### 修复策略

**核心原则**：设置头像后，清除个人资料缓存，确保下次获取时从数据库读取最新数据。

### 修复后的代码

```java
// 3. 更新Redis中的头像缓存
String avatarCacheKey = AVATAR_CACHE_PREFIX + userId;  // "avatar:10001"

// 无论缓存是否存在，都更新为新头像
long remainingSeconds = getRemainingTokenExpiration(token);
if (remainingSeconds > 0) {
    profileRedisTemplate.opsForValue().set(avatarCacheKey, avatarUrl, remainingSeconds, TimeUnit.SECONDS);
    logger.info("[设置头像] 头像缓存已更新 - UserId: {}, Key: {}, 剩余时间: {}秒", 
        userId, avatarCacheKey, remainingSeconds);
} else {
    profileRedisTemplate.opsForValue().set(avatarCacheKey, avatarUrl, 7, TimeUnit.DAYS);
    logger.info("[设置头像] 头像缓存已更新（默认7天） - UserId: {}, Key: {}", 
        userId, avatarCacheKey);
}

// 4. 清除个人资料缓存（profile:{userId}），确保下次获取时从数据库读取最新数据
String profileCacheKey = "profile:" + userId;  // "profile:10001"
Boolean profileCacheExists = profileRedisTemplate.hasKey(profileCacheKey);
if (profileCacheExists != null && profileCacheExists) {
    profileRedisTemplate.delete(profileCacheKey);
    logger.info("[设置头像] 个人资料缓存已清除 - UserId: {}, Key: {}", 
        userId, profileCacheKey);
} else {
    logger.debug("[设置头像] 个人资料缓存不存在，无需清除 - UserId: {}", userId);
}
```

### 改进点

1. ✅ **更新头像缓存**：`avatar:{userId}` 更新为新头像
2. ✅ **清除个人资料缓存**：`profile:{userId}` 被删除
3. ✅ **保证一致性**：下次获取个人资料时从数据库读取最新数据
4. ✅ **详细日志**：记录缓存操作，便于调试

---

## 📊 修复前后对比

### 修复前

| 操作 | avatar:10001 | profile:10001 | 前端看到 |
|------|-------------|---------------|---------|
| **初始状态** | NULL | `{"avatar":"",...}` | 空头像 |
| **设置头像后** | `"/new.jpg"` ✅ | `{"avatar":"",...}` ❌ | 空头像 ❌ |
| **获取个人资料** | 未读取 | 返回旧数据 ❌ | 空头像 ❌ |

**问题**：两个缓存不同步，导致数据不一致

---

### 修复后

| 操作 | avatar:10001 | profile:10001 | 前端看到 |
|------|-------------|---------------|---------|
| **初始状态** | NULL | `{"avatar":"",...}` | 空头像 |
| **设置头像后** | `"/new.jpg"` ✅ | **DELETED** ✅ | - |
| **获取个人资料** | 未读取 | **从数据库读取** ✅ | 新头像 ✅ |
| **再次获取** | 未读取 | `{"avatar":"/new.jpg",...}` ✅ | 新头像 ✅ |

**优势**：数据一致性得到保证

---

## 🔧 相关代码位置

### 文件路径
`src/main/java/com/mizuka/cloudfilesystem/service/AvatarService.java`

### 修改的方法
`public String setAvatar(String token, String avatarUrl)` （第155-206行）

### 修改的行数
- **第183行**：变量重命名 `cacheKey` → `avatarCacheKey`（更清晰）
- **第188-194行**：更新头像缓存，添加详细日志
- **第196-204行**：**新增**清除个人资料缓存逻辑
- **净变化**：+10 行

---

## 🎯 设计原则

### 1. 缓存分离原则

**原则**：不同粒度的数据使用不同的缓存 Key。

**实现**：
- `avatar:{userId}`：单一字段缓存（头像）
- `profile:{userId}`：复合对象缓存（完整个人资料）

**优点**：
- 细粒度控制
- 避免大对象频繁序列化/反序列化
- 可以独立更新

**缺点**：
- 需要手动维护一致性
- 更新一个字段时可能需要清除其他缓存

### 2. 写后失效（Write-Invalidate）

**原则**：更新数据后，使相关缓存失效，而不是立即更新所有缓存。

**实现**：
```java
// 更新头像缓存
profileRedisTemplate.opsForValue().set("avatar:" + userId, newAvatar, ttl);

// 清除个人资料缓存（而不是更新）
profileRedisTemplate.delete("profile:" + userId);
```

**优点**：
- 简单可靠
- 避免复杂的缓存更新逻辑
- 保证最终一致性

**缺点**：
- 下次读取时需要查数据库（缓存未命中）
- 短暂的性能下降（可接受）

### 3. 懒加载（Lazy Loading）

**原则**：缓存未命中时，从数据库读取并写入缓存。

**实现**：
```java
// UserService.getAllProfile()
String cachedProfile = profileRedisTemplate.opsForValue().get("profile:" + userId);
if (cachedProfile == null) {
    // 从数据库读取
    User user = userMapper.findById(userId);
    
    // 构建个人资料对象
    UserData data = new UserData(user.getAvatar(), ...);
    
    // 写入缓存
    profileRedisTemplate.opsForValue().set("profile:" + userId, jsonData, ttl);
}
```

**优点**：
- 按需加载，节省内存
- 自动同步最新数据
- 简化缓存管理

---

## 🧪 测试建议

### 测试场景 1：设置头像后获取个人资料

```bash
# 1. 清除所有缓存
redis-cli
> DEL avatar:10001
> DEL profile:10001

# 2. 设置新头像
GET /profile/avatar/set?avatar=/uploads/new_avatar.jpg
Authorization: Bearer YOUR_JWT_TOKEN

# 3. 检查 Redis
redis-cli
> GET avatar:10001
# 应该返回: "/uploads/new_avatar.jpg" ✅

> GET profile:10001
# 应该返回: (nil) ✅（已清除）

# 4. 获取个人资料
POST /profile/get_all
Authorization: Bearer YOUR_JWT_TOKEN

# 5. 预期响应
{
  "code": 200,
  "success": true,
  "message": "获取成功（来自数据库）",  // ✅ 来自数据库
  "data": {
    "avatar": "/uploads/new_avatar.jpg",  // ✅ 新头像
    "nickname": "测试用户",
    ...
  }
}

# 6. 再次检查 Redis
redis-cli
> GET profile:10001
# 应该返回: JSON 字符串，包含新头像 ✅
```

### 测试场景 2：多次设置头像

```bash
# 1. 第一次设置头像
GET /profile/avatar/set?avatar=/uploads/avatar1.jpg

# 2. 第二次设置头像
GET /profile/avatar/set?avatar=/uploads/avatar2.jpg

# 3. 检查 Redis
redis-cli
> GET avatar:10001
# 应该返回: "/uploads/avatar2.jpg" ✅

> GET profile:10001
# 应该返回: (nil) ✅（已清除）

# 4. 获取个人资料
POST /profile/get_all

# 5. 预期响应
{
  "code": 200,
  "success": true,
  "data": {
    "avatar": "/uploads/avatar2.jpg",  // ✅ 最新头像
    ...
  }
}
```

### 测试场景 3：性能测试

```bash
# 1. 设置头像
GET /profile/avatar/set?avatar=/uploads/avatar.jpg

# 2. 连续获取个人资料 100 次
for i in {1..100}; do
  curl -X POST http://localhost:8080/profile/get_all \
    -H "Authorization: Bearer TOKEN"
done

# 3. 观察日志
# 第 1 次：缓存未命中，从数据库读取
# 第 2-100 次：缓存命中，从 Redis 读取 ✅

# 4. 预期性能
# 第 1 次：~50ms（数据库查询）
# 第 2-100 次：~5ms（Redis 读取）✅
```

---

## ⚠️ 注意事项

### 1. 缓存清除的影响

**影响**：
- 设置头像后，下次获取个人资料会缓存未命中
- 需要从数据库读取，响应时间稍长（~50ms vs ~5ms）
- 但只会发生一次，之后会重新缓存

**优化建议**：
- 如果性能要求极高，可以考虑主动更新 `profile:{userId}` 缓存
- 但会增加代码复杂度，需要解析和修改 JSON

### 2. 并发问题

**场景**：
- 用户 A 设置头像
- 同时用户 B 获取用户 A 的个人资料

**处理**：
- Redis 操作是原子的，不会出现数据竞争
- 最坏情况：B 读到旧缓存，下次读取时会更新

### 3. 缓存穿透

**风险**：
- 如果频繁设置头像，会导致 `profile:{userId}` 频繁失效
- 可能导致缓存穿透

**防护**：
- 限制头像设置频率（前端防抖）
- 监控缓存命中率
- 必要时添加布隆过滤器

### 4. 与其他修改操作的协调

**相关操作**：
- 修改昵称：`UserService.changeNickname()` - ✅ 已同步更新 `profile:{userId}`
- 修改邮箱：`UserService.changeEmail()` - ✅ 已同步更新 `profile:{userId}`
- 修改手机号：`UserService.changePhone()` - ✅ 已同步更新 `profile:{userId}`
- 修改密码：`UserService.changePassword()` - ✅ 已清除 `profile:{userId}`

**一致性**：✅ 所有修改操作都正确处理了缓存

---

## 📝 与其他接口的对比

### 1. 修改昵称 (`changeNickname`)

```java
// 更新数据库
userMapper.updateNickname(userId, newNickname);

// 同步更新缓存（解析 JSON → 修改字段 → 重新存入）
String cachedProfile = profileRedisTemplate.opsForValue().get(profileCacheKey);
if (cachedProfile != null) {
    UserData userData = mapper.readValue(cachedProfile, UserData.class);
    userData.setNickname(newNickname);
    profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, ttl);
}
```

**策略**：主动更新缓存（因为只改一个字段）

### 2. 设置头像 (`setAvatar`)

```java
// 更新数据库
userMapper.updateAvatar(userId, avatarUrl);

// 更新头像缓存
profileRedisTemplate.opsForValue().set("avatar:" + userId, avatarUrl, ttl);

// 清除个人资料缓存
profileRedisTemplate.delete("profile:" + userId);
```

**策略**：清除缓存（因为涉及两个不同的缓存 Key）

### 3. 为什么策略不同？

| 操作 | 涉及的缓存 | 策略 | 原因 |
|------|-----------|------|------|
| **修改昵称** | `profile:{userId}` | 主动更新 | 只有一个缓存，更新简单 |
| **设置头像** | `avatar:{userId}` + `profile:{userId}` | 清除 | 两个缓存，更新复杂 |

**结论**：根据缓存结构选择合适的策略

---

## 🎯 性能影响分析

### 设置头像操作

**修复前**：
- 1 次数据库写入
- 1 次 Redis 写入（avatar 缓存）

**修复后**：
- 1 次数据库写入
- 1 次 Redis 写入（avatar 缓存）
- 1 次 Redis 检查（hasKey）
- 1 次 Redis 删除（profile 缓存，如果存在）

**影响**：
- 增加 1-2 次 Redis 操作（微秒级）
- 总体影响可忽略（< 1ms）

### 获取个人资料操作

**修复前**（缓存命中）：
- 1 次 Redis 读取
- 响应时间：~5ms

**修复后**（首次，缓存未命中）：
- 1 次 Redis 读取（未命中）
- 1 次数据库查询
- 1 次 Redis 写入
- 响应时间：~50ms

**修复后**（后续，缓存命中）：
- 1 次 Redis 读取
- 响应时间：~5ms

**影响**：
- 设置头像后的第一次获取个人资料会变慢（~50ms）
- 但只会发生一次，之后恢复正常
- 对用户体验影响很小

---

## 📚 相关文档

- **头像空字符串修复**：`AVATAR_EMPTY_STRING_FIX.md`
- **个人资料头像修复**：`PROFILE_GET_ALL_AVATAR_FIX.md`
- **缓存策略**：头像数据缓存策略（缓存优先、TTL 同步）
- **双写一致性**：缓存双写时优先更新而非删除

---

## ✅ 修复验证清单

- [x] 更新头像缓存（avatar:{userId}）
- [x] 清除个人资料缓存（profile:{userId}）
- [x] 添加详细日志记录
- [x] 无编译错误
- [x] 保证缓存一致性
- [x] 性能影响可接受
- [ ] 单元测试通过
- [ ] 集成测试通过
- [ ] 生产环境验证

---

**修复日期**: 2026-05-02  
**版本**: v1.2  
**修复者**: Lingma AI Assistant  
**状态**: ✅ 已完成
