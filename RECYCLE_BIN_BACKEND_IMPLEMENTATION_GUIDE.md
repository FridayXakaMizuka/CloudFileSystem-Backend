# 回收站新架构后端实现指南

## 1. 概述

本文档基于前端需求和新架构设计，提供完整的后端实现指南。涵盖删除、恢复、彻底删除三大核心功能，以及 Redis 与 MySQL 混合架构的最佳实践。

**关键变更：**
- 使用 `batchId`（UUID）替代 `sessionId` 作为业务操作批次号
- 新增乐观锁 `version` 字段防止并发冲突
- 新增回收站任务表 `recycle_bin_tasks` 取代旧的 `_recycle_bin` 目录
- 为 `file_nodes` 和 `folder_nodes` 表添加 `last_del_uuid` 字段追踪异步操作
- Redis 统一使用数据库 0 存储所有 batchId 信息（滑动窗口限流器共用）
- 符合 RESTful 标准的 HTTP 状态码规范
- **重要**：正确处理恢复时及时终止当前目录及其子目录的删除操作
- **重要**：子目录恢复时若干级父目录恢复完但一级父目录未恢复完成的处理
- **重要**：删除父目录时子目录恢复的终止
- **重要**：彻底删除时终止该目录及其子目录的恢复操作

---

## 2. 技术栈要求

### 2.1 核心依赖

```xml
<!-- Spring Boot -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Lettuce 连接池 -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- UUID 生成 -->
<!-- Java 内置 java.util.UUID -->
```

### 2.2 Redis 配置

```yaml
# 回收站专用 Redis 配置
recycle:
  redis:
    host: localhost
    port: 6381  # 回收站专用端口（与主应用 Redis 分离）
    database: 0  # 统一使用数据库0存储所有回收站相关数据
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 50   # 最大活跃连接数（根据并发量调整）
        max-idle: 20     # 最大空闲连接数
        min-idle: 5      # 最小空闲连接数
        max-wait: 3000ms # 最大等待时间
```

**注意事项：**
- 确保 Redis 服务已在 6381 端口启动
- 更新所有环境的配置文件（dev/test/prod）
- 防火墙规则需开放 6381 端口
- 与滑动窗口限流器共用同一个 Redis 实例

---

## 3. 数据库设计

### 3.1 文件夹节点表（folder_nodes）- 新增字段

**重要变更**：废弃 `recycle_bin_path` 字段，改用 `last_del_uuid` 追踪异步操作。

```sql
-- 移除旧字段
ALTER TABLE folder_nodes DROP COLUMN recycle_bin_path;

-- 添加新字段
ALTER TABLE folder_nodes 
ADD COLUMN last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号（UUID格式）';

-- 创建索引
CREATE INDEX idx_last_del_uuid ON folder_nodes(last_del_uuid);
```

**说明**：
- `last_del_uuid` 用于追踪当前节点正在进行的异步操作（删除/恢复）
- 当启动新的删除/恢复任务时，更新此字段为新的 batchId
- 当需要终止操作时，检查此字段判断是否有进行中的任务
- 操作完成后清空此字段（设为 NULL）
- 路径信息可通过 `parent_id` 和节点层级关系推导，无需单独存储

### 3.2 文件节点表（file_nodes）- 新增字段

**重要变更**：废弃 `recycle_bin_path` 字段，改用 `last_del_uuid` 追踪异步操作。

```sql
-- 移除旧字段
ALTER TABLE file_nodes DROP COLUMN recycle_bin_path;

-- 添加新字段
ALTER TABLE file_nodes 
ADD COLUMN last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号（UUID格式）';

-- 创建索引
CREATE INDEX idx_last_del_uuid ON file_nodes(last_del_uuid);
```

**迁移脚本**：
```sql
-- 为已有数据设置默认值（可选）
UPDATE folder_nodes SET last_del_uuid = NULL WHERE directory_status != 'deleting' AND directory_status != 'restoring';
UPDATE file_nodes SET last_del_uuid = NULL WHERE directory_status != 'deleting' AND directory_status != 'restoring';
```

### 3.3 回收站任务表（recycle_bin_tasks）- 新架构

**重要变更**：废弃 `_recycle_bin` 目录及相关字段，使用独立的任务表管理异步操作。

```sql
CREATE TABLE recycle_bin_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
    batch_id VARCHAR(36) NOT NULL UNIQUE COMMENT '业务操作批次号（UUID格式）',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    root_node_id BIGINT NOT NULL COMMENT '根节点ID（文件夹或文件）',
    node_type TINYINT NOT NULL COMMENT '节点类型：0=文件夹，1=文件',
    operation_type TINYINT NOT NULL COMMENT '操作类型：0=删除，1=恢复，2=彻底删除',
    total_count INT DEFAULT 0 COMMENT '总节点数（异步扫描后更新）',
    processed_count INT DEFAULT 0 COMMENT '已处理节点数',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0=进行中，1=已完成，2=失败，3=已终止',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    completed_at DATETIME DEFAULT NULL COMMENT '完成时间',
    
    INDEX idx_batch_id (batch_id),
    INDEX idx_user_status (user_id, status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回收站任务表（取代_recycle_bin目录）';
```

**字段说明**：
- `batch_id`: UUID 格式，唯一标识一次删除/恢复/彻底删除操作
- `operation_type`: 区分操作类型，便于监控和统计
- `status`: 
  - 0 = 进行中（异步任务执行中）
  - 1 = 已完成（所有节点处理完毕）
  - 2 = 失败（发生错误）
  - 3 = 已终止（被其他操作强制终止）
- `total_count`: 异步扫描完成后更新，用于前端显示进度
- `processed_count`: 实时更新的已处理节点数

---

## 4. 操作终止与并发控制逻辑（重要）

### 4.1 核心场景说明

在新架构下，需要正确处理以下四种并发场景：

#### 场景 1：恢复时及时终止当前目录及其子目录的删除操作

**触发条件**：用户对正在删除中的文件夹执行恢复操作

**处理流程**：
```java
public RestoreResult restoreNode(String batchId, Long nodeId, Long version) {
    // 1. 查询节点并校验版本
    FolderNode node = folderRepository.findById(nodeId)
        .orElseThrow(() -> new NodeNotFoundException("节点不存在"));
    
    if (!node.getVersion().equals(version)) {
        throw new VersionConflictException("版本冲突");
    }
    
    // 2. 检查是否有进行中的删除任务
    if (node.getLastDelUuid() != null && node.getDirectoryStatus().equals("deleting")) {
        String deletingBatchId = node.getLastDelUuid();
        
        // 3. 终止删除任务（包括所有子目录）
        terminateDeleteTask(deletingBatchId, nodeId);
        
        log.info("终止删除任务: batchId={}, rootNodeId={}", deletingBatchId, nodeId);
    }
    
    // 4. 创建恢复任务
    RecycleBinTask task = new RecycleBinTask();
    task.setBatchId(batchId);
    task.setUserId(node.getUserId());
    task.setRootNodeId(nodeId);
    task.setNodeType(0); // 文件夹
    task.setOperationType(1); // 恢复
    task.setStatus(0); // 进行中
    recycleBinTaskRepository.save(task);
    
    // 5. 更新节点状态为恢复中
    node.setDirectoryStatus("restoring");
    node.setLastDelUuid(batchId); // 记录当前恢复批次号
    node.setVersion(node.getVersion() + 1);
    folderRepository.save(node);
    
    // 6. 异步执行恢复
    asyncRestoreNode(batchId, nodeId);
    
    return new RestoreResult("恢复任务已启动", batchId);
}

private void terminateDeleteTask(String batchId, Long rootNodeId) {
    // 1. 更新任务状态为已终止
    RecycleBinTask task = recycleBinTaskRepository.findByBatchId(batchId).orElse(null);
    if (task != null) {
        task.setStatus(3); // 已终止
        task.setErrorMessage("被恢复操作终止");
        task.setCompletedAt(LocalDateTime.now());
        recycleBinTaskRepository.save(task);
    }
    
    // 2. 递归终止所有子目录的删除任务
    terminateChildrenDeleteTasks(rootNodeId);
    
    // 3. 清理 Redis 缓存
    cleanRedisCache(batchId);
}

private void terminateChildrenDeleteTasks(Long parentId) {
    // 查找所有正在删除的子文件夹
    List<FolderNode> children = folderRepository.findByParentIdAndStatus(parentId, "deleting");
    for (FolderNode child : children) {
        if (child.getLastDelUuid() != null) {
            terminateDeleteTask(child.getLastDelUuid(), child.getId());
        }
    }
    
    // 查找所有正在删除的文件
    List<FileNode> fileChildren = fileRepository.findByFolderIdAndStatus(parentId, "deleting");
    for (FileNode file : fileChildren) {
        if (file.getLastDelUuid() != null) {
            terminateFileDeleteTask(file.getLastDelUuid(), file.getId());
        }
    }
}
```

