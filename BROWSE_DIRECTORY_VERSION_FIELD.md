# 目录浏览和搜索添加乐观锁版本号

## 📋 修改概述

在目录浏览（browse）和搜索（search）功能中添加了乐观锁版本号（version）字段，使前端能够获取每个节点当前的版本号，用于后续的并发控制操作。

---

## ✅ 已完成的修改

### 1. **DirectoryNodeVO.java** - 添加 version 字段

**文件位置**: `src/main/java/com/mizuka/cloudfilesystem/dto/DirectoryNodeVO.java`

**新增字段**:
```java
/**
 * 乐观锁版本号，用于并发控制
 */
private Long version;
```

**位置**: 在 `updatedAt` 字段之后，回收站特有字段之前

---

### 2. **SearchResultVO.java** - 添加 version 字段

**文件位置**: `src/main/java/com/mizuka/cloudfilesystem/dto/SearchResultVO.java`

**新增字段**:
```java
/**
 * 乐观锁版本号，用于并发控制
 */
private Long version;
```

**位置**: 在 `createdAt` 字段之后

---

### 3. **DirectoryService.java** - 设置 version 值

**文件位置**: `src/main/java/com/mizuka/cloudfilesystem/service/DirectoryService.java`

#### 修改点 1: `convertFolderToVO()` 方法

在转换文件夹实体为 VO 时，添加 version 字段赋值：

```java
private DirectoryNodeVO convertFolderToVO(FolderNode folder, Map<Long, Integer> childCountMap) {
    DirectoryNodeVO vo = new DirectoryNodeVO();
    vo.setId(folder.getId());
    vo.setName(folder.getName());
    vo.setType("folder");
    vo.setPath(folder.getPath());
    vo.setParentId(folder.getParentId());
    vo.setCreatedAt(folder.getCreatedAt());
    vo.setUpdatedAt(folder.getUpdatedAt());
    vo.setVersion(folder.getVersion());  // ← 新增
    
    // ... 其他逻辑
}
```

#### 修改点 2: `convertFileToVO()` 方法

在转换文件实体为 VO 时，添加 version 字段赋值：

```java
private DirectoryNodeVO convertFileToVO(FileNode file) {
    DirectoryNodeVO vo = new DirectoryNodeVO();
    vo.setId(file.getId());
    vo.setName(file.getName());
    vo.setType("file");
    vo.setPath(file.getPath());
    vo.setParentId(file.getFolderId());
    vo.setSize(file.getFileSize());
    vo.setMimeType(file.getMimeType());
    vo.setExtension(file.getExtension());
    vo.setCreatedAt(file.getCreatedAt());
    vo.setUpdatedAt(file.getUpdatedAt());
    vo.setVersion(file.getVersion());  // ← 新增
    
    // ... 其他逻辑
}
```

#### 修改点 3: `convertToSearchResults()` 方法

在转换搜索结果为 VO 时，添加 version 字段赋值：

```java
private List<SearchResultVO> convertToSearchResults(List<Map<String, Object>> records) {
    return records.stream().map(record -> {
        SearchResultVO vo = new SearchResultVO();
        vo.setId(((Number) record.get("id")).longValue());
        vo.setName((String) record.get("name"));
        vo.setType((String) record.get("node_type"));
        vo.setPath((String) record.get("path"));
        vo.setRelevance(((Number) record.get("relevance")).doubleValue());
        
        // ... 其他字段 ...
        
        // 版本号
        Object versionObj = record.get("version");
        if (versionObj != null) {
            vo.setVersion(((Number) versionObj).longValue());  // ← 新增
        }
        
        return vo;
    }).collect(Collectors.toList());
}
```

---

## 📊 API 响应示例

### 浏览目录响应（包含 version 字段）

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
      "version": 3  // ← 新增字段
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
        "version": 1  // ← 新增字段
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
        "version": 2  // ← 新增字段
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

---

## 🔧 使用场景

### 1. **前端并发控制**

前端在执行更新操作时，可以携带 version 字段进行乐观锁检查：

```javascript
// 前端代码示例
async function updateFolderName(folderId, newName, currentVersion) {
  try {
    const response = await fetch(`/api/files/rename?nodeId=${folderId}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name: newName,
        version: currentVersion  // 携带当前版本号
      })
    });
    
    if (response.status === 409) {
      // 版本冲突，提示用户刷新数据
      alert('该文件夹已被其他人修改，请刷新后重试');
      refreshFolderList();
    }
  } catch (error) {
    console.error('更新失败:', error);
  }
}
```

### 2. **后端乐观锁检查**

后端在执行更新时，可以通过 WHERE 条件检查版本号：

```sql
UPDATE folder_nodes 
SET name = #{newName},
    version = version + 1,
    updated_at = NOW()
