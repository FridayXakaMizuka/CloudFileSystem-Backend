# 云文件系统 - 目录树系统设计文档

## 📋 目录

1. [系统概述](#系统概述)
2. [目录结构设计](#目录结构设计)
3. [数据库设计](#数据库设计)
4. [Redis 缓存策略](#redis-缓存策略)
5. [API 接口设计](#api-接口设计)
6. [实现步骤](#实现步骤)
7. [未来扩展](#未来扩展)

---

## 🎯 系统概述

### 核心需求

1. **用户根目录隔离**：每个用户拥有独立的根目录 `/files/users/{userID}`
2. **管理员根目录**：`_root` 标识的根目录，用于系统管理（头像、备份等）
3. **懒加载机制**：前端按需加载目录内容，提升性能
4. **文件索引解耦**：目录树只存储文件元数据索引，实际文件由文件上传服务管理
5. **Redis 缓存**：缓存目录树结构，减少数据库查询

### 技术栈

- **后端框架**：Spring Boot 4.0.x
- **数据库**：MySQL 8.0+
- **缓存**：Redis（专用端口 6381）
- **ORM**：MyBatis
- **文件存储**：本地文件系统 / OSS（可扩展）

---

## 📁 目录结构设计

### 逻辑目录树

```
_root                       ← 根节点（同时也是管理员根目录）
├── /avatar                ← 头像存储虚拟目录
│   └── /users/{userID}
├── /backup                ← 数据库备份目录
├── /system                ← 系统文件目录
└── /files                 ← 文件服务根目录
    └── /users             ← 用户目录容器
        ├── /10001         ← 用户 10001 的根目录
        │   ├── /documents ← 用户自建文件夹
        │   │   ├── /work
        │   │   └── /personal
        │   ├── /photos    ← 用户自建文件夹
        │   ├── file_001.pdf ← 用户上传的文件（索引）
        │   └── file_002.jpg
        │
        ├── /10002         ← 用户 10002 的根目录
        │   ├── /downloads
        │   └── file_003.docx
        │
        └── /10003         ← 用户 10003 的根目录
            └── ...
```

### 物理存储结构

```
/opt/cloudfilesystem/storage/
├── actual_files/          ← 实际文件存储（与目录树解耦）
│   ├── chunk_001_part1
│   ├── chunk_001_part2
│   ├── chunk_002
│   └── ...
│
├── thumbnails/            ← 缩略图存储
│   ├── thumb_001.jpg
│   └── ...
│
└── temp_uploads/          ← 临时上传文件
    └── ...
```

**关键设计**：
- 根节点 `_root` 同时也是管理员根目录，简化了目录结构
- 目录树中的"文件"只是元数据索引
- 实际文件存储在 `actual_files/` 目录
- 通过 `file_metadata` 表关联目录节点和实际文件
- `/avatar`、`/files`、`/backup`、`/system` 都是 `_root` 的直接子目录

---

## 🗄️ 数据库设计

### 1. 目录节点表 (`directory_nodes`)

存储所有目录和文件的树形结构。

```sql
CREATE TABLE directory_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '节点ID',
    parent_id BIGINT DEFAULT NULL COMMENT '父节点ID，NULL表示根节点',
    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    
    -- 节点类型
    node_type ENUM('folder', 'file') NOT NULL COMMENT '节点类型：文件夹或文件',
    
    -- 基本信息
    name VARCHAR(255) NOT NULL COMMENT '节点名称（文件夹名或文件名）',
    path VARCHAR(1000) NOT NULL COMMENT '完整路径，如 /_root/files/users/10001/documents',
    level INT DEFAULT 0 COMMENT '层级深度，根目录为0',
    
    -- 文件相关字段（仅当 node_type='file' 时有值）
    file_metadata_id BIGINT DEFAULT NULL COMMENT '关联文件元数据表ID',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小（字节）',
    mime_type VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    
    -- 排序和显示
    sort_order INT DEFAULT 0 COMMENT '同级节点排序顺序',
    is_hidden TINYINT(1) DEFAULT 0 COMMENT '是否隐藏',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_parent_id (parent_id),
    INDEX idx_user_id (user_id),
    INDEX idx_path (path(255)),
    INDEX idx_node_type (node_type),
    INDEX idx_file_metadata_id (file_metadata_id),
    
    -- 外键约束
    FOREIGN KEY (parent_id) REFERENCES directory_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (file_metadata_id) REFERENCES file_metadata(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='目录节点表';
```

### 2. 文件元数据表 (`file_metadata`)

存储实际文件的详细信息，与目录树解耦。

```sql
CREATE TABLE file_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件元数据ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID',
    
    -- 文件标识
    file_hash VARCHAR(64) NOT NULL COMMENT '文件SHA256哈希值（去重用）',
    original_filename VARCHAR(255) NOT NULL COMMENT '原始文件名',
    stored_filename VARCHAR(255) NOT NULL COMMENT '存储文件名（UUID或哈希）',
    
    -- 文件信息
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    mime_type VARCHAR(100) NOT NULL COMMENT 'MIME类型',
    extension VARCHAR(20) DEFAULT NULL COMMENT '文件扩展名',
    
    -- 分片信息（支持断点续传）
    total_chunks INT DEFAULT 1 COMMENT '总分片数',
    uploaded_chunks INT DEFAULT 0 COMMENT '已上传分片数',
    chunk_size BIGINT DEFAULT 5242880 COMMENT '分片大小（默认5MB）',
    
    -- 存储位置
    storage_path VARCHAR(500) NOT NULL COMMENT '实际存储路径',
    storage_type ENUM('local', 'oss', 's3') DEFAULT 'local' COMMENT '存储类型',
    
    -- 状态
    upload_status ENUM('pending', 'uploading', 'completed', 'failed', 'deleted') DEFAULT 'pending' COMMENT '上传状态',
    is_public TINYINT(1) DEFAULT 0 COMMENT '是否公开访问',
    
    -- 下载次数统计
    download_count INT DEFAULT 0 COMMENT '下载次数',
    last_download_at DATETIME DEFAULT NULL COMMENT '最后下载时间',
    
    -- 时间戳
    uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_user_id (user_id),
    UNIQUE INDEX idx_file_hash (file_hash),
    INDEX idx_upload_status (upload_status),
    INDEX idx_stored_filename (stored_filename),
    
    -- 外键
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件元数据表';
```

### 3. 文件分片表 (`file_chunks`)

记录每个文件的分片信息，支持断点续传。

```sql
CREATE TABLE file_chunks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分片ID',
    file_metadata_id BIGINT NOT NULL COMMENT '关联文件元数据ID',
    
    -- 分片信息
    chunk_index INT NOT NULL COMMENT '分片索引（从0开始）',
    chunk_hash VARCHAR(64) NOT NULL COMMENT '分片SHA256哈希值',
    chunk_size BIGINT NOT NULL COMMENT '分片大小（字节）',
    
    -- 存储位置
    chunk_path VARCHAR(500) NOT NULL COMMENT '分片存储路径',
    
    -- 状态
    upload_status ENUM('pending', 'uploaded', 'verified', 'failed') DEFAULT 'pending' COMMENT '上传状态',
    
    -- 时间戳
    uploaded_at DATETIME DEFAULT NULL COMMENT '上传完成时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    -- 索引
    UNIQUE INDEX idx_file_chunk (file_metadata_id, chunk_index),
    INDEX idx_chunk_hash (chunk_hash),
    
    -- 外键
    FOREIGN KEY (file_metadata_id) REFERENCES file_metadata(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件分片表';
```

### 4. 目录权限表 (`directory_permissions`)

控制目录和文件的访问权限。

```sql
CREATE TABLE directory_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '权限ID',
    node_id BIGINT NOT NULL COMMENT '目录节点ID',
    user_id BIGINT NOT NULL COMMENT '授权用户ID',
    
    -- 权限类型
    permission_type ENUM('read', 'write', 'delete', 'share') NOT NULL COMMENT '权限类型',
    is_granted TINYINT(1) DEFAULT 1 COMMENT '是否授予权限',
    
    -- 授权信息
    granted_by BIGINT DEFAULT NULL COMMENT '授权者用户ID',
    granted_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    expires_at DATETIME DEFAULT NULL COMMENT '权限过期时间',
    
    -- 索引
    UNIQUE INDEX idx_node_user_permission (node_id, user_id, permission_type),
    INDEX idx_user_id (user_id),
    
    -- 外键
    FOREIGN KEY (node_id) REFERENCES directory_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='目录权限表';
```

### 5. 初始化数据

```sql
-- 插入根节点（同时也是管理员根目录）
INSERT INTO directory_nodes (id, parent_id, user_id, node_type, name, path, level, sort_order) VALUES
(1, NULL, 0, 'folder', '_root', '/_root', 0, 0);

-- 插入头像存储目录
INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order) VALUES
(1, 0, 'folder', 'avatar', '/_root/avatar', 1, 0);

-- 插入文件服务根目录
INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order) VALUES
(1, 0, 'folder', 'files', '/_root/files', 1, 1);

-- 插入备份目录
INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order) VALUES
(1, 0, 'folder', 'backup', '/_root/backup', 1, 2);

-- 插入系统文件目录
INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order) VALUES
(1, 0, 'folder', 'system', '/_root/system', 1, 3);

-- 插入用户目录容器
INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order) VALUES
((SELECT id FROM directory_nodes WHERE path = '/_root/files'), 0, 'folder', 'users', '/_root/files/users', 2, 0);

-- 为现有用户创建根目录（假设用户ID从10001开始）
INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order)
SELECT 
    (SELECT id FROM directory_nodes WHERE path = '/_root/files/users'),
    u.id,
    'folder',
    CAST(u.id AS CHAR),
    CONCAT('/_root/files/users/', u.id),
    3,
    0
FROM users u
WHERE u.id >= 10001;
```

---

## 💾 Redis 缓存策略

### 缓存 Key 设计

#### 1. 目录内容缓存

```
Key: dir:children:{nodeId}:{userId}
Value: JSON 数组，包含子节点列表
TTL: 300秒（5分钟）
```

**示例**：
```json
[
  {
    "id": 100,
    "name": "documents",
    "type": "folder",
    "hasChildren": true,
    "createdAt": "2026-05-05T10:00:00Z"
  },
  {
    "id": 101,
    "name": "report.pdf",
    "type": "file",
    "size": 1048576,
    "mimeType": "application/pdf",
    "createdAt": "2026-05-05T10:05:00Z"
  }
]
```

#### 2. 节点详情缓存

```
Key: dir:node:{nodeId}
Value: JSON 对象，节点详细信息
TTL: 600秒（10分钟）
```

#### 3. 用户根目录缓存

```
Key: dir:user_root:{userId}
Value: 用户根节点ID
TTL: 3600秒（1小时）
```

#### 4. 路径缓存

```
Key: dir:path:{path}
Value: 节点ID
TTL: 600秒（10分钟）
```

### 缓存更新策略

#### 写操作时清除缓存

```java
// 创建文件夹后
public void createFolder(Long parentId, String folderName, Long userId) {
    // 1. 创建节点
    DirectoryNode newNode = directoryMapper.insert(parentId, userId, "folder", folderName);
    
    // 2. 清除父节点的子节点缓存
    String cacheKey = "dir:children:" + parentId + ":" + userId;
    redisTemplate.delete(cacheKey);
    
    // 3. 清除路径缓存
    redisTemplate.delete("dir:path:" + newNode.getPath());
}

// 删除节点后
public void deleteNode(Long nodeId, Long userId) {
    // 1. 获取节点信息
    DirectoryNode node = directoryMapper.findById(nodeId);
    
    // 2. 删除节点（级联删除子节点）
    directoryMapper.deleteById(nodeId);
    
    // 3. 清除父节点缓存
    if (node.getParentId() != null) {
        String cacheKey = "dir:children:" + node.getParentId() + ":" + userId;
        redisTemplate.delete(cacheKey);
    }
    
    // 4. 清除路径缓存
    redisTemplate.delete("dir:path:" + node.getPath());
}
```

#### 读操作时使用缓存

```java
public List<DirectoryNodeVO> getChildren(Long nodeId, Long userId) {
    // 1. 尝试从缓存获取
    String cacheKey = "dir:children:" + nodeId + ":" + userId;
    List<DirectoryNodeVO> cached = redisTemplate.opsForValue().get(cacheKey);
    
    if (cached != null) {
        logger.debug("[目录浏览] 缓存命中 - NodeId: {}", nodeId);
        return cached;
    }
    
    // 2. 缓存未命中，查询数据库
    logger.debug("[目录浏览] 缓存未命中，查询数据库 - NodeId: {}", nodeId);
    List<DirectoryNodeVO> children = directoryMapper.findChildren(nodeId, userId);
    
    // 3. 存入缓存
    if (!children.isEmpty()) {
        redisTemplate.opsForValue().set(cacheKey, children, 300, TimeUnit.SECONDS);
        logger.info("[目录浏览] 缓存已设置 - NodeId: {}, 子节点数: {}", nodeId, children.size());
    }
    
    return children;
}
```

### Redis 实例选择

- **端口 6381**：用于目录树缓存（高频读写）
- **端口 6380**：用于个人资料缓存（低频读写）

---

## 🔌 API 接口设计

### 1. 浏览目录内容

```
GET /api/files/browse?nodeId={nodeId}&page={page}&pageSize={pageSize}
```

**请求参数**：
- `nodeId`: 目录节点ID（必填）
- `page`: 页码，默认 1
- `pageSize`: 每页数量，默认 50

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": {
    "currentNode": {
      "id": 100,
      "name": "documents",
      "path": "/_root/files/users/10001/documents",
      "parentId": 50
    },
    "children": [
      {
        "id": 101,
        "name": "work",
        "type": "folder",
        "hasChildren": true,
        "childCount": 5,
        "createdAt": "2026-05-05T10:00:00Z"
      },
      {
        "id": 102,
        "name": "report.pdf",
        "type": "file",
        "size": 1048576,
        "mimeType": "application/pdf",
        "thumbnail": "/thumbnails/thumb_102.jpg",
        "createdAt": "2026-05-05T10:05:00Z"
      }
    ],
    "pagination": {
      "page": 1,
      "pageSize": 50,
      "total": 2,
      "totalPages": 1
    }
  }
}
```

### 2. 创建文件夹

```
POST /api/files/folder
```

**请求体**：
```json
{
  "parentId": 100,
  "folderName": "new_folder"
}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "文件夹创建成功",
  "data": {
    "id": 103,
    "name": "new_folder",
    "path": "/_root/files/users/10001/documents/new_folder"
  }
}
```

### 3. 重命名节点

```
PUT /api/files/rename/{nodeId}
```

**请求体**：
```json
{
  "newName": "renamed_folder"
}
```

### 4. 移动节点

```
PUT /api/files/move/{nodeId}
```

**请求体**：
```json
{
  "newParentId": 200
}
```

### 5. 删除节点

```
DELETE /api/files/{nodeId}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "删除成功"
}
```

### 6. 搜索文件/文件夹

```
GET /api/files/search?keyword={keyword}&userId={userId}&type={type}
```

**请求参数**：
- `keyword`: 搜索关键词
- `userId`: 用户ID
- `type`: 类型过滤（folder/file/all）

### 7. 获取文件下载链接

```
GET /api/files/download/{nodeId}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "获取成功",
  "data": {
    "downloadUrl": "/api/files/download/stream/{fileMetadataId}",
    "filename": "report.pdf",
    "size": 1048576,
    "mimeType": "application/pdf",
    "supportsRange": true,
    "expiresIn": 3600
  }
}
```

### 8. 分片上传 - 初始化

```
POST /api/files/upload/init
```

**请求体**：
```json
{
  "filename": "large_video.mp4",
  "fileSize": 104857600,
  "mimeType": "video/mp4",
  "parentId": 100,
  "totalChunks": 20
}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "上传初始化成功",
  "data": {
    "fileMetadataId": 500,
    "uploadId": "upload_abc123",
    "chunkSize": 5242880,
    "uploadedChunks": []
  }
}
```

### 9. 分片上传 - 上传分片

```
POST /api/files/upload/chunk
Content-Type: multipart/form-data
```

**表单数据**：
- `uploadId`: 上传ID
- `chunkIndex`: 分片索引
- `chunkHash`: 分片哈希
- `file`: 分片文件

### 10. 分片上传 - 完成

```
POST /api/files/upload/complete
```

**请求体**：
```json
{
  "uploadId": "upload_abc123",
  "fileMetadataId": 500,
  "chunkHashes": ["hash1", "hash2", ...]
}
```

### 11. 断点续传 - 查询进度

```
GET /api/files/upload/status/{uploadId}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "data": {
    "uploadId": "upload_abc123",
    "status": "uploading",
    "totalChunks": 20,
    "uploadedChunks": [0, 1, 2, 5, 6],
    "progress": 25
  }
}
```

---

## 🛠️ 实现步骤

### 阶段 1：数据库层（第 1-2 天）

#### 1.1 创建数据库表

```sql
-- 执行上述 SQL 脚本创建所有表
source /path/to/directory_tree_schema.sql
```

#### 1.2 创建 Entity 类

```java
// src/main/java/com/mizuka/cloudfilesystem/entity/DirectoryNode.java
package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