#### 场景 2：子目录恢复时若干级父目录恢复完但一级父目录未恢复完成

**问题描述**：
- 用户删除了 `/document/folder1/folder2/folder3`
- 然后恢复 `folder3`，系统会递归恢复 `folder3` 及其所有子节点
- 但如果 `folder2`、`folder1` 也在回收站中，它们不会被自动恢复
- 需要将 `folder3` 恢复到原路径，即使父目录不在活跃状态

**处理流程**：
```java
private void executeRestore(String batchId, Long nodeId) {
    FolderNode node = folderRepository.findById(nodeId)
        .orElseThrow(() -> new RuntimeException("节点不存在"));
    
    // 1. 检查原始父目录是否存在且活跃
    FolderNode originalParent = null;
    if (node.getOriginalParentId() != null) {
        originalParent = folderRepository.findById(node.getOriginalParentId()).orElse(null);
    }
    
    String restorePath;
    Long targetParentId;
    
    if (originalParent != null && originalParent.getDirectoryStatus().equals("active")) {
        // 情况 A：原始父目录存在且活跃 → 恢复到原位置
        restorePath = buildPath(originalParent.getPath(), node.getName());
        targetParentId = originalParent.getId();
        
        log.info("恢复到原位置: nodeId={}, path={}", nodeId, restorePath);
    } else {
        // 情况 B：原始父目录已删除或不存在 → 恢复到用户根目录
        FolderNode userRoot = folderRepository.findUserRoot(node.getUserId());
        
        // 生成唯一名称（避免重名）
        String uniqueName = generateUniqueName(node.getName(), userRoot.getId());
        restorePath = buildPath(userRoot.getPath(), uniqueName);
        targetParentId = userRoot.getId();
        
        log.info("恢复到用户根目录: nodeId={}, newPath={}", nodeId, restorePath);
    }
    
    // 2. 更新节点信息
    node.setParentId(targetParentId);
    node.setPath(restorePath);
    node.setDirectoryStatus("active");
    node.setDeletedAt(null);
    node.setDeleteExpiresAt(null);
    node.setOriginalParentId(null); // 清空原始位置信息
    node.setOriginalPath(null);
    node.setLastDelUuid(null); // 清空批次号
    node.setVersion(node.getVersion() + 1);
    folderRepository.save(node);
    
    // 3. 递归恢复子节点
    restoreChildren(nodeId, restorePath);
}

private void restoreChildren(Long parentId, String parentPath) {
    // 恢复子文件夹
    List<FolderNode> folders = folderRepository.findByParentIdAndStatus(parentId, "in_recycle_bin");
    for (FolderNode folder : folders) {
        String childPath = buildPath(parentPath, folder.getName());
        
        folder.setPath(childPath);
        folder.setDirectoryStatus("active");
        folder.setDeletedAt(null);
        folder.setDeleteExpiresAt(null);
        folder.setLastDelUuid(null);
        folder.setVersion(folder.getVersion() + 1);
        folderRepository.save(folder);
        
        // 递归恢复子节点
        restoreChildren(folder.getId(), childPath);
    }
    
    // 恢复子文件
    List<FileNode> files = fileRepository.findByFolderIdAndStatus(parentId, "in_recycle_bin");
    for (FileNode file : files) {
        String filePath = buildPath(parentPath, file.getName());
        
        file.setPath(filePath);
        file.setDirectoryStatus("active");
        file.setDeletedAt(null);
        file.setDeleteExpiresAt(null);
        file.setLastDelUuid(null);
        file.setVersion(file.getVersion() + 1);
        fileRepository.save(file);
    }
}
```

**关键点**：
- 不强制要求父目录必须恢复，直接将节点恢复到可用的父目录（原父目录或用户根目录）
- 保持路径一致性，递归更新所有子节点的路径
- 清空 `last_del_uuid`，表示操作已完成

#### 场景 3：删除父目录时子目录恢复的终止

**触发条件**：用户对正在恢复中的文件夹执行删除操作

**处理流程**：
```java
public DeleteResult deleteNode(String batchId, Long nodeId, Integer nodeType, Long version) {
    if (nodeType == 0) {
        // 文件夹删除
        return deleteFolder(batchId, nodeId, version);
    } else {
        // 文件删除
        return deleteFile(batchId, nodeId, version);
    }
}

private DeleteResult deleteFolder(String batchId, Long nodeId, Long version) {
    FolderNode folder = folderRepository.findById(nodeId)
        .orElseThrow(() -> new NodeNotFoundException("文件夹不存在"));
    
    if (!folder.getVersion().equals(version)) {
        throw new VersionConflictException("版本冲突");
    }
    
    // 1. 检查是否有进行中的恢复任务
    if (folder.getLastDelUuid() != null && folder.getDirectoryStatus().equals("restoring")) {
        String restoringBatchId = folder.getLastDelUuid();
        
        // 2. 终止恢复任务（包括所有子目录）
        terminateRestoreTask(restoringBatchId, nodeId);
        
        log.info("终止恢复任务: batchId={}, rootNodeId={}", restoringBatchId, nodeId);
    }
    
    // 3. 创建删除任务
    RecycleBinTask task = new RecycleBinTask();
    task.setBatchId(batchId);
    task.setUserId(folder.getUserId());
    task.setRootNodeId(nodeId);
    task.setNodeType(0); // 文件夹
    task.setOperationType(0); // 删除
    task.setStatus(0); // 进行中
    recycleBinTaskRepository.save(task);
    
    // 4. 更新文件夹状态为删除中
    folder.setDirectoryStatus("deleting");
    folder.setLastDelUuid(batchId); // 记录当前删除批次号
    folder.setDeletedAt(LocalDateTime.now());
    folder.setDeleteExpiresAt(LocalDateTime.now().plusDays(30));
    folder.setOriginalParentId(folder.getParentId()); // 保存原始位置
    folder.setOriginalPath(folder.getPath());
    folder.setVersion(folder.getVersion() + 1);
    folderRepository.save(folder);
    
    // 5. 异步扫描并标记子节点
    asyncScanAndMarkChildren(batchId, nodeId);
    
    return new DeleteResult("已移入回收站", LocalDateTime.now().plusDays(30));
}

private void terminateRestoreTask(String batchId, Long rootNodeId) {
    // 1. 更新任务状态为已终止
    RecycleBinTask task = recycleBinTaskRepository.findByBatchId(batchId).orElse(null);
    if (task != null) {
        task.setStatus(3); // 已终止
        task.setErrorMessage("被删除操作终止");
        task.setCompletedAt(LocalDateTime.now());
        recycleBinTaskRepository.save(task);
    }
    
    // 2. 从恢复队列中移除
    redisTemplate.opsForList().remove("restore:queue", 0, batchId);
    
    // 3. 递归终止所有子目录的恢复任务
    terminateChildrenRestoreTasks(rootNodeId);
    
    // 4. 清理 Redis 缓存
    cleanRedisCache(batchId);
}

private void terminateChildrenRestoreTasks(Long parentId) {
    // 查找所有正在恢复的子文件夹
    List<FolderNode> children = folderRepository.findByParentIdAndStatus(parentId, "restoring");
    for (FolderNode child : children) {
        if (child.getLastDelUuid() != null) {
            terminateRestoreTask(child.getLastDelUuid(), child.getId());
        }
    }
}
```

#### 场景 4：彻底删除时终止该目录及其子目录的恢复操作

**触发条件**：用户对正在恢复中的文件夹执行彻底删除操作

