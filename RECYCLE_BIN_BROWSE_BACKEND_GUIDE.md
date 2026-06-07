# 浏览回收站后端实现指南（基于 recycle_bin_tasks 表）

## 1. 概述

本文档基于新的数据库架构 `database_schema_complete_v3.sql`，提供浏览回收站功能的完整后端实现指南。

**核心变更：**
- ❌ **废弃**：原有的 `_recycle_bin` 目录结构
- ✅ **新增**：`recycle_bin_tasks` 任务表追踪删除/恢复操作
- ✅ **优化**：通过 `folder_nodes` 和 `file_nodes` 的 `directory_status` 字段标识回收站状态

**架构优势：**
1. **性能提升**：无需遍历虚拟目录，直接查询状态字段
2. **数据一致性**：任务表记录完整的操作历史
3. **可扩展性**：支持异步批量操作和进度追踪
4. **简化逻辑**：回收站不再是物理目录，而是逻辑状态

---

## 2. 数据库表结构分析

### 2.1 recycle_bin_tasks 表（核心任务表）

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回收站任务表（取代_recycle_bin目录）';
```

**字段说明：**

| 字段 | 类型 | 说明 | 使用场景 |
|------|------|------|---------|
| `batch_id` | VARCHAR(36) | UUID 格式的业务批次号 | 前端传递的唯一标识 |
| `user_id` | BIGINT | 用户 ID | 权限隔离 |
| `root_node_id` | BIGINT | 根节点 ID（文件夹或文件） | 关联 folder_nodes/file_nodes |
| `node_type` | TINYINT | 0=文件夹，1=文件 | 区分节点类型 |
| `operation_type` | TINYINT | 0=删除，1=恢复，2=彻底删除 | 区分操作类型 |
| `total_count` | INT | 总节点数（异步扫描后更新） | 进度显示 |
| `processed_count` | INT | 已处理节点数 | 进度显示 |
| `status` | TINYINT | 0=进行中，1=已完成，2=失败，3=已终止 | 任务状态 |
| `created_at` | DATETIME | 任务创建时间 | 排序依据（游标分页） |

### 2.2 folder_nodes 表（回收站相关字段）

```sql
-- 软删除支持
is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',
delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',

-- 目录状态（用于回收站）
directory_status ENUM('active', 'in_recycle_bin', 'unassigned', 'deleting', 'restoring') DEFAULT 'active' COMMENT '目录状态',

-- 原始位置信息（用于恢复）
original_parent_id BIGINT DEFAULT NULL COMMENT '原始父文件夹ID（删除时记录，用于恢复）',
original_path VARCHAR(1000) DEFAULT NULL COMMENT '原始完整路径（删除时记录，用于恢复）',

-- 最后删除/恢复批次号（用于追踪异步操作）
last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号（UUID格式）',

-- 乐观锁版本号
version BIGINT DEFAULT 0 COMMENT '乐观锁版本号，每次更新自动+1',
```

### 2.3 file_nodes 表（回收站相关字段）

```sql
-- 软删除支持
is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',
delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',

-- 目录状态（用于回收站）
directory_status ENUM('active', 'in_recycle_bin', 'permanently_deleted', 'deleting', 'restoring') DEFAULT 'active' COMMENT '文件状态',

-- 原始位置信息（用于恢复）
original_folder_id BIGINT DEFAULT NULL COMMENT '原始所属文件夹ID（删除时记录，用于恢复）',
original_path VARCHAR(1000) DEFAULT NULL COMMENT '原始完整路径（删除时记录，用于恢复）',

-- 最后删除/恢复批次号（用于追踪异步操作）
last_del_uuid VARCHAR(36) DEFAULT NULL COMMENT '最后删除/恢复批次号（UUID格式）',

