# 回收站架构调整说明文档

## 1. 概述

本文档记录了回收站系统架构的关键调整，包括数据库字段变更、API 响应格式优化以及前端适配说明。

**调整日期**: 2026-06-03  
**版本**: v1.1  
**影响范围**: 删除、恢复、彻底删除三大核心功能

---

## 2. 数据库字段调整

### 2.1 file_table 表字段变更

**变更前：**
```sql
recycle_bin_path VARCHAR(500) COMMENT '回收站路径',
```

**变更后：**
```sql
last_del_uuid VARCHAR(36) COMMENT '最后删除/恢复批次号',
```

**调整原因：**
1. **简化存储**：`last_del_uuid` 直接记录最后一次操作的 batchId（UUID），无需维护完整路径
2. **统一标识**：删除和恢复操作都使用同一个字段追踪批次号
3. **减少冗余**：路径信息可通过 parent_id 和节点层级关系推导，无需单独存储

**迁移脚本：**
```sql
ALTER TABLE file_table 
DROP COLUMN recycle_bin_path,
ADD COLUMN last_del_uuid VARCHAR(36) COMMENT '最后删除/恢复批次号';

-- 为已有数据设置默认值（可选）
UPDATE file_table SET last_del_uuid = NULL WHERE status != 1;
```

**索引建议：**
```sql
CREATE INDEX idx_last_del_uuid ON file_table(last_del_uuid);
```

---

## 3. Redis 配置调整

### 3.1 端口变更

**变更前：**
```yaml
spring:
  redis:
    port: 6379
```

**变更后：**
```yaml
directo:
  redis:
    port: 6381
```

**注意事项：**
- 确保 Redis 服务已在 6381 端口启动
- 更新所有环境的配置文件（dev/test/prod）
- 防火墙规则需开放 6381 端口

---

## 4. API 接口调整

### 4.1 恢复节点接口响应优化

**接口**: `POST /recycle/restore`

#### 4.1.1 响应示例 1（原父目录仍存在）

**状态码**: `200 OK`

**响应体：**
```json
{
  "code": 200,
  "success": true,
  "message": "恢复成功",
  "data": {
    "newName": "restored_folder",
    "nodeType": "folder",
    "restoredPath": "_root/_files/10001/document/restored_folder",
    "newVersion": 2
  }
}
```

**字段说明：**
- `newName`: 恢复后的名称（可能与原名称相同）
- `nodeType`: 节点类型（"folder" 或 "file"）
- `restoredPath`: 恢复后的完整路径
- `newVersion`: 恢复后的新版本号（用于后续操作）

#### 4.1.2 响应示例 2（原父目录已删除且重命名）

**状态码**: `204 No Content` ⚠️ **重要变更**

**响应体：**
```json
{
  "code": 204,
  "success": true,
  "message": "原父目录不存在或已删除，已恢复到用户根目录",
  "data": {
    "newName": "restored_file(3)",
    "nodeType": "file",
    "restoredPath": "_root/_files/10001/restored_file(3)",
    "newVersion": 2
  }
}
```

**关键变化：**
1. **状态码从 200 改为 204**：表示"恢复成功但需要特殊处理"
2. **消息更明确**：区分"原父目录不存在"和"已删除"两种情况
3. **新增 newVersion**：前端需要使用此版本号进行后续操作

**RESTful 语义：**
- `200 OK`: 正常恢复，无需额外提示
- `204 No Content`: 恢复成功但发生了重命名或路径变更，需要告知用户

---

## 5. 前端适配说明

### 5.1 已完成的适配

#### 5.1.1 directory.js - restoreNode 函数

**修改内容：**
```javascript
// 支持 200 和 204 两种成功状态码
if ((result.code === 200 || result.code === 204) && result.success) {
  return {
    success: true,
    code: result.code,  // ← 保留原始状态码
    data: result.data,
    message: result.message
  }
}
```

**关键点：**
- ✅ 同时接受 200 和 204 状态码
- ✅ 保留 `code` 字段供上层判断
- ✅ 返回完整的 `data` 对象（包含 newVersion）

#### 5.1.2 RecycleBinView.vue - handleRestore 函数

