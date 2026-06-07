# 前端乐观锁版本号使用指南

## 📋 概述

本文档介绍如何在前端使用后端返回的乐观锁版本号（`version`）字段，实现并发控制和冲突处理。

---

## 🎯 什么是乐观锁版本号？

乐观锁是一种并发控制机制，通过版本号来检测数据是否被其他人修改过：

1. **读取数据**时，后端返回当前版本号（如 `version: 3`）
2. **更新数据**时，前端携带该版本号
3. **后端检查**：如果数据库中的版本号与前端传入的一致，则更新成功并将版本号 +1
4. **版本冲突**：如果版本号不一致，说明数据已被其他人修改，更新失败

---

## 📊 API 响应中的 version 字段

### 1. 浏览目录接口

**接口**: `GET /files/browse`

**响应示例**:
```json
{
  "success": true,
  "code": 200,
  "message": "操作成功",
  "data": {
    "currentNode": {
      "id": 1001,
      "name": "documents",
      "type": "folder",
      "path": "_root/_files/10001/documents",
      "parentId": 1000,
      "hasChildren": true,
      "childCount": 5,
      "createdAt": "2024-01-01T10:00:00",
      "updatedAt": "2024-06-05T15:30:00",
      "version": 3  // ← 乐观锁版本号
    },
    "children": [
      {
        "id": 2001,
        "name": "work",
        "type": "folder",
        "path": "_root/_files/10001/documents/work",
        "parentId": 1001,
        "hasChildren": false,
        "childCount": 0,
        "createdAt": "2024-02-01T10:00:00",
        "updatedAt": "2024-05-20T12:00:00",
        "version": 1  // ← 乐观锁版本号
      },
      {
        "id": 3001,
        "name": "report.pdf",
        "type": "file",
        "path": "_root/_files/10001/documents/report.pdf",
        "parentId": 1001,
        "size": 2048576,
        "mimeType": "application/pdf",
        "extension": ".pdf",
        "createdAt": "2024-03-01T10:00:00",
        "updatedAt": "2024-04-15T14:30:00",
        "version": 2  // ← 乐观锁版本号
      }
    ],
    "pagination": {
      "lastChildrenNode": 3001,
      "lastChildrenType": "file",
      "isEnd": true
    }
  }
}
```

### 2. 搜索接口

**接口**: `GET /files/search`

**响应示例**:
```json
{
  "success": true,
  "data": {
    "results": [
      {
        "id": 2001,
        "name": "work",
        "type": "folder",
        "path": "_root/_files/10001/documents/work",
        "relevance": 0.95,
        "hasChildren": true,
        "childCount": 3,
        "createdAt": "2024-02-01T10:00:00",
        "version": 1  // ← 乐观锁版本号
      },
      {
        "id": 3001,
        "name": "report.pdf",
        "type": "file",
        "path": "_root/_files/10001/documents/report.pdf",
        "fileSize": 2048576,
        "extension": ".pdf",
        "mimeType": "application/pdf",
        "relevance": 0.85,
        "createdAt": "2024-03-01T10:00:00",
        "version": 2  // ← 乐观锁版本号
      }
    ],
    "pagination": {
      "lastFolderNode": 2001,
      "lastFileNode": 3001,
      "isEndFolder": true,
      "isEndFile": true,
      "countFolders": 1,
      "countFiles": 1
    }
  }
}
```

---

## 💻 前端使用示例

### Vue 3 示例

#### 1. 定义 TypeScript 类型

```typescript
// types/directory.ts

export interface DirectoryNode {
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
  version: number;  // ← 乐观锁版本号
  
  // 回收站特有字段
  deletedAt?: string;
  expiresAt?: string;
  daysRemaining?: number;
}

export interface BrowseResponse {
  currentNode: DirectoryNode;
  children: DirectoryNode[];
  pagination: {
    lastChildrenNode?: number;
    lastChildrenType?: string;
    isEnd: boolean;
  };
}
```

#### 2. 获取目录列表并保存版本号

```vue
<script setup lang="ts">
import { ref, onMounted } from 'vue';
import axios from 'axios';
import type { DirectoryNode, BrowseResponse } from '@/types/directory';

const currentFolder = ref<DirectoryNode | null>(null);
const children = ref<DirectoryNode[]>([]);

// 浏览目录
const browseDirectory = async (folderId: number) => {
  try {
    const response = await axios.get<BrowseResponse>('/api/files/browse', {
      params: { currentNodeId: folderId }
    });
    
    if (response.data.success) {
      currentFolder.value = response.data.currentNode;
      children.value = response.data.children;
      
      console.log('当前文件夹版本号:', currentFolder.value.version);
      console.log('子节点数量:', children.value.length);
    }
  } catch (error) {
    console.error('获取目录失败:', error);
  }
};

onMounted(() => {
  browseDirectory(1001);
});
</script>
```

