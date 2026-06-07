# 回收站接口快速参考

> **版本**: v1.0  
> **更新日期**: 2026-06-07

---

## 📌 接口列表

### 1. 恢复节点
**接口**: `POST /recycle/restore`

**参数**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| batchId | String | ✅ | 业务操作批次号（UUID） |
| version | Long | ✅ | 乐观锁版本号 |

**示例**:
```bash
POST /recycle/restore?batchId=550e8400-e29b-41d4-a716-446655440000&version=2
```

**响应**:
- 成功（原位置）: HTTP 200
- 成功（根目录）: HTTP 204
- 失败: HTTP 400/401/403/404/410/500

---

### 2. 彻底删除
**接口**: `DELETE /files/delete/permanent`

**参数**:
| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| mode | Boolean | ✅ | true=回收站模式, false=浏览模式 |
| batchId | String | 条件 | mode=true 时必填 |
| nodeId | Long | 条件 | mode=false 时必填 |
| version | Long | 条件 | mode=false 时建议填写 |

**示例**:
```bash
# 回收站模式
DELETE /files/delete/permanent?mode=true&batchId=550e8400-e29b-41d4-a716-446655440000

# 浏览界面模式
DELETE /files/delete/permanent?mode=false&nodeId=12345&version=3
```

**响应**:
- 成功: HTTP 200
- 失败: HTTP 400/401/403/404/500

---

### 3. 获取恢复进程列表
**接口**: `GET /recycle/restore/processes`

**参数**: 无

**示例**:
```bash
GET /recycle/restore/processes
```

**响应数据**:
```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": [
    {
      "batchId": "550e8400-e29b-41d4-a716-446655440000",
      "nodeId": 456,
      "nodeName": "work_folder",
      "status": 0,
      "totalCount": 150,
      "processedCount": 75,
      "createdAt": "2026-06-07T10:00:00"
    }
  ]
}
```

---

## 🔑 认证方式

所有接口都需要在请求头中携带 JWT Token：

```
Authorization: Bearer {your_jwt_token}
```

---

## ⚠️ 常见错误码

| Code | 说明 | 处理建议 |
|------|------|---------|
| 200 | 成功 | - |
| 204 | 成功（无内容） | 恢复到根目录时使用 |
| 40001 | 参数错误 | 检查必填参数 |
| 401 | 未认证 | 重新登录获取 Token |
| 40301 | 权限不足 | 确认是否为资源所有者 |
| 40401 | 资源不存在 | 检查 batchId/nodeId 是否正确 |
| 40901 | 乐观锁冲突 | 刷新后重试，获取最新 version |
| 41001 | 节点已过期 | 无法恢复，已超时 |
| 50001 | 服务器错误 | 联系管理员 |

---

## 💡 使用提示

### 恢复节点流程
1. 从 `/files/recycle` 接口获取 `batchId` 和 `version`
2. 调用 `POST /recycle/restore` 进行恢复
3. 根据返回的状态码判断恢复位置（原位置或根目录）
4. 如需进度反馈，轮询 `GET /recycle/restore/processes`

### 彻底删除流程
1. **回收站模式**: 
   - 从 `/files/recycle` 获取 `batchId`
   - 调用 `DELETE /files/delete/permanent?mode=true&batchId=xxx`

2. **浏览界面模式**:
   - 从浏览接口获取 `nodeId` 和 `version`
   - 调用 `DELETE /files/delete/permanent?mode=false&nodeId=xxx&version=xxx`

### 轮询恢复进度
```javascript
// 每 3 秒轮询一次
const interval = setInterval(async () => {
  const response = await fetch('/api/recycle/restore/processes', {
    headers: { Authorization: `Bearer ${token}` }
  });
  const result = await response.json();
  
  if (result.data.length === 0) {
    clearInterval(interval); // 所有任务完成
  }
}, 3000);
```

---

## 📖 相关文档

- 完整接口文档: `RECYCLE_BIN_RESTORE_AND_PERMANENT_DELETE_API.md`
- 修复报告: `RECYCLE_BIN_API_FIX_REPORT.md`

---

**最后更新**: 2026-06-07