-- 乐观锁版本号
version BIGINT DEFAULT 0 COMMENT '乐观锁版本号，每次更新自动+1',
```

---

## 3. 浏览回收站 API 实现

### 3.1 API 接口定义

**接口**: `GET /files/recycle`

**功能**: 浏览用户回收站中的内容（支持游标分页、多种排序）

**请求参数**:

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `currentNodeId` | Long | ✅ | - | 固定为回收站根节点 ID（从 users 表或配置获取） |
| `maxPageSize` | Integer | ❌ | 20 | 每页数量（最大 100） |
| `lastBatchId` | String | ❌ | null | 游标锚点（最后一个删除任务的 batch_id） |
| `sortedBy` | Integer | ❌ | 2 | 排序字段：0=name, 1=size（仅文件）, 2=deletedAt |
| `order` | Boolean | ❌ | true | 排序顺序：false=asc, true=desc |

**响应示例**:

```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": {
    "children": [
      {
        "id": 5001,
        "name": "work.pdf",
        "type": "file",
        "size": 1048576,
        "createdAt": "2026-05-01T10:00:00",
        "updatedAt": "2026-05-01T10:00:00",
        "deletedAt": "2026-05-05T10:05:00",
        "expiresAt": "2026-06-04T10:05:00",
        "daysRemaining": 30,
        "version": 2,
        "batchId": "550e8400-e29b-41d4-a716-446655440000"
      },
      {
        "id": 3001,
        "name": "documents",
        "type": "folder",
        "size": 0,
        "createdAt": "2026-04-28T09:00:00",
        "updatedAt": "2026-04-28T09:00:00",
        "deletedAt": "2026-05-05T10:00:00",
        "expiresAt": "2026-06-04T10:00:00",
        "daysRemaining": 30,
        "version": 5,
        "batchId": "660e8400-e29b-41d4-a716-446655440001"
      }
    ],
    "pagination": {
      "lastBatchId": "660e8400-e29b-41d4-a716-446655440001",
      "isEnd": false
    }
  }
}
```

---

## 4. SQL 查询设计

### 4.1 核心查询逻辑

**关键思路：**
1. 从 `recycle_bin_tasks` 表获取删除任务列表（按 `created_at` 排序）
2. 根据 `root_node_id` 和 `node_type` 关联查询 `folder_nodes` 或 `file_nodes`
3. 过滤条件：`directory_status = 'in_recycle_bin'`
4. 支持游标分页：`created_at < lastBatchCreatedAt`

### 4.2 基础查询 SQL

```sql
-- 查询回收站中的文件夹和文件（联合查询）
SELECT 
    -- 通用字段
    COALESCE(fn.id, filen.id) AS id,
    COALESCE(fn.name, filen.name) AS name,
    CASE 
        WHEN fn.id IS NOT NULL THEN 'folder'
        ELSE 'file'
    END AS type,
    COALESCE(filen.file_size, 0) AS size,
    COALESCE(fn.created_at, filen.created_at) AS createdAt,
    COALESCE(fn.updated_at, filen.updated_at) AS updatedAt,
    COALESCE(fn.deleted_at, filen.deleted_at) AS deletedAt,
    COALESCE(fn.delete_expires_at, filen.delete_expires_at) AS expiresAt,
    DATEDIFF(COALESCE(fn.delete_expires_at, filen.delete_expires_at), NOW()) AS daysRemaining,
    COALESCE(fn.version, filen.version) AS version,
    rbt.batch_id AS batchId
    
FROM recycle_bin_tasks rbt

-- 左连接文件夹节点
LEFT JOIN folder_nodes fn 
    ON rbt.root_node_id = fn.id 
    AND rbt.node_type = 0
    AND fn.directory_status = 'in_recycle_bin'

-- 左连接文件节点
LEFT JOIN file_nodes filen 
    ON rbt.root_node_id = filen.id 
    AND rbt.node_type = 1
    AND filen.directory_status = 'in_recycle_bin'

WHERE rbt.user_id = :userId
  AND rbt.operation_type = 0  -- 只查询删除任务
  AND rbt.status IN (0, 1)    -- 进行中或已完成的任务
  
  -- 游标分页：如果提供了 lastBatchId，则查询更早的任务
  AND (:lastBatchId IS NULL OR rbt.created_at < (
      SELECT created_at FROM recycle_bin_tasks 
      WHERE batch_id = :lastBatchId AND user_id = :userId
  ))

ORDER BY rbt.created_at DESC  -- 按删除时间降序

LIMIT :maxPageSize;
```

### 4.3 排序支持

#### 4.3.1 按名称排序（sortedBy=0）

```sql
ORDER BY 
    CASE 
        WHEN :sortedBy = 0 THEN COALESCE(fn.name, filen.name)
    END ASC/DESC,
    rbt.created_at DESC  -- 次要排序：确保稳定性
```

#### 4.3.2 按大小排序（sortedBy=1，仅文件有效）

```sql
ORDER BY 
    CASE 
        WHEN :sortedBy = 1 AND filen.id IS NOT NULL THEN filen.file_size
        ELSE 0  -- 文件夹大小为 0
    END ASC/DESC,
    rbt.created_at DESC
