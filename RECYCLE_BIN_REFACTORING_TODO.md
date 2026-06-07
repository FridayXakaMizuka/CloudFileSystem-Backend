# 回收站系统重构 - 待完成任务清单

## 📋 已完成的工作 ✅

### 1. 核心数据结构
- ✅ RecycleBinTask 实体类
- ✅ RecycleBinTaskMapper 接口和 XML
- ✅ RecycleBinItemDTO、RecycleBinBrowseResponse DTO
- ✅ RestoreResult、RestoreData DTO（支持 204 状态码）
- ✅ RecycleBinService 服务类

### 2. API 层
- ✅ `/files/recycle` 浏览回收站接口已更新为使用新的 recycle_bin_tasks 表

---

## 🔧 待完成的任务

### 任务 1: 修改删除节点功能以支持 batchId

#### 1.1 修改 FileController.deleteNode()

**当前签名**:
```java
@PostMapping("/delete")
public Result<DeleteNodeResponse> deleteNode(
    @RequestParam Long nodeId,
    @RequestParam Integer nodeType,
    @RequestParam Long version,
    @RequestParam(required = false) String sessionId)
```

**需要改为**:
```java
@PostMapping("/delete")
public Result<DeleteNodeResponse> deleteNode(
    @RequestParam Long nodeId,
    @RequestParam Integer nodeType,
    @RequestParam Long version,
    @RequestParam(required = false) String batchId)  // 改用 batchId
```

**实现逻辑**:
```java
// 如果前端没有传 batchId，后端生成一个 UUID
if (batchId == null || batchId.trim().isEmpty()) {
    batchId = UUID.randomUUID().toString();
}

// 调用 DirectoryService，传入 batchId
DeleteNodeResponse response = directoryService.deleteNodeWithBatchId(
    nodeId, nodeType, userId, version, batchId
);
```

#### 1.2 在 DirectoryService 中添加新方法

```java
@Transactional
public DeleteNodeResponse deleteNodeWithBatchId(Long nodeId, Integer nodeType, 
                                                 Long userId, Long version, String batchId) {
    // 1. 参数校验（同现有逻辑）
    
    // 2. 查询节点并校验版本（同现有逻辑）
    
    // 3. 创建回收站任务记录
    RecycleBinTask task = new RecycleBinTask();
    task.setBatchId(batchId);
    task.setUserId(userId);
    task.setRootNodeId(nodeId);
    task.setNodeType(nodeType);
    task.setOperationType(0); // 删除操作
    task.setStatus(0); // 进行中
    task.setProcessedCount(0);
    task.setTotalCount(0); // 异步扫描后更新
    task.setCreatedAt(LocalDateTime.now());
    
    Long taskId = recycleBinTaskMapper.insert(task);
    
    // 4. 计算回收站路径和过期时间
    String recycleBinPath = calculateRecycleBinPath(...);
    LocalDateTime expiresAt = LocalDateTime.now().plusDays(30);
    
    // 5. 执行软删除（文件夹/文件分别处理）
    if (nodeType == 0) {
        // 文件夹：标记根节点 + 启动异步任务
        softDeleteFolderRoot(nodeId, recycleBinPath, expiresAt);
        
        // 更新节点的 last_del_uuid
        folderNodeMapper.updateLastDelUuid(nodeId, batchId);
        
        // 异步扫描子节点
        asyncDirectoryDeleteService.asyncDeleteFolderWithBatchId(
            nodeId, batchId, userId, recycleBinPath, expiresAt
        );
    } else {
        // 文件：直接标记删除
        softDeleteFile(nodeId, recycleBinPath, expiresAt);
        
        // 更新文件的 last_del_uuid
        fileNodeMapper.updateLastDelUuid(nodeId, batchId);
        
        // 更新任务状态为已完成
        recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, 1, 1);
    }
    
    return new DeleteNodeResponse(recycleBinPath, expiresAt);
}
```

#### 1.3 在 FolderNodeMapper 和 FileNodeMapper 中添加方法

