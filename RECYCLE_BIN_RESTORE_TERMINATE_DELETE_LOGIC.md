# 回收站恢复功能 - 删除任务终止逻辑补充说明

## 📋 功能概述

在恢复目录时，系统会自动检测对应的batch是否还在删除中。如果检测到删除任务正在进行（`operation_type=0` 且 `status=0`），则会先终止删除任务，然后再进行恢复逻辑。

---

## 🔧 实现细节

### 1. RecycleBinTaskMapper.java（新增方法）

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/mapper/RecycleBinTaskMapper.java`

#### 新增方法: updateTaskOperationType()

```java
/**
 * 更新任务操作类型和状态（用于从删除切换到恢复）
 */
@Update("UPDATE recycle_bin_tasks SET operation_type = #{operationType}, status = #{status}, " +
        "error_message = #{errorMessage}, processed_count = #{processedCount}, " +
        "total_count = #{totalCount}, completed_at = #{completedAt} WHERE batch_id = #{batchId}")
void updateTaskOperationType(@Param("batchId") String batchId,
                              @Param("operationType") Integer operationType,
                              @Param("status") Integer status,
                              @Param("errorMessage") String errorMessage,
                              @Param("processedCount") Integer processedCount,
                              @Param("totalCount") Integer totalCount,
                              @Param("completedAt") LocalDateTime completedAt);
```

**用途：**
- 同时更新 `operation_type` 和 `status`
- 用于将任务从"删除"切换为"恢复"

---

### 2. AsyncRecycleBinRestoreService.java（修改逻辑）

**文件路径**: `src/main/java/com/mizuka/cloudfilesystem/service/AsyncRecycleBinRestoreService.java`

#### 修改位置: asyncRestoreBatch() 方法

**新增逻辑步骤：**

```java
// 【新增】2. 检查是否有正在进行的删除任务，如果有则终止
if (task.getOperationType() == 0 && task.getStatus() == 0) {
    log.info("[异步恢复] 检测到删除任务正在进行，先终止删除 - BatchId: {}", batchId);
    
    // 终止删除任务（status=3 表示已终止）
    recycleBinTaskMapper.updateTask(batchId, 3, LocalDateTime.now(), 
        "用户主动终止（开始恢复）", null, null);
    
    log.info("[异步恢复] 删除任务已终止 - BatchId: {}", batchId);
}

// 3. 更新任务操作类型为恢复（operation_type=1），状态为进行中（status=0）
recycleBinTaskMapper.updateTaskOperationType(batchId, 1, 0, null, 0, null, null);
log.info("[异步恢复] 任务状态已更新为 restoring - BatchId: {}, OperationType: 1", batchId);
```

---

## 🔄 完整流程

### 场景：用户在删除过程中发起恢复请求

```
1. 用户删除文件夹
   → 创建 batch，operation_type=0（删除），status=0（进行中）
   → 异步删除任务开始执行
   
2. 删除任务进行中...
   → 正在扫描子节点
   → 正在软删除节点
   
3. 用户决定恢复该文件夹
   → POST /files/recycle/restore?batchId=xxx
   
4. AsyncRecycleBinRestoreService.asyncRestoreBatch() 启动
   a. 查询 batch 信息
      → operation_type=0, status=0 （删除进行中）
   
   b. 【关键】检测到删除任务正在进行
      → 终止删除任务
      → UPDATE recycle_bin_tasks 
         SET status=3, 
             error_message='用户主动终止（开始恢复）',
             completed_at=NOW()
         WHERE batch_id=?
      → 记录日志："检测到删除任务正在进行，先终止删除"
   
   c. 切换为恢复任务
      → UPDATE recycle_bin_tasks 
         SET operation_type=1, status=0
         WHERE batch_id=?
      → 记录日志："任务状态已更新为 restoring"
   
   d. 继续执行恢复逻辑
      → 从 Redis ZSET 获取节点
      → 按顺序恢复所有节点
      → 完成后清理缓存并更新状态为 "Restored"
```

---

## 📊 状态流转对比

### 原有流程（无删除任务）

```
删除完成 (status=1, operation_type=0)
    ↓
用户发起恢复
    ↓
恢复开始 (status=0, operation_type=1) ← "restoring"
    ↓
恢复完成 (status=1, operation_type=1) ← "Restored"
```

### 新流程（删除任务进行中）

```
删除进行中 (status=0, operation_type=0)
    ↓
用户发起恢复
    ↓
【新增】终止删除 (status=3, operation_type=0) ← "Terminated"
    ↓
切换为恢复 (status=0, operation_type=1) ← "Restoring"
    ↓
