# 乐观锁版本号 - 快速参考

## 📌 修改文件清单

| 文件 | 修改内容 | 行号 |
|------|---------|------|
| `DirectoryNodeVO.java` | 添加 `version` 字段 | L77-80 |
| `SearchResultVO.java` | 添加 `version` 字段 | L69-72 |
| `DirectoryService.java` | `convertFolderToVO()` 设置 version | L707 |
| `DirectoryService.java` | `convertFileToVO()` 设置 version | L751 |
| `DirectoryService.java` | `convertToSearchResults()` 设置 version | L1453-1457 |

---

## 🔍 代码位置速查

### 1. DirectoryNodeVO.java

```java
// 位置：L77-80
/**
 * 乐观锁版本号，用于并发控制
 */
private Long version;
```

### 2. SearchResultVO.java

```java
// 位置：L69-72
/**
 * 乐观锁版本号，用于并发控制
 */
private Long version;
```

### 3. DirectoryService.java - convertFolderToVO()

```java
// 位置：L707
vo.setVersion(folder.getVersion());
```

### 4. DirectoryService.java - convertFileToVO()

```java
// 位置：L751
vo.setVersion(file.getVersion());
```

### 5. DirectoryService.java - convertToSearchResults()

```java
// 位置：L1453-1457
// 版本号
Object versionObj = record.get("version");
if (versionObj != null) {
    vo.setVersion(((Number) versionObj).longValue());
}
```

---

## 🎯 影响的 API

### ✅ 包含 version 字段的接口

1. **GET /files/browse** - 浏览目录
2. **GET /files/recycle-bin/browse** - 浏览回收站
3. **GET /files/search** - 搜索文件/文件夹
4. **GET /files/recycle-bin/search** - 搜索回收站

### 📊 响应示例

```json
{
  "success": true,
  "data": {
    "currentNode": {
      "id": 1001,
      "name": "documents",
      "type": "folder",
      "version": 3  // ← 新增
    },
    "children": [
      {
        "id": 2001,
        "name": "file.pdf",
        "type": "file",
        "version": 2  // ← 新增
      }
    ]
  }
}
```

---

## 💡 使用示例

### 前端获取 version

```javascript
// 1. 浏览目录获取 version
const response = await fetch('/api/files/browse?currentNodeId=1001');
const data = await response.json();

const folder = data.data.children[0];
console.log('Folder version:', folder.version); // 例如: 3

// 2. 更新时携带 version
await fetch('/api/files/rename', {
  method: 'PUT',
  body: JSON.stringify({
    nodeId: folder.id,
    name: 'new_name',
    version: folder.version  // ← 携带当前版本号
  })
});
```

### 后端乐观锁检查（待实现）

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

---

## ⚠️ 注意事项

### 1. 数据库字段必须存在

```sql
-- 检查字段
SHOW COLUMNS FROM folder_nodes LIKE 'version';
SHOW COLUMNS FROM file_nodes LIKE 'version';

-- 如果不存在，需要添加
ALTER TABLE folder_nodes ADD COLUMN version BIGINT DEFAULT 0;
ALTER TABLE file_nodes ADD COLUMN version BIGINT DEFAULT 0;
```

### 2. Entity 类必须包含 version

确认以下文件已有 version 字段：
- ✅ `FolderNode.java` - L79
- ✅ `FileNode.java` - L76

### 3. Mapper 查询必须返回 version

确保 SQL 查询包含 version 字段：
```sql
SELECT id, name, ..., version FROM folder_nodes WHERE ...
```

或使用 `SELECT *`。

---

## 🔧 测试命令

### 测试浏览目录

```bash
curl -X GET "http://localhost:8080/files/browse?currentNodeId=1001" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" | jq '.data.currentNode.version'
```

### 测试搜索

```bash
curl -X GET "http://localhost:8080/files/search?keyword=test" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" | jq '.data.results[0].version'
```

---

## 📖 相关文档

- [完整实现文档](./BROWSE_DIRECTORY_VERSION_FIELD.md)
- [乐观锁运作模式](memory://fddb0316-5e7a-47f7-96f7-09d1fae8348f)
- [DATABASE_SCHEMA_GUIDE.md](./docs/DATABASE_SCHEMA_GUIDE.md)

---

## ✨ 完成状态

- ✅ DirectoryNodeVO 添加 version 字段
- ✅ SearchResultVO 添加 version 字段
- ✅ convertFolderToVO() 设置 version
- ✅ convertFileToVO() 设置 version
- ✅ convertToSearchResults() 设置 version
- ✅ 无编译错误
- ✅ 文档已更新

**最后更新**: 2026-06-05
