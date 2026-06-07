# 乐观锁版本号 - 文档索引

## 📚 文档导航

### 🎯 快速开始

**我是前端开发者，想快速了解如何使用：**
→ 阅读 [FRONTEND_VERSION_QUICK_REF.md](./FRONTEND_VERSION_QUICK_REF.md)

**我是后端开发者，想了解实现细节：**
→ 阅读 [BROWSE_DIRECTORY_VERSION_FIELD.md](./BROWSE_DIRECTORY_VERSION_FIELD.md)

---

### 📖 完整文档列表

#### 前端文档

| 文档 | 说明 | 适合人群 |
|------|------|---------|
| [FRONTEND_VERSION_GUIDE.md](./FRONTEND_VERSION_GUIDE.md) | 前端完整使用指南（740行）<br>- TypeScript 类型定义<br>- Vue 3 / React 示例<br>- 错误处理<br>- 最佳实践 | 需要深入理解的前端开发者 |
| [FRONTEND_VERSION_QUICK_REF.md](./FRONTEND_VERSION_QUICK_REF.md) | 前端快速参考（245行）<br>- 快速开始<br>- 代码片段<br>- 常见场景 | 需要快速查阅的前端开发者 |

#### 后端文档

| 文档 | 说明 | 适合人群 |
|------|------|---------|
| [BROWSE_DIRECTORY_VERSION_FIELD.md](./BROWSE_DIRECTORY_VERSION_FIELD.md) | 后端实现文档（339行）<br>- 修改文件清单<br>- API 响应示例<br>- 测试建议 | 需要了解实现的后端开发者 |
| [VERSION_FIELD_QUICK_REF.md](./VERSION_FIELD_QUICK_REF.md) | 后端快速参考（205行）<br>- 代码位置速查<br>- 测试命令 | 需要快速定位代码的后端开发者 |
| [VERSION_IMPLEMENTATION_SUMMARY.md](./VERSION_IMPLEMENTATION_SUMMARY.md) | 实现总结（214行）<br>- 完成状态<br>- 下一步建议 | 项目管理者、技术负责人 |

---

## 🚀 快速查找

### 我想...

#### 了解 version 字段是什么
→ [FRONTEND_VERSION_GUIDE.md - 概述](./FRONTEND_VERSION_GUIDE.md#-概述)

#### 查看 API 响应示例
→ [FRONTEND_VERSION_GUIDE.md - API 响应](./FRONTEND_VERSION_GUIDE.md#-api-响应中的-version-字段)

#### 学习如何在 Vue 中使用
→ [FRONTEND_VERSION_GUIDE.md - Vue 示例](./FRONTEND_VERSION_GUIDE.md#vue-3-示例)

#### 学习如何在 React 中使用
→ [FRONTEND_VERSION_GUIDE.md - React 示例](./FRONTEND_VERSION_GUIDE.md#react-示例)

#### 了解如何处理版本冲突
→ [FRONTEND_VERSION_GUIDE.md - 错误处理](./FRONTEND_VERSION_GUIDE.md#-错误处理)

#### 查看 TypeScript 类型定义
→ [FRONTEND_VERSION_GUIDE.md - 类型定义](./FRONTEND_VERSION_GUIDE.md#1-定义-typescript-类型)

#### 了解后端如何实现的
→ [BROWSE_DIRECTORY_VERSION_FIELD.md](./BROWSE_DIRECTORY_VERSION_FIELD.md)

#### 快速复制代码片段
→ [FRONTEND_VERSION_QUICK_REF.md](./FRONTEND_VERSION_QUICK_REF.md)

#### 查看修改了哪些文件
→ [VERSION_FIELD_QUICK_REF.md - 修改文件清单](./VERSION_FIELD_QUICK_REF.md#-修改文件清单)

#### 运行测试命令
→ [VERSION_FIELD_QUICK_REF.md - 测试命令](./VERSION_FIELD_QUICK_REF.md#-测试命令)

---

## 📊 文档结构

```
CloudFileSystem/
├── FRONTEND_VERSION_GUIDE.md          # 前端完整指南 ⭐
├── FRONTEND_VERSION_QUICK_REF.md      # 前端快速参考
├── BROWSE_DIRECTORY_VERSION_FIELD.md  # 后端实现文档
├── VERSION_FIELD_QUICK_REF.md         # 后端快速参考
├── VERSION_IMPLEMENTATION_SUMMARY.md  # 实现总结
└── VERSION_DOCS_INDEX.md              # 本文档（索引）
```

---

## 🎯 核心概念

### 什么是乐观锁版本号？

乐观锁是一种并发控制机制：

1. **读取**时获取版本号（如 `version: 3`）
2. **更新**时携带该版本号
3. **后端检查**版本号是否匹配
4. **冲突**时返回 409 错误

### 为什么要使用？

防止多人同时编辑同一文件/文件夹时，后提交的覆盖先提交的修改。

### 如何使用？

```typescript
// 1. 从 API 获取 version
const folder = await fetchFolder(1001);
console.log(folder.version); // 3

// 2. 更新时携带 version
await updateFolder(1001, newName, folder.version);

// 3. 处理冲突
try {
  await updateFolder(1001, newName, folder.version);
} catch (error) {
  if (error.status === 409) {
    // 版本冲突，刷新数据
    await refreshData();
  }
}
```

---

## 🔗 相关链接

- [乐观锁运作模式（记忆）](memory://fddb0316-5e7a-47f7-96f7-09d1fae8348f)
- [DATABASE_SCHEMA_GUIDE.md](./docs/DATABASE_SCHEMA_GUIDE.md)
- [UNIFIED_BROWSE_API_GUIDE.md](./docs/UNIFIED_BROWSE_API_GUIDE.md)

---

## 💡 使用建议

### 前端开发者

1. **首次使用**：阅读 [FRONTEND_VERSION_GUIDE.md](./FRONTEND_VERSION_GUIDE.md) 的"快速开始"章节
2. **日常开发**：收藏 [FRONTEND_VERSION_QUICK_REF.md](./FRONTEND_VERSION_QUICK_REF.md) 作为速查手册
3. **遇到问题**：查看"错误处理"和"最佳实践"章节

### 后端开发者

1. **了解实现**：阅读 [BROWSE_DIRECTORY_VERSION_FIELD.md](./BROWSE_DIRECTORY_VERSION_FIELD.md)
2. **定位代码**：使用 [VERSION_FIELD_QUICK_REF.md](./VERSION_FIELD_QUICK_REF.md) 的代码位置速查
3. **测试验证**：参考"测试命令"章节

### 技术负责人

1. **了解进度**：查看 [VERSION_IMPLEMENTATION_SUMMARY.md](./VERSION_IMPLEMENTATION_SUMMARY.md)
2. **规划下一步**：参考"下一步建议"章节

---

## ✨ 文档特点

| 特性 | 说明 |
|------|------|
| **完整性** | 涵盖前后端实现、使用示例、最佳实践 |
| **实用性** | 提供可直接复制的代码片段 |
| **易读性** | 清晰的章节划分和目录导航 |
| **多框架支持** | 包含 Vue 3 和 React 示例 |
| **快速查阅** | 提供快速参考手册 |

---

## 📝 更新记录

| 日期 | 更新内容 |
|------|---------|
| 2026-06-05 | 创建所有文档，完成实现 |

---

**最后更新**: 2026-06-05  
**维护者**: CloudFileSystem Team