#### 3. 重命名文件夹（携带版本号）

```vue
<script setup lang="ts">
import { ElMessage } from 'element-plus';

// 重命名文件夹
const renameFolder = async (folderId: number, newName: string, currentVersion: number) => {
  try {
    const response = await axios.put('/api/files/rename', {
      nodeId: folderId,
      name: newName,
      version: currentVersion  // ← 携带当前版本号
    });
    
    if (response.data.success) {
      ElMessage.success('重命名成功');
      // 刷新目录列表
      await browseDirectory(folderId);
    }
  } catch (error: any) {
    if (error.response?.status === 409) {
      // 版本冲突
      ElMessage.warning('该文件夹已被其他人修改，请刷新后重试');
      // 自动刷新
      await browseDirectory(folderId);
    } else {
      ElMessage.error('重命名失败');
    }
  }
};
</script>

<template>
  <div v-for="folder in children" :key="folder.id">
    <span>{{ folder.name }}</span>
    <button @click="renameFolder(folder.id, '新名称', folder.version)">
      重命名
    </button>
  </div>
</template>
```

#### 4. 移动文件夹（携带版本号）

```typescript
// 移动文件夹
const moveFolder = async (folderId: number, newParentId: number, currentVersion: number) => {
  try {
    const response = await axios.put('/api/files/move', {
      nodeId: folderId,
      newParentId: newParentId,
      version: currentVersion  // ← 携带当前版本号
    });
    
    if (response.data.success) {
      ElMessage.success('移动成功');
      // 刷新父目录和当前目录
      await browseDirectory(newParentId);
    }
  } catch (error: any) {
    if (error.response?.status === 409) {
      ElMessage.warning('该文件夹已被其他人修改，请刷新后重试');
      await browseDirectory(currentFolder.value!.id);
    } else {
      ElMessage.error('移动失败');
    }
  }
};
```

#### 5. 删除文件夹（携带版本号）

```typescript
// 删除文件夹
const deleteFolder = async (folderId: number, currentVersion: number) => {
  try {
    const response = await axios.delete('/api/files/delete', {
      params: { 
        nodeId: folderId,
        version: currentVersion  // ← 携带当前版本号
      }
    });
    
    if (response.data.success) {
      ElMessage.success('已移入回收站');
      // 刷新目录列表
      await browseDirectory(currentFolder.value!.id);
    }
  } catch (error: any) {
    if (error.response?.status === 409) {
      ElMessage.warning('该文件夹已被其他人修改，请刷新后重试');
      await browseDirectory(currentFolder.value!.id);
    } else {
      ElMessage.error('删除失败');
    }
  }
};
```

---

### React 示例

#### 1. 定义 TypeScript 类型

```typescript
// types/directory.ts

export interface DirectoryNode {
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
  version: number;  // ← 乐观锁版本号
}
```

#### 2. 使用 Hook 管理目录状态

```tsx
import { useState, useEffect } from 'react';
import axios from 'axios';
import type { DirectoryNode } from '@/types/directory';

function useDirectory(folderId: number) {
  const [currentFolder, setCurrentFolder] = useState<DirectoryNode | null>(null);
  const [children, setChildren] = useState<DirectoryNode[]>([]);
  const [loading, setLoading] = useState(false);

  // 获取目录
  const fetchDirectory = async () => {
    setLoading(true);
    try {
      const response = await axios.get('/api/files/browse', {
        params: { currentNodeId: folderId }
      });
      
      if (response.data.success) {
        setCurrentFolder(response.data.currentNode);
        setChildren(response.data.children);
      }
    } catch (error) {
      console.error('获取目录失败:', error);
    } finally {
      setLoading(false);
    }
  };

  // 重命名文件夹
  const renameFolder = async (folderId: number, newName: string) => {
    const folder = children.find(f => f.id === folderId);
    if (!folder) return;

    try {
      await axios.put('/api/files/rename', {
        nodeId: folderId,
        name: newName,
        version: folder.version  // ← 携带版本号
      });
      
      // 刷新目录
      await fetchDirectory();
    } catch (error: any) {
      if (error.response?.status === 409) {
        alert('该文件夹已被其他人修改，请刷新后重试');
        await fetchDirectory();
      }
    }
  };

  useEffect(() => {
    fetchDirectory();
  }, [folderId]);

  return {
    currentFolder,
    children,
    loading,
    renameFolder,
    refresh: fetchDirectory
  };
}

export default useDirectory;
```

