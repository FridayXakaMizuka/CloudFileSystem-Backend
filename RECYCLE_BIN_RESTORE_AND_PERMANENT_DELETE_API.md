# 回收站文件还原与彻底删除接口文档

> **版本**: v1.0  
> **更新日期**: 2026-06-07  
> **认证方式**: JWT Token（在请求头中携带 `Authorization: Bearer {token}`）

---

## 📋 目录

1. [恢复节点](#1-恢复节点)
2. [彻底删除](#2-彻底删除)
3. [获取恢复进程列表](#3-获取恢复进程列表)
4. [数据库表结构](#数据库表结构)
5. [前端实现示例](#前端实现示例)

---

## 通用说明

### 响应格式

所有接口返回统一的 JSON 格式：

```json
{
  "code": 200,
  "success": true,
  "message": "操作成功",
  "data": { ... }
}
```

### 常见错误码

| Code | 说明 |
|------|------|
| 200 | 成功 |
| 204 | 成功（无内容返回） |
| 40001 | 参数错误 |
| 401 | 未认证或会话过期 |
| 40301 | 权限不足 |
| 40401 | 资源不存在 |
| 40901 | 乐观锁冲突（版本号不匹配） |
| 50001 | 服务器内部错误 |

---

## 1. 恢复节点

**接口**: `POST /recycle/restore`

**功能**: 从回收站恢复文件或文件夹到原位置或用户根目录

### 业务逻辑

1. **检查删除任务状态**：如果后端删除记录未完成，则停止后端删除任务
2. **启动恢复任务**：异步执行恢复操作
3. **确定恢复位置**：
   - 如果原始父目录仍存在 → 恢复到原位置
   - 如果原始父目录已删除或被清理 → 恢复到用户根目录
4. **处理名称冲突**：如果目标位置存在同名文件/文件夹，自动添加 `(n)` 后缀

### 请求参数

#### Query Parameters

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `batchId` | String | ✅ | 业务操作批次号（UUID格式，从浏览回收站接口获取） |
| `version` | Long | ✅ | 乐观锁版本号（从浏览回收站接口获取） |

### 请求示例

```bash
curl -X POST "http://localhost:8835/recycle/restore?batchId=this-is-a-UUID1&version=2" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json"
```

### 响应示例

#### 场景 1：恢复到原位置（父目录仍存在）

```json
{
  "code": 200,
  "success": true,
  "message": "恢复成功",
  "data": {
    "newName": "restored_folder2",
    "restoredPath": "_root/_files/10001/document/restored_folder2"
  }
}
```

#### 场景 2：恢复到用户根目录（父目录已删除）

```json
{
  "code": 200,
  "success": true,
  "message": "原目录已删除，已恢复到用户根目录",
  "data": {
    "newName": "restored_folder1(3)",
    "restoredPath": "_root/_files/10001/restored_folder1(3)"
  }
}
```

#### 场景 3：恢复单个文件

```json
{
  "code": 200,
  "success": true,
  "message": "恢复成功",
  "data": {
    "newName": "report.pdf",
    "restoredPath": "_root/_files/10001/documents/report.pdf"
  }
}
```

#### 错误响应：乐观锁冲突

```json
{
  "code": 40901,
  "success": false,
  "message": "版本号冲突，数据已被其他操作修改",
  "data": null
}
```

#### 错误响应：资源不存在

```json
{
  "code": 40401,
  "success": false,
  "message": "回收站任务不存在或已处理",
  "data": null
}
```

### 注意事项

1. **异步处理**：恢复操作是异步执行的，特别是大文件夹可能需要较长时间
2. **进度查询**：可通过 `/recycle/restore/processes` 接口查询恢复进度
3. **批量恢复**：每次调用只能恢复一个 batchId 对应的节点（文件夹及其子树）
4. **权限要求**：必须是节点的所有者
5. **版本号管理**：使用 `version` 字段进行乐观锁控制，防止并发冲突

---

## 2. 彻底删除

**接口**: `DELETE /files/delete/permanent`

**功能**: 彻底删除节点（不可恢复），支持两种模式

### 业务逻辑

1. **回收站模式**（`mode=true`）：从回收站中彻底删除
   - 通过 `batchId` 定位删除任务
   - 标记为 `permanently_deleted` 状态
   - 物理文件将在后台定时任务中清理

2. **浏览界面模式**（`mode=false`）：直接从目录中彻底删除
   - 通过 `nodeId` 定位节点
   - 跳过回收站，直接标记为 `permanently_deleted`
   - 使用乐观锁防止并发冲突

### 请求参数

#### Query Parameters

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `mode` | Boolean | ✅ | 模式：`true`=回收站模式，`false`=浏览界面模式 |
| `batchId` | String | 条件必填 | 业务操作批次号（`mode=true` 时需填写） |
| `nodeId` | Long | 条件必填 | 节点ID（`mode=false` 时需填写） |
| `version` | Long | 条件必填 | 乐观锁版本号（`mode=false` 时需填写） |

### 请求示例

#### 示例 1：回收站模式 - 彻底删除文件夹

```bash
curl -X DELETE "http://localhost:8835/files/delete/permanent?mode=true&batchId=this-is-a-UUID1" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json"
```

#### 示例 2：浏览界面模式 - 彻底删除文件

```bash
curl -X DELETE "http://localhost:8835/files/delete/permanent?mode=false&nodeId=12345&version=3" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json"
```

### 响应示例

#### 成功响应

```json
{
  "code": 200,
  "success": true,
  "message": "已彻底删除，目录进入待分配池",
  "data": null
}
```

#### 错误响应：缺少必要参数

```json
{
  "code": 40001,
  "success": false,
  "message": "回收站模式必须提供 batchId",
  "data": null
}
```

#### 错误响应：资源不存在

```json
{
  "code": 40401,
  "success": false,
  "message": "节点不存在或无权限访问",
  "data": null
}
```

#### 错误响应：乐观锁冲突

```json
{
  "code": 40901,
  "success": false,
  "message": "版本号冲突，数据已被其他操作修改",
  "data": null
}
```

### 注意事项

1. **不可恢复**：彻底删除后数据无法恢复，请谨慎操作
2. **异步清理**：标记为 `permanently_deleted` 后，物理文件由后台定时任务清理
3. **目录复用**：删除的文件夹节点会进入"待分配池"，可被新创建的文件夹复用
4. **权限要求**：必须是管理员或节点的所有者
5. **批量删除**：每次调用只能删除一个 batchId 或 nodeId 对应的节点

---

## 3. 获取恢复进程列表

**接口**: `GET /recycle/restore/processes`

**功能**: 查询当前用户正在进行的恢复任务列表

### 请求参数

无需参数

### 请求示例

```bash
curl -X GET "http://localhost:8835/recycle/restore/processes" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

### 响应示例

#### 有进行中的恢复任务

```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": [
    {
      "batchId": "this-is-a-UUID1",
      "nodeId": 456,
      "nodeName": "work_folder",
      "status": 0,
      "totalCount": 150,
      "processedCount": 75,
      "createdAt": "2026-06-07T10:00:00"
    },
    {
      "batchId": "this-is-a-UUID2",
      "nodeId": 789,
      "nodeName": "photos",
      "status": 0,
      "totalCount": 50,
      "processedCount": 10,
      "createdAt": "2026-06-07T10:05:00"
    }
  ]
}
```

#### 没有进行中的恢复任务

```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": []
}
```

### 字段说明

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `batchId` | String | 业务操作批次号（UUID格式） |
| `nodeId` | Long | 恢复的根节点ID |
| `nodeName` | String | 恢复的根节点名称 |
| `status` | Integer | 状态：0=进行中，1=已完成，2=失败，3=已终止 |
| `totalCount` | Integer | 总节点数 |
| `processedCount` | Integer | 已处理节点数 |
| `createdAt` | String | 任务创建时间（ISO 8601格式） |

### 前端轮询建议

```javascript
// 每 3 秒轮询一次恢复进度
const pollingInterval = setInterval(async () => {
  const response = await fetch('/api/recycle/restore/processes', {
    headers: {
      Authorization: `Bearer ${token}`
    }
  });
  
  const result = await response.json();
  
  if (result.success && result.data.length === 0) {
    // 所有任务已完成，停止轮询
    clearInterval(pollingInterval);
    showToast('所有恢复任务已完成');
  }
}, 3000);
```

---

## 数据库表结构

### recycle_bin_tasks（回收站任务表）

该表用于追踪删除、恢复和彻底删除操作。

```sql
CREATE TABLE recycle_bin_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
    batch_id VARCHAR(36) NOT NULL UNIQUE COMMENT '业务操作批次号（UUID格式）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    root_node_id BIGINT NOT NULL COMMENT '根节点ID（文件夹或文件）',
    node_type TINYINT NOT NULL COMMENT '节点类型：0=文件夹，1=文件',
    operation_type TINYINT NOT NULL COMMENT '操作类型：0=删除，1=恢复，2=彻底删除',
    total_count INT DEFAULT 0 COMMENT '总节点数（异步扫描后更新）',
    processed_count INT DEFAULT 0 COMMENT '已处理节点数',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0=进行中，1=已完成，2=失败，3=已终止',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    
    INDEX idx_batch_id (batch_id),
    INDEX idx_user_status (user_id, status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回收站任务表';
```

### 关键字段说明

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `batch_id` | VARCHAR(36) | UUID格式，唯一标识一次删除/恢复/彻底删除操作 |
| `operation_type` | TINYINT | 0=删除，1=恢复，2=彻底删除 |
| `status` | TINYINT | 0=进行中，1=已完成，2=失败，3=已终止 |
| `total_count` | INT | 需要处理的总节点数（文件夹包含子节点时大于1） |
| `processed_count` | INT | 已处理的节点数，用于显示进度 |

### folder_nodes / file_nodes（节点表）

相关字段：

```sql
-- folder_nodes 表
directory_status ENUM('active', 'in_recycle_bin', 'permanently_deleted', 'deleting', 'restoring') 
    DEFAULT 'active' COMMENT '文件夹状态',
delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
version BIGINT DEFAULT 0 COMMENT '乐观锁版本号',

-- file_nodes 表
directory_status ENUM('active', 'in_recycle_bin', 'permanently_deleted', 'deleting', 'restoring') 
    DEFAULT 'active' COMMENT '文件状态',
delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
version BIGINT DEFAULT 0 COMMENT '乐观锁版本号',
```

---

## 前端实现示例

### Vue 3 + Composition API

#### 1. 恢复节点

```javascript
import { createLogger } from '@/utils/logger'
import { getToken } from '@/utils/auth'
import { FILE_API } from '@/config/api'

const logger = createLogger('RecycleBin')

/**
 * 恢复节点
 * @param {string} batchId - 业务操作批次号
 * @param {number} version - 乐观锁版本号
 * @returns {Promise<Object>} 响应数据
 */
export async function restoreNode(batchId, version) {
  const queryParams = new URLSearchParams({
    batchId: batchId,
    version: version.toString()
  })

  const url = `${FILE_API.RESTORE}?${queryParams.toString()}`

  logger.info('请求恢复节点:', {
    url,
    params: { batchId, version }
  })

  try {
    const token = getToken()
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : ''
      }
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const result = await response.json()
    logger.info('恢复节点响应:', result)

    // 支持 200 和 204 两种成功状态码
    if ((result.code === 200 || result.code === 204) && result.success) {
      return {
        success: true,
        code: result.code,
        data: result.data,
        message: result.message
      }
    } else {
      throw new Error(result.message || '恢复失败')
    }
  } catch (error) {
    logger.error('恢复节点失败:', error)
    return {
      success: false,
      error: error.message,
      message: error.message
    }
  }
}
```

#### 2. 彻底删除节点

```javascript
/**
 * 彻底删除节点（不可恢复）
 * 支持两种模式：回收站模式（通过 batchId）或浏览界面模式（通过 nodeId）
 * @param {boolean} mode - 模式：true=回收站模式，false=浏览界面模式
 * @param {string} [batchId] - 业务操作批次号（mode=true时需填写）
 * @param {number} [nodeId] - 节点ID（mode=false时需填写）
 * @param {number} [version] - 乐观锁版本号（从浏览接口获取）
 * @returns {Promise<Object>} 响应数据
 */
export async function permanentDelete(mode, batchId, nodeId, version) {
  const queryParams = new URLSearchParams({
    mode: mode.toString()
  })

  // 根据模式添加不同参数
  if (mode) {
    // 回收站模式：需要 batchId
    if (!batchId) {
      throw new Error('回收站模式必须提供 batchId')
    }
    queryParams.append('batchId', batchId)
  } else {
    // 浏览界面模式：需要 nodeId
    if (!nodeId) {
      throw new Error('浏览界面模式必须提供 nodeId')
    }
    queryParams.append('nodeId', nodeId.toString())
    if (version !== undefined && version !== null) {
      queryParams.append('version', version.toString())
    }
  }

  const url = `${FILE_API.PERMANENT_DELETE}?${queryParams.toString()}`

  logger.info('请求彻底删除节点:', {
    url,
    params: { mode, batchId, nodeId, version }
  })

  try {
    const token = getToken()
    const response = await fetch(url, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : ''
      }
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const result = await response.json()
    logger.info('彻底删除节点响应:', result)

    if (result.code === 200 && result.success) {
      return {
        success: true,
        data: result.data,
        message: result.message
      }
    } else {
      throw new Error(result.message || '彻底删除失败')
    }
  } catch (error) {
    logger.error('彻底删除节点失败:', error)
    return {
      success: false,
      error: error.message,
      message: error.message
    }
  }
}
```

#### 3. 获取恢复进程列表

```javascript
/**
 * 获取恢复进程列表
 * @returns {Promise<Object>} 响应数据
 */
export async function getRestoreProcesses() {
  const url = FILE_API.RESTORE_PROCESSES

  logger.info('请求获取恢复进程列表:', { url })

  try {
    const token = getToken()
    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : ''
      }
    })

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const result = await response.json()
    logger.info('获取恢复进程列表响应:', result)

    if (result.code === 200 && result.success) {
      return {
        success: true,
        data: result.data || [],
        count: result.data ? result.data.length : 0
      }
    } else {
      throw new Error(result.message || '获取恢复进程失败')
    }
  } catch (error) {
    logger.error('获取恢复进程列表失败:', error)
    return {
      success: false,
      error: error.message,
      message: error.message,
      data: []
    }
  }
}
```

#### 4. 组件中使用示例

```vue
<template>
  <div class="recycle-bin">
    <!-- 文件列表 -->
    <div v-for="file in files" :key="file.batchId" class="file-item">
      <span>{{ file.name }}</span>
      <button @click="handleRestore(file)">还原</button>
      <button @click="handlePermanentDelete(file)">彻底删除</button>
    </div>
    
    <!-- 恢复进程提示 -->
    <div v-if="restoreProcesses.length > 0" class="restore-hint">
      {{ restoreProcesses.length }}个恢复任务正在进行中
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { restoreNode, permanentDelete, getRestoreProcesses } from '@/utils/directory'
import { showSuccess, showError } from '@/utils/toast'