WHERE id = #{id} AND version = #{expectedVersion};
```

如果返回的影响行数为 0，说明版本号不匹配，更新失败。

---

## 📝 版本号规则

| 属性 | 值 |
|------|-----|
| **数据类型** | `BIGINT` (Java: `Long`) |
| **初始值** | `0` |
| **递增规则** | 每次更新自动 `+1` |
| **查询时机** | 浏览目录时返回给前端 |
| **更新时机** | 重命名、移动、删除等操作时递增 |

---

## 🎯 影响范围

### ✅ 影响的接口

以下接口的响应中都会包含 `version` 字段：

1. **GET /files/browse** - 浏览目录内容
2. **GET /files/recycle-bin/browse** - 浏览回收站内容
3. **GET /files/search** - 搜索文件或文件夹
4. **GET /files/recycle-bin/search** - 搜索回收站内容

### ❌ 不受影响的接口

- 搜索接口（需要单独添加）
- 文件上传接口
- 其他不涉及目录浏览的接口

---

## 🔍 测试建议

### 1. **基本功能测试**

```bash
# 测试浏览目录是否返回 version 字段
curl -X GET "http://localhost:8080/files/browse?currentNodeId=1001" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

**预期结果**: 响应中的 `currentNode` 和 `children` 都包含 `version` 字段

### 2. **版本号一致性测试**

```bash
# 1. 浏览目录，记录 version
# 2. 执行更新操作（如重命名）
# 3. 再次浏览目录，检查 version 是否 +1
```

### 3. **并发冲突测试**

```bash
# 1. 两个客户端同时浏览同一目录，获取相同的 version
# 2. 客户端 A 先更新，version 从 3 → 4
# 3. 客户端 B 尝试用 version=3 更新，应该失败
```

---

## ⚠️ 注意事项

### 1. **数据库字段必须存在**

确保 `folder_nodes` 和 `file_nodes` 表中已有 `version` 字段：

```sql
-- 检查字段是否存在
SHOW COLUMNS FROM folder_nodes LIKE 'version';
SHOW COLUMNS FROM file_nodes LIKE 'version';

-- 如果不存在，需要添加
ALTER TABLE folder_nodes ADD COLUMN version BIGINT DEFAULT 0 COMMENT '乐观锁版本号';
ALTER TABLE file_nodes ADD COLUMN version BIGINT DEFAULT 0 COMMENT '乐观锁版本号';
```

### 2. **Entity 类必须包含 version 字段**

确认 `FolderNode.java` 和 `FileNode.java` 中已有 version 字段：

```java
// FolderNode.java 和 FileNode.java
private Long version;
```

### 3. **Mapper 查询必须包含 version**

确保 MyBatis 的 SELECT 语句中包含 `version` 字段：

```xml
<!-- 如果使用 XML 配置 -->
<select id="findById" resultType="FolderNode">
    SELECT id, parent_id, name, path, ..., version 
    FROM folder_nodes 
    WHERE id = #{id}
</select>
```

如果使用注解方式（`@Select`），确保 `SELECT *` 或明确列出 `version` 字段。

---

## 🚀 后续优化建议

### 1. **搜索接口也添加 version**

在搜索结果中也返回 version 字段，方便用户在搜索结果上直接操作。

### 2. **实现完整的乐观锁更新**

在所有写操作（重命名、移动、删除等）中使用乐观锁：

```java
@Update("UPDATE folder_nodes SET " +
        "name = #{name}, " +
        "version = version + 1, " +
        "updated_at = NOW() " +
        "WHERE id = #{id} AND version = #{version}")
int updateWithOptimisticLock(@Param("id") Long id,
                              @Param("name") String name,
                              @Param("version") Long version);
```

### 3. **添加版本冲突处理**

当检测到版本冲突时，返回明确的错误码和消息：

```java
if (affectedRows == 0) {
    throw new OptimisticLockException("数据已被其他人修改，请刷新后重试");
}
```

HTTP 状态码建议使用 `409 Conflict`。

---

## 📖 相关文档

- [乐观锁版本号格式与运作模式](./memory://fddb0316-5e7a-47f7-96f7-09d1fae8348f)
- [DATABASE_SCHEMA_GUIDE.md](./docs/DATABASE_SCHEMA_GUIDE.md)
- [UNIFIED_BROWSE_API_GUIDE.md](./docs/UNIFIED_BROWSE_API_GUIDE.md)

---

## ✨ 总结

本次修改成功在目录浏览和搜索功能中添加了乐观锁版本号支持：

✅ **DirectoryNodeVO** 新增 `version` 字段  
✅ **SearchResultVO** 新增 `version` 字段  
✅ **convertFolderToVO()** 设置文件夹版本号  
✅ **convertFileToVO()** 设置文件版本号  
✅ **convertToSearchResults()** 设置搜索结果版本号  
✅ 所有浏览目录和搜索的 API 响应都包含 version 字段  
✅ 无编译错误，向后兼容  

前端现在可以获取每个节点的版本号，并在后续操作中实现并发控制。
