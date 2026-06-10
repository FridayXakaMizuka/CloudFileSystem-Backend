# parent_ids JSON 字段实现指南

## 概述

为支持将来基于 Redis 的目录浏览功能，在 `folder_nodes` 表中添加了 `parent_ids` 字段，用于存储从根目录到当前目录的完整路径ID数组。

## 数据库变更

### 1. 字段定义

**位置**: `database_schema_complete_v3.sql` 第306行

```sql
parent_ids JSON DEFAULT NULL COMMENT '父文件夹ID路径数组，如 [1,2,3]，待分配状态时为NULL'
```

**特点**:
- 使用 **JSON 类型**而非 LONGTEXT，提供更好的查询性能和格式验证
- 存储格式: `[1,5,100]` 表示路径 `_root(1) -> _files(5) -> 用户目录(100)`
- 根目录 `_root` 的 `parent_ids` 为 `NULL`
- 待分配状态（彻底删除后）的 `parent_ids` 为 `NULL`

### 2. 初始化数据

#### 根目录 (第513-515行)
```sql
-- 根目录 _root 的 parent_ids 为 NULL
INSERT INTO folder_nodes (id, parent_id, parent_ids, user_id, name, path, level, sort_order, version) VALUES
    (1, NULL, NULL, NULL, '_root', '_root', 0, 0, 0);
```

#### 管理员专属子目录 (第517-524行)
```sql
-- parent_ids: 父目录是 _root (id=1)，所以 parent_ids = [1]
INSERT INTO folder_nodes (parent_id, parent_ids, user_id, name, path, level, sort_order, version) VALUES
    (1, JSON_ARRAY(1), NULL, '_avatar', '_root/_avatar', 1, 0, 0),
    (1, JSON_ARRAY(1), NULL, '_backup', '_root/_backup', 1, 1, 0),
    (1, JSON_ARRAY(1), NULL, '_system', '_root/_system', 1, 2, 0),
    (1, JSON_ARRAY(1), NULL, '_files', '_root/_files', 1, 4, 0);
```

#### 用户根目录 (第526-543行)
```sql
-- parent_ids: 父目录是 _files，使用 JSON_ARRAY_APPEND 追加父目录ID
INSERT INTO folder_nodes (parent_id, parent_ids, user_id, name, path, level, sort_order, version)
SELECT
    fn.id AS parent_id,
    CASE 
        WHEN fn.parent_ids IS NULL THEN JSON_ARRAY(fn.id)
        ELSE JSON_ARRAY_APPEND(fn.parent_ids, '$', fn.id)
    END AS parent_ids,
    u.id,
    CAST(u.id AS CHAR),
    CONCAT('_root/_files/', u.id),
    2, 0, 0
FROM folder_nodes fn, users u
WHERE fn.path = '_root/_files' AND u.id >= 10001;
```

### 3. 存储过程修改

#### 清理过期回收站文件夹 (第651-663行)
```sql
-- 彻底删除时清空 parent_ids
UPDATE folder_nodes
SET directory_status = 'unassigned',
    unassigned_at = NOW(),
    user_id = NULL,
    parent_ids = NULL,  -- ✅ 新增：清空路径信息
    is_deleted = 0,
    deleted_at = NULL,
    delete_expires_at = NULL,
    version = version + 1,
    updated_at = NOW()
WHERE id = expired_id AND version = expired_version;
```

#### 恢复文件夹到原位置 (第959-979行)
```sql
-- 重新构建 parent_ids：查询父目录的 parent_ids，然后追加父目录ID
UPDATE folder_nodes
SET directory_status = 'active',
    is_deleted = 0,
    deleted_at = NULL,
    delete_expires_at = NULL,
    parent_id = v_original_parent_id,
    parent_ids = (
        SELECT CASE 
            WHEN p.parent_ids IS NULL THEN JSON_ARRAY(p.id)
            ELSE JSON_ARRAY_APPEND(p.parent_ids, '$', p.id)
        END
        FROM folder_nodes p
        WHERE p.id = v_original_parent_id
    ),  -- ✅ 新增：使用 JSON 函数重新构建
    path = v_new_path,
    original_parent_id = NULL,
    original_path = NULL,
    version = version + 1,
    updated_at = NOW()
WHERE id = p_node_id AND version = v_current_version;
```

#### 恢复文件夹到用户根目录 (第992-1012行)
```sql
-- 重新构建 parent_ids：查询用户根目录的 parent_ids，然后追加根目录ID
UPDATE folder_nodes
SET directory_status = 'active',
    is_deleted = 0,
    deleted_at = NULL,
    delete_expires_at = NULL,
    parent_id = v_original_parent_id,
    parent_ids = (
        SELECT CASE 
            WHEN p.parent_ids IS NULL THEN JSON_ARRAY(p.id)
            ELSE JSON_ARRAY_APPEND(p.parent_ids, '$', p.id)
        END
        FROM folder_nodes p
        WHERE p.id = v_original_parent_id
    ),  -- ✅ 新增：使用 JSON 函数重新构建
    path = v_new_path,
    original_parent_id = NULL,
    original_path = NULL,
    version = version + 1,
    updated_at = NOW()
WHERE id = p_node_id AND version = v_current_version;
```