**修改内容：**
```javascript
if (result.success) {
  // 根据响应码显示不同消息
  if (result.code === 204) {
    showInfo(result.message || '原父目录不存在或已删除，已恢复到用户根目录')
    logger.info('恢复成功（重命名）:', result.data)
  } else {
    showSuccess(result.message || '已开始恢复，请稍后查看恢复进度')
    logger.info('恢复请求成功:', result.message)
  }
  
  // 显示恢复详情（如果有）
  if (result.data) {
    logger.info('恢复详情:', {
      newName: result.data.newName,
      nodeType: result.data.nodeType,
      restoredPath: result.data.restoredPath,
      newVersion: result.data.newVersion  // ← 新增字段
    })
  }
  
  // 从列表中移除该文件
  recycleBinState.state.files = recycleBinState.state.files.filter(f => f.id !== file.id)
}
```

**用户体验优化：**
- ✅ 204 状态码使用 `showInfo`（蓝色提示）而非 `showSuccess`（绿色成功）
- ✅ 明确告知用户"已恢复到用户根目录"
- ✅ 记录详细的恢复信息便于调试

### 5.2 回收站浏览逻辑验证

**确认事项：**
- ✅ `browseRecycleBin` 函数正确获取 `batchId` 和 `version` 字段
- ✅ 数据通过 `setFiles()` 和 `appendFiles()` 透明传递
- ✅ 前端模板正确绑定 `file.batchId` 和 `file.version`

**示例代码：**
```javascript
// RecycleBinView.vue L459-463
const batchId = file.batchId  // ← 从列表项获取
const version = file.version || 0
const result = await restoreNode(batchId, version)
```

---

## 6. 后端实现建议

### 6.1 恢复逻辑伪代码

```java
public RestoreResult restoreNode(String batchId, Long version) {
    // 1. 查询回收站任务
    RecycleTask task = taskRepository.findByBatchId(batchId)
            .orElseThrow(() -> new NodeNotFoundException("恢复任务不存在"));
    
    // 2. 查询根节点
    FileNode rootNode = fileRepository.findById(task.getRootNodeId())
            .orElseThrow(() -> new NodeNotFoundException("节点不存在"));
    
    // 3. 校验版本（乐观锁）
    if (!rootNode.getVersion().equals(version)) {
        throw new VersionConflictException("版本冲突");
    }
    
    // 4. 检查状态
    if (rootNode.getStatus() == 2) { // 恢复中
        throw new RestoringException("该节点正在恢复中");
    }
    
    // 5. 判断原始位置是否仍存在
    FileNode parentNode = fileRepository.findById(rootNode.getParentId()).orElse(null);
    boolean originalLocationExists = (parentNode != null && parentNode.getStatus() == 0);
    
    // 6. 确定恢复路径和名称
    String restorePath;
    String newName;
    int httpCode;
    String message;
    
    if (originalLocationExists) {
        // 恢复到原位置
        restorePath = parentNode.getPath() + "/" + rootNode.getName();
        newName = rootNode.getName();
        httpCode = 200;
        message = "恢复成功";
    } else {
        // 恢复到用户根目录，可能需要重命名
        restorePath = "/_root/_files/" + rootNode.getUserId();
        newName = generateUniqueName(rootNode.getName(), restorePath);
        restorePath += "/" + newName;
        httpCode = 204;  // ← 关键：使用 204 状态码
        message = "原父目录不存在或已删除，已恢复到用户根目录";
    }
    
    // 7. 执行恢复操作
    rootNode.setStatus(0); // 正常
    rootNode.setParentId(originalLocationExists ? parentNode.getId() : getRootDirectoryId());
    rootNode.setLastDelUuid(null); // 清除批次号
    rootNode.setDeletedAt(null);
    rootNode.setVersion(rootNode.getVersion() + 1);
    fileRepository.save(rootNode);
    
    // 8. 递归恢复子节点（异步）
    asyncRestoreChildren(rootNode.getId());
    
    // 9. 构建响应
    RestoreData data = new RestoreData();
    data.setNewName(newName);
    data.setNodeType(rootNode.getType() == 0 ? "folder" : "file");
    data.setRestoredPath(restorePath);
    data.setNewVersion(rootNode.getVersion());
    
    return new RestoreResult(httpCode, true, message, data);
}
```