```

#### 4.3.3 按删除时间排序（sortedBy=2，默认）

```sql
ORDER BY 
    COALESCE(fn.deleted_at, filen.deleted_at) ASC/DESC,
    rbt.created_at DESC
```

### 4.4 完整查询示例（Java MyBatis）

```xml
<!-- Mapper XML -->
<select id="browseRecycleBin" resultType="com.cloudfs.dto.RecycleBinItemDTO">
    SELECT 
        COALESCE(fn.id, filen.id) AS id,
        COALESCE(fn.name, filen.name) AS name,
        CASE 
            WHEN fn.id IS NOT NULL THEN 'folder'
            ELSE 'file'
        END AS type,
        COALESCE(filen.file_size, 0) AS size,
        COALESCE(fn.created_at, filen.created_at) AS createdAt,
        COALESCE(fn.updated_at, filen.updated_at) AS updatedAt,
        COALESCE(fn.deleted_at, filen.deleted_at) AS deletedAt,
        COALESCE(fn.delete_expires_at, filen.delete_expires_at) AS expiresAt,
        DATEDIFF(COALESCE(fn.delete_expires_at, filen.delete_expires_at), NOW()) AS daysRemaining,
        COALESCE(fn.version, filen.version) AS version,
        rbt.batch_id AS batchId
        
    FROM recycle_bin_tasks rbt
    
    LEFT JOIN folder_nodes fn 
        ON rbt.root_node_id = fn.id 
        AND rbt.node_type = 0
        AND fn.directory_status = 'in_recycle_bin'
        AND fn.is_deleted = 0
        
    LEFT JOIN file_nodes filen 
        ON rbt.root_node_id = filen.id 
        AND rbt.node_type = 1
        AND filen.directory_status = 'in_recycle_bin'
        AND filen.is_deleted = 0
        
    WHERE rbt.user_id = #{userId}
      AND rbt.operation_type = 0
      
      <!-- 游标分页 -->
      <if test="lastBatchId != null and lastBatchId != ''">
          AND rbt.created_at &lt; (
              SELECT created_at FROM recycle_bin_tasks 
              WHERE batch_id = #{lastBatchId} AND user_id = #{userId}
          )
      </if>
      
    ORDER BY
        <choose>
            <when test="sortedBy == 0">
                <!-- 按名称排序 -->
                COALESCE(fn.name, filen.name) ${orderStr},
            </when>
            <when test="sortedBy == 1">
                <!-- 按大小排序（仅文件） -->
                CASE WHEN filen.id IS NOT NULL THEN filen.file_size ELSE 0 END ${orderStr},
            </when>
            <otherwise>
                <!-- 按删除时间排序（默认） -->
                COALESCE(fn.deleted_at, filen.deleted_at) ${orderStr},
            </otherwise>
        </choose>
        rbt.created_at DESC
        
    LIMIT #{maxPageSize}
</select>
```

### 4.5 查询是否有更多数据

```sql
-- 检查是否还有更多数据
SELECT COUNT(*) > 0 AS hasMore
FROM recycle_bin_tasks rbt
WHERE rbt.user_id = :userId
  AND rbt.operation_type = 0
  AND rbt.created_at &lt; (
      SELECT MIN(created_at) FROM (
          SELECT created_at FROM recycle_bin_tasks 
          WHERE user_id = :userId AND operation_type = 0
          ORDER BY created_at DESC
          LIMIT :maxPageSize
      ) AS last_page
  );
```

---

## 5. Service 层实现

### 5.1 DTO 定义

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecycleBinItemDTO {
    private Long id;
    private String name;
    private String type; // "folder" or "file"
    private Long size;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
    private LocalDateTime expiresAt;
    private Integer daysRemaining;
    private Long version;
    private String batchId;
}

@Data
@AllArgsConstructor
public class RecycleBinBrowseResponse {
    private List<RecycleBinItemDTO> children;
    private PaginationInfo pagination;
    
    @Data
    @AllArgsConstructor
    public static class PaginationInfo {
        private String lastBatchId;
        private Boolean isEnd;
    }
}
```

### 5.2 Service 实现