public class DirectoryNode {
    private Long id;
    private Long parentId;
    private Long userId;
    private String nodeType; // "folder" or "file"
    private String name;
    private String path;
    private Integer level;
    private Long fileMetadataId;
    private Long fileSize;
    private String mimeType;
    private Integer sortOrder;
    private Boolean isHidden;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // getters and setters...
}
```

```java
// src/main/java/com/mizuka/cloudfilesystem/entity/FileMetadata.java
package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

public class FileMetadata {
    private Long id;
    private Long userId;
    private String fileHash;
    private String originalFilename;
    private String storedFilename;
    private Long fileSize;
    private String mimeType;
    private String extension;
    private Integer totalChunks;
    private Integer uploadedChunks;
    private Long chunkSize;
    private String storagePath;
    private String storageType;
    private String uploadStatus;
    private Boolean isPublic;
    private Integer downloadCount;
    private LocalDateTime lastDownloadAt;
    private LocalDateTime uploadedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // getters and setters...
}
```

#### 1.3 创建 Mapper 接口

```java
// src/main/java/com/mizuka/cloudfilesystem/mapper/DirectoryNodeMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.DirectoryNode;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DirectoryNodeMapper {
    
    @Select("SELECT * FROM directory_nodes WHERE id = #{id}")
    DirectoryNode findById(Long id);
    
    @Select("SELECT * FROM directory_nodes WHERE parent_id = #{parentId} AND user_id = #{userId} ORDER BY sort_order, created_at")
    List<DirectoryNode> findChildren(@Param("parentId") Long parentId, @Param("userId") Long userId);
    
    @Insert("INSERT INTO directory_nodes (parent_id, user_id, node_type, name, path, level, sort_order) " +
            "VALUES (#{parentId}, #{userId}, #{nodeType}, #{name}, #{path}, #{level}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DirectoryNode node);
    
    @Update("UPDATE directory_nodes SET name = #{name}, path = #{path}, updated_at = NOW() WHERE id = #{id}")
    int update(DirectoryNode node);
    
    @Delete("DELETE FROM directory_nodes WHERE id = #{id}")
    int deleteById(Long id);
    
    @Select("SELECT * FROM directory_nodes WHERE path = #{path}")
    DirectoryNode findByPath(String path);
    
    @Select("SELECT COUNT(*) FROM directory_nodes WHERE parent_id = #{parentId}")
    int countChildren(Long parentId);
}
```

```java
// src/main/java/com/mizuka/cloudfilesystem/mapper/FileMetadataMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FileMetadata;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FileMetadataMapper {
    
    @Select("SELECT * FROM file_metadata WHERE id = #{id}")
    FileMetadata findById(Long id);
    
    @Insert("INSERT INTO file_metadata (user_id, file_hash, original_filename, stored_filename, " +
            "file_size, mime_type, extension, total_chunks, uploaded_chunks, chunk_size, " +
            "storage_path, storage_type, upload_status) " +
            "VALUES (#{userId}, #{fileHash}, #{originalFilename}, #{storedFilename}, " +
            "#{fileSize}, #{mimeType}, #{extension}, #{totalChunks}, #{uploadedChunks}, " +
            "#{chunkSize}, #{storagePath}, #{storageType}, #{uploadStatus})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileMetadata metadata);
    
    @Update("UPDATE file_metadata SET upload_status = #{uploadStatus}, " +
            "uploaded_chunks = #{uploadedChunks}, updated_at = NOW() WHERE id = #{id}")
    int updateUploadStatus(FileMetadata metadata);
    
    @Select("SELECT * FROM file_metadata WHERE file_hash = #{fileHash}")
    FileMetadata findByHash(String fileHash);
}
```

### 阶段 2：Service 层（第 3-5 天）

#### 2.1 目录服务

```java
// src/main/java/com/mizuka/cloudfilesystem/service/DirectoryService.java
package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.entity.DirectoryNode;
import com.mizuka.cloudfilesystem.mapper.DirectoryNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class DirectoryService {
    
    private static final Logger logger = LoggerFactory.getLogger(DirectoryService.class);
    
    @Autowired
    private DirectoryNodeMapper directoryNodeMapper;
    
    @Autowired
    @Qualifier("directoryRedisTemplate") // 使用端口 6381
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * 获取目录子节点（带缓存）
     */
    public List<DirectoryNode> getChildren(Long nodeId, Long userId) {
        String cacheKey = "dir:children:" + nodeId + ":" + userId;
        
        // 尝试从缓存获取
        @SuppressWarnings("unchecked")
        List<DirectoryNode> cached = (List<DirectoryNode>) redisTemplate.opsForValue().get(cacheKey);
        
        if (cached != null) {
            logger.debug("[目录浏览] 缓存命中 - NodeId: {}", nodeId);
            return cached;
        }
        
        // 查询数据库
        logger.debug("[目录浏览] 缓存未命中，查询数据库 - NodeId: {}", nodeId);
        List<DirectoryNode> children = directoryNodeMapper.findChildren(nodeId, userId);
        
        // 存入缓存
        if (!children.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, children, 300, TimeUnit.SECONDS);
            logger.info("[目录浏览] 缓存已设置 - NodeId: {}, 子节点数: {}", nodeId, children.size());
        }
        
        return children;
    }
    
    /**
     * 创建文件夹
     */
    @Transactional
    public DirectoryNode createFolder(Long parentId, String folderName, Long userId) {
        // 验证父节点存在且属于该用户
        DirectoryNode parent = directoryNodeMapper.findById(parentId);
        if (parent == null || !parent.getUserId().equals(userId)) {
            throw new IllegalArgumentException("父目录不存在或无权限");
        }
        
        // 检查同名文件夹
        List<DirectoryNode> siblings = directoryNodeMapper.findChildren(parentId, userId);
        boolean exists = siblings.stream()
            .anyMatch(node -> node.getName().equals(folderName));
        
        if (exists) {
            throw new IllegalArgumentException("同名文件夹已存在");
        }
        
        // 创建新节点
        DirectoryNode newNode = new DirectoryNode();
        newNode.setParentId(parentId);
        newNode.setUserId(userId);
        newNode.setNodeType("folder");
        newNode.setName(folderName);
        newNode.setPath(parent.getPath() + "/" + folderName);
        newNode.setLevel(parent.getLevel() + 1);
        newNode.setSortOrder(siblings.size());
        
        directoryNodeMapper.insert(newNode);
        
        // 清除父节点缓存
        String cacheKey = "dir:children:" + parentId + ":" + userId;
        redisTemplate.delete(cacheKey);
        
        logger.info("[创建文件夹] 成功 - UserId: {}, ParentId: {}, FolderName: {}", 
            userId, parentId, folderName);
        
        return newNode;
    }
    
    /**
     * 删除节点（级联删除）
     */
    @Transactional
    public void deleteNode(Long nodeId, Long userId) {
        DirectoryNode node = directoryNodeMapper.findById(nodeId);
        if (node == null || !node.getUserId().equals(userId)) {
            throw new IllegalArgumentException("节点不存在或无权限");
        }
        
        // 如果是文件，删除文件元数据
        if ("file".equals(node.getNodeType()) && node.getFileMetadataId() != null) {
            // TODO: 调用文件服务删除实际文件
            logger.info("[删除文件] 标记删除 - FileMetadataId: {}", node.getFileMetadataId());
        }
        
        // 删除节点（数据库外键会级联删除子节点）
        directoryNodeMapper.deleteById(nodeId);
        
        // 清除父节点缓存
        if (node.getParentId() != null) {
            String cacheKey = "dir:children:" + node.getParentId() + ":" + userId;
            redisTemplate.delete(cacheKey);
        }
        
        logger.info("[删除节点] 成功 - NodeId: {}, UserId: {}", nodeId, userId);
    }
}
```

#### 2.2 文件上传服务

```java
// src/main/java/com/mizuka/cloudfilesystem/service/FileUploadService.java
package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.entity.FileMetadata;
import com.mizuka.cloudfilesystem.mapper.FileMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Service
public class FileUploadService {
    