### 6.2 Controller 层处理

```java
@PostMapping("/restore")
public ResponseEntity<ApiResponse<RestoreData>> restoreNode(
        @RequestParam String batchId,
        @RequestParam Long version) {
    
    try {
        RestoreResult result = fileService.restoreNode(batchId, version);
        
        // 根据状态码返回不同的 HTTP 响应
        if (result.getCode() == 204) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT)
                    .body(ApiResponse.success(result.getMessage(), result.getData()));
        } else {
            return ResponseEntity.ok(ApiResponse.success(result.getMessage(), result.getData()));
        }
    } catch (RestoringException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(409, "该节点正在恢复中"));
    } catch (VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(409, "版本冲突，请刷新后重试"));
    } catch (NodeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "节点不存在"));
    } catch (Exception e) {
        log.error("恢复节点失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "恢复失败"));
    }
}
```

### 6.3 ApiResponse 通用结构

```java
@Data
@AllArgsConstructor
public class ApiResponse<T> {
    private Integer code;
    private Boolean success;
    private String message;
    private T data;
    
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, true, message, data);
    }
    
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, false, message, null);
    }
}
```

---

## 7. 测试用例

### 7.1 单元测试

```java
@SpringBootTest
class RestoreNodeTest {
    
    @Autowired
    private FileService fileService;
    
    @Test
    void testRestoreToOriginalLocation() {
        // 准备：创建文件夹并删除
        Long folderId = createTestFolder();
        String batchId = deleteFolder(folderId);
        Long version = getVersion(folderId);
        
        // 执行：恢复
        RestoreResult result = fileService.restoreNode(batchId, version);
        
        // 验证：状态码为 200，恢复到原位置
        assertEquals(200, result.getCode());
        assertEquals("恢复成功", result.getMessage());
        assertEquals("test_folder", result.getData().getNewName());
        assertNotNull(result.getData().getNewVersion());
    }
    
    @Test
    void testRestoreWithRenaming() {
        // 准备：删除文件夹，然后删除其父目录
        Long folderId = createTestFolder();
        Long parentId = getParentId(folderId);
        String batchId1 = deleteFolder(folderId);
        String batchId2 = deleteFolder(parentId);
        Long version = getVersion(folderId);
        
        // 执行：恢复子文件夹
        RestoreResult result = fileService.restoreNode(batchId1, version);
        
        // 验证：状态码为 204，重命名并恢复到根目录
        assertEquals(204, result.getCode());
        assertTrue(result.getMessage().contains("原父目录不存在"));
        assertTrue(result.getData().getNewName().matches("test_folder\\(\\d+\\)"));
        assertTrue(result.getData().getRestoredPath().startsWith("/_root/_files/"));
    }
}
```

### 7.2 集成测试

```java
@SpringBootTest
@AutoConfigureMockMvc
class RestoreControllerIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testRestoreEndpointWith200() throws Exception {
        mockMvc.perform(post("/recycle/restore")
                .param("batchId", "test-batch-id-1")
                .param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.newVersion").exists());
    }
    
    @Test
    void testRestoreEndpointWith204() throws Exception {
        mockMvc.perform(post("/recycle/restore")
                .param("batchId", "test-batch-id-2")
                .param("version", "1"))
                .andExpect(status().isNoContent())
                .andExpect(jsonPath("$.code").value(204))
                .andExpect(jsonPath("$.message").value(containsString("原父目录不存在")));
    }
}
```

---

## 8. 兼容性说明

### 8.1 向后兼容性

**问题**: 旧版前端可能无法正确处理 204 状态码

**解决方案**:
1. **短期**: 保持 200 和 204 同时支持，前端根据 `code` 字段判断
2. **长期**: 逐步淘汰旧版前端，统一使用新的响应格式

### 8.2 前端版本要求

| 前端版本 | 支持状态 | 说明 |
|---------|---------|------|
| < v1.0 | ❌ 不支持 | 无法识别 204 状态码 |
| ≥ v1.1 | ✅ 完全支持 | 已适配新的响应格式 |