```java
@Service
@Transactional(readOnly = true)
public class RecycleBinService {
    
    @Autowired
    private RecycleBinMapper recycleBinMapper;
    
    @Autowired
    private UserService userService;
    
    /**
     * 浏览回收站
     */
    public RecycleBinBrowseResponse browseRecycleBin(
            Long userId,
            Integer maxPageSize,
            String lastBatchId,
            Integer sortedBy,
            Boolean order) {
        
        // 1. 参数校验
        if (maxPageSize == null || maxPageSize <= 0) {
            maxPageSize = 20;
        }
        if (maxPageSize > 100) {
            maxPageSize = 100; // 限制最大页数
        }
        if (sortedBy == null) {
            sortedBy = 2; // 默认按删除时间排序
        }
        if (order == null) {
            order = true; // 默认降序
        }
        
        // 2. 构建排序字符串
        String orderStr = order ? "DESC" : "ASC";
        
        // 3. 查询回收站列表
        List<RecycleBinItemDTO> items = recycleBinMapper.browseRecycleBin(
            userId, maxPageSize, lastBatchId, sortedBy, orderStr
        );
        
        // 4. 计算分页信息
        String newLastBatchId = null;
        Boolean isEnd = true;
        
        if (!items.isEmpty()) {
            // 获取最后一项的 batchId
            newLastBatchId = items.get(items.size() - 1).getBatchId();
            
            // 检查是否还有更多数据
            isEnd = !hasMoreItems(userId, newLastBatchId);
        }
        
        // 5. 构建响应
        RecycleBinBrowseResponse.PaginationInfo pagination = 
            new RecycleBinBrowseResponse.PaginationInfo(newLastBatchId, isEnd);
        
        return new RecycleBinBrowseResponse(items, pagination);
    }
    
    /**
     * 检查是否还有更多数据
     */
    private boolean hasMoreItems(Long userId, String lastBatchId) {
        Integer count = recycleBinMapper.countMoreItems(userId, lastBatchId);
        return count != null && count > 0;
    }
}
```

### 5.3 Mapper 接口

```java
@Mapper
public interface RecycleBinMapper {
    
    /**
     * 浏览回收站
     */
    List<RecycleBinItemDTO> browseRecycleBin(
        @Param("userId") Long userId,
        @Param("maxPageSize") Integer maxPageSize,
        @Param("lastBatchId") String lastBatchId,
        @Param("sortedBy") Integer sortedBy,
        @Param("orderStr") String orderStr
    );
    
    /**
     * 检查是否还有更多数据
     */
    Integer countMoreItems(
        @Param("userId") Long userId,
        @Param("lastBatchId") String lastBatchId
    );
}
```

---

## 6. Controller 层实现

```java
@RestController
@RequestMapping("/files")
public class FileController {
    
    @Autowired
    private RecycleBinService recycleBinService;
    
    /**
     * 浏览回收站
     */
    @GetMapping("/recycle")
    public ResponseEntity<ApiResponse<RecycleBinBrowseResponse>> browseRecycleBin(
            @RequestParam Long currentNodeId,
            @RequestParam(required = false, defaultValue = "20") Integer maxPageSize,
            @RequestParam(required = false) String lastBatchId,
            @RequestParam(required = false, defaultValue = "2") Integer sortedBy,
            @RequestParam(required = false, defaultValue = "true") Boolean order) {
        
        try {
            // 1. 获取当前用户 ID（从 JWT Token 中解析）
            Long userId = SecurityUtils.getCurrentUserId();
            
            // 2. 调用 Service 层
            RecycleBinBrowseResponse response = recycleBinService.browseRecycleBin(
                userId, maxPageSize, lastBatchId, sortedBy, order
            );
            
            // 3. 返回响应
            return ResponseEntity.ok(ApiResponse.success("获取成功", response));
            
        } catch (Exception e) {
            log.error("浏览回收站失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(500, "获取回收站失败"));
        }
    }
}
```

---

## 7. 删除操作与任务表联动

### 7.1 删除节点时创建任务记录

