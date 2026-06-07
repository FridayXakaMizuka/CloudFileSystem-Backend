# 文件恢复进程管理后端参考文档

## 概述

本文档描述了文件/文件夹从回收站恢复时的进程管理机制。该机制允许用户在恢复大型目录结构时继续执行其他操作，同时通过前端实时查看恢复进度。

## 核心设计

### 1. 架构思路

- **异步恢复**：恢复操作立即返回，后端在后台异步执行实际的恢复任务
- **进程追踪**：使用 Redis ZSet 存储每个用户的恢复进程信息
- **限流控制**：全局限制并发 I/O 操作（约 1000 IOPS）
- **前端轮询**：前端定期查询恢复进程状态，提供实时反馈

### 2. 技术栈

- **Redis ZSet**：存储恢复会话，score 为时间戳，member 为进程 ID
- **独立 SessionId**：每个恢复进程分配唯一的 sessionId，用于标识和追踪
- **后台线程池**：处理异步恢复任务，避免阻塞主请求

---

## API 接口规范

### 1. 恢复节点（启动恢复进程）

**接口**: `POST /files/recycle/restore?nodeId={nodeId}&nodeType={nodeType}`

**功能**: 从回收站恢复文件或文件夹，创建后台恢复进程

#### 请求参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `nodeId` | Long | ✅ | 节点ID（文件或文件夹） |
| `nodeType` | Integer | ✅ | 节点类型（0=文件夹，1=文件） |

#### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "已开始恢复，请稍后查看进度",
  "data": {
    "processId": "proc_1234567890",
    "nodeName": "documents",
    "sessionId": "sess_restore_abc123"
  }
}
```

#### 后端处理流程

1. **验证权限**：检查用户是否拥有该节点的恢复权限
2. **生成进程ID**：创建唯一的 processId（格式：`proc_{timestamp}_{random}`）
3. **分配 SessionId**：为该进程分配独立的 sessionId（格式：`sess_restore_{uuid}`）
4. **存入 Redis**：
   ```redis
   ZADD user:{userId}:restore_processes {timestamp} {
     "processId": "proc_1234567890",
     "nodeName": "documents",
     "nodeId": 12345,
     "nodeType": 0,
     "sessionId": "sess_restore_abc123",
     "startTime": "2026-06-03T10:30:00Z",
     "status": "running"
   }
   ```
5. **启动后台任务**：在线程池中异步执行恢复逻辑
6. **立即返回**：不等待恢复完成，直接返回进程信息

#### 恢复逻辑（后台异步执行）

```java
@Async
public void restoreNodeAsync(Long nodeId, Integer nodeType, String processId, String sessionId) {
    try {
        // 1. 获取节点信息
        Node node = nodeRepository.findById(nodeId);
        
        // 2. 确定恢复目标路径
        String targetPath;
        if (originalParentExists(node.originalParentId)) {
            targetPath = node.originalPath;
        } else {
            targetPath = userRootPath + "/" + node.name;
        }
        
        // 3. 如果是文件夹，递归恢复子节点
        if (nodeType == 0) { // folder
            restoreFolderRecursive(node, targetPath, sessionId);
        } else {
            restoreFile(node, targetPath, sessionId);
        }
        
        // 4. 更新进程状态为完成
        updateProcessStatus(processId, "completed");
        
        // 5. 从 Redis 中移除完成的进程
        removeProcessFromRedis(processId);
        
    } catch (Exception e) {
        log.error("恢复失败: processId={}", processId, e);
        updateProcessStatus(processId, "failed", e.getMessage());
        removeProcessFromRedis(processId);
    }
}
```

#### 限流控制

```java
// 使用 Semaphore 或 RateLimiter 控制并发
private static final Semaphore IO_SEMAPHORE = new Semaphore(1000);

public void restoreWithLimit() {
    IO_SEMAPHORE.acquire(); // 获取许可
    try {
        // 执行 I/O 密集型恢复操作
        performRestore();
    } finally {
        IO_SEMAPHORE.release(); // 释放许可
    }
}
```

---

### 2. 获取恢复进程列表

**接口**: `GET /files/recycle/restore/processes`

**功能**: 获取当前用户所有正在进行的恢复进程

#### 请求头

```
Authorization: Bearer {jwt_token}
```

#### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "成功",
  "data": [
    {
      "processId": "proc_1234567890",
      "nodeName": "documents",
      "nodeId": 12345,
      "nodeType": 0,
      "sessionId": "sess_restore_abc123",
      "startTime": "2026-06-03T10:30:00Z",
      "status": "running",
      "progress": {
        "totalFiles": 150,
        "restoredFiles": 45,
        "totalFolders": 20,
        "restoredFolders": 8
      }
    },
    {
      "processId": "proc_0987654321",
      "nodeName": "photos",
      "nodeId": 67890,
      "nodeType": 0,
      "sessionId": "sess_restore_xyz789",
      "startTime": "2026-06-03T10:32:00Z",
      "status": "running",
      "progress": {
        "totalFiles": 500,
        "restoredFiles": 120,
        "totalFolders": 50,
        "restoredFolders": 15
      }
    }
  ]
}
```

