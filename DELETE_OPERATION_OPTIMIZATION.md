# 删除操作优化 - 不移动文件到回收站目录

> **修改日期**: 2026-06-07  
> **修改范围**: FolderNodeMapper.java, FileNodeMapper.java

---

## 📋 问题描述

在 `/files/delete` 操作中，原有的软删除逻辑会：
1. ❌ 修改 `path` 字段为回收站路径
2. ❌ 将 `parent_id` (文件夹) 或 `folder_id` (文件) 设置为 NULL
3. ❌ 保存原始位置到 `original_parent_id` / `original_folder_id` 和 `original_path`

这导致文件实际上被"移动"到了回收站目录结构中。

---

## ✅ 优化方案

**新的软删除策略**：只标记状态，不修改位置信息

### 修改内容

#### 1. 文件夹软删除 (`FolderNodeMapper.softDeleteFolder`)

**修改前**:
```sql
UPDATE folder_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    path = #{recycleBinPath},                    -- ❌ 修改路径
    original_parent_id = parent_id,              -- ❌ 保存原父ID
    original_path = path,                        -- ❌ 保存原路径
    parent_id = NULL,                            -- ❌ 清空父ID
    updated_at = NOW()
WHERE id = #{id}
```

**修改后**:
```sql
UPDATE folder_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    updated_at = NOW()
WHERE id = #{id}
```

**改进**:
- ✅ 保留 `parent_id` 不变
- ✅ 保留 `path` 不变
- ✅ 仅标记状态为 `in_recycle_bin` 和 `is_deleted = 1`

---

#### 2. 文件软删除 (`FileNodeMapper.softDeleteFile`)

**修改前**:
```sql
UPDATE file_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    path = #{recycleBinPath},                    -- ❌ 修改路径
    original_folder_id = folder_id,              -- ❌ 保存原文件夹ID
    original_path = path,                        -- ❌ 保存原路径
    folder_id = NULL,                            -- ❌ 清空文件夹ID
    updated_at = NOW()
WHERE id = #{id}
```

**修改后**:
```sql
UPDATE file_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    updated_at = NOW()
WHERE id = #{id}
```

**改进**:
- ✅ 保留 `folder_id` 不变
- ✅ 保留 `path` 不变
- ✅ 仅标记状态为 `in_recycle_bin` 和 `is_deleted = 1`

---

#### 3. 递归软删除子文件夹 (`FolderNodeMapper.softDeleteAllChildrenFolders`)

**修改前**:
```sql
UPDATE folder_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    path = CONCAT(#{recycleBinPath}, SUBSTRING(path, LENGTH(#{oldPathPrefix}) + 1)),  -- ❌ 重构路径
    updated_at = NOW()
WHERE parent_id = #{folderId} AND is_deleted = 0
```

**修改后**:
```sql
UPDATE folder_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    updated_at = NOW()
WHERE parent_id = #{folderId} AND is_deleted = 0
```

**改进**:
- ✅ 不再重构 `path`
- ✅ 保留原有目录结构

---

#### 4. 递归软删除子文件 (`FileNodeMapper.softDeleteAllFilesInFolder`)

**修改前**:
```sql
UPDATE file_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    path = CONCAT(#{recycleBinPath}, SUBSTRING(path, LENGTH(#{oldPathPrefix}) + 1)),  -- ❌ 重构路径
    updated_at = NOW()
WHERE folder_id = #{folderId} AND is_deleted = 0
```

**修改后**:
```sql
UPDATE file_nodes SET 
    directory_status = 'in_recycle_bin',
    is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = #{expiresAt},
    updated_at = NOW()
WHERE folder_id = #{folderId} AND is_deleted = 0
```

**改进**:
- ✅ 不再重构 `path`
- ✅ 保留原有目录结构

---

## 🎯 核心优势

### 1. **保持目录结构完整性**
- 文件和文件夹仍然保留在原来的位置
- `parent_id` / `folder_id` 关系不变
- `path` 路径保持不变

### 2. **简化恢复逻辑**
- 恢复时不需要从 `original_parent_id` / `original_folder_id` 还原
- 恢复时不需要重新构建 `path`
- 只需将状态改回 `active` 和 `is_deleted = 0`

### 3. **减少数据冗余**
- 不再需要存储 `original_parent_id` / `original_folder_id`
- 不再需要存储 `original_path`
- 数据库字段使用更高效

### 4. **提高性能**
- 删除操作更快（不需要更新 path 字段）
- 减少了字符串拼接操作
- 降低了数据库 I/O

