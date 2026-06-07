# 乐观锁版本号 - 前端快速参考

## 🚀 快速开始

### 1. API 响应中的 version 字段

所有目录浏览和搜索接口都会返回 `version` 字段：

```json
{
  "id": 1001,
  "name": "documents",
  "type": "folder",
  "version": 3  // ← 乐观锁版本号
}
```

### 2. 写操作携带 version

```typescript
// ✅ 重命名
await axios.put('/api/files/rename', {
  nodeId: 1001,
  name: '新名称',
  version: 3  // ← 必须携带
});

// ✅ 移动
await axios.put('/api/files/move', {
  nodeId: 1001,
  newParentId: 2001,
  version: 3  // ← 必须携带
});

// ✅ 删除
await axios.delete('/api/files/delete', {
  params: { 
    nodeId: 1001,
    version: 3  // ← 必须携带
  }
});
```

---

## ⚠️ 错误处理

### 版本冲突（409）

```typescript
try {
  await updateFolder(id, name, version);
} catch (error: any) {
  if (error.response?.status === 409) {
    // 版本冲突：数据已被其他人修改
    alert('数据已更新，请刷新后重试');
    await refreshData();
  }
}
```

---

## 📋 TypeScript 类型定义

```typescript
interface DirectoryNode {
  id: number;
  name: string;
  type: 'folder' | 'file';
  path: string;
  parentId?: number;
  hasChildren?: boolean;
  childCount?: number;
  size?: number;
  mimeType?: string;
  extension?: string;
  createdAt: string;
  updatedAt: string;
  version: number;  // ← 必填
}
```

---

## 💡 Vue 3 示例

```vue
<script setup lang="ts">
import { ref } from 'vue';
import axios from 'axios';

const children = ref<DirectoryNode[]>([]);

// 获取目录
const fetchDirectory = async () => {
  const res = await axios.get('/api/files/browse', {
    params: { currentNodeId: 1001 }
  });
  children.value = res.data.data.children;
};

// 重命名（携带版本号）
const renameFolder = async (folder: DirectoryNode) => {
  try {
    await axios.put('/api/files/rename', {
      nodeId: folder.id,
      name: '新名称',
      version: folder.version  // ← 关键
    });
    await fetchDirectory(); // 刷新
  } catch (error: any) {
    if (error.response?.status === 409) {
      alert('版本冲突，已自动刷新');
      await fetchDirectory();
    }
  }
};
</script>

<template>
  <div v-for="folder in children" :key="folder.id">
    {{ folder.name }} (v{{ folder.version }})
    <button @click="renameFolder(folder)">重命名</button>
  </div>
</template>
```

---

## 💡 React 示例

```tsx
import { useState, useEffect } from 'react';
import axios from 'axios';

function DirectoryList() {
  const [folders, setFolders] = useState<DirectoryNode[]>([]);

  // 获取目录
  const fetchDirectory = async () => {
    const res = await axios.get('/api/files/browse', {
      params: { currentNodeId: 1001 }
    });
    setFolders(res.data.data.children);
  };

  // 重命名（携带版本号）
  const renameFolder = async (folder: DirectoryNode) => {
    try {
      await axios.put('/api/files/rename', {
        nodeId: folder.id,
        name: '新名称',
        version: folder.version  // ← 关键
      });
      await fetchDirectory(); // 刷新
    } catch (error: any) {
      if (error.response?.status === 409) {
        alert('版本冲突，已自动刷新');
        await fetchDirectory();
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
          {folder.name} (v{folder.version})
          <button onClick={() => renameFolder(folder)}>重命名</button>
        </div>
      ))}
    </div>
  );
}
```

---

## 🔑 关键规则

| 规则 | 说明 |
|------|------|
| **始终携带** | 所有写操作必须携带 `version` |
| **使用最新值** | 从最新 API 响应中获取，不用缓存 |
| **处理 409** | 捕获版本冲突，自动刷新 |
| **不要修改** | `version` 是只读字段，不要手动修改 |

---

## 🎯 常见场景

### 场景 1：列表页面

```typescript
// 1. 获取列表（包含 version）
const list = await fetchDirectory();

// 2. 用户点击编辑
const item = list.find(i => i.id === selectedId);

// 3. 提交时携带 version
await updateItem(item.id, newData, item.version);
```

### 场景 2：批量操作

```typescript
// 每个操作独立携带自己的 version
await Promise.all(
  items.map(item => 
    updateItem(item.id, item.newName, item.version)
  )
);
```

### 场景 3：乐观更新

```typescript
// 1. 立即更新 UI
updateUI(newData);

try {
  // 2. 发送请求
  await api.update(id, data, version);
} catch (error) {
  // 3. 失败时回滚
  rollbackUI();
}
```

---

## 📖 完整文档

查看 [FRONTEND_VERSION_GUIDE.md](./FRONTEND_VERSION_GUIDE.md) 获取更详细的示例和最佳实践。

---

**最后更新**: 2026-06-05