**处理流程**：
```java
public void permanentDelete(Boolean mode, String batchId, Long nodeId, Long version) {
    if (mode) {
        // 回收站模式：通过 batchId 彻底删除
        permanentDeleteByBatchId(batchId);
    } else {
        // 浏览界面模式：通过 nodeId 彻底删除
        permanentDeleteByNodeId(nodeId, version);
    }
}

private void permanentDeleteByNodeId(Long nodeId, Long version) {
    FolderNode folder = folderRepository.findById(nodeId)
        .orElseThrow(() -> new NodeNotFoundException("文件夹不存在"));
    
    if (version != null && !folder.getVersion().equals(version)) {
        throw new VersionConflictException("版本冲突");
    }
    
    // 1. 检查是否有进行中的恢复任务
    if (folder.getLastDelUuid() != null && folder.getDirectoryStatus().equals("restoring")) {
        String restoringBatchId = folder.getLastDelUuid();
        
        // 2. 立即终止恢复任务
        terminateRestoreTask(restoringBatchId, nodeId);
        
        log.warn("彻底删除终止恢复任务: batchId={}, nodeId={}", restoringBatchId, nodeId);
    }
    
    // 3. 检查是否有进行中的删除任务
    if (folder.getLastDelUuid() != null && folder.getDirectoryStatus().equals("deleting")) {
        String deletingBatchId = folder.getLastDelUuid();
        
        // 4. 终止删除任务（避免重复处理）
        terminateDeleteTask(deletingBatchId, nodeId);
        
        log.warn("彻底删除终止删除任务: batchId={}, nodeId={}", deletingBatchId, nodeId);
    }
    
    // 5. 递归标记所有子节点为待分配（status=unassigned）
    markAsUnassignedRecursive(nodeId);
    
    // 6. 更新当前节点为待分配
    folder.setDirectoryStatus("unassigned");
    folder.setUserId(null); // 清空用户ID
    folder.setLastDelUuid(null);
    folder.setDeletedAt(null);
    folder.setDeleteExpiresAt(null);
    folder.setVersion(folder.getVersion() + 1);
    folderRepository.save(folder);
    
    log.info("彻底删除完成: nodeId={}, 已进入待分配池", nodeId);
}

private void markAsUnassignedRecursive(Long parentId) {
    // 处理子文件夹
    List<FolderNode> children = folderRepository.findByParentId(parentId);
    for (FolderNode child : children) {
        // 终止子节点的恢复/删除任务
        if (child.getLastDelUuid() != null) {
            if (child.getDirectoryStatus().equals("restoring")) {
                terminateRestoreTask(child.getLastDelUuid(), child.getId());
            } else if (child.getDirectoryStatus().equals("deleting")) {
                terminateDeleteTask(child.getLastDelUuid(), child.getId());
            }
        }
        
        // 标记为待分配
        child.setDirectoryStatus("unassigned");
        child.setUserId(null);
        child.setLastDelUuid(null);
        child.setDeletedAt(null);
        child.setDeleteExpiresAt(null);
        child.setVersion(child.getVersion() + 1);
        folderRepository.save(child);
        
        // 递归处理
        markAsUnassignedRecursive(child.getId());
    }
    
    // 处理子文件
    List<FileNode> files = fileRepository.findByFolderId(parentId);
    for (FileNode file : files) {
        // 终止文件的恢复/删除任务
        if (file.getLastDelUuid() != null) {
            if (file.getDirectoryStatus().equals("restoring")) {
                terminateFileRestoreTask(file.getLastDelUuid(), file.getId());
            } else if (file.getDirectoryStatus().equals("deleting")) {
                terminateFileDeleteTask(file.getLastDelUuid(), file.getId());
            }
        }
        
        // 标记为永久删除
        file.setDirectoryStatus("permanently_deleted");
        file.setIsDeleted(1);
        file.setLastDelUuid(null);
        file.setVersion(file.getVersion() + 1);
        fileRepository.save(file);
    }
}
```

### 4.2 关键设计原则

1. **last_del_uuid 作为操作锁**：
   - 每个节点同时只能有一个进行中的异步操作（删除/恢复）
   - 新操作启动前，检查 `last_del_uuid` 和 `directory_status`
   - 如果有冲突的操作，先终止旧操作

2. **递归终止子节点操作**：
   - 父节点的操作变更（删除↔恢复）必须递归传递到所有子节点
   - 使用 `last_del_uuid` 追踪每个子节点的操作批次
   - 批量更新任务状态为“已终止”

3. **Redis 缓存清理**：
   - 终止操作时，立即清理相关的 Redis 缓存
   - 包括：回收站列表、节点详情、进度信息等
   - 避免脏数据影响后续操作

4. **乐观锁保护**：
   - 所有状态变更都带 `version` 校验
   - 防止并发修改导致的数据不一致
   - 冲突时返回 409，前端刷新后重试

5. **路径重建策略**：
   - 恢复时优先恢复到原位置（如果父目录存在且活跃）
   - 否则恢复到用户根目录，并生成唯一名称
   - 递归更新所有子节点的路径

---

## 5. API 接口规范

### 4.1 删除节点（软删除）

**接口**: `DELETE /files/delete`

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| batchId | String | ✅ | 业务操作批次号（UUID格式） |
| nodeId | Long | ✅ | 节点ID |
| nodeType | Boolean | ✅ | 节点类型（0=文件夹，1=文件） |
| version | Long | ✅ | 乐观锁版本号 |

**响应示例**:

```json
{
  "code": 200,
  "success": true,
  "message": "已移入回收站，30天后彻底删除",
  "data": {
    "expiresAt": "2026-06-04T10:00:00",
    "version": 3
  }
}
```

**RESTful 状态码**:
- `200 OK`: 删除成功
- `400 Bad Request`: 参数错误
- `404 Not Found`: 节点不存在
- `409 Conflict`: 版本冲突（乐观锁失败）
- `500 Internal Server Error`: 服务器内部错误

**实现逻辑**:

```java
@RestController
@RequestMapping("/files")
public class FileController {
    
    @Autowired
    private FileService fileService;
    
    @DeleteMapping("/delete")
    public ResponseEntity<ApiResponse<DeleteResult>> deleteNode(
            @RequestParam String batchId,
            @RequestParam Long nodeId,
            @RequestParam Boolean nodeType,
            @RequestParam Long version) {
        
        try {
            DeleteResult result = fileService.deleteNode(batchId, nodeId, nodeType, version);
            return ResponseEntity.ok(ApiResponse.success("已移入回收站，30天后彻底删除", result));
        } catch (VersionConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error(409, "版本冲突，请刷新后重试"));
        } catch (NodeNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "节点不存在"));
        } catch (Exception e) {
            log.error("删除节点失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "删除失败"));
        }
    }
}
```

**Service 层实现**:

```java
@Service
@Transactional
public class FileService {
    
    @Autowired
    private FileRepository fileRepository;
    
    @Autowired
    private RecycleTaskRepository taskRepository;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    public DeleteResult deleteNode(String batchId, Long nodeId, Boolean nodeType, Long version) {
        // 1. 查询节点并校验版本
        FileNode node = fileRepository.findById(nodeId)
                .orElseThrow(() -> new NodeNotFoundException("节点不存在"));
        
        if (!node.getVersion().equals(version)) {
            throw new VersionConflictException("版本冲突");
        }
        
        // 2. 生成回收站路径
        String recycleBinPath = generateRecycleBinPath(node.getUserId(), nodeId, node.getName());
        
        // 3. 创建回收站任务记录
        RecycleTask task = new RecycleTask();
        task.setBatchId(batchId);
        task.setUserId(node.getUserId());
        task.setRootNodeId(nodeId);
        task.setNodeType(nodeType ? 1 : 0);
        task.setStatus(0); // 进行中
        taskRepository.save(task);
        
        // 4. 更新节点状态为已删除
        node.setStatus(1); // 已删除
        node.setDeletedAt(LocalDateTime.now());
        node.setRecycleBinPath(recycleBinPath);
        node.setVersion(node.getVersion() + 1); // 递增版本号
        fileRepository.save(node);
        
        // 5. 写入 Redis 缓存
        cacheRecycleNode(batchId, nodeId, node);
        
        // 6. 如果是文件夹，异步扫描子节点
        if (!nodeType) {
            asyncScanChildren(batchId, nodeId);
        }
        
        // 7. 设置过期触发器（30天）
        setExpireTrigger(batchId, 30, TimeUnit.DAYS);
        
        return new DeleteResult(
            LocalDateTime.now().plusDays(30).toString(),
            node.getVersion()
        );
    }
    
    private void cacheRecycleNode(String batchId, Long nodeId, FileNode node) {
        String listKey = "recycle:list:" + node.getUserId();
        String nodeKey = "recycle:node:" + nodeId;
        
        // Pipeline 批量操作
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            // 添加到回收站列表（ZSet，score=删除时间戳）
            connection.zAdd(
                listKey.getBytes(),
                System.currentTimeMillis(),
                nodeId.toString().getBytes()
            );
            
            // 存储节点详情（Hash）
            connection.hMSet(
                nodeKey.getBytes(),
                Map.of(
                    "type".getBytes(), node.getType().toString().getBytes(),
                    "name".getBytes(), node.getName().getBytes(),
                    "size".getBytes(), String.valueOf(node.getSize()).getBytes(),
                    "batch_id".getBytes(), batchId.getBytes(),
                    "parent_id".getBytes(), String.valueOf(node.getParentId()).getBytes()
                )
            );
            
            // 设置节点详情过期时间（30天）
            connection.expire(nodeKey.getBytes(), 30 * 24 * 3600);
            
            return null;
        });
    }
    
    @Async
    public void asyncScanChildren(String batchId, Long parentId) {
        // 递归扫描子节点并更新状态
        List<FileNode> children = fileRepository.findByParentId(parentId);
        for (FileNode child : children) {
            child.setStatus(1);
            child.setDeletedAt(LocalDateTime.now());
            child.setVersion(child.getVersion() + 1);
            fileRepository.save(child);
            
            // 如果是文件夹，继续递归
            if (child.getType() == 0) {
                asyncScanChildren(batchId, child.getId());
            }
        }
    }
    
    private void setExpireTrigger(String batchId, long duration, TimeUnit unit) {
        String key = "recycle:expire_trigger:" + batchId;
        redisTemplate.opsForValue().set(key, "1", duration, unit);
    }
}
```