```java
@Service
@Transactional
public class FileDeleteService {
    
    @Autowired
    private RecycleBinTaskRepository taskRepository;
    
    @Autowired
    private FolderNodeRepository folderNodeRepository;
    
    @Autowired
    private FileNodeRepository fileNodeRepository;
    
    /**
     * 删除节点（软删除，移入回收站）
     */
    public DeleteResult deleteNode(String batchId, Long nodeId, Boolean nodeType, Long version) {
        
        // 1. 查询节点并校验版本
        FileNode node;
        if (nodeType) {
            // 文件
            node = fileNodeRepository.findById(nodeId)
                    .orElseThrow(() -> new NodeNotFoundException("文件不存在"));
        } else {
            // 文件夹
            FolderNode folder = folderNodeRepository.findById(nodeId)
                    .orElseThrow(() -> new NodeNotFoundException("文件夹不存在"));
            // 转换为通用节点对象
            node = convertToNode(folder);
        }
        
        if (!node.getVersion().equals(version)) {
            throw new VersionConflictException("版本冲突");
        }
        
        // 2. 创建回收站任务记录
        RecycleBinTask task = new RecycleBinTask();
        task.setBatchId(batchId);
        task.setUserId(node.getUserId());
        task.setRootNodeId(nodeId);
        task.setNodeType(nodeType ? 1 : 0);
        task.setOperationType(0); // 删除操作
        task.setStatus(0); // 进行中
        task.setCreatedAt(LocalDateTime.now());
        taskRepository.save(task);
        
        // 3. 更新节点状态为回收站
        if (nodeType) {
            // 文件
            node.setDirectoryStatus("in_recycle_bin");
            node.setIsDeleted(0); // 软删除标记
            node.setDeletedAt(LocalDateTime.now());
            node.setDeleteExpiresAt(LocalDateTime.now().plusDays(30));
            node.setOriginalFolderId(node.getFolderId()); // 记录原始位置
            node.setOriginalPath(node.getPath());
            node.setLastDelUuid(batchId);
            node.setVersion(node.getVersion() + 1);
            fileNodeRepository.save((FileNode) node);
        } else {
            // 文件夹
            FolderNode folder = (FolderNode) node;
            folder.setDirectoryStatus("in_recycle_bin");
            folder.setIsDeleted(0);
            folder.setDeletedAt(LocalDateTime.now());
            folder.setDeleteExpiresAt(LocalDateTime.now().plusDays(30));
            folder.setOriginalParentId(folder.getParentId());
            folder.setOriginalPath(folder.getPath());
            folder.setLastDelUuid(batchId);
            folder.setVersion(folder.getVersion() + 1);
            folderNodeRepository.save(folder);
        }
        
        // 4. 如果是文件夹，异步扫描子节点
        if (!nodeType) {
            asyncScanChildren(batchId, nodeId);
        }
        
        // 5. 构建响应
        return new DeleteResult(
            LocalDateTime.now().plusDays(30).toString(),
            node.getVersion()
        );
    }
    
    @Async
    public void asyncScanChildren(String batchId, Long parentId) {
        // 递归扫描子节点并更新状态
        // ...（实现略）
    }
}
```

### 7.2 恢复节点时更新任务状态

```java
@Service
@Transactional
public class RecycleRestoreService {
    
    @Autowired
    private RecycleBinTaskRepository taskRepository;
    
    /**
     * 恢复节点
     */
    public RestoreResult restoreNode(String batchId, Long version) {
        
        // 1. 查询回收站任务
        RecycleBinTask task = taskRepository.findByBatchId(batchId)
                .orElseThrow(() -> new NodeNotFoundException("恢复任务不存在"));
        
        // 2. 查询根节点
        FileNode rootNode;
        if (task.getNodeType() == 0) {
            // 文件夹
            FolderNode folder = folderNodeRepository.findById(task.getRootNodeId())
                    .orElseThrow(() -> new NodeNotFoundException("文件夹不存在"));
            rootNode = convertToNode(folder);
        } else {
            // 文件
            rootNode = fileNodeRepository.findById(task.getRootNodeId())
                    .orElseThrow(() -> new NodeNotFoundException("文件不存在"));
        }
        
        // 3. 校验版本
        if (!rootNode.getVersion().equals(version)) {
            throw new VersionConflictException("版本冲突");
        }
        
        // 4. 执行恢复逻辑
        // ...（恢复逻辑略，参考之前的文档）
        
        // 5. 更新任务状态为已完成
        task.setStatus(1); // 已完成
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
        
        // 6. 返回结果
        return new RestoreResult(...);
    }
}
```

---

## 8. 性能优化建议

### 8.1 索引优化

```sql
-- 确保以下索引存在
ALTER TABLE recycle_bin_tasks ADD INDEX idx_user_operation_created (user_id, operation_type, created_at);
ALTER TABLE folder_nodes ADD INDEX idx_status_deleted (directory_status, is_deleted, deleted_at);
ALTER TABLE file_nodes ADD INDEX idx_status_deleted (directory_status, is_deleted, deleted_at);
```