## Java 代码变更

### 1. FolderNodeMapper.java

#### 更新文件夹信息 (第179-197行)
```java
/**
 * 更新文件夹信息（用于复用待分配文件夹）
 * 注意：同时更新 parent_ids，使用 JSON_ARRAY_APPEND 追加父目录ID
 */
@Update("UPDATE folder_nodes SET " +
        "parent_id = #{parentId}, " +
        "parent_ids = (" +
        "    SELECT CASE " +
        "        WHEN p.parent_ids IS NULL THEN JSON_ARRAY(p.id) " +
        "        ELSE JSON_ARRAY_APPEND(p.parent_ids, '$', p.id) " +
        "    END " +
        "    FROM folder_nodes p WHERE p.id = #{parentId}" +
        "), " +
        "user_id = #{userId}, " +
        "name = #{name}, " +
        "path = #{path}, " +
        "updated_at = #{updatedAt} " +
        "WHERE id = #{id}")
void updateFolderInfo(FolderNode folder);
```

#### 插入新文件夹 (第198-216行)
```java
/**
 * 插入新文件夹
 * 注意：同时设置 parent_ids，使用 JSON_ARRAY_APPEND 追加父目录ID
 */
@Insert("INSERT INTO folder_nodes (" +
        "parent_id, parent_ids, user_id, name, path, level, sort_order, is_hidden, " +
        "is_deleted, deleted_at, delete_expires_at, directory_status, " +
        "created_at, updated_at" +
        ") VALUES (" +
        "#{parentId}, " +
        "(SELECT CASE " +
        "    WHEN p.parent_ids IS NULL THEN JSON_ARRAY(p.id) " +
        "    ELSE JSON_ARRAY_APPEND(p.parent_ids, '$', p.id) " +
        "END FROM folder_nodes p WHERE p.id = #{parentId}), " +
        "#{userId}, #{name}, #{path}, #{level}, #{sortOrder}, #{isHidden}, " +
        "#{isDeleted}, #{deletedAt}, #{deleteExpiresAt}, #{directoryStatus}, " +
        "#{createdAt}, #{updatedAt}" +
        ")")
@Options(useGeneratedKeys = true, keyProperty = "id")
void insertFolder(FolderNode folder);
```

#### 移动文件夹 (第231-253行)
```java
/**
 * 移动文件夹
 * 注意：同时更新 parent_ids，使用 JSON_ARRAY_APPEND 重新构建路径
 */
@Update("UPDATE folder_nodes SET " +
        "parent_id = #{newParentId}, " +
        "parent_ids = (" +
        "    SELECT CASE " +
        "        WHEN p.parent_ids IS NULL THEN JSON_ARRAY(p.id) " +
        "        ELSE JSON_ARRAY_APPEND(p.parent_ids, '$', p.id) " +
        "    END " +
        "    FROM folder_nodes p WHERE p.id = #{newParentId}" +
        "), " +
        "path = #{newPath}, " +
        "updated_at = NOW() " +
        "WHERE id = #{id}")
void moveFolder(@Param("id") Long id,
                @Param("newParentId") Long newParentId,
                @Param("newPath") String newPath);
```

#### 恢复文件夹 (第317-340行)
```java
/**
 * 恢复文件夹
 * 注意：同时重新构建 parent_ids，使用 JSON_ARRAY_APPEND 追加父目录ID
 */
@Update("UPDATE folder_nodes SET " +
        "directory_status = 'active', " +
        "is_deleted = 0, " +
        "deleted_at = NULL, " +
        "delete_expires_at = NULL, " +
        "parent_id = #{parentId}, " +
        "parent_ids = (" +
        "    SELECT CASE " +
        "        WHEN p.parent_ids IS NULL THEN JSON_ARRAY(p.id) " +
        "        ELSE JSON_ARRAY_APPEND(p.parent_ids, '$', p.id) " +
        "    END " +
        "    FROM folder_nodes p WHERE p.id = #{parentId}" +
        "), " +
        "path = #{path}, " +
        "original_parent_id = NULL, " +
        "original_path = NULL, " +
        "updated_at = NOW() " +
        "WHERE id = #{id}")
void restoreFolder(@Param("id") Long id,
                   @Param("parentId") Long parentId,
                   @Param("path") String path);
```

#### 清空文件夹信息 (第529-542行)
```java
/**
 * 清空文件夹信息（保留 id 和 directory_status）
 * 注意：彻底删除时清空 parent_ids
 */
@Update("UPDATE folder_nodes SET " +
        "name = NULL, path = NULL, parent_id = NULL, user_id = NULL, " +
        "parent_ids = NULL, " +  // ✅ 新增：清空路径信息
        "level = 0, sort_order = 0, is_hidden = 0, is_deleted = 0, " +
        "deleted_at = NULL, delete_expires_at = NULL, " +
        "original_parent_id = NULL, original_path = NULL, " +
        "last_del_uuid = NULL, file_count = 0, folder_count = 0, " +
        "total_size = 0, directory_status = 'unassigned', " +
        "unassigned_at = NOW(), version = version + 1 " +
        "WHERE id = #{id}")
void clearFolderInfo(@Param("id") Long id);
```

