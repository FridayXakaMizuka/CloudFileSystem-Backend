# 删除接口乐观锁版本控制 - 前端快速参考

## 📋 概述

删除接口已添加乐观锁校验，防止多人同时删除同一文件/文件夹时的并发冲突。

---

## 🔧 API 变更

## 删除节点

**接口**: `DELETE /files/delete`

**功能**: 软删除节点，移入回收站（30天后彻底删除）

### 请求参数

| 参数       | 类型      | 必填 | 说明                |
|----------|---------|------|-------------------|
| `nodeId` | Long    | ✅ 是 | 节点ID              |
| `nodeType` | Integer | ✅ 是 | 节点类型（0为文件夹，1为文件）   |
| `version` | Long | ✅ 是 | 乐观锁版本号（从浏览接口获取） |
| `sessionId` | String  | ❌ 否 | 会话ID（用于后端唯一标识删除请求，不传则自动生成） |

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

**错误响应（版本冲突）**:

```json
{
  "success": false,
  "code": 409,
  "message": "文件夹已被其他人修改，请刷新后重试",
  "data": null
}
```

---

## 💻 使用示例

### Vue 3 示例

```vue
<script setup lang="ts">
import { ref } from 'vue';
import axios from 'axios';
import { ElMessage } from 'element-plus';

interface DirectoryNode {
  id: number;
  name: string;
  type: 'folder' | 'file';
  version: number;  // ← 版本号字段
  // ... 其他字段
}

const children = ref<DirectoryNode[]>([]);

// 获取目录列表（包含 version）
const fetchDirectory = async () => {
  const response = await axios.get('/api/files/browse', {
    params: { currentNodeId: 1001 }
  });
  children.value = response.data.data.children;
};

// 删除文件夹（携带 version 和 nodeType）
const deleteFolder = async (folder: DirectoryNode) => {
  try {
    await axios.delete('/api/files/delete', {
      params: {
        nodeId: folder.id,
        nodeType: 0,  // ← 0 表示文件夹
        version: folder.version  // ← 必须携带版本号
      }
    });
    
    ElMessage.success('已移入回收站');
    await fetchDirectory(); // 刷新列表
    
  } catch (error: any) {
    if (error.response?.status === 409) {
      // 版本冲突处理
      ElMessage.warning(error.response.data.message);
      await fetchDirectory(); // 自动刷新
    } else {
      ElMessage.error('删除失败');
    }
  }
};
</script>

<template>
  <div v-for="folder in children" :key="folder.id">
    <span>{{ folder.name }}</span>
    <button @click="deleteFolder(folder)">删除</button>
  </div>
</template>
```

### React 示例

```tsx
import { useState, useEffect } from 'react';
import axios from 'axios';
import { message } from 'antd';

interface DirectoryNode {
  id: number;
  name: string;
  type: 'folder' | 'file';
  version: number;  // ← 版本号字段
}

function DirectoryList() {
  const [folders, setFolders] = useState<DirectoryNode[]>([]);

  // 获取目录列表
  const fetchDirectory = async () => {
    const response = await axios.get('/api/files/browse', {
      params: { currentNodeId: 1001 }
    });
    setFolders(response.data.data.children);
  };

  // 删除文件夹（携带 version 和 nodeType）
  const deleteFolder = async (folder: DirectoryNode) => {
    try {
      await axios.delete('/api/files/delete', {
        params: {
          nodeId: folder.id,
          nodeType: 0,  // ← 0 表示文件夹
          version: folder.version  // ← 必须携带版本号
        }
      });
      
      message.success('已移入回收站');
      await fetchDirectory(); // 刷新列表
      
    } catch (error: any) {
      if (error.response?.status === 409) {
        // 版本冲突处理
        message.warning(error.response.data.message);
        await fetchDirectory(); // 自动刷新
      } else {
        message.error('删除失败');
      }
    }
  };

  useEffect(() => {
    fetchDirectory();
  }, []);

  return (
    <div>
      {folders.map(folder => (
        <div key={folder.id}>
          <span>{folder.name}</span>
          <button onClick={() => deleteFolder(folder)}>删除</button>
        </div>
      ))}
    </div>
  );
}
```

---

## ⚠️ 关键规则

### 1. 始终携带最新版本号和节点类型

