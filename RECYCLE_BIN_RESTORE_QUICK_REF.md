# 回收站恢复功能 - 快速参考

## 🚀 API调用

### 恢复节点

**接口**: `POST /files/recycle/restore`

**请求参数**:
```
batchId: String (必填) - 批次号（UUID格式）
```

**响应示例**:
```json
{
  "code": 200,
  "message": "恢复任务已启动，请稍后查询进度",
  "data": null
}
```

**状态码**:
- 200: 恢复任务已启动
- 401: 未认证
- 403: 无权恢复
- 404: batch不存在
- 500: 恢复失败

---

## 📊 状态查询

### 查询恢复进度

**接口**: `GET /files/recycle/restore/processes`

**响应示例**:
```json
{
  "code": 200,
  "message": "获取成功",
  "data": [
    {
      "batchId": "550e8400-e29b-41d4-a716-446655440000",
      "nodeId": 12345,
      "nodeName": "我的文档",
      "status": 0,
      "totalCount": 156,
      "processedCount": 50,
      "createdAt": "2026-06-07T10:30:00"
    }
  ]
}
```

**status含义**:
- 0: 进行中（restoring）
- 1: 已完成（Restored）
- 2: 失败
- 3: 已终止

---

## 🔑 Redis Key结构

### Batch节点集合（ZSET）

**Key**: `recycle:batch:{batchId}:nodes`

**Member格式**: `{nodeType}:{nodeId}`
- `0:12345` → 文件夹
- `1:67890` → 文件

**Score**: 删除时间戳（毫秒）

**操作示例**:
```redis
# 获取所有节点（按顺序）
ZRANGE recycle:batch:550e8400:nodes 0 -1
# 返回: ["0:12345", "0:12346", "1:12347"]

# 移除已恢复的节点
ZREM recycle:batch:550e8400:nodes "0:12345"

# 获取节点总数
ZCARD recycle:batch:550e8400:nodes
```

---

## 💻 Java代码示例

### 启动异步恢复

```java
@Autowired
private AsyncRecycleBinRestoreService asyncRecycleBinRestoreService;

// 启动恢复任务
asyncRecycleBinRestoreService.asyncRestoreBatch(batchId, userId);
```

### 从ZSET获取节点

```java
@Autowired
private RecycleBinRedisService recycleBinRedisService;

// 获取所有节点
Set<String> members = recycleBinRedisService.getAllNodesFromBatch(batchId).join();

// 遍历并解析
for (String member : members) {
    String[] parts = member.split(":");
    Integer nodeType = Integer.parseInt(parts[0]);  // 0或1
    Long nodeId = Long.parseLong(parts[1]);
    
    // 根据nodeType恢复
    if (nodeType == 0) {
        // 恢复文件夹
    } else {
        // 恢复文件
    }
}
```

### 移除已恢复节点

```java
// 从ZSET移除
recycleBinRedisService.removeNodeFromBatch(batchId, "0:12345").join();
```

---

## 🔄 状态流转

```
删除完成 (status=1, operation_type=0)
    ↓
用户发起恢复请求
    ↓
恢复开始 (status=0, operation_type=1) ← "restoring"
    ↓
恢复进行中 (status=0, processedCount递增)
    ↓
恢复完成 (status=1, operation_type=1) ← "Restored"
    ↓
Redis缓存清理完成
```

---

## ⚠️ 注意事项

### 1. nodeType值定义

| nodeType | 含义 | 对应表 |
|----------|------|--------|
| 0 | 文件夹 | `folder_nodes` |
| 1 | 文件 | `file_nodes` |

### 2. 限流控制

- 默认限制：1000 IOPS
- 触发限流时自动等待
- 不会丢失节点，保留在ZSET中

### 3. 异步执行

- 恢复任务在后台线程池执行
- HTTP请求立即返回
- 通过查询接口获取进度

### 4. 容错处理

- 单个节点失败不影响其他节点
- ZSET已空时仍处理根节点
- 失败时更新任务状态为2

---

## 📈 性能指标

| 指标 | 数值 |
|------|------|
| 恢复速度 | 1000节点/秒 |
| HTTP响应时间 | <100ms |
| Redis操作延迟 | <5ms |
| 完整恢复时间 | N/1000秒（N=节点数） |

---

## 🔗 相关文档

1. **实施报告**: [RECYCLE_BIN_RESTORE_IMPLEMENTATION_REPORT.md](file://C:\Users\ROG\Desktop\develop\BackEnd\CloudFileSystem\RECYCLE_BIN_RESTORE_IMPLEMENTATION_REPORT.md)
2. **Redis设计**: [RECYCLE_BIN_REDIS_STORAGE_DESIGN.md](file://C:\Users\ROG\Desktop\develop\BackEnd\CloudFileSystem\RECYCLE_BIN_REDIS_STORAGE_DESIGN.md)
3. **ZSET格式参考**: [RECYCLE_BIN_ZSET_MEMBER_FORMAT_QUICK_REF.md](file://C:\Users\ROG\Desktop\develop\BackEnd\CloudFileSystem\RECYCLE_BIN_ZSET_MEMBER_FORMAT_QUICK_REF.md)

---

**最后更新**: 2026-06-07