    private static final Logger logger = LoggerFactory.getLogger(FileUploadService.class);
    
    private static final String UPLOAD_DIR = "/opt/cloudfilesystem/storage/actual_files/";
    private static final long CHUNK_SIZE = 5 * 1024 * 1024; // 5MB
    
    @Autowired
    private FileMetadataMapper fileMetadataMapper;
    
    /**
     * 初始化分片上传
     */
    @Transactional
    public UploadInitResponse initUpload(UploadInitRequest request, Long userId) {
        // 计算文件哈希（可选，用于去重）
        String fileHash = calculateFileHash(request.getFilename(), request.getFileSize());
        
        // 检查是否已存在相同文件
        FileMetadata existing = fileMetadataMapper.findByHash(fileHash);
        if (existing != null && "completed".equals(existing.getUploadStatus())) {
            logger.info("[文件去重] 文件已存在 - FileHash: {}", fileHash);
            return new UploadInitResponse(existing.getId(), null, CHUNK_SIZE, true);
        }
        
        // 创建文件元数据
        FileMetadata metadata = new FileMetadata();
        metadata.setUserId(userId);
        metadata.setFileHash(fileHash);
        metadata.setOriginalFilename(request.getFilename());
        metadata.setStoredFilename(UUID.randomUUID().toString());
        metadata.setFileSize(request.getFileSize());
        metadata.setMimeType(request.getMimeType());
        metadata.setExtension(getExtension(request.getFilename()));
        metadata.setTotalChunks(request.getTotalChunks());
        metadata.setUploadedChunks(0);
        metadata.setChunkSize(CHUNK_SIZE);
        metadata.setStoragePath(UPLOAD_DIR + metadata.getStoredFilename());
        metadata.setStorageType("local");
        metadata.setUploadStatus("pending");
        
        fileMetadataMapper.insert(metadata);
        
        logger.info("[上传初始化] 成功 - UserId: {}, FileMetadataId: {}", userId, metadata.getId());
        
        return new UploadInitResponse(metadata.getId(), "upload_" + UUID.randomUUID(), CHUNK_SIZE, false);
    }
    