#### 后端处理流程

1. **从 JWT 提取 userId**
2. **查询 Redis**：
   ```redis
   ZRANGEBYSCORE user:{userId}:restore_processes -inf +inf WITHSCORES
   ```
3. **解析进程信息**：将 JSON 字符串反序列化为对象列表
4. **清理过期进程**：移除超过 24 小时的僵尸进程
5. **返回进程列表**

#### Java 实现示例

```java
@GetMapping("/files/recycle/restore/processes")
public ResponseEntity<ApiResponse<List<RestoreProcess>>> getRestoreProcesses(
    @RequestHeader("Authorization") String token) {
    
    Long userId = JwtUtil.extractUserId(token);
    String redisKey = "user:" + userId + ":restore_processes";
    
    // 从 Redis 获取进程列表
    Set<String> processes = redisTemplate.opsForZSet().range(redisKey, 0, -1);
    
    List<RestoreProcess> processList = processes.stream()
        .map(json -> JSON.parseObject(json, RestoreProcess.class))
        .filter(p -> !isExpired(p.getStartTime())) // 过滤过期进程
        .collect(Collectors.toList());
    
    return ResponseEntity.ok(ApiResponse.success(processList));
}
```

---

## Redis 数据结构设计

### 1. 键名规范

```
user:{userId}:restore_processes
```

示例：`user:10001:restore_processes`

### 2. ZSet 结构

- **Key**: `user:10001:restore_processes`
- **Member**: JSON 字符串（进程信息）
- **Score**: 开始时间戳（用于排序和清理）

```redis
ZADD user:10001:restore_processes 1717387800 "{
  \"processId\": \"proc_1234567890\",
  \"nodeName\": \"documents\",
  \"nodeId\": 12345,
  \"nodeType\": 0,
  \"sessionId\": \"sess_restore_abc123\",
  \"startTime\": \"2026-06-03T10:30:00Z\",
  \"status\": \"running\"
}"
```

### 3. 进程信息字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `processId` | String | 进程唯一标识 |
| `nodeName` | String | 节点名称（用于前端显示） |
| `nodeId` | Long | 节点ID |
| `nodeType` | Integer | 节点类型（0=文件夹，1=文件） |
| `sessionId` | String | 独立会话ID |
| `startTime` | String | ISO 8601 格式的开始时间 |
| `status` | String | 状态（running/completed/failed） |
| `progress` | Object | 进度信息（可选） |

### 4. 自动清理策略

```java
// 定时任务：每小时清理一次
@Scheduled(fixedRate = 3600000)
public void cleanupExpiredProcesses() {
    long cutoffTime = System.currentTimeMillis() - 24 * 3600 * 1000; // 24小时前
    
    Set<String> keys = redisTemplate.keys("user:*:restore_processes");
    for (String key : keys) {
        // 移除 score < cutoffTime 的成员
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, cutoffTime);
    }
}
```

---

## SessionId 管理

### 1. 生成规则

```java
public String generateRestoreSessionId() {
    return "sess_restore_" + UUID.randomUUID().toString().replace("-", "");
}
```

### 2. 生命周期

- **创建时机**：调用恢复接口时生成
- **存储位置**：Redis 进程信息中
- **清除时机**：
  - 恢复完成时自动清除
  - 恢复失败时自动清除
  - 用户退出登录时清除所有活跃进程
  - 24 小时后自动清理（防止僵尸进程）

### 3. 用途

- **日志追踪**：所有恢复操作的日志都带上 sessionId
- **权限隔离**：不同进程的恢复操作互不干扰
- **进度关联**：前端通过 sessionId 关联进度信息

---

## 前端集成要点

### 1. 恢复流程

```javascript
// 1. 发起恢复请求
const result = await restoreNode(file.id, nodeType)

if (result.success) {
  // 2. 从列表中移除该文件
  recycleBinState.state.files = files.filter(f => f.id !== file.id)
  
  // 3. 刷新恢复进程列表
  await fetchRestoreProcesses()
  
  // 4. 如果有进程在运行，启动轮询（每 3 秒）
  if (restoreProcesses.value.length > 0) {
    startRestorePolling()
  }
}
```

### 2. 轮询机制

```javascript
const startRestorePolling = () => {
  restorePollingTimer.value = setInterval(async () => {
    await fetchRestoreProcesses()
    
    // 如果所有进程都完成了，停止轮询
    if (restoreProcesses.value.length === 0) {
      stopRestorePolling()
      showInfo('所有恢复任务已完成')
      showRestoreModal.value = false
    }
  }, 3000) // 每 3 秒轮询一次
}
```

### 3. UI 展示

- **提示文字**：在回收站标题下方显示 `{count}个恢复任务正在进行中`
- **点击弹窗**：点击提示文字弹出模态框，显示详细进程列表
- **遮罩层**：半透明黑色背景（30% 不透明度），点击关闭弹窗
- **加载动画**：圆圈旋转动画表示进行中
- **淡入淡出**：弹窗出现/消失时有平滑过渡动画

---

## 错误处理

### 1. 常见错误场景