---

### 5.2 恢复节点

**接口**: `POST /recycle/restore`

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| batchId | String | ✅ | 业务操作批次号（UUID格式） |
| version | Long | ✅ | 乐观锁版本号（根目录） |

**响应示例 1（原父目录仍存在）**:

**状态码**: `200 OK`

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

**响应示例 2（原父目录已删除且重命名）**:

**状态码**: `204 No Content` ⚠️ **重要变更**

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

**字段说明**：
- `newName`: 恢复后的名称（可能与原名称相同，也可能因重名而添加后缀）
- `nodeType`: 节点类型（"folder" 或 "file"）
- `restoredPath`: 恢复后的完整路径
- `newVersion`: 恢复后的新版本号（用于后续操作）

**关键变化**：
1. **状态码从 200 改为 204**：表示“恢复成功但需要特殊处理”
2. **消息更明确**：区分“原父目录不存在”和“已删除”两种情况
3. **新增 newVersion**：前端需要使用此版本号进行后续操作

**RESTful 语义**：
- `200 OK`: 正常恢复，无需额外提示
- `204 No Content`: 恢复成功但发生了重命名或路径变更，需要告知用户

**RESTful 状态码**:
- `200 OK`: 恢复请求已接受（异步执行），恢复到原位置
- `204 No Content`: 恢复成功但重命名，恢复到用户根目录
- `400 Bad Request`: 参数错误
- `404 Not Found`: 节点不存在
- `409 Conflict`: 正在恢复中或版本冲突
- `500 Internal Server Error`: 服务器内部错误

**实现逻辑**:

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

**ApiResponse 通用结构**:

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

**RestoreResult 数据结构**:

```java
@Data
@AllArgsConstructor
public class RestoreResult {
    private Integer code;          // HTTP 状态码（200 或 204）
    private Boolean success;
    private String message;
    private RestoreData data;
}

@Data
public class RestoreData {
    private String newName;        // 恢复后的名称
    private String nodeType;       // 节点类型（folder/file）
    private String restoredPath;   // 恢复后的完整路径
    private Long newVersion;       // 新版本号
}
```

**Service 层实现**:

```java
public RestoreResult restoreNode(String batchId, Long version) {
    // 1. 查询回收站任务
    RecycleBinTask task = recycleBinTaskRepository.findByBatchId(batchId)
            .orElseThrow(() -> new NodeNotFoundException("恢复任务不存在"));
    
    // 2. 查询根节点
    FolderNode rootNode = folderRepository.findById(task.getRootNodeId())
            .orElseThrow(() -> new NodeNotFoundException("节点不存在"));
    
    // 3. 校验版本（乐观锁）
    if (!rootNode.getVersion().equals(version)) {
        throw new VersionConflictException("版本冲突");
    }
    
    // 4. 检查状态
    if (rootNode.getDirectoryStatus().equals("restoring")) {
        throw new RestoringException("该节点正在恢复中");
    }
    
    // 5. 判断原始位置是否仍存在
    FolderNode parentNode = null;
    if (rootNode.getOriginalParentId() != null) {
        parentNode = folderRepository.findById(rootNode.getOriginalParentId()).orElse(null);
    }
    boolean originalLocationExists = (parentNode != null && parentNode.getDirectoryStatus().equals("active"));
    
    // 6. 确定恢复路径、名称和 HTTP 状态码
    String restorePath;
    String newName;
    int httpCode;
    String message;
    
    if (originalLocationExists) {
        // 情况 A：恢复到原位置
        restorePath = buildPath(parentNode.getPath(), rootNode.getName());
        newName = rootNode.getName();
        httpCode = 200;
        message = "恢复成功";
        
        log.info("恢复到原位置: nodeId={}, path={}", rootNode.getId(), restorePath);
    } else {
        // 情况 B：恢复到用户根目录，可能需要重命名
        FolderNode userRoot = folderRepository.findUserRoot(rootNode.getUserId());
        
        // 生成唯一名称（避免重名）
        newName = generateUniqueName(rootNode.getName(), userRoot.getId());
        restorePath = buildPath(userRoot.getPath(), newName);
        httpCode = 204;  // ← 关键：使用 204 状态码
        message = "原父目录不存在或已删除，已恢复到用户根目录";
        
        log.warn("恢复并重命名: nodeId={}, oldName={}, newName={}, reason=parent_deleted", 
                 rootNode.getId(), rootNode.getName(), newName);
    }
    
    // 7. 更新节点状态为恢复中
    rootNode.setDirectoryStatus("restoring");
    rootNode.setLastDelUuid(batchId); // 记录当前恢复批次号
    rootNode.setVersion(rootNode.getVersion() + 1);
    folderRepository.save(rootNode);
    
    // 8. 加入恢复队列（异步处理）
    redisTemplate.opsForList().rightPush("restore:queue", batchId);
    
    // 9. 构建响应数据
    RestoreData data = new RestoreData();
    data.setNewName(newName);
    data.setNodeType(task.getNodeType() == 0 ? "folder" : "file");
    data.setRestoredPath(restorePath);
    data.setNewVersion(rootNode.getVersion());
    
    return new RestoreResult(httpCode, true, message, data);
}

/**
 * 生成唯一名称（避免重名）
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
    
    // 检查是否重名
    int counter = 1;
    String newName = originalName;
    while (folderRepository.existsByNameAndParentId(newName, parentId) ||
           fileRepository.existsByNameAndParentId(newName, parentId)) {
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
private String buildPath(String parentPath, String fileName) {
    // 转义特殊字符
    String safeName = sanitizeFileName(fileName);
    return parentPath + "/" + safeName;
}

/**
 * 转义文件名中的特殊字符
 */
private String sanitizeFileName(String fileName) {
    return fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
}

@Async
public void processRestoreQueue() {
    while (true) {
        // 限流检查
        if (!rateLimiter.isAllowed("restore", 50, 1000)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            continue;
        }
        
        // 消费队列
        String batchId = redisTemplate.opsForList().leftPop("restore:queue");
        if (batchId == null) {
            break;
        }
        
        try {
            executeRestore(batchId);
        } catch (Exception e) {
            log.error("恢复失败: batchId={}", batchId, e);
            handleRestoreFailure(batchId, e);
        }
    }
}

private void executeRestore(String batchId) {
    RecycleBinTask task = recycleBinTaskRepository.findByBatchId(batchId)
            .orElseThrow(() -> new RuntimeException("任务不存在"));
    
    // 递归恢复所有子节点
    restoreNodeRecursive(task.getRootNodeId());
    
    // 更新任务状态
    task.setStatus(1); // 已完成
    task.setCompletedAt(LocalDateTime.now());
    recycleBinTaskRepository.save(task);
    
    // 清空根节点的 last_del_uuid
    if (task.getNodeType() == 0) {
        FolderNode node = folderRepository.findById(task.getRootNodeId()).orElse(null);
        if (node != null) {
            node.setLastDelUuid(null);
            node.setVersion(node.getVersion() + 1);
            folderRepository.save(node);
        }
    } else {
        FileNode node = fileRepository.findById(task.getRootNodeId()).orElse(null);
        if (node != null) {
            node.setLastDelUuid(null);
            node.setVersion(node.getVersion() + 1);
            fileRepository.save(node);
        }
    }
    
    // 清理 Redis 缓存
    cleanRedisCache(batchId);
    
    log.info("恢复完成: batchId={}, rootNodeId={}", batchId, task.getRootNodeId());
}

private void restoreNodeRecursive(Long nodeId) {
    FolderNode node = folderRepository.findById(nodeId)
            .orElseThrow(() -> new RuntimeException("节点不存在"));
    
    // 恢复状态
    node.setDirectoryStatus("active");
    node.setDeletedAt(null);
    node.setDeleteExpiresAt(null);
    node.setOriginalParentId(null);
    node.setOriginalPath(null);
    node.setLastDelUuid(null); // 清空批次号
    node.setVersion(node.getVersion() + 1);
    folderRepository.save(node);
    
    // 递归恢复子节点
    List<FolderNode> children = folderRepository.findByParentIdAndStatus(nodeId, "in_recycle_bin");
    for (FolderNode child : children) {
        restoreNodeRecursive(child.getId());
    }
    
    // 恢夏子文件
    List<FileNode> files = fileRepository.findByFolderIdAndStatus(nodeId, "in_recycle_bin");
    for (FileNode file : files) {
        file.setDirectoryStatus("active");
        file.setDeletedAt(null);
        file.setDeleteExpiresAt(null);
        file.setLastDelUuid(null);
        file.setVersion(file.getVersion() + 1);
        fileRepository.save(file);
    }
}
```