```typescript
// ✅ 正确：从最新数据中获取 version 和 nodeType
const folder = children.find(f => f.id === folderId);
await deleteNode(folder.id, 0, folder.version);  // 0=文件夹

const file = children.find(f => f.id === fileId);
await deleteNode(file.id, 1, file.version);  // 1=文件

// ❌ 错误：使用缓存的旧版本号
await deleteNode(folderId, 0, cachedVersion);
```

### 2. 处理 409 版本冲突

```typescript
try {
  await deleteFolder(id, version);
} catch (error: any) {
  if (error.response?.status === 409) {
    // 版本冲突：数据已被其他人修改
    alert('该文件夹已被其他人修改，请刷新后重试');
    await refreshData(); // 自动刷新
  }
}
```

### 3. 工作流程

```
1. 用户浏览目录 → 获取 version 和 type
2. 用户点击删除 → 携带 nodeId, nodeType, version 发送请求
3. 后端校验 version → 匹配则删除，不匹配返回 409
4. 前端处理响应 → 成功刷新列表，冲突提示并刷新
```

---

## 🎯 完整示例

```typescript
class FileManager {
  // 获取目录
  async getDirectory(folderId: number): Promise<DirectoryNode[]> {
    const response = await axios.get('/api/files/browse', {
      params: { currentNodeId: folderId }
    });
    return response.data.data.children;
  }

  // 删除节点
  async deleteNode(nodeId: number, nodeType: number, version: number): Promise<void> {
    try {
      const response = await axios.delete('/api/files/delete', {
        params: { nodeId, nodeType, version }
      });
      
      console.log('删除成功:', response.data.message);
      return response.data.data;
      
    } catch (error: any) {
      if (error.response?.status === 409) {
        // 版本冲突
        throw new Error('VERSION_CONFLICT: ' + error.response.data.message);
      }
      throw error;
    }
  }

  // 带重试的删除
  async deleteWithRetry(nodeId: number, maxRetries = 3): Promise<void> {
    let retries = 0;
    
    while (retries < maxRetries) {
      try {
        // 1. 获取最新数据
        const directory = await this.getDirectory(1001);
        const node = directory.find(n => n.id === nodeId);
        
        if (!node) {
          throw new Error('节点不存在');
        }
        
        // 2. 尝试删除
        await this.deleteNode(nodeId, node.type === 'folder' ? 0 : 1, node.version);
        return; // 成功
        
      } catch (error: any) {
        if (error.message.startsWith('VERSION_CONFLICT')) {
          retries++;
          console.log(`版本冲突，重试 ${retries}/${maxRetries}`);
          
          if (retries >= maxRetries) {
            throw new Error('多次重试失败，请稍后手动刷新');
          }
          
          // 等待后重试
          await new Promise(resolve => setTimeout(resolve, 1000));
        } else {
          throw error; // 其他错误直接抛出
        }
      }
    }
  }
}

// 使用
const manager = new FileManager();

// 简单删除
const folders = await manager.getDirectory(1001);
await manager.deleteNode(folders[0].id, 0, folders[0].version);  // 0=文件夹

// 带重试的删除
await manager.deleteWithRetry(1001);
```

---

## 📊 错误码说明

| 状态码 | 含义 | 处理方式 |
|--------|------|---------|
| 200 | 删除成功 | 刷新列表 |
| 400 | 参数错误（缺少 version） | 检查参数 |
| 409 | 版本冲突 | 刷新数据后重试 |
| 403 | 无权限 | 提示用户 |
| 404 | 节点不存在 | 刷新列表 |
| 500 | 服务器错误 | 提示稍后重试 |

---

## 🔑 要点总结

1. **必填参数**: `nodeId`、`nodeType` 和 `version` 都是必填的
2. **节点类型**: `0` 表示文件夹，`1` 表示文件
3. **获取 version**: 从浏览接口（`/files/browse`）的响应中获取
4. **处理 409**: 捕获版本冲突，自动刷新数据
5. **不要缓存**: 每次删除前都使用最新的 version
6. **用户体验**: 冲突时友好提示，避免数据丢失

---

## 📖 相关文档

- [前端乐观锁使用指南](./FRONTEND_VERSION_GUIDE.md)
- [快速参考手册](./FRONTEND_VERSION_QUICK_REF.md)
- [文档索引](./VERSION_DOCS_INDEX.md)

---

**最后更新**: 2026-06-05