    /**
     * 上传分片
     */
    @Transactional
    public void uploadChunk(Long uploadId, Integer chunkIndex, MultipartFile chunkFile, String chunkHash) {
        // TODO: 实现分片上传逻辑
        // 1. 保存分片到临时目录
        // 2. 验证分片哈希
        // 3. 更新 file_chunks 表
        // 4. 更新 file_metadata 的 uploaded_chunks
        
        logger.info("[上传分片] ChunkIndex: {}, Size: {}", chunkIndex, chunkFile.getSize());
    }
    
    /**
     * 完成上传
     */
    @Transactional
    public void completeUpload(Long fileMetadataId, String uploadId) {
        // TODO: 实现上传完成逻辑
        // 1. 合并所有分片
        // 2. 验证文件完整性
        // 3. 更新 upload_status 为 completed
        // 4. 清理临时文件
        
        FileMetadata metadata = fileMetadataMapper.findById(fileMetadataId);
        metadata.setUploadStatus("completed");
        metadata.setUploadedChunks(metadata.getTotalChunks());
        fileMetadataMapper.updateUploadStatus(metadata);
        
        logger.info("[上传完成] FileMetadataId: {}", fileMetadataId);
    }
    
    /**
     * 计算文件哈希
     */
    private String calculateFileHash(String filename, Long fileSize) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String input = filename + fileSize;
            byte[] hash = md.digest(input.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex + 1) : "";
    }
}
```

### 阶段 3：Controller 层（第 6-7 天）

```java
// src/main/java/com/mizuka/cloudfilesystem/controller/FileController.java
package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.service.DirectoryService;
import com.mizuka.cloudfilesystem.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
public class FileController {
    