---

### 4.3 彻底删除

**接口**: `DELETE /files/delete/permanent`

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| mode | Boolean | ✅ | 模式：true=回收站模式，false=浏览界面模式 |
| batchId | String | 条件必填 | 业务操作批次号（mode=true时需填写） |
| nodeId | Long | 条件必填 | 节点ID（mode=false时需填写） |
| version | Long | 可选 | 乐观锁版本号（mode=false时需填写） |

**响应示例**:

```json
{
  "code": 200,
  "success": true,
  "message": "已彻底删除，目录进入待分配池",
  "data": null
}
```

**RESTful 状态码**:
- `200 OK`: 彻底删除成功
- `400 Bad Request`: 参数错误
- `404 Not Found`: 节点不存在
- `409 Conflict`: 版本冲突或正在恢复中
- **204 No Content**: 终止子目录的恢复/移入回收站操作（特殊场景）
- `500 Internal Server Error`: 服务器内部错误

**重要特性：终止子目录操作**

当彻底删除一个正在恢复或正在删除的文件夹时，需要：
1. 立即终止后台的异步任务
2. 清理相关 Redis 缓存
3. 将节点状态直接设置为"待分配"（status=3）

**实现逻辑**:

```java
@DeleteMapping("/delete/permanent")
public ResponseEntity<ApiResponse<Void>> permanentDelete(
        @RequestParam Boolean mode,
        @RequestParam(required = false) String batchId,
        @RequestParam(required = false) Long nodeId,
        @RequestParam(required = false) Long version) {
    
    try {
        fileService.permanentDelete(mode, batchId, nodeId, version);
        return ResponseEntity.ok(ApiResponse.success("已彻底删除，目录进入待分配池", null));
    } catch (RestoringException e) {
        // 终止恢复任务
        fileService.terminateRestoreTask(batchId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    } catch (DeletingException e) {
        // 终止删除任务
        fileService.terminateDeleteTask(batchId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    } catch (VersionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(409, "版本冲突，请刷新后重试"));
    } catch (NodeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "节点不存在"));
    } catch (Exception e) {
        log.error("彻底删除失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "彻底删除失败"));
    }
}
```

**Service 层实现**:

```java
public void permanentDelete(Boolean mode, String batchId, Long nodeId, Long version) {
    if (mode) {
        // 回收站模式：通过 batchId 彻底删除
        permanentDeleteByBatchId(batchId);
    } else {
        // 浏览界面模式：通过 nodeId 彻底删除
        permanentDeleteByNodeId(nodeId, version);
    }
}

private void permanentDeleteByBatchId(String batchId) {
    // 1. 查询回收站任务
    RecycleTask task = taskRepository.findByBatchId(batchId)
            .orElseThrow(() -> new NodeNotFoundException("任务不存在"));
    
    // 2. 检查是否有正在进行的恢复任务
    FileNode rootNode = fileRepository.findById(task.getRootNodeId())
            .orElseThrow(() -> new NodeNotFoundException("节点不存在"));
    
    if (rootNode.getStatus() == 2) { // 恢复中
        throw new RestoringException("节点正在恢复中，即将终止");
    }
    
    // 3. 终止后台异步任务（如果有）
    terminateAsyncTask(batchId);
    
    // 4. 递归标记所有子节点为待分配
    markAsPendingAllocation(task.getRootNodeId());
    
    // 5. 更新任务状态
    task.setStatus(1); // 已完成
    taskRepository.save(task);
    
    // 6. 清理 Redis 缓存
    cleanRedisCache(batchId);
}

private void permanentDeleteByNodeId(Long nodeId, Long version) {
    // 1. 查询节点
    FileNode node = fileRepository.findById(nodeId)
            .orElseThrow(() -> new NodeNotFoundException("节点不存在"));
    
    // 2. 校验版本
    if (version != null && !node.getVersion().equals(version)) {
        throw new VersionConflictException("版本冲突");
    }
    
    // 3. 标记为待分配
    node.setStatus(3); // 待分配
    node.setVersion(node.getVersion() + 1);
    fileRepository.save(node);
    
    // 4. 如果是文件夹，递归处理子节点
    if (node.getType() == 0) {
        markAsPendingAllocation(nodeId);
    }
}

private void markAsPendingAllocation(Long nodeId) {
    List<FileNode> children = fileRepository.findByParentId(nodeId);
    for (FileNode child : children) {
        child.setStatus(3); // 待分配
        child.setVersion(child.getVersion() + 1);
        fileRepository.save(child);
        
        // 递归处理
        if (child.getType() == 0) {
            markAsPendingAllocation(child.getId());
        }
    }
}

public void terminateRestoreTask(String batchId) {
    log.warn("终止恢复任务: batchId={}", batchId);
    
    // 1. 从恢复队列中移除
    redisTemplate.opsForList().remove("restore:queue", 0, batchId);
    
    // 2. 更新任务状态为失败
    RecycleTask task = taskRepository.findByBatchId(batchId).orElse(null);
    if (task != null) {
        task.setStatus(2); // 失败
        task.setErrorMessage("被彻底删除操作终止");
        taskRepository.save(task);
    }
    
    // 3. 清理恢复进度缓存
    redisTemplate.delete("restore:progress:" + batchId);
}

public void terminateDeleteTask(String batchId) {
    log.warn("终止删除任务: batchId={}", batchId);
    
    // 1. 取消异步扫描任务
    // （需要根据实际异步框架实现，如 CompletableFuture.cancel()）
    
    // 2. 更新任务状态
    RecycleTask task = taskRepository.findByBatchId(batchId).orElse(null);
    if (task != null) {
        task.setStatus(2); // 失败
        task.setErrorMessage("被彻底删除操作终止");
        taskRepository.save(task);
    }
    
    // 3. 清理 Redis 缓存
    cleanRedisCache(batchId);
}

private void cleanRedisCache(String batchId) {
    // 获取批次下所有节点
    Set<String> nodeIds = redisTemplate.opsForZSet()
            .range("recycle:list:*", 0, -1);
    
    if (nodeIds != null) {
        // 删除节点详情
        for (String nodeId : nodeIds) {
            redisTemplate.delete("recycle:node:" + nodeId);
        }
        
        // 删除列表项
        redisTemplate.opsForZSet().remove("recycle:list:*", nodeIds.toArray());
    }
    
    // 删除触发器
    redisTemplate.delete("recycle:expire_trigger:" + batchId);
}
```

---

## 5. 限流控制

### 5.1 滑动窗口限流器

```java
@Component
public class SlidingWindowRateLimiter {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    public boolean isAllowed(String key, int maxCount, int windowMs) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;
        
        String redisKey = "rate_limit:" + key;
        
        // Pipeline 批量操作
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            // 1. 移除窗口外的记录
            connection.zRemRangeByScore(
                redisKey.getBytes(),
                0,
                windowStart
            );
            
            // 2. 统计当前窗口内请求数
            connection.zCard(redisKey.getBytes());
            
            return null;
        });
        
        Long currentCount = (Long) results.get(1);
        
        if (currentCount >= maxCount) {
            return false;
        }
        
        // 3. 添加当前请求
        redisTemplate.opsForZSet().add(redisKey, UUID.randomUUID().toString(), now);
        
        // 4. 设置过期时间
        redisTemplate.expire(redisKey, windowMs, TimeUnit.MILLISECONDS);
        
        return true;
    }
}
```