```java
// FolderNodeMapper.java
@Update("UPDATE folder_nodes SET last_del_uuid = #{batchId}, version = version + 1 WHERE id = #{id}")
void updateLastDelUuid(@Param("id") Long id, @Param("batchId") String batchId);

// FileNodeMapper.java
@Update("UPDATE file_nodes SET last_del_uuid = #{batchId}, version = version + 1 WHERE id = #{id}")
void updateLastDelUuid(@Param("id") Long id, @Param("batchId") String batchId);
```

#### 1.4 修改 AsyncDirectoryDeleteService

添加支持 batchId 的异步删除方法，并在删除过程中更新任务进度：

```java
@Async
public void asyncDeleteFolderWithBatchId(Long rootFolderId, String batchId, Long userId,
                                          String recycleBinPath, LocalDateTime expiresAt) {
    try {
        // 1. 扫描总节点数
        int totalCount = countAllNodes(rootFolderId);
        recycleBinTaskMapper.updateProgress(batchId, 0, totalCount);
        
        // 2. 递归删除子节点（带限流和进度更新）
        int processedCount = deleteChildNodesRecursive(rootFolderId, batchId, userId,
            recycleBinPath, expiresAt, 0, totalCount);
        
        // 3. 更新任务状态为已完成
        recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, 
            processedCount, totalCount);
        
    } catch (Exception e) {
        log.error("异步删除失败 - BatchId: {}", batchId, e);
        recycleBinTaskMapper.updateTask(batchId, 2, LocalDateTime.now(), 
            e.getMessage(), null, null);
    }
}
```

---

### 任务 2: 修改恢复节点功能以支持 204 状态码

#### 2.1 修改 FileController.restoreNode()

**当前返回类型**: `Result<RestoreNodeResponse>`

**需要改为**: `ResponseEntity<?>` 以支持不同的 HTTP 状态码

```java
@PostMapping("/recycle/restore/{nodeId}")
public ResponseEntity<?> restoreNode(@PathVariable Long nodeId) {
    try {
        Long userId = SecurityUtils.getCurrentUserId();
        
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(401, "未认证或会话已过期"));
        }
        
        // 调用新的恢复方法
        RestoreResult result = directoryService.restoreNodeWithNewFormat(nodeId, userId);
        
        // 根据状态码返回不同的 HTTP 响应
        if (result.getCode() == 204) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(Result.success(result.getMessage(), result.getData()));
        } else {
            return ResponseEntity.ok(Result.success(result.getMessage(), result.getData()));
        }
        
    } catch (Exception e) {
        // 异常处理...
    }
}
```

#### 2.2 在 DirectoryService 中添加新方法

```java
@Transactional
public RestoreResult restoreNodeWithNewFormat(Long nodeId, Long userId) {
    // 1. 查询节点（先查文件夹，再查文件）
    FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
    FileNode file = null;
    boolean isFolder = true;
    
    if (folder == null) {
        file = fileNodeMapper.findInRecycleBinById(nodeId);
        if (file == null) {
            throw new RuntimeException("节点不存在或不在回收站中");
        }
        isFolder = false;
    }
    
    // 2. 验证权限和过期时间（同现有逻辑）
    
    // 3. 判断原始位置是否仍存在
    String restorePath;
    String newName;
    int httpCode;
    String message;
    
    if (isFolder) {
        // 文件夹恢复逻辑
        FolderNode parentNode = null;
        if (folder.getOriginalParentId() != null) {
            parentNode = folderNodeMapper.findById(folder.getOriginalParentId());
        }
        
        boolean originalLocationExists = (parentNode != null && 
            "active".equals(parentNode.getDirectoryStatus()));
        
        if (originalLocationExists) {
            // 恢复到原位置
            restorePath = buildPath(parentNode.getPath(), folder.getName());
            newName = folder.getName();
            httpCode = 200;
            message = "恢复成功";
            
            // 执行恢复
            restoreFolderToOriginalLocation(nodeId, folder.getOriginalParentId(), restorePath);
        } else {
            // 恢复到用户根目录并重命名
            FolderNode userRoot = folderNodeMapper.findUserRoot(userId);
            newName = generateUniqueName(folder.getName(), userRoot.getId());
            restorePath = buildPath(userRoot.getPath(), newName);
            httpCode = 204;
            message = "原父目录不存在或已删除，已恢复到用户根目录";
            
            // 执行恢复并重命名
            restoreFolderToUserRoot(nodeId, userRoot.getId(), newName, restorePath);
        }
        
        // 更新 last_del_uuid 为恢复批次号
        String restoreBatchId = UUID.randomUUID().toString();
        folderNodeMapper.updateLastDelUuid(nodeId, restoreBatchId);
        
    } else {
        // 文件恢复逻辑（类似文件夹）
        // ...
    }
    
    // 4. 更新任务状态
    String batchId = isFolder ? folder.getLastDelUuid() : file.getLastDelUuid();
    if (batchId != null) {
        recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, null, null);
    }
    
    // 5. 构建响应
    RestoreData data = new RestoreData();
    data.setNewName(newName);
    data.setNodeType(isFolder ? "folder" : "file");
    data.setRestoredPath(restorePath);
    data.setNewVersion(isFolder ? folder.getVersion() : file.getVersion());
    
    return new RestoreResult(httpCode, true, message, data);
}
```