    @Autowired
    private DirectoryService directoryService;
    
    @Autowired
    private FileUploadService fileUploadService;
    
    /**
     * 浏览目录
     */
    @GetMapping("/browse")
    public ResponseEntity<?> browseDirectory(
            @RequestParam Long nodeId,
            @RequestParam Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "50") Integer pageSize) {
        
        // TODO: 实现分页逻辑
        var children = directoryService.getChildren(nodeId, userId);
        
        return ResponseEntity.ok(children);
    }
    
    /**
     * 创建文件夹
     */
    @PostMapping("/folder")
    public ResponseEntity<?> createFolder(@RequestBody CreateFolderRequest request) {
        var folder = directoryService.createFolder(
            request.getParentId(), 
            request.getFolderName(), 
            request.getUserId()
        );
        
        return ResponseEntity.ok(folder);
    }
    
    /**
     * 删除节点
     */
    @DeleteMapping("/{nodeId}")
    public ResponseEntity<?> deleteNode(
            @PathVariable Long nodeId,
            @RequestParam Long userId) {
        
        directoryService.deleteNode(nodeId, userId);
        
        return ResponseEntity.ok().build();
    }
}
```

### 阶段 4：前端集成（第 8-10 天）

#### 前端组件结构

```
src/components/file-browser/
├── FileBrowser.vue          # 主组件
├── FolderTree.vue           # 文件夹树
├── FileList.vue             # 文件列表
├── ContextMenu.vue          # 右键菜单
└── UploadDialog.vue         # 上传对话框
```

#### 懒加载实现

```javascript
// FileBrowser.vue
<template>
  <div class="file-browser">
    <!-- 面包屑导航 -->
    <Breadcrumb :path="currentPath" @navigate="navigateTo" />
    
    <!-- 工具栏 -->
    <Toolbar 
      @create-folder="showCreateFolderDialog" 
      @upload="showUploadDialog" 
    />
    
    <!-- 文件列表 -->
    <FileList 
      :items="currentItems" 
      :loading="loading"
      @open-folder="openFolder"
      @download-file="downloadFile"
      @context-menu="showContextMenu"
    />
  </div>