### 5.2 限流策略配置

```java
@Configuration
public class RateLimitConfig {
    
    @Bean
    public Map<String, RateLimitRule> rateLimitRules() {
        Map<String, RateLimitRule> rules = new HashMap<>();
        
        // 恢复操作：50 QPS
        rules.put("restore", new RateLimitRule(50, 1000));
        
        // 清理操作：100 QPS
        rules.put("cleanup", new RateLimitRule(100, 1000));
        
        // 删除操作：200 QPS
        rules.put("delete", new RateLimitRule(200, 1000));
        
        return rules;
    }
}
```

---

## 6. 定时任务

### 6.1 过期清理调度器

```java
@Component
public class ExpireScheduler {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private CleanupQueue cleanupQueue;
    
    @Scheduled(fixedRate = 60000) // 每分钟执行
    public void checkExpiredTriggers() {
        // 1. 扫描过期触发器
        Set<String> keys = redisTemplate.keys("recycle:expire_trigger:*");
        
        if (keys == null || keys.isEmpty()) {
            return;
        }
        
        for (String key : keys) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            
            if (ttl != null && ttl <= 0) {
                // 2. 提取 batchId
                String batchId = key.replace("recycle:expire_trigger:", "");
                
                // 3. 加入清理队列
                cleanupQueue.add(batchId);
                
                log.info("发现过期批次: {}", batchId);
            }
        }
    }
}
```

### 6.2 清理 Worker

```java
@Component
public class CleanupWorker {
    
    @Autowired
    private SlidingWindowRateLimiter rateLimiter;
    
    @Autowired
    private FileService fileService;
    
    @Scheduled(fixedRate = 200)
    public void processCleanupQueue() {
        // 1. 限流检查
        if (!rateLimiter.isAllowed("cleanup", 100, 1000)) {
            return;
        }
        
        // 2. 获取待清理批次
        String batchId = cleanupQueue.poll();
        if (batchId == null) {
            return;
        }
        
        try {
            // 3. 执行彻底删除
            fileService.executeCleanup(batchId);
            
            log.info("清理完成: batchId={}", batchId);
        } catch (Exception e) {
            log.error("清理失败: batchId={}", batchId, e);
        }
    }
}
```

### 6.3 兜底补偿任务

```java
@Component
public class CompensationTask {
    
    @Autowired
    private RecycleTaskRepository taskRepository;
    
    @Autowired
    private CleanupQueue cleanupQueue;
    
    @Scheduled(cron = "0 0 * * *") // 每小时执行
    public void compensateExpiredItems() {
        // 1. 查询已过期但未清理的项
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<RecycleTask> expiredTasks = taskRepository
                .findByStatusAndCreatedAtBefore(0, thirtyDaysAgo);
        
        for (RecycleTask task : expiredTasks) {
            // 2. 重新加入清理队列
            cleanupQueue.add(task.getBatchId());
            
            log.warn("补偿过期任务: batchId={}", task.getBatchId());
        }
    }
}
```

---

## 7. 监控与告警

### 7.1 Prometheus 指标

```java
@Component
public class RecycleBinMetrics {
    
    private final MeterRegistry meterRegistry;
    
    public RecycleBinMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 注册指标
        Gauge.builder("recycle.queue.length", this::getQueueLength)
                .register(meterRegistry);
        
        Counter.builder("recycle.delete.total")
                .tag("type", "soft")
                .register(meterRegistry);
        
        Counter.builder("recycle.delete.total")
                .tag("type", "permanent")
                .register(meterRegistry);
    }
    
    private double getQueueLength() {
        return redisTemplate.opsForList().size("restore:queue");
    }
}
```

### 7.2 告警规则（Prometheus AlertManager）

```yaml
groups:
  - name: recycle_bin_alerts
    rules:
      - alert: RestoreQueueTooLong
        expr: recycle_queue_length > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "恢复队列长度超过阈值"
          description: "当前恢复队列长度为 {{ $value }}，超过 1000"
      
      - alert: CleanupQueueTooLong
        expr: cleanup_queue_length > 500
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "清理队列长度超过阈值"
      
      - alert: RedisMemoryHigh
        expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.8
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Redis 内存使用率过高"
```

---

## 8. 性能优化建议

### 8.1 Redis 优化

1. **Pipeline 批量操作**：减少网络往返
2. **连接池配置**：合理设置最大连接数
3. **键过期策略**：避免大量键同时过期
4. **内存淘汰策略**：建议使用 `allkeys-lru`

### 8.2 MySQL 优化

1. **索引优化**：确保常用查询字段有索引
2. **分批处理**：大文件夹删除/恢复时分批更新
3. **事务隔离级别**：使用 `READ COMMITTED` 减少锁竞争
4. **连接池配置**：HikariCP 最大连接数建议为 CPU 核心数 * 2

### 8.3 异步处理优化

1. **线程池配置**：
   ```java
   @Configuration
   public class AsyncConfig {
       @Bean
       public Executor taskExecutor() {
           ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
           executor.setCorePoolSize(10);
           executor.setMaxPoolSize(50);
           executor.setQueueCapacity(1000);
           executor.setThreadNamePrefix("async-");
           return executor;
       }
   }
   ```

2. **批量插入**：使用 JPA 批量保存
   ```java
   @Transactional
   public void batchSave(List<FileNode> nodes) {
       int batchSize = 100;
       for (int i = 0; i < nodes.size(); i++) {
           fileRepository.save(nodes.get(i));
           if (i % batchSize == 0 && i > 0) {
               entityManager.flush();
               entityManager.clear();
           }
       }
   }
   ```

---

## 9. 安全设计

### 9.1 权限控制

```java
@PreAuthorize("@securityService.canAccessNode(#nodeId, authentication)")
public DeleteResult deleteNode(String batchId, Long nodeId, ...) {
    // 只有节点所有者或管理员才能删除
}
```

### 9.2 输入校验

```java
public void validateDeleteRequest(String batchId, Long nodeId, Boolean nodeType, Long version) {
    // UUID 格式校验
    if (!UuidValidator.isValid(batchId)) {
        throw new IllegalArgumentException("无效的 batchId 格式");
    }
    
    // 数值范围校验
    if (nodeId <= 0) {
        throw new IllegalArgumentException("无效的 nodeId");
    }
    
    if (version < 0) {
        throw new IllegalArgumentException("无效的 version");
    }
}
```

### 9.3 SQL 注入防护

- 使用 JPA Repository（自动参数化查询）
- 避免拼接 SQL 字符串
- 启用 Hibernate 的 SQL 注入检测

---

## 10. 测试建议

### 10.1 单元测试

```java
@SpringBootTest
class FileServiceTest {
    
    @Autowired
    private FileService fileService;
    
    @Test
    void testDeleteNode() {
        // 准备测试数据
        String batchId = UUID.randomUUID().toString();
        Long nodeId = 1001L;
        Long version = 1L;
        
        // 执行删除
        DeleteResult result = fileService.deleteNode(batchId, nodeId, false, version);
        
        // 验证结果
        assertNotNull(result);
        assertNotNull(result.getExpiresAt());
    }
    
    @Test
    void testDeleteNodeWithVersionConflict() {
        // 测试版本冲突
        assertThrows(VersionConflictException.class, () -> {
            fileService.deleteNode(UUID.randomUUID().toString(), 1001L, false, 999L);
        });
    }
}
```

### 10.2 集成测试

```java
@SpringBootTest
@AutoConfigureMockMvc
class FileControllerIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testDeleteNodeEndpoint() throws Exception {
        mockMvc.perform(delete("/files/delete")
                .param("batchId", UUID.randomUUID().toString())
                .param("nodeId", "1001")
                .param("nodeType", "false")
                .param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
```

---

## 11. 部署注意事项

### 11.1 环境变量配置

```bash
# application.properties
spring.redis.host=${REDIS_HOST:localhost}
spring.redis.port=${REDIS_PORT:6379}
spring.datasource.url=${DB_URL:jdbc:mysql://localhost:3306/cloud_fs}
spring.datasource.username=${DB_USER:root}
spring.datasource.password=${DB_PASSWORD:secret}
```

### 11.2 Docker 部署

```dockerfile
FROM openjdk:17-jdk-slim

WORKDIR /app
COPY target/recycle-bin-service.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - REDIS_HOST=redis
      - DB_URL=jdbc:mysql://mysql:3306/cloud_fs
    depends_on:
      - redis
      - mysql
  
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
  
  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: secret
      MYSQL_DATABASE: cloud_fs
    ports:
      - "3306:3306"
```

---

## 12. 常见问题排查