#### 2.3 添加辅助方法

```java
/**
 * 生成唯一文件名（避免重名）
 */
private String generateUniqueName(String originalName, Long parentId) {
    String baseName = originalName;
    String extension = "";
    
    // 分离文件名和扩展名
    int dotIndex = originalName.lastIndexOf('.');
    if (dotIndex > 0) {
        baseName = originalName.substring(0, dotIndex);
        extension = originalName.substring(dotIndex);
    }
    
    // 尝试生成唯一名称
    int counter = 1;
    String newName = originalName;
    while (folderNodeMapper.existsByNameAndParentId(newName, parentId) ||
           fileNodeMapper.existsByNameAndParentId(newName, parentId)) {
        newName = baseName + "(" + counter + ")" + extension;
        counter++;
        
        // 防止无限循环
        if (counter > 1000) {
            newName = UUID.randomUUID().toString() + extension;
            break;
        }
    }
    
    return newName;
}

/**
 * 构建完整路径
 */
private String buildPath(String parentPath, String name) {
    return parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;
}

/**
 * 清理文件名中的特殊字符
 */
private String sanitizeFileName(String fileName) {
    return fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
}
```

#### 2.4 在 Mapper 中添加必要的方法

```java
// FolderNodeMapper.java
@Select("SELECT COUNT(*) > 0 FROM folder_nodes WHERE name = #{name} AND parent_id = #{parentId} AND is_deleted = 0")
Boolean existsByNameAndParentId(@Param("name") String name, @Param("parentId") Long parentId);

@Select("SELECT * FROM folder_nodes WHERE user_id = #{userId} AND parent_id IS NULL LIMIT 1")
FolderNode findUserRoot(@Param("userId") Long userId);

// FileNodeMapper.java
@Select("SELECT COUNT(*) > 0 FROM file_nodes WHERE name = #{name} AND folder_id = #{folderId} AND is_deleted = 0")
Boolean existsByNameAndParentId(@Param("name") String name, @Param("folderId") Long folderId);
```

---

### 任务 3: 修改彻底删除功能以支持终止异步操作

#### 3.1 修改 FileController.permanentDeleteNode()

```java
@DeleteMapping("/permanent/{nodeId}")
public Result<Void> permanentDeleteNode(@PathVariable Long nodeId) {
    try {
        Long userId = SecurityUtils.getCurrentUserId();
        
        if (userId == null) {
            return Result.error(401, "未认证或会话已过期");
        }
        
        // 检查是否有正在进行的异步操作
        String batchId = getBatchIdByNodeId(nodeId);
        if (batchId != null) {
            RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
            if (task != null && task.getStatus() == 0) { // 进行中
                // 终止异步操作
                recycleBinTaskMapper.updateTask(batchId, 3, LocalDateTime.now(), 
                    "用户主动终止", null, null);
                
                log.info("用户 {} 终止异步操作 - BatchId: {}", userId, batchId);
            }
        }
        
        // 执行彻底删除
        directoryService.permanentDeleteNode(nodeId, userId);
        
        return Result.success("已彻底删除，目录进入待分配池", null);
        
    } catch (Exception e) {
        // 异常处理...
    }
}
```