const files = ref([])
const restoreProcesses = ref([])
let pollingTimer = null

/**
 * 恢复文件
 */
const handleRestore = async (file) => {
  const result = await restoreNode(file.batchId, file.version)
  
  if (result.success) {
    showSuccess(result.message || '恢复成功')
    // 从列表中移除
    files.value = files.value.filter(f => f.batchId !== file.batchId)
    // 开始轮询恢复进度
    startRestorePolling()
  } else {
    showError(result.message || '恢复失败')
  }
}

/**
 * 彻底删除文件
 */
const handlePermanentDelete = async (file) => {
  if (!confirm('确定要彻底删除吗？此操作不可恢复！')) {
    return
  }
  
  const result = await permanentDelete(true, file.batchId, null, null)
  
  if (result.success) {
    showSuccess('已彻底删除')
    // 从列表中移除
    files.value = files.value.filter(f => f.batchId !== file.batchId)
  } else {
    showError(result.message || '彻底删除失败')
  }
}

/**
 * 启动恢复进程轮询
 */
const startRestorePolling = () => {
  if (pollingTimer) {
    return
  }
  
  pollingTimer = setInterval(async () => {
    const result = await getRestoreProcesses()
    
    if (result.success) {
      restoreProcesses.value = result.data
      
      // 如果所有任务都完成了，停止轮询
      if (result.data.length === 0) {
        stopRestorePolling()
        showSuccess('所有恢复任务已完成')
      }
    }
  }, 3000) // 每3秒轮询一次
}