### 8.2 查询优化

1. **避免 N+1 查询**：使用 JOIN 一次性获取所有数据
2. **限制返回字段**：只查询必要的字段，避免 `SELECT *`
3. **缓存热点数据**：使用 Redis 缓存用户的回收站列表（TTL=5分钟）

### 8.3 分页优化

- **游标分页**：基于 `created_at` 而非 `OFFSET`，性能更好
- **限制最大页数**：`maxPageSize <= 100`，防止恶意请求

---

## 9. 测试用例

### 9.1 单元测试

```java
@SpringBootTest
class RecycleBinServiceTest {
    
    @Autowired
    private RecycleBinService recycleBinService;
    
    @Test
    void testBrowseRecycleBin() {
        Long userId = 10001L;
        RecycleBinBrowseResponse response = recycleBinService.browseRecycleBin(
            userId, 20, null, 2, true
        );
        
        assertNotNull(response);
        assertNotNull(response.getChildren());
        assertNotNull(response.getPagination());
    }
    
    @Test
    void testBrowseRecycleBinWithCursor() {
        Long userId = 10001L;
        String lastBatchId = "550e8400-e29b-41d4-a716-446655440000";
        
        RecycleBinBrowseResponse response = recycleBinService.browseRecycleBin(
            userId, 20, lastBatchId, 2, true
        );
        
        // 验证游标分页生效
        assertTrue(response.getChildren().size() <= 20);
    }
}
```

### 9.2 集成测试

```java
@SpringBootTest
@AutoConfigureMockMvc
class RecycleBinControllerIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Test
    void testBrowseRecycleBinEndpoint() throws Exception {
        mockMvc.perform(get("/files/recycle")
                .param("currentNodeId", "1")
                .param("maxPageSize", "20")
                .param("sortedBy", "2")
                .param("order", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.children").isArray());
    }
}
```

---

## 10. 常见问题排查

### 10.1 回收站列表为空

**症状**: 用户删除了文件，但回收站列表显示为空

**可能原因**:
1. `recycle_bin_tasks` 表中没有对应的任务记录
2. `directory_status` 未正确更新为 `in_recycle_bin`
3. 查询条件中的 `user_id` 不匹配

**解决方案**:
```sql
-- 检查任务表
SELECT * FROM recycle_bin_tasks WHERE user_id = 10001 AND operation_type = 0;

-- 检查节点状态
SELECT * FROM folder_nodes WHERE user_id = 10001 AND directory_status = 'in_recycle_bin';
SELECT * FROM file_nodes WHERE user_id = 10001 AND directory_status = 'in_recycle_bin';
```

### 10.2 游标分页失效

**症状**: 多次请求返回相同的数据

**可能原因**:
1. `lastBatchId` 传递错误
2. `created_at` 时间戳精度问题

**解决方案**:
```java
// 确保 lastBatchId 正确传递
String lastBatchId = items.get(items.size() - 1).getBatchId();
log.info("Next cursor: {}", lastBatchId);
```

### 10.3 性能问题

**症状**: 查询耗时超过 1 秒

**可能原因**:
1. 缺少索引
2. 数据量过大（回收站中有数万条记录）

**解决方案**:
1. 添加复合索引：`idx_user_operation_created`
2. 限制最大返回数量：`maxPageSize <= 100`
3. 使用 Redis 缓存

---

## 11. 总结

本实现指南基于新的 `recycle_bin_tasks` 表架构，提供了完整的浏览回收站后端实现方案。

**核心优势：**
1. ✅ **清晰的职责分离**：任务表记录操作历史，节点表存储状态
2. ✅ **高性能查询**：基于索引的游标分页，避免 OFFSET 性能问题
3. ✅ **可扩展性**：支持异步批量操作和进度追踪
4. ✅ **数据一致性**：通过 `batch_id` 关联任务和节点

**实施步骤：**
1. 执行数据库迁移脚本（创建 `recycle_bin_tasks` 表）
2. 实现 Mapper 层的 SQL 查询
3. 实现 Service 层的业务逻辑
4. 实现 Controller 层的 API 接口
5. 编写单元测试和集成测试
6. 部署并监控性能指标

---

**文档版本**: v1.0  
**最后更新**: 2026-06-05  
**作者**: AI Assistant  
**审核状态**: 待审核