恢复完成 (status=1, operation_type=1) ← "Restored"
```

---

## ⚠️ 关键要点

### 1. 判断条件

```java
if (task.getOperationType() == 0 && task.getStatus() == 0)
```

**含义：**
- `operation_type=0` → 删除操作
- `status=0` → 进行中

只有同时满足这两个条件，才说明删除任务正在进行中。

---

### 2. 终止删除的方式

```java
recycleBinTaskMapper.updateTask(batchId, 3, LocalDateTime.now(), 
    "用户主动终止（开始恢复）", null, null);
```

**参数说明：**
- `status=3` → 已终止
- `completed_at=NOW()` → 记录终止时间
- `error_message="用户主动终止（开始恢复）"` → 说明终止原因

---

### 3. 切换为恢复任务

```java
recycleBinTaskMapper.updateTaskOperationType(batchId, 1, 0, null, 0, null, null);
```

**参数说明：**
- `operation_type=1` → 恢复操作
- `status=0` → 进行中
- 其他字段设为null，保持不变

---

## 🎯 优势分析

### 1. 避免资源浪费

**问题：** 如果不终止删除任务
- 删除任务继续执行，占用系统资源
- 恢复任务也在执行，造成冲突
- 可能导致数据不一致

**解决：** 立即终止删除任务
- 释放系统资源
- 避免并发冲突
- 保证数据一致性

---

### 2. 提升用户体验

**问题：** 用户需要等待删除完成才能恢复
- 删除可能需要几分钟甚至更久
- 用户无法立即恢复

**解决：** 立即终止并开始恢复
- 无需等待删除完成
- 快速响应用户需求
- 提升用户体验

---

### 3. 清晰的状态追踪

**好处：**
- 删除任务状态明确标记为"已终止"
- 错误消息说明终止原因
- 便于后续审计和问题排查

---

## 📝 日志示例

### 正常恢复（无删除任务）

```
[异步恢复] 开始 - BatchId: 550e8400, UserId: 10001
[异步恢复] 任务状态已更新为 restoring - BatchId: 550e8400, OperationType: 1
[异步恢复] 待恢复节点总数: 156
[异步恢复] 恢复节点 - NodeType: 0, NodeId: 12345
[异步恢复] 节点恢复成功 - NodeType: 0, NodeId: 12345
...
[异步恢复] 完成 - BatchId: 550e8400, Duration: 1523ms, Success: 156/156
```

### 终止删除后恢复

```
[异步恢复] 开始 - BatchId: 550e8400, UserId: 10001
[异步恢复] 检测到删除任务正在进行，先终止删除 - BatchId: 550e8400
[异步恢复] 删除任务已终止 - BatchId: 550e8400
[异步恢复] 任务状态已更新为 restoring - BatchId: 550e8400, OperationType: 1
[异步恢复] 待恢复节点总数: 156
[异步恢复] 恢复节点 - NodeType: 0, NodeId: 12345
[异步恢复] 节点恢复成功 - NodeType: 0, NodeId: 12345
...
[异步恢复] 完成 - BatchId: 550e8400, Duration: 1523ms, Success: 156/156
```

---

## ✅ 验证清单

### 功能验证（待测试）

- [ ] 删除任务进行中时发起恢复，能正确终止删除
- [ ] 删除任务已完成时发起恢复，不执行终止逻辑
- [ ] 删除任务已失败时发起恢复，不执行终止逻辑
- [ ] MySQL中删除任务状态正确更新为3（已终止）
- [ ] MySQL中恢复任务状态正确更新为0（进行中）
- [ ] 日志中正确记录终止和切换过程
- [ ] 恢复任务正常执行并完成

### 边界情况

- [ ] 删除任务刚启动就发起恢复
- [ ] 删除任务即将完成时发起恢复
- [ ] 多个用户同时对同一batch发起恢复
- [ ] 恢复过程中再次发起恢复（应拒绝）

---

## 🔗 相关文档

1. **实施报告**: [RECYCLE_BIN_RESTORE_IMPLEMENTATION_REPORT.md](file://C:\Users\ROG\Desktop\develop\BackEnd\CloudFileSystem\RECYCLE_BIN_RESTORE_IMPLEMENTATION_REPORT.md)
2. **快速参考**: [RECYCLE_BIN_RESTORE_QUICK_REF.md](file://C:\Users\ROG\Desktop\develop\BackEnd\CloudFileSystem\RECYCLE_BIN_RESTORE_QUICK_REF.md)
3. **Redis设计**: [RECYCLE_BIN_REDIS_STORAGE_DESIGN.md](file://C:\Users\ROG\Desktop\develop\BackEnd\CloudFileSystem\RECYCLE_BIN_REDIS_STORAGE_DESIGN.md)

---

**文档版本**: v1.1  
**最后更新**: 2026-06-07  
**主要变更**: 新增删除任务终止逻辑  
**作者**: CloudFileSystem Team