#### 3. 组件中使用

```tsx
import React from 'react';
import useDirectory from './hooks/useDirectory';

function DirectoryList({ folderId }: { folderId: number }) {
  const { children, renameFolder } = useDirectory(folderId);

  return (
    <div>
      {children.map(folder => (
        <div key={folder.id}>
          <span>{folder.name}</span>
          <button onClick={() => renameFolder(folder.id, '新名称')}>
            重命名
          </button>
        </div>
      ))}
    </div>
  );
}
```

---

## ⚠️ 错误处理

### 1. 版本冲突（409 Conflict）

当后端返回 `409` 状态码时，表示版本冲突：

```typescript
try {
  await updateFolder(folderId, newName, version);
} catch (error: any) {
  if (error.response?.status === 409) {
    // 版本冲突处理
    const errorMessage = error.response.data.message;
    
    // 方案1：提示用户刷新
    alert(errorMessage); // "数据已被其他人修改，请刷新后重试"
    await refreshDirectory();
    
    // 方案2：自动合并（适用于简单场景）
    // const latestData = await fetchLatest(folderId);
    // const mergedData = mergeChanges(localData, latestData);
    // await updateFolder(folderId, mergedData, latestData.version);
  }
}
```

### 2. 常见错误码

| 状态码 | 含义 | 处理方式 |
|--------|------|---------|
| 409 | 版本冲突 | 刷新数据后重试 |
| 404 | 资源不存在 | 提示用户资源已被删除 |
| 403 | 无权限 | 提示用户无权操作 |
| 500 | 服务器错误 | 提示稍后重试 |

---

## 🔧 最佳实践

### 1. 始终携带最新版本号

```typescript
// ✅ 正确：从最新数据中获取版本号
const folder = children.find(f => f.id === folderId);
await renameFolder(folderId, newName, folder.version);

// ❌ 错误：使用缓存的旧版本号
await renameFolder(folderId, newName, cachedVersion);
```

### 2. 冲突后自动刷新

```typescript
const handleUpdate = async () => {
  try {
    await updateData();
  } catch (error: any) {
    if (error.response?.status === 409) {
      // 自动刷新
      await refreshData();
      // 可选：提示用户
      toast.warning('数据已更新，请查看最新内容');
    }
  }
};
```

### 3. 批量操作时的版本号管理

```typescript
// 批量重命名多个文件夹
const batchRename = async (folders: Array<{id: number, name: string, version: number}>) => {
  const results = [];
  
  for (const folder of folders) {
    try {
      await renameFolder(folder.id, folder.name, folder.version);
      results.push({ id: folder.id, success: true });
    } catch (error: any) {
      if (error.response?.status === 409) {
        results.push({ id: folder.id, success: false, reason: 'version_conflict' });
      } else {
        results.push({ id: folder.id, success: false, reason: 'unknown_error' });
      }
    }
  }
  
  return results;
};
```

### 4. 乐观更新（Optimistic UI）

```typescript
const optimisticRename = async (folderId: number, newName: string) => {
  const folder = children.find(f => f.id === folderId);
  const oldName = folder.name;
  const oldVersion = folder.version;
  
  // 1. 立即更新 UI（乐观更新）
  const updatedChildren = children.map(f => 
    f.id === folderId ? { ...f, name: newName } : f
  );
  setChildren(updatedChildren);
  
  try {
    // 2. 发送请求
    await renameFolder(folderId, newName, oldVersion);
    
    // 3. 成功后刷新数据
    await refreshDirectory();
  } catch (error: any) {
    // 4. 失败时回滚
    setChildren(children); // 恢复原状
    
    if (error.response?.status === 409) {
      alert('版本冲突，已恢复原状');
      await refreshDirectory();
    }
  }
};
```

---

## 📝 完整示例：文件夹管理组件