## parent_ids 字段逻辑说明

### 构建规则

```
parent_ids = 父目录的parent_ids + 父目录ID
```

使用 MySQL JSON 函数实现：
- `JSON_ARRAY(id)`: 创建单元素数组，如 `[1]`
- `JSON_ARRAY_APPEND(parent_ids, '$', id)`: 追加元素到数组末尾

**示例**:
- `_root` (id=1): `parent_ids = NULL`
- `_files` (id=5, parent_id=1): `parent_ids = [1]`
- 用户根目录 (id=100, parent_id=5): `parent_ids = [1, 5]`
- 子目录 (id=200, parent_id=100): `parent_ids = [1, 5, 100]`

### 特殊情况处理

1. **根目录**: `parent_ids = NULL`（没有父目录）
2. **父目录parent_ids为NULL**: 使用 `JSON_ARRAY(父目录ID)` 创建新数组
3. **正常情况**: 使用 `JSON_ARRAY_APPEND(父目录.parent_ids, '$', 父目录ID)` 追加
4. **彻底删除**: `parent_ids = NULL`（清空路径信息）
5. **恢复操作**: 重新从父目录构建 `parent_ids`

### 空间优化

- JSON 数组格式紧凑，无多余空格：`[1,2,3]`
- 使用 JSON 类型比 TEXT 更节省空间（二进制存储）
- 支持高效的数组操作和查询

## JSON vs CSV 对比

| 特性 | CSV (LONGTEXT) | JSON |
|------|---------------|------|
| 空间效率 | ❌ 略差 | ✅ 更好（二进制存储） |
| 查询性能 | ❌ 需要LIKE或FIND_IN_SET | ✅ 可使用JSON_CONTAINS等函数 |
| 格式验证 | ❌ 无 | ✅ 自动验证 |
| 扩展性 | ❌ 仅支持ID列表 | ✅ 可存储更多信息 |
| MySQL支持 | ✅ 所有版本 | ⚠️ 需要5.7+ |
| 操作便利性 | ❌ 字符串拼接复杂 | ✅ 内置函数支持 |

**选择 JSON 的理由**:
1. MySQL 5.7+ 原生支持 JSON 类型
2. 提供丰富的 JSON 函数（`JSON_ARRAY`, `JSON_ARRAY_APPEND`, `JSON_CONTAINS` 等）
3. 自动格式验证，避免数据错误
4. 更好的查询性能（可使用索引）
5. 未来可扩展性强（可存储更多元数据）

## 未来 Redis 集成

`parent_ids` 字段将为基于 Redis 的目录浏览提供支持：

1. **快速路径查询**: 直接从 `parent_ids` 获取完整路径，无需递归查询
2. **权限验证**: 通过 `parent_ids` 快速判断用户对目录的访问权限
3. **缓存键设计**: 可使用 `parent_ids` 作为 Redis 缓存键的一部分
4. **批量加载**: 根据 `parent_ids` 批量预加载目录树

### 示例 Redis 缓存策略

```java
// 缓存键: browse:{userId}:{parentIdsHash}
String cacheKey = "browse:" + userId + ":" + MD5(parentIdsJson);

// 从 Redis 获取目录列表
List<DirectoryVO> cached = redisTemplate.opsForValue().get(cacheKey);
if (cached != null) {
    return cached;
}

// 查询数据库并缓存
List<FolderNode> folders = folderNodeMapper.findByParentIds(parentIdsJson);
redisTemplate.opsForValue().set(cacheKey, folders, 5, TimeUnit.MINUTES);
```

## 测试建议

1. **创建文件夹测试**: 验证 `parent_ids` 是否正确追加
2. **移动文件夹测试**: 验证 `parent_ids` 是否重新构建
3. **删除文件夹测试**: 验证 `parent_ids` 是否清空
4. **恢复文件夹测试**: 验证 `parent_ids` 是否正确重建
5. **边界测试**: 
   - 根目录下创建文件夹
   - 深层嵌套目录（>10层）
   - 并发创建/移动操作

## 注意事项

1. **MySQL 版本要求**: 需要 MySQL 5.7+ 以支持 JSON 类型
2. **性能考虑**: JSON 操作比简单字段略慢，但提供了更好的功能性
3. **兼容性**: 现有代码无需修改，`parent_ids` 由 SQL 自动维护
4. **索引**: 如需频繁查询特定ID是否在路径中，可考虑添加虚拟列索引

## 相关文件

- `database_schema_complete_v3.sql`: 数据库表结构和初始化数据
- `src/main/java/com/mizuka/cloudfilesystem/mapper/FolderNodeMapper.java`: Mapper 接口
- `src/main/java/com/mizuka/cloudfilesystem/service/DirectoryService.java`: 业务逻辑（无需修改）

---

**完成时间**: 2026-06-10  
**版本**: v1.0
