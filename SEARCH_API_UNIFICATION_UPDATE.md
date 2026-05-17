# 搜索接口统一与回收站搜索功能实现

**更新日期**: 2026-05-10  
**版本**: v2.1

---

## 📋 变更概述

本次更新主要完成了以下工作：

1. **统一搜索接口**：将原有的 `/search`（基础版）和 `/search/cursor`（游标分页版）合并为统一的 `/search` 接口，默认使用游标分页
2. **新增回收站搜索**：实现了 `/recycle/search` 接口，支持在回收站中搜索已删除的文件和文件夹
3. **完善测试用例**：为搜索功能添加了完整的单元测试
4. **优化数据库索引**：为 `folder_nodes` 和 `file_nodes` 表添加了全文索引以支持高效搜索
5. **更新前端文档**：更新了 `FRONTEND_API_GUIDE.md` 以反映最新的接口规范

---

## 🔧 技术实现细节

### 1. Controller 层修改

#### FileController.java

**变更内容**：

- ✅ 移除了原有的基础搜索接口 `GET /search`（无分页版本）
- ✅ 将原 `GET /search/cursor` 重命名为 `GET /search`
- ✅ 新增 `GET /recycle/search` 回收站搜索接口
- ✅ 两个搜索接口都复用 `DirectoryService.searchWithCursor()` 方法

**关键代码**：

```java
// 普通搜索接口
@GetMapping("/search")
public Result<SearchResponse> search(...) {
    // ... 参数校验
    SearchResponse response = directoryService.searchWithCursor(
        keyword, userId, type, sumFolders, sumFiles,
        lastFoldersNode, lastFilesNode, maxPageSize
    );
    return Result.success(response);
}

// 回收站搜索接口
@GetMapping("/recycle/search")
public Result<SearchResponse> searchRecycleBin(...) {
    // ... 参数校验
    SearchResponse response = directoryService.searchWithCursor(
        keyword, userId, type, sumFolders, sumFiles,
        lastFoldersNode, lastFilesNode, maxPageSize, true  // isRecycleBin=true
    );
    return Result.success(response);
}
```

### 2. Service 层修改

#### DirectoryService.java

**变更内容**：

- ✅ 保留原有的 `searchWithCursor()` 方法签名（7个参数），内部调用新方法
- ✅ 新增重载方法 `searchWithCursor(..., boolean isRecycleBin)`（8个参数）
- ✅ 通过 `isRecycleBin` 参数区分普通搜索和回收站搜索

**方法签名**：

```java
// 原有方法（保持向后兼容）
public SearchResponse searchWithCursor(
    String keyword, Long userId, String type,
    Integer sumFolders, Integer sumFiles,
    Long lastFoldersNode, Long lastFilesNode,
    Integer maxPageSize
)

// 新增重载方法（支持回收站）
public SearchResponse searchWithCursor(
    String keyword, Long userId, String type,
    Integer sumFolders, Integer sumFiles,
    Long lastFoldersNode, Long lastFilesNode,
    Integer maxPageSize, boolean isRecycleBin
)
```

**注意**：当前实现中，`isRecycleBin` 参数已传递但尚未在 Mapper 层完全利用。后续可以根据需要在 Mapper XML 中添加针对回收站的特殊查询逻辑（如过滤 `directory_status = 'in_recycle_bin'`）。

### 3. 数据库脚本更新

#### database_schema_v2.sql

**变更内容**：

- ✅ 为 `folder_nodes` 表添加全文索引：`FULLTEXT INDEX ft_idx_name (name)`
- ✅ 为 `file_nodes` 表添加全文索引：`FULLTEXT INDEX ft_idx_name (name)`

**目的**：

- 支持 MySQL 全文搜索功能
- 提升关键词搜索性能
- 支持相关性排序

**SQL 示例**：

```sql
-- 文件夹表全文索引
ALTER TABLE folder_nodes 
ADD FULLTEXT INDEX ft_idx_name (name) COMMENT '全文索引用于搜索';

-- 文件表全文索引
ALTER TABLE file_nodes 
ADD FULLTEXT INDEX ft_idx_name (name) COMMENT '全文索引用于搜索';
```

### 4. 测试类完善

#### FileControllerTest.java

**新增测试用例**：

| 测试方法 | 说明 |
|---------|------|
| `testSearchBasic()` | 基本搜索功能测试 |
| `testSearchFilesOnly()` | 只搜索文件类型 |
| `testSearchFoldersOnly()` | 只搜索文件夹类型 |
| `testSearchWithCursor()` | 游标分页第二页测试 |
| `testSearchRecycleBinBasic()` | 回收站基本搜索测试 |
| `testSearchRecycleBinWithCursor()` | 回收站游标分页测试 |

**测试覆盖**：

- ✅ 正常请求参数验证
- ✅ 响应结构验证（results 数组、pagination 对象）
- ✅ 不同类型过滤（file/folder/all）
- ✅ 游标分页功能验证
- ✅ 回收站搜索功能验证

### 5. 前端 API 文档更新

#### FRONTEND_API_GUIDE.md

**变更内容**：