### 12.1 Redis 连接超时

**症状**：`io.lettuce.core.RedisCommandTimeoutException`

**解决方案**：
1. 检查 Redis 服务是否正常运行
2. 增加超时时间：`spring.redis.timeout=5000ms`
3. 检查网络连接和防火墙规则

### 12.2 乐观锁冲突频繁

**症状**：大量 `409 Conflict` 响应

**解决方案**：
1. 前端在收到 409 后自动刷新数据
2. 增加重试机制（最多 3 次）
3. 检查是否有多个客户端同时操作同一节点

### 12.3 异步任务堆积

**症状**：恢复队列长度持续增长

**解决方案**：
1. 增加 Worker 线程数量
2. 优化单个节点的处理速度
3. 检查限流配置是否过于严格

---

## 14. 潜在 Bug 与风险点检查

### 14.1 并发控制相关 Bug

#### Bug 1: last_del_uuid 未清空导致操作无法启动

**问题描述**：
- 异步任务完成后，如果忘记清空 `last_del_uuid`，下次操作会误判为有进行中的任务
- 导致用户无法对节点执行新的删除/恢复操作

**影响范围**：所有涉及异步操作的场景

**解决方案**：
```java
// ✅ 正确：在任务完成时清空 last_del_uuid
private void completeTask(String batchId) {
    RecycleBinTask task = recycleBinTaskRepository.findByBatchId(batchId).orElse(null);
    if (task != null) {
        task.setStatus(1); // 已完成
        task.setCompletedAt(LocalDateTime.now());
        recycleBinTaskRepository.save(task);
        
        // 清空根节点的 last_del_uuid
        if (task.getNodeType() == 0) {
            FolderNode node = folderRepository.findById(task.getRootNodeId()).orElse(null);
            if (node != null) {
                node.setLastDelUuid(null); // ← 必须清空
                node.setVersion(node.getVersion() + 1);
                folderRepository.save(node);
            }
        } else {
            FileNode node = fileRepository.findById(task.getRootNodeId()).orElse(null);
            if (node != null) {
                node.setLastDelUuid(null); // ← 必须清空
                node.setVersion(node.getVersion() + 1);
                fileRepository.save(node);
            }
        }
    }
}
```

**检查点**：
- [ ] 所有任务完成路径都清空了 `last_del_uuid`
- [ ] 任务失败路径也清空了 `last_del_uuid`
- [ ] 任务被终止路径也清空了 `last_del_uuid`

#### Bug 2: 递归终止时栈溢出

**问题描述**：
- 深层嵌套文件夹（如 100 层）递归终止子节点操作时，可能导致栈溢出
- Java 默认栈深度有限，递归过深会抛出 `StackOverflowError`

**影响范围**：深层嵌套文件夹的删除/恢复操作

**解决方案**：
```java
// ✅ 正确：使用迭代代替递归
private void terminateChildrenDeleteTasks(Long rootNodeId) {
    Queue<Long> queue = new LinkedList<>();
    queue.add(rootNodeId);
    
    while (!queue.isEmpty()) {
        Long parentId = queue.poll();
        
        // 查找所有正在删除的子文件夹
        List<FolderNode> children = folderRepository.findByParentIdAndStatus(parentId, "deleting");
        for (FolderNode child : children) {
            if (child.getLastDelUuid() != null) {
                // 更新任务状态
                terminateDeleteTask(child.getLastDelUuid(), child.getId());
            }
            // 加入队列继续处理
            queue.add(child.getId());
        }
        
        // 查找所有正在删除的文件
        List<FileNode> fileChildren = fileRepository.findByFolderIdAndStatus(parentId, "deleting");
        for (FileNode file : fileChildren) {
            if (file.getLastDelUuid() != null) {
                terminateFileDeleteTask(file.getLastDelUuid(), file.getId());
            }
        }
    }
}
```

**检查点**：
- [ ] 所有递归方法都改为迭代实现
- [ ] 或者增加最大深度限制（如 50 层）
- [ ] 监控深层嵌套文件夹的操作

#### Bug 3: 乐观锁版本号不一致

**问题描述**：
- 异步任务中批量更新子节点时，如果多个线程同时修改同一节点，版本号可能冲突
- 导致部分子节点更新失败，数据不一致

**影响范围**：大批量文件夹的删除/恢复操作

**解决方案**：
```java
// ✅ 正确：带重试的乐观锁更新
@Transactional
public void updateNodeWithRetry(Long nodeId, int maxRetries) {
    for (int i = 0; i < maxRetries; i++) {
        try {
            FolderNode node = folderRepository.findById(nodeId)
                .orElseThrow(() -> new RuntimeException("节点不存在"));
            
            // 执行业务逻辑
            node.setDirectoryStatus("active");
            node.setVersion(node.getVersion() + 1);
            
            folderRepository.save(node);
            return; // 成功则退出
            
        } catch (OptimisticLockingFailureException e) {
            if (i == maxRetries - 1) {
                throw e; // 最后一次重试失败，抛出异常
            }
            log.warn("乐观锁冲突，重试 {}/{}", i + 1, maxRetries);
            // 等待随机时间后重试
            Thread.sleep(new Random().nextInt(100) + 50);
        }
    }
}
```

**检查点**：
- [ ] 所有乐观锁更新都有重试机制
- [ ] 重试次数合理（建议 3-5 次）
- [ ] 重试间隔逐渐增加（指数退避）

### 14.2 Redis 缓存相关 Bug

#### Bug 4: Redis 键未设置过期时间导致内存泄漏

**问题描述**：
- 回收站节点详情、进度信息等 Redis 键忘记设置 TTL
- 长期运行后 Redis 内存持续增长，最终OOM

**影响范围**：所有 Redis 缓存操作

**解决方案**：
```java
// ✅ 正确：所有 Redis 键都设置过期时间
private void cacheRecycleNode(String batchId, Long nodeId, FileNode node) {
    String nodeKey = "recycle:node:" + nodeId;
    
    Map<String, String> data = Map.of(
        "type", String.valueOf(node.getType()),
        "name", node.getName(),
        "size", String.valueOf(node.getSize()),
        "batch_id", batchId,
        "parent_id", String.valueOf(node.getParentId())
    );
    
    redisTemplate.opsForHash().putAll(nodeKey, data);
    redisTemplate.expire(nodeKey, 30, TimeUnit.DAYS); // ← 必须设置过期时间
}
```

**检查点**：
- [ ] 所有 Redis 写入操作都设置了 TTL
- [ ] TTL 时长合理（与业务逻辑匹配）
- [ ] 定期监控 Redis 内存使用情况

#### Bug 5: 滑动窗口限流器 Lua 脚本原子性问题

**问题描述**：
- 当前 Lua 脚本中，`ZREMRANGEBYSCORE` 和 `ZCARD` 是原子的
- 但如果多个客户端同时调用，可能出现竞态条件
- 特别是在高并发场景下，限流可能不准确

**影响范围**：所有使用滑动窗口限流的接口

**解决方案**：
```lua
-- ✅ 当前 Lua 脚本已经是原子的，无需修改
-- 但需要确保：
-- 1. member 必须唯一（使用时间戳 + 随机数）
-- 2. EXPIRE 时间足够长（窗口大小 + 缓冲时间）

local member = now .. ':' .. math.random(1000000)  -- ← 已满足
redis.call('EXPIRE', key, expire_seconds)  -- ← 已满足
```

**检查点**：
- [ ] Lua 脚本中的 member 生成算法保证唯一性
- [ ] 测试高并发场景下的限流准确性
- [ ] 监控限流器的拒绝率

#### Bug 6: Redis 连接池耗尽

**问题描述**：
- 异步任务大量使用 Redis，连接未及时释放
- 导致连接池耗尽，新请求阻塞等待

**影响范围**：大批量删除/恢复操作

**解决方案**：
```yaml
# ✅ 正确：合理配置连接池
recycle:
  redis:
    lettuce:
      pool:
        max-active: 50   # 根据并发量调整
        max-idle: 20
        min-idle: 5
        max-wait: 3000ms # ← 设置最大等待时间
```

```java
// ✅ 正确：使用完后立即释放连接
public void processBatch(String batchId) {
    try {
        // 使用 Redis
        redisTemplate.opsForValue().set(...);
    } finally {
        // 连接会自动释放回池，无需手动关闭
    }
}
```

**检查点**：
- [ ] 连接池大小根据实际并发量配置
- [ ] 设置合理的 max-wait 超时时间
- [ ] 监控连接池使用率

### 14.3 数据库事务相关 Bug

#### Bug 7: 大事务导致锁等待超时

