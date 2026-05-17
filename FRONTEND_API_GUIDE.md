# 云文件系统 - 前端接口文档

> **版本**: v2.0  
> **更新日期**: 2026-05-10  
> **基础URL**: `/api/files`  
> **认证方式**: JWT Token（在请求头中携带 `Authorization: Bearer {token}`）

---

## 📋 目录

1. [浏览目录](#1-浏览目录)
2. [浏览回收站](#2-浏览回收站)
3. [创建文件夹](#3-创建文件夹)
4. [重命名节点](#4-重命名节点)
5. [移动节点](#5-移动节点)
6. [删除节点](#6-删除节点)
7. [恢复节点](#7-恢复节点)
8. [彻底删除](#8-彻底删除)
9. [搜索文件/文件夹](#9-搜索文件文件夹)
10. [搜索回收站](#10-搜索回收站)

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
| 40001 | 参数错误 |
| 401 | 未认证或会话过期 |
| 40301 | 权限不足 |
| 40401 | 资源不存在 |
| 40402 | 游标失效 |
| 50001 | 服务器内部错误 |

---

## 1. 浏览目录

**接口**: `GET /browse`

**功能**: 浏览指定目录下的子节点（支持游标分页、多种排序）

### 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `currentNodeId` | Long | ✅ | - | 当前目录节点ID |
| `lastChildrenNode` | Long | ❌ | null | 游标锚点：上一页最后一个子节点的ID |
| `lastChildrenType` | String | ❌ | null | 游标锚点类型：`folder` 或 `file` |
| `maxPageSize` | Integer | ❌ | 50 | 期望的最大返回数量（最大200） |
| `sortedBy` | Integer | ❌ | 0 | 排序字段：0=createdAt, 1=name, 2=updatedAt |
| `order` | Integer | ❌ | 1 | 排序顺序：0=asc（升序）, 1=desc（降序） |
| `excludeNewFileIds` | Array<Long> | ❌ | null | 需要排除的新增文件ID列表 |
| `excludeNewFolderIds` | Array<Long> | ❌ | null | 需要排除的新增文件夹ID列表 |

### 排序策略

- **升序（order=0）**：文件夹优先 → 文件补充
- **降序（order=1）**：文件优先 → 文件夹补充 ⭐

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": {
    "currentNode": {
      "id": 100,
      "name": "documents",
      "path": "_root/_files/10001/documents",
      "parentId": 50
    },
    "children": [
      {
        "id": 101,
        "name": "work",
        "type": "folder",
        "hasChildren": true,
        "childCount": 5,
        "createdAt": "2026-05-05T10:00:00",
        "updatedAt": "2026-05-05T10:00:00"
      },
      {
        "id": 102,
        "name": "report.pdf",
        "type": "file",
        "size": 1048576,
        "mimeType": "application/pdf",
        "extension": "pdf",
        "thumbnail": "/thumbnails/thumb_102.jpg",
        "createdAt": "2026-05-05T10:05:00",
        "updatedAt": "2026-05-05T10:05:00"
      }
    ],
    "pagination": {
      "lastChildrenNode": 102,
      "lastChildrenType": "file",
      "isEnd": false
    }
  }
}
```

### 前端实现示例

```javascript
// React 示例
async function browseDirectory(currentNodeId, cursor = null) {
  const params = new URLSearchParams({
    currentNodeId,
    maxPageSize: 20,
    order: 1  // 降序：最新文件在前
  });
  
  if (cursor) {
    params.append('lastChildrenNode', cursor.lastNode);
    params.append('lastChildrenType', cursor.lastType);
  }
  
  const response = await fetch(`/api/files/browse?${params}`, {
    headers: {
      Authorization: `Bearer ${token}`
    }
  });
  
  return await response.json();
}
```

---

## 2. 浏览回收站

**接口**: `GET /recycle`

**功能**: 浏览用户回收站中的内容（支持游标分页、多种排序）

### 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `currentNodeId` | Long | ✅ | - | 回收站根节点ID |
| `lastChildrenNode` | Long | ❌ | null | 游标锚点 |
| `lastChildrenType` | String | ❌ | null | 游标锚点类型 |
| `maxPageSize` | Integer | ❌ | 50 | 每页数量 |
| `sortedBy` | Integer | ❌ | 0 | 排序字段 |
| `order` | Integer | ❌ | 1 | 排序顺序（默认降序） |

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": {
    "currentNode": {
      "id": 5000,
      "name": "_recycle_bin",
      "path": "_root/_recycle_bin/10001",
      "parentId": 4
    },
    "children": [
      {
        "id": 339,
        "name": "work.pdf",
        "type": "file",
        "size": 1048576,
        "mimeType": "application/pdf",
        "deletedAt": "2026-05-05T10:05:00",
        "expiresAt": "2026-06-04T10:05:00",
        "daysRemaining": 29
      },
      {
        "id": 9178,
        "name": "work",
        "type": "folder",
        "hasChildren": true,
        "childCount": 5,
        "deletedAt": "2026-05-05T10:00:00",
        "expiresAt": "2026-06-04T10:00:00",
        "daysRemaining": 29
      }
    ],
    "pagination": {
      "lastChildrenNode": 9178,
      "lastChildrenType": "folder",
      "isEnd": false
    }
  }
}
```

### 回收站特有字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `deletedAt` | DateTime | 删除时间 |
| `expiresAt` | DateTime | 过期时间（30天后） |
| `daysRemaining` | Integer | 剩余天数 |

---

## 3. 创建文件夹

**接口**: `POST /folder`

**功能**: 在指定父目录下创建新文件夹（支持从待分配池复用）

### 请求体

```json
{
  "parentId": 100,
  "folderName": "new_folder"
}
```

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "文件夹创建成功",
  "data": {
    "id": 103,
    "name": "new_folder",
    "path": "_root/_files/10001/documents/new_folder",
    "reusedFromPool": false
  }
}
```

### 注意事项

- 新建的文件夹会临时存储在前端缓存中
- 后端加载目录时会从请求中加载排除项（`excludeNewFolderIds`）
- 前端刷新时会清空缓存
- 前端应自动按创建时间由新到老排序

---

## 4. 重命名节点

**接口**: `PUT /rename/{nodeId}`

**功能**: 重命名文件夹或文件

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `nodeId` | Long | 节点ID |

### 请求体

```json
{
  "newName": "renamed_folder"
}
```

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "重命名成功",
  "data": null
}
```

---

## 5. 移动节点

**接口**: `PUT /move/{nodeId}`

**功能**: 将文件或文件夹移动到新目录

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `nodeId` | Long | 节点ID |

### 请求体

```json
{
  "newParentId": 200
}
```

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "移动成功",
  "data": null
}
```

### 注意事项

- 不能将文件夹移动到自己或其子文件夹下
- 目标目录必须存在且用户有权限访问

---

## 6. 删除节点

**接口**: `DELETE /{nodeId}`

**功能**: 软删除节点，移入回收站（30天后彻底删除）

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `nodeId` | Long | 节点ID |

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "已移入回收站，30天后彻底删除",
  "data": {
    "recycleBinPath": "_root/_recycle_bin/10001/deleted_folder_001",
    "expiresAt": "2026-06-04T10:00:00"
  }
}
```

---

## 7. 恢复节点

**接口**: `POST /recycle/restore/{nodeId}`

**功能**: 从回收站恢复文件或文件夹

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `nodeId` | Long | 节点ID |

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "恢复成功",
  "data": {
    "restoredPath": "_root/_files/10001/documents/restored_folder"
  }
}
```

### 恢复逻辑

1. 如果原始位置仍存在，恢复到原位置
2. 如果原始位置已删除，恢复到用户根目录

---

## 8. 彻底删除

**接口**: `DELETE /permanent/{nodeId}`

**功能**: 彻底删除回收站中的节点（不可恢复）

### 路径参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `nodeId` | Long | 节点ID |

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "message": "已彻底删除，目录进入待分配池",
  "data": null
}
```

### 权限要求

- 必须是节点的所有者
- 节点必须在回收站中

---

## 9. 搜索文件/文件夹

**接口**: `GET /search`

**功能**: 统一游标分页搜索（推荐用于生产环境）⭐

### 请求参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `keyword` | String | ✅ | - | 搜索关键词 |
| `type` | String | ❌ | all | 类型过滤：`file`/`folder`/`all` |
| `sumFolders` | Integer | ❌ | null | 已显示文件夹数 |
| `sumFiles` | Integer | ❌ | null | 已显示文件数 |
| `lastFoldersNode` | Long | ❌ | null | 文件夹游标锚点 |
| `lastFilesNode` | Long | ❌ | null | 文件游标锚点 |
| `maxPageSize` | Integer | ❌ | 50 | 每页数量 |

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "data": {
    "results": [
      {
        "id": 339,
        "name": "work.pdf",
        "type": "file",
        "size": 1048576,
        "mimeType": "application/pdf",
        "thumbnail": "/thumbnails/thumb_339.jpg",
        "extension": "pdf",
        "relevance": 0.95,
        "createdAt": "2026-05-05T10:05:00"
      },
      {
        "id": 9178,
        "name": "work",
        "type": "folder",
        "hasChildren": true,
        "childCount": 5,
        "relevance": 0.95,
        "createdAt": "2026-05-05T10:00:00"
      }
    ],
    "pagination": {
      "lastFolderNode": 9178,
      "lastFileNode": 339,
      "isEndFolder": false,
      "isEndFile": false,
      "countFolders": 1,
      "countFiles": 1
    }
  }
}
```

### 排序规则

1. **主要排序**：相关性得分（`relevance`）降序
2. **次要排序**：相关性相同时，**文件优先于文件夹**
3. **文件之间**：扩展名升序 → 名称升序 → ID降序
4. **文件夹之间**：名称升序 → ID降序

### 前端实现示例

```javascript
// Vue 3 示例
const searchResults = ref([]);
const pagination = ref(null);

async function search(keyword, cursor = null) {
  const params = new URLSearchParams({
    keyword,
    type: 'all',
    maxPageSize: 10
  });
  
  if (cursor) {
    if (cursor.lastFolderNode) {
      params.append('lastFoldersNode', cursor.lastFolderNode);
    }
    if (cursor.lastFileNode) {
      params.append('lastFilesNode', cursor.lastFileNode);
    }
  }
  
  const response = await fetch(`/api/files/search?${params}`, {
    headers: {
      Authorization: `Bearer ${token}`
    }
  });
  
  const result = await response.json();
  
  if (result.success) {
    if (cursor) {
      searchResults.value.push(...result.data.results);
    } else {
      searchResults.value = result.data.results;
    }
    
    pagination.value = result.data.pagination;
  }
}

// 加载更多
function loadMore() {
  if (!pagination.value || (pagination.value.isEndFolder && pagination.value.isEndFile)) {
    return;
  }
  
  search(keyword.value, {
    lastFolderNode: pagination.value.lastFolderNode,
    lastFileNode: pagination.value.lastFileNode
  });
}
```

---

## 10. 搜索回收站

**接口**: `GET /recycle/search`

**功能**: 搜索回收站中的内容（统一游标分页）⭐

### 请求参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `keyword` | String | ✅ | - | 搜索关键词 |
| `type` | String | ❌ | all | 类型过滤 |
| `sumFolders` | Integer | ❌ | null | 已显示文件夹数 |
| `sumFiles` | Integer | ❌ | null | 已显示文件数 |
| `lastFoldersNode` | Long | ❌ | null | 文件夹游标锚点 |
| `lastFilesNode` | Long | ❌ | null | 文件游标锚点 |
| `maxPageSize` | Integer | ❌ | 50 | 每页数量 |

### 响应示例

```json
{
  "code": 200,
  "success": true,
  "data": {
    "results": [
      {
        "id": 339,
        "name": "work.pdf",
        "type": "file",
        "size": 1048576,
        "mimeType": "application/pdf",
        "thumbnail": "/thumbnails/thumb_339.jpg",
        "extension": "pdf",
        "relevance": 0.95,
        "createdAt": "2026-05-05T10:05:00"
      },
      {
        "id": 9178,
        "name": "work",
        "type": "folder",
        "hasChildren": true,
        "childCount": 5,
        "relevance": 0.95,
        "createdAt": "2026-05-05T10:00:00"
      }
    ],
    "pagination": {
      "lastFolderNode": 9178,
      "lastFileNode": 339,
      "isEndFolder": false,
      "isEndFile": false,
      "countFolders": 1,
      "countFiles": 1
    }
  }
}
```

### 排序规则

1. **主要排序**：相关性得分（`relevance`）降序
2. **次要排序**：相关性相同时，**文件优先于文件夹**
3. **文件之间**：扩展名升序 → 名称升序 → ID降序
4. **文件夹之间**：名称升序 → ID降序

### 前端实现示例

```javascript
// Vue 3 示例
const searchResults = ref([]);
const pagination = ref(null);

async function search(keyword, cursor = null) {
  const params = new URLSearchParams({
    keyword,
    type: 'all',
    maxPageSize: 10
  });
  
  if (cursor) {
    if (cursor.lastFolderNode) {
      params.append('lastFoldersNode', cursor.lastFolderNode);
    }
    if (cursor.lastFileNode) {
      params.append('lastFilesNode', cursor.lastFileNode);
    }
  }
  
  const response = await fetch(`/api/files/recycle/search?${params}`, {
    headers: {
      Authorization: `Bearer ${token}`
    }
  });
  
  const result = await response.json();
  
  if (result.success) {
    if (cursor) {
      searchResults.value.push(...result.data.results);
    } else {
      searchResults.value = result.data.results;
    }
    
    pagination.value = result.data.pagination;
  }
}

// 加载更多
function loadMore() {
  if (!pagination.value || (pagination.value.isEndFolder && pagination.value.isEndFile)) {
    return;
  }
  
  search(keyword.value, {
    lastFolderNode: pagination.value.lastFolderNode,
    lastFileNode: pagination.value.lastFileNode
  });
}
```

### 回收站特有字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `deletedAt` | DateTime | 删除时间 |
| `expiresAt` | DateTime | 过期时间（30天后） |
| `daysRemaining` | Integer | 剩余天数 |

---

## 📊 数据结构说明

### DirectoryNodeVO（目录节点）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 节点ID |
| `name` | String | 节点名称 |
| `type` | String | 节点类型：`folder` 或 `file` |
| `path` | String | 完整路径 |
| `parentId` | Long | 父节点ID |
| `hasChildren` | Boolean | 是否有子节点（仅文件夹） |
| `childCount` | Integer | 子节点数量（仅文件夹） |
| `size` | Long | 文件大小（字节，仅文件） |
| `mimeType` | String | MIME类型（仅文件） |
| `extension` | String | 文件扩展名（仅文件） |
| `thumbnail` | String | 缩略图路径（仅文件） |
| `createdAt` | DateTime | 创建时间 |
| `updatedAt` | DateTime | 更新时间 |
| `deletedAt` | DateTime | 删除时间（仅回收站） |
| `expiresAt` | DateTime | 过期时间（仅回收站） |
| `daysRemaining` | Integer | 剩余天数（仅回收站） |

### CursorPagination（游标分页信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastChildrenNode` | Long | 最后一个子节点的ID |
| `lastChildrenType` | String | 最后一个子节点的类型 |
| `isEnd` | Boolean | 是否到达末尾 |

### SearchPagination（搜索分页信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| `lastFolderNode` | Long | 最后一个文件夹的ID |
| `lastFileNode` | Long | 最后一个文件的ID |
| `isEndFolder` | Boolean | 文件夹是否到达末尾 |
| `isEndFile` | Boolean | 文件是否到达末尾 |
| `countFolders` | Integer | 当前返回的文件夹数量 |
| `countFiles` | Integer | 当前返回的文件数量 |

---

## 💡 最佳实践

### 1. 无限滚动实现

```javascript
// 使用 Intersection Observer 实现无限滚动
const observer = new IntersectionObserver((entries) => {
  if (entries[0].isIntersecting && !loading && !isEnd) {
    loadMore();
  }
});

observer.observe(loadMoreTriggerElement);
```

### 2. 防抖搜索

```javascript
// 搜索防抖（300ms）
let searchTimer = null;

function handleSearch(keyword) {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    performSearch(keyword);
  }, 300);
}
```

### 3. 错误处理

```javascript
async function safeApiCall(apiFunction) {
  try {
    const result = await apiFunction();
    
    if (!result.success) {
      // 处理业务错误
      showError(result.message);
      return null;
    }
    
    return result.data;
  } catch (error) {
    // 处理网络错误
    showError('网络请求失败，请检查网络连接');
    return null;
  }
}
```

### 4. 缓存策略

```javascript
// 简单的内存缓存
const cache = new Map();

async function browseWithCache(currentNodeId) {
  const cacheKey = `browse_${currentNodeId}`;
  
  if (cache.has(cacheKey)) {
    return cache.get(cacheKey);
  }
  
  const data = await browseDirectory(currentNodeId);
  cache.set(cacheKey, data);
  
  // 5分钟后清除缓存
  setTimeout(() => cache.delete(cacheKey), 5 * 60 * 1000);
  
  return data;
}
```

---

## 🔐 安全注意事项

1. **JWT Token 管理**
   - Token 存储在 `localStorage` 或 `sessionStorage`
   - 每次请求都携带 Token
   - Token 过期时自动跳转到登录页

2. **权限校验**
   - 所有接口都会验证用户身份
   - 用户只能访问自己的文件和文件夹
   - 系统目录（以 `_` 开头）仅管理员可访问

3. **输入验证**
   - 文件名不能包含特殊字符：`/ \ : * ? " < > |`
   - 文件名长度限制：1-255 字符
   - 关键词搜索会自动过滤 SQL 注入字符

---

## 📞 技术支持

如有问题，请联系后端开发团队或查看项目文档：

- 设计文档：`DIRECTORY_TREE_SYSTEM_DESIGN_V2.md`
- API 更新文档：`docs/BROWSE_AND_RECYCLE_BIN_API_UPDATE.md`
- 搜索 API 文档：`docs/SEARCH_API_DUAL_CURSOR_GUIDE.md`

---

**最后更新**: 2026-05-10  
**文档版本**: v2.0