| 错误场景 | HTTP 状态码 | 错误消息 |
|----------|------------|---------|
| 节点不存在 | 404 | "节点不存在或已被删除" |
| 无权限恢复 | 403 | "您没有权限恢复此节点" |
| 节点不在回收站 | 400 | "该节点不在回收站中" |
| Redis 连接失败 | 500 | "系统繁忙，请稍后重试" |
| 限流触发 | 429 | "当前恢复任务过多，请稍后重试" |

### 2. 错误响应格式

```json
{
  "code": 403,
  "success": false,
  "message": "您没有权限恢复此节点",
  "data": null
}
```

---

## 性能优化建议

### 1. 批量恢复优化

对于包含大量子节点的文件夹：

```java
// 分批处理，每批 100 个节点
int batchSize = 100;
List<Node> children = nodeRepository.findChildren(folderId);

for (int i = 0; i < children.size(); i += batchSize) {
    List<Node> batch = children.subList(i, Math.min(i + batchSize, children.size()));
    restoreBatch(batch, sessionId);
    
    // 更新进度
    updateProgress(processId, i + batchSize, children.size());
}
```

### 2. Redis 管道操作

```java
// 使用 Pipeline 批量写入 Redis
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (RestoreProcess process : processes) {
        connection.zAdd(
            redisKey.getBytes(),
            process.getStartTime().getTime(),
            JSON.toJSONString(process).getBytes()
        );
    }
    return null;
});
```

### 3. 缓存进程列表

```java
// 缓存进程列表 5 秒，减少 Redis 查询压力
@Cacheable(value = "restoreProcesses", key = "#userId", unless = "#result.isEmpty()")
public List<RestoreProcess> getProcesses(Long userId) {
    // ... 查询逻辑
}
```

---

## 监控与日志

### 1. 关键日志点

```java
log.info("启动恢复进程: processId={}, nodeId={}, userId={}", 
    processId, nodeId, userId);

log.info("恢复完成: processId={}, nodeName={}, duration={}ms", 
    processId, nodeName, duration);

log.error("恢复失败: processId={}, error={}", 
    processId, e.getMessage(), e);
```

### 2. 监控指标

- **活跃进程数**：`ZCARD user:{userId}:restore_processes`
- **平均恢复时间**：统计 completed 进程的 duration
- **失败率**：统计 failed 进程占比
- **限流触发次数**：记录 Semaphore 拒绝次数

---

## 测试用例

### 1. 单元测试

```java
@Test
public void testRestoreNodeCreatesProcess() {
    // 调用恢复接口
    ApiResponse response = restoreService.restore(12345L, 0, userId);
    
    // 验证进程已创建
    assertTrue(response.isSuccess());
    assertNotNull(response.getData().getProcessId());
    
    // 验证 Redis 中有记录
    String redisKey = "user:" + userId + ":restore_processes";
    Long count = redisTemplate.opsForZSet().size(redisKey);
    assertEquals(1L, count.longValue());
}
```

### 2. 集成测试

```java
@Test
public void testRestoreProcessLifecycle() throws InterruptedException {
    // 1. 启动恢复
    String processId = startRestore(nodeId, nodeType);
    
    // 2. 查询进程列表
    List<RestoreProcess> processes = getProcesses();
    assertTrue(processes.stream()
        .anyMatch(p -> p.getProcessId().equals(processId)));
    
    // 3. 等待恢复完成
    Thread.sleep(5000);
    
    // 4. 验证进程已移除
    processes = getProcesses();
    assertFalse(processes.stream()
        .anyMatch(p -> p.getProcessId().equals(processId)));
}
```

---

## 接口变更总结

### 新增接口

1. **`POST /files/recycle/restore?nodeId={nodeId}&nodeType={nodeType}`**
   - 原接口可能已存在，需要改为异步模式
   - 新增返回值：`processId`, `sessionId`

2. **`GET /files/recycle/restore/processes`**
   - 全新接口，用于查询恢复进程列表

### 修改说明

- **恢复接口行为变更**：从同步等待改为异步立即返回
- **响应结构扩展**：增加进程追踪相关字段
- **新增查询接口**：支持前端轮询获取进度

---

## 附录：完整数据流图

```
用户点击"还原"
    ↓
前端发送 POST /files/recycle/restore
    ↓
后端验证权限 → 生成 processId & sessionId
    ↓
存入 Redis ZSet → 启动后台异步任务
    ↓
立即返回 {processId, sessionId, nodeName}
    ↓
前端从回收站列表移除该文件
    ↓
前端启动轮询（每 3 秒）→ GET /files/recycle/restore/processes
    ↓
后端从 Redis 读取进程列表 → 返回给前端
    ↓
前端显示弹窗："{count}个恢复任务正在进行中"
    ↓
后台任务完成恢复 → 从 Redis 移除进程
    ↓
前端轮询发现进程数为 0 → 停止轮询 → 显示"所有恢复任务已完成"
```

---

## 联系方式

如有疑问或需要进一步的技术支持，请联系后端开发团队。

**文档版本**: v1.0  
**最后更新**: 2026-06-03