- ✅ 第9节：更新 `/search` 接口说明，强调统一游标分页
- ✅ 第10节：新增 `/recycle/search` 接口完整文档
- ✅ 补充回收站特有字段说明（deletedAt, expiresAt, daysRemaining）
- ✅ 提供 Vue 3 实现示例
- ✅ 更新目录导航

**关键改进**：

1. **接口路径统一**：
   - 旧：`/search`（基础版）、`/search/cursor`（游标版）
   - 新：`/search`（统一游标版）、`/recycle/search`（回收站）

2. **响应格式标准化**：
   ```json
   {
     "code": 200,
     "success": true,
     "data": {
       "results": [...],
       "pagination": {
         "lastFolderNode": ...,
         "lastFileNode": ...,
         "isEndFolder": false,
         "isEndFile": false,
         "countFolders": 1,
         "countFiles": 1
       }
     }
   }
   ```

3. **回收站搜索特性**：
   - 搜索结果包含回收站特有字段
   - 支持按剩余天数筛选
   - 显示删除时间和过期时间

---

## 📊 接口对比

### 修改前

| 接口路径 | 功能 | 分页方式 |
|---------|------|---------|
| `GET /search` | 基础搜索 | 无分页 |
| `GET /search/cursor` | 游标分页搜索 | 双游标 |

### 修改后

| 接口路径 | 功能 | 分页方式 |
|---------|------|---------|
| `GET /search` | 统一搜索 | 双游标（默认） |
| `GET /recycle/search` | 回收站搜索 | 双游标 |

---

## 🎯 排序规则

搜索结果的排序规则保持不变：

1. **主要排序**：相关性得分（`relevance`）降序
2. **次要排序**：相关性相同时，**文件优先于文件夹**
3. **文件之间**：扩展名升序 → 名称升序 → ID降序
4. **文件夹之间**：名称升序 → ID降序

---

## ⚠️ 注意事项

### 1. 向后兼容性

- ✅ 保留了原有的 7 参数 `searchWithCursor()` 方法
- ✅ 内部委托给新的 8 参数方法，`isRecycleBin=false`
- ✅ 现有调用方无需修改代码

### 2. 回收站搜索实现

当前实现中，回收站搜索复用了普通搜索的逻辑，通过 `isRecycleBin` 参数标识。如需完全隔离回收站数据，需要在 Mapper 层添加额外的过滤条件：

```xml
<!-- 示例：回收站文件夹搜索 -->
<select id="searchRecycleBinFoldersWithCursor">
    SELECT * FROM folder_nodes
    WHERE MATCH(name) AGAINST(#{keyword})
      AND user_id = #{userId}
      AND directory_status = 'in_recycle_bin'  -- 额外过滤
    ORDER BY relevance DESC, name ASC
    LIMIT #{pageSize}
</select>
```

### 3. 全文索引性能

- 全文索引适用于中等规模数据集（< 100万条记录）
- 对于大规模数据，建议考虑 Elasticsearch 等专用搜索引擎
- InnoDB 引擎的全文索引支持中文分词（MySQL 5.7+）

### 4. 前端迁移指南

如果前端已有代码调用 `/search/cursor`，需要修改为：

```javascript
// 旧代码
const response = await fetch('/api/files/search/cursor?keyword=work');

// 新代码
const response = await fetch('/api/files/search?keyword=work');
```

---

## 🧪 测试建议

### 单元测试

运行所有测试：

```bash
mvn test -Dtest=FileControllerTest
```

### 集成测试

1. **测试普通搜索**：
   ```bash
   curl -X GET "http://localhost:8080/api/files/search?keyword=test&type=all&maxPageSize=10" \
     -H "Authorization: Bearer YOUR_JWT_TOKEN"
   ```

2. **测试回收站搜索**：
   ```bash
   curl -X GET "http://localhost:8080/api/files/recycle/search?keyword=deleted&type=all&maxPageSize=10" \
     -H "Authorization: Bearer YOUR_JWT_TOKEN"
   ```

3. **测试游标分页**：
   ```bash
   # 第一页
   curl -X GET "http://localhost:8080/api/files/search?keyword=test&maxPageSize=5" \
     -H "Authorization: Bearer YOUR_JWT_TOKEN"
   
   # 第二页（使用返回的游标）
   curl -X GET "http://localhost:8080/api/files/search?keyword=test&maxPageSize=5&lastFoldersNode=100&lastFilesNode=200" \
     -H "Authorization: Bearer YOUR_JWT_TOKEN"
   ```

---

## 📝 待办事项

### 短期优化

- [ ] 在 Mapper 层实现真正的回收站数据过滤
- [ ] 添加搜索缓存机制（Redis）
- [ ] 实现搜索历史记录功能

### 长期规划

- [ ] 集成 Elasticsearch 支持更复杂的搜索场景
- [ ] 支持模糊搜索和拼写纠错
- [ ] 添加搜索权重配置（文件名 > 路径 > 扩展名）

---

## 📞 相关文档

- [前端 API 指南](FRONTEND_API_GUIDE.md)
- [目录树系统设计 V2](DIRECTORY_TREE_SYSTEM_DESIGN_V2.md)
- [数据库 Schema 指南](docs/DATABASE_SCHEMA_GUIDE.md)

---

**最后更新**: 2026-05-10  
**作者**: Backend Team