</template>

<script>
export default {
  data() {
    return {
      currentNodeId: null,
      currentItems: [],
      loading: false,
      currentPath: []
    };
  },
  
  methods: {
    async loadDirectory(nodeId) {
      this.loading = true;
      try {
        const response = await this.$http.get('/api/files/browse', {
          params: {
            nodeId: nodeId,
            userId: this.$store.state.user.id
          }
        });
        
        this.currentItems = response.data;
        this.currentNodeId = nodeId;
        this.updateBreadcrumb(nodeId);
      } catch (error) {
        this.$message.error('加载目录失败');
      } finally {
        this.loading = false;
      }
    },
    
    openFolder(folderId) {
      // 懒加载：只在打开时才请求子目录
      this.loadDirectory(folderId);
    },
    
    navigateTo(path) {
      // 跳转到指定路径
      this.loadDirectory(path.nodeId);
    }
  },
  
  mounted() {
    // 初始加载用户根目录
    this.loadDirectory(this.$store.state.user.rootDirectoryId);
  }
};
</script>
```

---

## 🚀 未来扩展

### 1. 分片下载与断点续传

```java
@GetMapping("/download/stream/{fileMetadataId}")
public ResponseEntity<Resource> downloadFile(
        @PathVariable Long fileMetadataId,
        @RequestHeader(value = "Range", required = false) String range) {
    
    FileMetadata metadata = fileMetadataMapper.findById(fileMetadataId);
    Path filePath = Paths.get(metadata.getStoragePath());
    Resource resource = new UrlResource(filePath.toUri());
    
    if (range != null) {
        // 处理 Range 请求（断点续传）
        return handleRangeRequest(resource, range, metadata);
    }
    
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(metadata.getMimeType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, 
                "attachment; filename=\"" + metadata.getOriginalFilename() + "\"")
        .body(resource);
}
```

### 2. 文件版本控制

```sql
CREATE TABLE file_versions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_metadata_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    file_metadata_snapshot_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    
    FOREIGN KEY (file_metadata_id) REFERENCES file_metadata(id),
    FOREIGN KEY (created_by) REFERENCES users(id)
);
```

### 3. 文件共享与协作

```sql
CREATE TABLE file_shares (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_metadata_id BIGINT NOT NULL,
    shared_by BIGINT NOT NULL,
    shared_with BIGINT NOT NULL,
    permission_type ENUM('view', 'edit', 'download') NOT NULL,
    expires_at DATETIME DEFAULT NULL,
    share_token VARCHAR(64) UNIQUE NOT NULL,
    
    FOREIGN KEY (file_metadata_id) REFERENCES file_metadata(id),
    FOREIGN KEY (shared_by) REFERENCES users(id),
    FOREIGN KEY (shared_with) REFERENCES users(id)
);
```

### 4. 全文搜索（Elasticsearch）

```java
@Service
public class FileSearchService {
    