/**
 * 停止恢复进程轮询
 */
const stopRestorePolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

onMounted(() => {
  // 初始加载恢复进程
  getRestoreProcesses().then(result => {
    if (result.success) {
      restoreProcesses.value = result.data
      // 如果有进行中的任务，开始轮询
      if (result.data.length > 0) {
        startRestorePolling()
      }
    }
  })
})

onUnmounted(() => {
  stopRestorePolling()
})
</script>
```

---

## 最佳实践

### 1. 错误处理

```javascript
// 统一错误处理
const handleError = (error, defaultMessage) => {
  switch (error.code) {
    case 401:
      return '会话已过期，请重新登录'
    case 40301:
      return '权限不足，无法执行此操作'
    case 40401:
      return '资源不存在或已被删除'
    case 40901:
      return '数据已被其他操作修改，请刷新后重试'
    default:
      return defaultMessage || '操作失败，请稍后重试'
  }
}
```

### 2. 确认对话框

```javascript
// 彻底删除前必须确认
const confirmPermanentDelete = async (file) => {
  const confirmed = await showDialog({
    title: '确认彻底删除',
    message: `确定要彻底删除 "${file.name}" 吗？此操作不可恢复！`,
    type: 'warning',
    confirmText: '彻底删除',
    cancelText: '取消'
  })
  
  if (confirmed) {
    await permanentDelete(true, file.batchId, null, null)
  }
}
```

### 3. 进度显示

```javascript
// 显示恢复进度
const formatProgress = (process) => {
  const percentage = Math.round((process.processedCount / process.totalCount) * 100)
  return `${process.nodeName}: ${process.processedCount}/${process.totalCount} (${percentage}%)`
}
```

### 4. 乐观更新

```javascript
// 先更新UI，再发送请求（乐观更新）
const optimisticRestore = async (file) => {
  // 1. 立即从列表中移除（提升用户体验）
  const originalFiles = [...files.value]
  files.value = files.value.filter(f => f.batchId !== file.batchId)
  
  try {
    // 2. 发送请求
    const result = await restoreNode(file.batchId, file.version)
    
    if (!result.success) {
      // 3. 如果失败，恢复列表
      files.value = originalFiles
      showError(result.message)
    }
  } catch (error) {
    // 4. 异常时也恢复列表
    files.value = originalFiles
    showError('网络错误')
  }
}
```

---

## 常见问题

### Q1: 为什么恢复操作是异步的？

**A**: 恢复大型文件夹可能涉及大量子节点的处理，同步执行会导致请求超时。异步处理可以：
- 避免HTTP请求超时
- 提供进度查询能力
- 提升用户体验（可以立即继续其他操作）

### Q2: 如何判断恢复是否完成？

**A**: 有两种方式：
1. 轮询 `/recycle/restore/processes` 接口，直到返回空数组
2. 重新加载回收站列表，检查对应 batchId 是否还存在

### Q3: 彻底删除和移入回收站有什么区别？

**A**: 
- **移入回收站**（`DELETE /files/delete`）：软删除，30天后自动清理，可随时恢复
- **彻底删除**（`DELETE /files/delete/permanent`）：永久删除，不可恢复，立即释放存储空间

### Q4: 乐观锁冲突如何处理？

**A**: 当多个用户同时操作同一资源时可能发生冲突。处理方式：
1. 捕获 40901 错误码
2. 提示用户"数据已被其他操作修改"
3. 建议用户刷新页面后重试
4. 自动重新获取最新的 version 值

### Q5: batchId 和 nodeId 有什么区别？

**A**: 
- **batchId**: UUID格式，用于回收站模式，标识一次删除操作（可能包含多个子节点）
- **nodeId**: Long类型，用于浏览界面模式，标识单个节点

---

## 总结

### 核心要点

1. ✅ **恢复节点**：异步处理，支持原位置恢复和根目录恢复
2. ✅ **彻底删除**：两种模式（回收站/浏览界面），不可恢复
3. ✅ **进度查询**：通过轮询 `/recycle/restore/processes` 获取实时进度
4. ✅ **乐观锁**：使用 `version` 字段防止并发冲突
5. ✅ **权限控制**：必须是节点所有者或管理员

### 优势

- 🚀 异步处理大文件夹恢复，避免超时
- 💾 乐观锁机制保证数据一致性
- 📊 实时进度反馈，提升用户体验
- 🔒 严格的权限控制，保障数据安全
- ♻️ 目录节点复用，优化存储效率

---

**文档版本**: v1.0  
**更新日期**: 2026-06-07  
**联系人**: 后端开发团队