#### 3.2 在 DirectoryService 中优化 permanentDeleteNode()

确保彻底删除时清理相关的任务记录和资源。

---

### 任务 4: 集成滑动窗口限流器

#### 4.1 创建限流器 Lua 脚本

在 `src/main/resources/scripts/` 目录下创建 `rate_limiter.lua`:

```lua
-- 滑动窗口限流器
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])
local max_requests = tonumber(ARGV[3])

-- 移除过期的请求记录
redis.call('ZREMRANGEBYSCORE', key, 0, now - window_size)

-- 获取当前窗口内的请求数
local current_count = redis.call('ZCARD', key)

if current_count < max_requests then
    -- 允许请求，记录时间戳
    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
    redis.call('EXPIRE', key, window_size)
    return 1
else
    -- 拒绝请求
    return 0
end
```

#### 4.2 创建 RateLimiterService

```java
@Service
public class RateLimiterService {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    private static final String LUA_SCRIPT = 
        "local key = KEYS[1]\n" +
        "local now = tonumber(ARGV[1])\n" +
        "local window_size = tonumber(ARGV[2])\n" +
        "local max_requests = tonumber(ARGV[3])\n" +
        "redis.call('ZREMRANGEBYSCORE', key, 0, now - window_size)\n" +
        "local current_count = redis.call('ZCARD', key)\n" +
        "if current_count < max_requests then\n" +
        "    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))\n" +
        "    redis.call('EXPIRE', key, window_size)\n" +
        "    return 1\n" +
        "else\n" +
        "    return 0\n" +
        "end";
    
    private DefaultRedisScript<Long> script;
    
    @PostConstruct
    public void init() {
        script = new DefaultRedisScript<>();
        script.setScriptText(LUA_SCRIPT);
        script.setResultType(Long.class);
    }
    
    /**
     * 尝试获取许可
     */
    public boolean tryAcquire(String key, int maxRequests, int windowSizeSeconds) {
        long now = System.currentTimeMillis();
        Long result = redisTemplate.execute(
            script,
            Collections.singletonList(key),
            String.valueOf(now),
            String.valueOf(windowSizeSeconds * 1000),
            String.valueOf(maxRequests)
        );
        return result != null && result == 1;
    }
    
    /**
     * 阻塞式获取许可（带退避）
     */
    public void acquireWithBackoff(String key, int maxRequests) throws InterruptedException {
        int windowSizeSeconds = 60;
        int maxRetries = 10;
        int retryCount = 0;
        
        while (!tryAcquire(key, maxRequests, windowSizeSeconds)) {
            retryCount++;
            if (retryCount > maxRetries) {
                throw new RuntimeException("限流器获取超时");
            }
            
            // 指数退避
            long waitTime = (long) Math.pow(2, retryCount) * 100;
            Thread.sleep(waitTime);
        }
    }
}
```

#### 4.3 在异步删除中使用限流器

```java
// 在 asyncDeleteFolderWithBatchId 方法中
String rateLimitKey = "rate_limit:delete:" + userId;
try {
    rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException("删除任务被中断", e);
}
```

---

## 📝 总结

### 优先级排序

1. **高优先级**（核心功能）:
   - 任务 1: 修改删除节点功能以支持 batchId
   - 任务 2: 修改恢复节点功能以支持 204 状态码

2. **中优先级**（增强功能）:
   - 任务 3: 修改彻底删除功能以支持终止异步操作
   - 任务 4: 集成滑动窗口限流器

3. **低优先级**（优化）:
   - 添加单元测试
   - 性能优化和监控
   - 文档完善

### 预计工作量

- 任务 1: 4-6 小时
- 任务 2: 6-8 小时
- 任务 3: 2-3 小时
- 任务 4: 3-4 小时

**总计**: 15-21 小时

### 测试建议

1. **单元测试**: 每个 Service 方法都需要单元测试
2. **集成测试**: 测试完整的删除-恢复-彻底删除流程
3. **并发测试**: 测试乐观锁和限流器的并发安全性
4. **性能测试**: 测试大批量删除/恢复的性能

---

**文档版本**: v1.0  
**最后更新**: 2026-06-05  
**状态**: 待实施