**检测方法：**
```javascript
// 检查前端是否支持 204 状态码
if (result.code === 204) {
  // 新版前端：显示特殊提示
  showInfo(result.message)
} else {
  // 旧版前端：降级处理
  showSuccess(result.message)
}
```

---

## 9. 部署 checklist

### 9.1 数据库迁移

- [ ] 执行 ALTER TABLE 语句添加 `last_del_uuid` 字段
- [ ] 删除 `recycle_bin_path` 字段
- [ ] 创建索引 `idx_last_del_uuid`
- [ ] 验证数据迁移完整性

### 9.2 Redis 配置

- [ ] 更新 Redis 端口为 6381
- [ ] 重启 Redis 服务
- [ ] 验证连接正常
- [ ] 更新所有环境配置

### 9.3 后端部署

- [ ] 更新恢复接口返回 204 状态码的逻辑
- [ ] 添加 `newVersion` 字段到响应数据
- [ ] 运行单元测试和集成测试
- [ ] 灰度发布（先部署 10% 流量）
- [ ] 监控错误日志

### 9.4 前端部署

- [ ] 确认 `directory.js` 已支持 204 状态码
- [ ] 确认 `RecycleBinView.vue` 已更新提示逻辑
- [ ] 运行端到端测试
- [ ] 全量发布

---

## 10. 监控与告警

### 10.1 关键指标

| 指标名称 | 采集方式 | 告警阈值 | 说明 |
|---------|---------|---------|------|
| 恢复成功率 | Prometheus | < 95% | 200 和 204 都算成功 |
| 204 响应占比 | Prometheus | > 30% | 过高可能表示父目录频繁删除 |
| 恢复平均耗时 | Prometheus | > 500ms | 性能退化预警 |
| 版本冲突次数 | Prometheus | > 10/min | 并发冲突过多 |

### 10.2 日志记录

```java
// 恢复成功（200）
log.info("恢复成功: batchId={}, newName={}, version={}", 
    batchId, data.getNewName(), data.getNewVersion());

// 恢复成功但重命名（204）
log.warn("恢复并重命名: batchId={}, oldName={}, newName={}, reason=parent_deleted", 
    batchId, originalName, data.getNewName());

// 恢复失败
log.error("恢复失败: batchId={}, error={}", batchId, e.getMessage());
```

---

## 11. 常见问题排查

### 11.1 前端收到 204 但显示错误

**症状**: 后端返回 204，前端显示"网络错误"

**原因**: 旧版前端未适配 204 状态码

**解决方案**:
1. 升级前端到 v1.1+
2. 临时方案：后端将 204 改为 200（不推荐）

### 11.2 newVersion 字段为空

**症状**: 响应中 `newVersion` 为 null

**原因**: 后端未正确递增版本号

**解决方案**:
```java
// 确保在保存前递增版本号
rootNode.setVersion(rootNode.getVersion() + 1);
fileRepository.save(rootNode);

// 返回时使用最新的版本号
data.setNewVersion(rootNode.getVersion());
```

### 11.3 last_del_uuid 字段未更新

**症状**: 数据库中 `last_del_uuid` 仍为 NULL

**原因**: 恢复操作未清除该字段

**解决方案**:
```java
// 恢复时清除批次号
rootNode.setLastDelUuid(null);
fileRepository.save(rootNode);
```

---

## 12. 总结

本次调整的核心变更：

1. ✅ **数据库字段简化**: `recycle_bin_path` → `last_del_uuid`
2. ✅ **Redis 端口变更**: 6379 → 6381
3. ✅ **恢复接口优化**: 引入 204 状态码区分特殊场景
4. ✅ **响应数据增强**: 新增 `newVersion`、`nodeType`、`restoredPath` 字段
5. ✅ **前端完全适配**: 支持 200/204 双状态码处理

**优势：**
- 更清晰的 RESTful 语义
- 更好的用户体验（明确告知重命名）
- 更简洁的数据库设计
- 更强的可追溯性（通过 batchId）

**风险提示：**
- 需要确保前后端版本同步升级
- 旧版前端需要兼容处理
- Redis 端口变更需通知运维团队

---

**文档版本**: v1.0  
**最后更新**: 2026-06-03  
**作者**: AI Assistant  
**审核状态**: 待审核