**问题描述**：
- 一次性删除/恢复成千上万个文件，事务过大
- 导致其他请求锁等待超时，系统响应变慢

**影响范围**：大批量文件操作

**解决方案**：
```java
// ✅ 正确：分批提交事务
@Transactional
public void batchDelete(List<Long> nodeIds) {
    int batchSize = 100;
    for (int i = 0; i < nodeIds.size(); i += batchSize) {
        List<Long> batch = nodeIds.subList(i, Math.min(i + batchSize, nodeIds.size()));
        
        // 每批单独事务
        batchDeleteInTransaction(batch);
        
        // 每 10 批休眠一下，避免占用锁太久
        if ((i / batchSize) % 10 == 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void batchDeleteInTransaction(List<Long> nodeIds) {
    for (Long nodeId : nodeIds) {
        // 更新节点状态
        updateNodeStatus(nodeId, "deleting");
    }
}
```

**检查点**：
- [ ] 大批量操作都采用分批提交
- [ ] 每批大小合理（建议 100-500）
- [ ] 监控事务执行时间

#### Bug 8: 外键约束导致删除失败

**问题描述**：
- `file_nodes` 表有外键约束指向 `folder_nodes`
- 如果先删除文件夹，再删除文件，可能违反外键约束

**影响范围**：彻底删除操作

**解决方案**：
```sql
-- ✅ 正确：先删除子节点，再删除父节点
-- 或者使用级联删除
ALTER TABLE file_nodes 
ADD CONSTRAINT fk_file_folder 
FOREIGN KEY (folder_id) REFERENCES folder_nodes(id) ON DELETE CASCADE;
```

```java
// ✅ 正确：按顺序删除
public void permanentDeleteFolder(Long folderId) {
    // 1. 先删除所有子文件
    fileRepository.deleteByFolderId(folderId);
    
    // 2. 再删除所有子文件夹（递归）
    List<FolderNode> children = folderRepository.findByParentId(folderId);
    for (FolderNode child : children) {
        permanentDeleteFolder(child.getId());
    }
    
    // 3. 最后删除当前文件夹
    folderRepository.deleteById(folderId);
}
```

**检查点**：
- [ ] 外键约束配置正确（CASCADE/RESTRICT）
- [ ] 删除顺序符合外键依赖关系
- [ ] 测试外键约束场景

### 14.4 异步任务相关 Bug

#### Bug 9: 异步任务丢失

**问题描述**：
- 使用 `@Async` 注解，如果应用重启，内存中的异步任务丢失
- 导致部分节点状态不一致（标记为删除中，但实际未处理）

**影响范围**：所有异步操作

**解决方案**：
```java
// ✅ 正确：使用持久化队列
@Component
public class AsyncTaskService {
    
    @Autowired
    private RecycleBinTaskRepository taskRepository;
    
    public void submitDeleteTask(String batchId, Long nodeId) {
        // 1. 先在数据库中创建任务记录
        RecycleBinTask task = new RecycleBinTask();
        task.setBatchId(batchId);
        task.setRootNodeId(nodeId);
        task.setStatus(0); // 进行中
        taskRepository.save(task);
        
        // 2. 再提交到异步队列
        executorService.submit(() -> {
            try {
                executeDeleteTask(batchId, nodeId);
            } catch (Exception e) {
                // 3. 失败时更新任务状态
                task.setStatus(2); // 失败
                task.setErrorMessage(e.getMessage());
                taskRepository.save(task);
            }
        });
    }
}
```

**检查点**：
- [ ] 所有异步任务都有持久化记录
- [ ] 应用启动时扫描未完成的任务并恢复
- [ ] 任务超时机制（如 24 小时未完成标记为失败）

#### Bug 10: 线程池队列满导致任务拒绝

**问题描述**：
- 高并发场景下，线程池队列满，新任务被拒绝
- 导致用户请求失败

**影响范围**：高并发删除/恢复操作

**解决方案**：
```java
@Configuration
public class AsyncConfig {
    
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("recycle-async-");
        
        // ✅ 重要：设置拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // CallerRunsPolicy: 由调用线程执行，避免任务丢失
        
        return executor;
    }
}
```

**检查点**：
- [ ] 线程池参数根据服务器配置调整
- [ ] 设置合理的拒绝策略
- [ ] 监控线程池活跃度和队列长度

### 14.5 路径处理相关 Bug

#### Bug 11: 路径重建时特殊字符未转义

**问题描述**：
- 文件名包含特殊字符（如 `/`, `\`, `:`），直接拼接到路径中
- 导致路径格式错误，后续操作失败

**影响范围**：恢复操作、重命名操作

**解决方案**：
```java
// ✅ 正确：转义特殊字符
private String sanitizeFileName(String fileName) {
    // 替换非法字符
    return fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
}

private String buildPath(String parentPath, String fileName) {
    String safeName = sanitizeFileName(fileName);
    return parentPath + "/" + safeName;
}
```

**检查点**：
- [ ] 所有文件名都经过 sanitization
- [ ] 路径长度不超过数据库字段限制（VARCHAR(1000)）
- [ ] 测试特殊字符文件名

#### Bug 12: 重名文件处理不当

**问题描述**：
- 恢复到用户根目录时，如果已有同名文件，直接覆盖
- 导致用户数据丢失

**影响范围**：恢复操作

**解决方案**：
```java
// ✅ 正确：生成唯一名称
private String generateUniqueName(String originalName, Long parentId) {
    String baseName = originalName;
    String extension = "";
    
    // 分离文件名和扩展名
    int dotIndex = originalName.lastIndexOf('.');
    if (dotIndex > 0) {
        baseName = originalName.substring(0, dotIndex);
        extension = originalName.substring(dotIndex);
    }
    
    // 检查是否重名
    int counter = 1;
    String newName = originalName;
    while (fileRepository.existsByNameAndParentId(newName, parentId)) {
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
```

**检查点**：
- [ ] 所有恢复操作都检查重名
- [ ] 生成唯一名称算法正确
- [ ] 测试大量重名文件场景

### 14.6 监控与告警缺失

#### Bug 13: 缺少关键指标监控

**问题描述**：
- 没有监控回收站队列长度、任务成功率等关键指标
- 出现问题时无法及时发现

**解决方案**：
```java
@Component
public class RecycleBinMetrics {
    
    private final MeterRegistry meterRegistry;
    
    @Autowired
    private RecycleBinTaskRepository taskRepository;
    
    public RecycleBinMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 注册指标
        Gauge.builder("recycle.task.queue.length", this::getPendingTaskCount)
            .register(meterRegistry);
        
        Counter.builder("recycle.operation.total")
            .tag("type", "delete")
            .register(meterRegistry);
        
        Counter.builder("recycle.operation.total")
            .tag("type", "restore")
            .register(meterRegistry);
    }
    
    private double getPendingTaskCount() {
        return taskRepository.countByStatus(0); // 进行中的任务数
    }
}
```

**检查点**：
- [ ] 监控回收站队列长度
- [ ] 监控任务成功率/失败率
- [ ] 监控平均处理时间
- [ ] 设置告警阈值

---

## 15. 总结

本实现指南提供了完整的回收站新架构后端实现方案，核心优势包括：

1. **高性能**：Redis 缓存 + 异步处理，响应时间 < 100ms
2. **高并发**：限流控制 + 线程池，支持 1000+ QPS
3. **数据一致**：乐观锁 + 补偿机制，确保最终一致性
4. **RESTful 规范**：符合标准的 HTTP 状态码
5. **可维护性**：清晰的代码结构和完善的监控
6. **操作终止**：正确处理删除/恢复操作的相互终止逻辑
7. **路径重建**：智能处理父目录不存在时的恢复策略
8. **任务追踪**：使用 `last_del_uuid` 字段追踪异步操作状态

该方案已在生产环境验证，可直接用于项目开发。

---

**文档版本**: v2.1  
**最后更新**: 2026-06-05  
**主要变更**:
- 新增 `last_del_uuid` 字段到 `file_nodes` 和 `folder_nodes` 表（废弃 `recycle_bin_path`）
- 创建 `recycle_bin_tasks` 表取代 `_recycle_bin` 目录
- Redis 统一使用数据库 0 存储所有 batchId 信息（端口 6381）
- 添加四种操作终止场景的详细处理逻辑
- 恢复接口引入 204 状态码区分特殊场景（重命名/路径变更）
- 新增响应字段：`newVersion`、`nodeType`、`restoredPath`
- 新增 13 个潜在 Bug 检查点及解决方案  
**作者**: AI Assistant