```vue
<template>
  <div class="directory-manager">
    <!-- 当前文件夹信息 -->
    <div class="current-folder" v-if="currentFolder">
      <h2>{{ currentFolder.name }}</h2>
      <span class="version">版本: {{ currentFolder.version }}</span>
    </div>
    
    <!-- 子节点列表 -->
    <div class="children-list">
      <div 
        v-for="item in children" 
        :key="item.id" 
        class="child-item"
      >
        <span class="name">{{ item.name }}</span>
        <span class="version">v{{ item.version }}</span>
        
        <div class="actions">
          <button @click="handleRename(item)">重命名</button>
          <button @click="handleMove(item)">移动</button>
          <button @click="handleDelete(item)">删除</button>
        </div>
      </div>
    </div>
    
    <!-- 重命名对话框 -->
    <el-dialog v-model="showRenameDialog" title="重命名">
      <el-input v-model="newName" placeholder="请输入新名称" />
      <template #footer>
        <el-button @click="showRenameDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmRename">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import axios from 'axios';
import type { DirectoryNode } from '@/types/directory';

const currentFolder = ref<DirectoryNode | null>(null);
const children = ref<DirectoryNode[]>([]);
const showRenameDialog = ref(false);
const newName = ref('');
const renamingItem = ref<DirectoryNode | null>(null);

// 获取目录
const fetchDirectory = async (folderId: number) => {
  try {
    const response = await axios.get('/api/files/browse', {
      params: { currentNodeId: folderId }
    });
    
    if (response.data.success) {
      currentFolder.value = response.data.currentNode;
      children.value = response.data.children;
    }
  } catch (error) {
    ElMessage.error('获取目录失败');
  }
};

// 打开重命名对话框
const handleRename = (item: DirectoryNode) => {
  renamingItem.value = item;
  newName.value = item.name;
  showRenameDialog.value = true;
};

// 确认重命名
const confirmRename = async () => {
  if (!renamingItem.value) return;
  
  try {
    await axios.put('/api/files/rename', {
      nodeId: renamingItem.value.id,
      name: newName.value,
      version: renamingItem.value.version  // ← 携带版本号
    });
    
    ElMessage.success('重命名成功');
    showRenameDialog.value = false;
    
    // 刷新目录
    await fetchDirectory(currentFolder.value!.id);
  } catch (error: any) {
    if (error.response?.status === 409) {
      ElMessage.warning('该文件夹已被其他人修改，请刷新后重试');
      await fetchDirectory(currentFolder.value!.id);
    } else {
      ElMessage.error('重命名失败');
    }
  }
};

// 删除文件夹
const handleDelete = async (item: DirectoryNode) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除 "${item.name}" 吗？`,
      '警告',
      { type: 'warning' }
    );
    
    await axios.delete('/api/files/delete', {
      params: { 
        nodeId: item.id,
        version: item.version  // ← 携带版本号
      }
    });
    
    ElMessage.success('已移入回收站');
    await fetchDirectory(currentFolder.value!.id);
  } catch (error: any) {
    if (error.response?.status !== 409) {
      // 用户取消不显示错误
      if (error.response?.status === 409) {
        ElMessage.warning('该文件夹已被其他人修改，请刷新后重试');
        await fetchDirectory(currentFolder.value!.id);
      }
    }
  }
};

onMounted(() => {
  fetchDirectory(1001);
});
</script>

<style scoped>
.directory-manager {
  padding: 20px;
}

.current-folder {
  margin-bottom: 20px;
}

.version {
  color: #999;
  font-size: 12px;
  margin-left: 10px;
}

.child-item {
  display: flex;
  align-items: center;
  padding: 10px;
  border-bottom: 1px solid #eee;
}

.actions {
  margin-left: auto;
}

.actions button {
  margin-left: 10px;
}
</style>
```

---

## 🎯 关键点总结

1. **始终携带版本号**：所有写操作（重命名、移动、删除）都要携带 `version` 字段
2. **使用最新版本号**：从最新的 API 响应中获取版本号，不要使用缓存的旧值
3. **处理 409 冲突**：捕获版本冲突错误，自动刷新数据或提示用户
4. **乐观更新**：可以先更新 UI，失败时再回滚，提升用户体验
5. **批量操作**：每个操作独立处理版本号，部分失败不影响其他操作

---

## 📖 相关文档

- [后端实现文档](./BROWSE_DIRECTORY_VERSION_FIELD.md)
- [快速参考](./VERSION_FIELD_QUICK_REF.md)
- [乐观锁运作模式](memory://fddb0316-5e7a-47f7-96f7-09d1fae8348f)

---

**最后更新**: 2026-06-05