### 5. **逻辑更清晰**
- 删除只是"标记"，不是"移动"
- 符合软删除的标准实践
- 更容易理解和维护

---

## 📊 对比表

| 特性 | 旧方案 | 新方案 |
|------|--------|--------|
| 修改 `parent_id`/`folder_id` | ✅ 是（设为NULL） | ❌ 否（保持不变） |
| 修改 `path` | ✅ 是（改为回收站路径） | ❌ 否（保持不变） |
| 保存原始位置 | ✅ 是（original_* 字段） | ❌ 否（不需要） |
| 目录结构变化 | ✅ 是（移动到回收站） | ❌ 否（原地标记） |
| 恢复复杂度 | 🔴 高（需要还原位置和路径） | 🟢 低（只需改状态） |
| 性能 | 🟡 中等 | 🟢 高 |

---

## 🔄 对现有功能的影响

### 1. **浏览回收站**
- ✅ **无影响**：通过 `directory_status = 'in_recycle_bin'` 和 `is_deleted = 1` 过滤
- 查询条件示例：
  ```sql
  SELECT * FROM folder_nodes 
  WHERE user_id = #{userId} 
    AND is_deleted = 1 
    AND directory_status = 'in_recycle_bin'
  ```

### 2. **恢复节点**
- ✅ **简化**：不再需要从 `original_parent_id` 还原
- 恢复逻辑只需：
  ```sql
  UPDATE folder_nodes SET 
      directory_status = 'active',
      is_deleted = 0,
      deleted_at = NULL,
      delete_expires_at = NULL
  WHERE id = #{id}
  ```

### 3. **彻底删除**
- ✅ **无影响**：直接物理删除节点
- 不受 path 是否修改的影响

### 4. **异步删除**
- ✅ **无影响**：异步服务也使用相同的软删除方法
- 所有子节点都只标记状态，不修改位置

---

## ⚠️ 注意事项

### 1. **数据库字段保留**
虽然新方案不再使用 `original_parent_id`、`original_folder_id`、`original_path` 字段，但建议：
- ✅ 保留这些字段（向后兼容）
- ✅ 可以在未来版本中移除
- ✅ 或者用于其他用途

### 2. **恢复逻辑调整**
如果之前有代码依赖 `original_parent_id` 等字段进行恢复，需要同步修改：
- 移除对 `original_*` 字段的读取
- 直接使用当前的 `parent_id` / `folder_id` 和 `path`

### 3. **前端展示**
- 回收站中的文件仍然显示在原位置
- 可以通过 UI 标识（如图标、颜色）区分已删除文件
- 或者在回收站专用页面中展示

---

## 🧪 测试建议

### 1. 删除文件夹测试
```bash
# 删除一个包含子文件夹和文件的文件夹
DELETE /files/delete?nodeId=123&nodeType=0&version=1&batchId={uuid}

# 验证：
# - parent_id 未改变
# - path 未改变
# - directory_status = 'in_recycle_bin'
# - is_deleted = 1
```

### 2. 删除文件测试
```bash
# 删除一个文件
DELETE /files/delete?nodeId=456&nodeType=1&version=1&batchId={uuid}

# 验证：
# - folder_id 未改变
# - path 未改变
# - directory_status = 'in_recycle_bin'
# - is_deleted = 1
```

### 3. 恢复测试
```bash
# 恢复刚才删除的节点
POST /recycle/restore?batchId={uuid}&version=2

# 验证：
# - directory_status = 'active'
# - is_deleted = 0
# - parent_id/folder_id 仍然是原来的值
# - path 仍然是原来的值
```

### 4. 浏览回收站测试
```bash
# 浏览回收站
GET /files/recycle?maxPageSize=20

# 验证：
# - 返回的节点 directory_status = 'in_recycle_bin'
# - 节点的 path 仍然是原始路径
# - 节点的 parent_id/folder_id 仍然是原始值
```

---

## 📝 总结

本次优化实现了**纯标记式软删除**，主要改进：

1. ✅ **不修改位置信息**：`parent_id`、`folder_id`、`path` 保持不变
2. ✅ **简化数据结构**：不再需要 `original_*` 字段
3. ✅ **提高性能**：减少数据库更新操作
4. ✅ **简化恢复**：只需修改状态字段
5. ✅ **逻辑清晰**：删除是"标记"而非"移动"

所有修改已通过编译检查，可以进行集成测试。

---

**修改完成时间**: 2026-06-07  
**修改人员**: AI Assistant  
**审核状态**: 待测试验证