    @Autowired
    private ElasticsearchRestTemplate elasticsearchTemplate;
    
    public List<FileMetadata> searchFiles(String keyword, Long userId) {
        NativeSearchQuery query = new NativeSearchQueryBuilder()
            .withQuery(QueryBuilders.multiMatchQuery(keyword, 
                "originalFilename", "content"))
            .withFilter(QueryBuilders.termQuery("userId", userId))
            .build();
        
        SearchHits<FileMetadata> hits = elasticsearchTemplate.search(
            query, FileMetadata.class);
        
        return hits.stream()
            .map(SearchHit::getContent)
            .collect(Collectors.toList());
    }
}
```

---

## 📊 性能优化建议

### 1. 数据库优化

- **索引优化**：确保常用查询字段有索引
- **分区表**：按用户ID或时间分区
- **读写分离**：主库写，从库读

### 2. Redis 优化

- **缓存预热**：系统启动时预加载热门目录
- **缓存淘汰策略**：使用 LRU 策略
- **集群部署**：高并发时使用 Redis Cluster

### 3. 文件存储优化

- **CDN 加速**：静态文件使用 CDN
- **对象存储**：大文件使用 OSS/S3
- **压缩传输**：启用 Gzip/Brotli

---

## 📝 总结

本设计文档提供了完整的目录树系统实现方案，包括：

✅ **数据库设计**：5 张核心表，支持完整的文件管理功能  
✅ **Redis 缓存**：多级缓存策略，提升读取性能  
✅ **API 设计**：RESTful 接口，支持懒加载和分片上传  
✅ **实现步骤**：分 4 个阶段，10 天完成开发  
✅ **未来扩展**：预留分片下载、版本控制、文件共享等功能  

**下一步行动**：
1. 执行数据库建表脚本
2. 创建 Entity 和 Mapper
3. 实现 Service 层业务逻辑
4. 开发 Controller 接口
5. 前端集成测试

---

**文档版本**: 1.0  
**创建日期**: 2026-05-05  
**作者**: AI Assistant  
**状态**: ✅ 已完成
