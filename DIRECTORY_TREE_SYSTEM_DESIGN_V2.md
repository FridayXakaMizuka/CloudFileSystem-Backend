# 云文件系统 - 目录树系统设计文档 (v2.0)

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

1. **用户根目录隔离**：每个用户拥有独立的根目录 `/_root/files/users/{userID}`
2. **管理员根目录**：`_root` 标识的根目录，用于系统管理（头像、备份等）
3. **回收站机制**：用户删除的文件/文件夹临时存储在回收站，有效期30天
4. **待分配目录池**：彻底删除的目录进入待分配池，新用户创建时优先复用
5. **懒加载机制**：前端按需加载目录内容，提升性能
6. **文件索引解耦**：目录树只存储文件元数据索引，实际文件由文件上传服务管理
7. **Redis 缓存**：缓存目录树结构，减少数据库查询
8. **管理员目录规范**：所有管理员可访问的目录以 `_` 开头，普通用户无法访问

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
_root                        ← 根节点（管理员专属，以_开头，无前导斜杠）
├── _avatar                  ← 头像存储虚拟目录（管理员可访问）
├── _backup                  ← 数据库备份目录（管理员可访问）
├── _system                  ← 系统文件目录（管理员可访问）
├── _recycle_bin             ← 回收站（管理员可访问，用户删除后暂存30天）
│   ├── 10001                ← 按用户ID直接分配的回收站文件夹
│   │   ├── deleted_folder_001
│   │   └── deleted_file_001.pdf
│   └── 10002                ← 用户 10002 的回收站
└── _files                   ← 文件服务根目录（管理员可访问）
    ├── 10001                ← 用户 10001 的根目录
    │   ├── documents        ← 用户自建文件夹
    │   │   ├── work
    │   │   └── personal
    │   ├── photos           ← 用户自建文件夹
    │   ├── file_001.pdf     ← 用户上传的文件（索引）
    │   └── file_002.jpg
    │
    ├── 10002                ← 用户 10002 的根目录
    │   ├── downloads
    │   └── file_003.docx
    │
    └── 10003                ← 用户 10003 的根目录
        └── ...
```

### 物理存储结构

```
/opt/cloudfilesystem/storage/
├── actual_files/          ← 实际文件存储（与目录树完全解耦）
│   ├── chunk_001_part1    ← 文件内容始终存储在这里
│   ├── chunk_001_part2    ← 删除操作不影响物理文件
│   ├── chunk_002          ← 只修改元数据状态
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
- ✅ **管理员目录规范**：所有管理员专属目录以 `_` 开头（`_root`, `_avatar`, `_backup`, `_system`, `_recycle_bin`, `_files`）
- ✅ **路径无前导斜杠**：根目录路径为 `_root`，便于作为 URL 参数传递
- ✅ **回收站机制**：用户删除操作不移动物理文件，只修改 `file_nodes` 的状态为 `in_recycle_bin`
- ✅ **回收站结构简化**：直接在 `_recycle_bin` 下按用户 ID 分配文件夹（如 `_recycle_bin/10001`）
- ✅ **物理存储解耦**：删除的文件仍存储在 `actual_files/`，通过 `file_metadata.storage_path` 关联
- ✅ **待分配目录池**：逻辑概念，彻底删除的文件夹标记为 `unassigned`，不移动物理位置
- ✅ **目录复用**：新用户创建文件夹时，优先复用标记为 `unassigned` 的空闲记录
- ✅ **权限隔离**：普通用户只能访问 `_files/{自己的ID}`，无法访问其他 `_` 开头的目录
- ✅ **目录树中的“文件”只是元数据索引**，实际文件存储在 `actual_files/` 目录
- ✅ **通过 `file_metadata` 表关联目录节点和实际文件**

---

## 🗄️ 数据库设计

### 1. 文件夹节点表 (`folder_nodes`)

存储所有文件夹的树形结构，支持软删除和目录复用。

```sql
CREATE TABLE folder_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件夹ID',
    parent_id BIGINT DEFAULT NULL COMMENT '父文件夹ID，NULL表示根目录',
    user_id BIGINT DEFAULT NULL COMMENT '所属用户ID，NULL表示系统/管理员目录（仅管理员可访问）',
    
    -- 基本信息
    name VARCHAR(255) NOT NULL COMMENT '文件夹名称',
    path VARCHAR(1000) NOT NULL COMMENT '完整路径，如 _root/_files/10001/documents（无前导斜杠）',
    level INT DEFAULT 0 COMMENT '层级深度，根目录为0',
    
    -- 排序和显示
    sort_order INT DEFAULT 0 COMMENT '同级节点排序顺序',
    is_hidden TINYINT(1) DEFAULT 0 COMMENT '是否隐藏',
    
    -- 软删除支持
    is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',
    delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
    
    -- 目录状态（用于回收站和待分配池）
    directory_status ENUM('active', 'in_recycle_bin', 'unassigned') DEFAULT 'active' COMMENT '目录状态：活跃/回收站中/待分配',
    unassigned_at DATETIME DEFAULT NULL COMMENT '进入待分配池的时间（逻辑标记，无物理存储）',
    
    -- 原始位置信息（用于恢复）
    original_parent_id BIGINT DEFAULT NULL COMMENT '原始父文件夹ID（删除时记录，用于恢复）',
    original_path VARCHAR(1000) DEFAULT NULL COMMENT '原始完整路径（删除时记录，用于恢复）',
    
    -- 统计信息
    file_count INT DEFAULT 0 COMMENT '直接子文件数量',
    folder_count INT DEFAULT 0 COMMENT '直接子文件夹数量',
    total_size BIGINT DEFAULT 0 COMMENT '文件夹总大小（字节，包含所有子文件）',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_parent_id (parent_id),
    INDEX idx_user_id (user_id),
    INDEX idx_path (path(255)),
    INDEX idx_is_deleted (is_deleted),
    INDEX idx_directory_status (directory_status),
    INDEX idx_delete_expires_at (delete_expires_at),
    
    -- 外键约束
    FOREIGN KEY (parent_id) REFERENCES folder_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件夹节点表';
```

### 2. 文件节点表 (`file_nodes`)

存储所有文件的索引信息，与文件夹节点分表设计，支持软删除和**位置恢复**。

```sql
CREATE TABLE file_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件节点ID',
    folder_id BIGINT NOT NULL COMMENT '所属文件夹ID',
    user_id BIGINT DEFAULT NULL COMMENT '所属用户ID，NULL表示系统文件（仅管理员可访问）',
    file_metadata_id BIGINT NOT NULL COMMENT '关联文件元数据ID',
    
    -- 基本信息
    name VARCHAR(255) NOT NULL COMMENT '显示文件名',
    path VARCHAR(1000) NOT NULL COMMENT '完整路径，如 _root/_files/10001/documents/file.pdf（无前导斜杠）',
    
    -- 文件信息（冗余存储，提升查询性能）
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    mime_type VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    extension VARCHAR(20) DEFAULT NULL COMMENT '文件扩展名',
    
    -- 排序和显示
    sort_order INT DEFAULT 0 COMMENT '同级节点排序顺序',
    is_hidden TINYINT(1) DEFAULT 0 COMMENT '是否隐藏',
    
    -- 软删除支持
    is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',
    delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
    
    -- 目录状态（用于回收站）
    directory_status ENUM('active', 'in_recycle_bin', 'permanently_deleted') DEFAULT 'active' COMMENT '文件状态：活跃/回收站中/已彻底删除',
    
    -- 原始位置信息（用于恢复）
    original_folder_id BIGINT DEFAULT NULL COMMENT '原始所属文件夹ID（删除时记录，用于恢复）',
    original_path VARCHAR(1000) DEFAULT NULL COMMENT '原始完整路径（删除时记录，用于恢复）',
    
    -- 时间戳
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_folder_id (folder_id),
    INDEX idx_user_id (user_id),
    INDEX idx_file_metadata_id (file_metadata_id),
    INDEX idx_path (path(255)),
    INDEX idx_is_deleted (is_deleted),
    INDEX idx_directory_status (directory_status),
    INDEX idx_delete_expires_at (delete_expires_at),
    
    -- 外键约束
    FOREIGN KEY (folder_id) REFERENCES folder_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (file_metadata_id) REFERENCES file_metadata(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件节点表';
```

### 3. 文件元数据表 (`file_metadata`)

存储实际文件的详细信息，与目录树解耦，支持软删除和**多目录引用**。

**关键设计**：
- ✅ **解耦架构**：一个 `file_metadata` 可以被多个 `file_nodes` 引用（类似硬链接/快捷方式）
- ✅ **引用计数**：通过 `reference_count` 字段跟踪有多少个目录节点引用该元数据
- ✅ **去重优化**：相同哈希的文件只存储一份物理文件，多个目录节点共享同一元数据
- ✅ **全软删除**：所有删除操作都是软删除，避免物理 DELETE 操作，提升性能和数据安全

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
    
    -- 软删除支持
    is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',
    
    -- 引用计数（支持多目录引用同一文件）
    reference_count INT DEFAULT 0 COMMENT '被file_nodes引用的次数',
    
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
    INDEX idx_is_deleted (is_deleted),
    INDEX idx_reference_count (reference_count),
    
    -- 外键
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件元数据表';
```

### 4. 文件分片表 (`file_chunks`)

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

### 5. 目录权限表 (`directory_permissions`)

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

### 6. 初始化数据

```sql
-- ============================================
-- 初始化目录树结构
-- ============================================

-- 1. 插入根节点（管理员专属，以_开头，无前导斜杠）
INSERT INTO folder_nodes (id, parent_id, user_id, name, path, level, sort_order) VALUES
(1, NULL, NULL, '_root', '_root', 0, 0);

-- 2. 插入管理员专属子目录（均以_开头）
INSERT INTO folder_nodes (parent_id, user_id, name, path, level, sort_order) VALUES
(1, NULL, '_avatar', '_root/_avatar', 1, 0),
(1, NULL, '_backup', '_root/_backup', 1, 1),
(1, NULL, '_system', '_root/_system', 1, 2),
(1, NULL, '_recycle_bin', '_root/_recycle_bin', 1, 3),
(1, NULL, '_files', '_root/_files', 1, 4);

-- 注意：待分配目录池是逻辑概念，不需要物理存储
-- 当文件夹被彻底删除时，只是标记 directory_status='unassigned'
-- 新用户创建文件夹时，优先复用这些标记为 unassigned 的空闲记录

-- 3. 为现有用户创建根目录（假设用户ID从10001开始）
-- 注意：新用户的目录优先从 unassigned 池中复用
INSERT INTO folder_nodes (parent_id, user_id, name, path, level, sort_order)
SELECT 
    (SELECT id FROM folder_nodes WHERE path = '_root/_files'),
    u.id,
    CAST(u.id AS CHAR),
    CONCAT('_root/_files/', u.id),
    2,
    0
FROM users u
WHERE u.id >= 10001;

-- 4. 为现有用户在回收站中创建隔离目录（直接在 _recycle_bin 下按用户ID分配）
INSERT INTO folder_nodes (parent_id, user_id, name, path, level, sort_order)
SELECT 
    (SELECT id FROM folder_nodes WHERE path = '_root/_recycle_bin'),
    u.id,
    CAST(u.id AS CHAR),
    CONCAT('_root/_recycle_bin/', u.id),
    2,
    0
FROM users u
WHERE u.id >= 10001;
```

---

## 🔗 文件目录与元数据解耦设计

### 核心概念

本系统实现了**文件目录节点**与**文件元数据**的完全解耦，支持以下高级功能：

#### 1. **一对多引用关系**

```
file_metadata (ID: 100, 文件哈希: abc123)
    ↓ 引用
file_node_1 (ID: 501, 路径: /users/10001/docs/report.pdf)
file_node_2 (ID: 502, 路径: /users/10001/backup/report_copy.pdf)
file_node_3 (ID: 503, 路径: /users/10002/shared/report.pdf)
```

- ✅ **同一个物理文件**可以在多个目录中出现
- ✅ **不同的显示名称**：每个 file_node 可以有独立的 `name`
- ✅ **独立的路径管理**：移动、重命名只影响 file_node，不影响其他引用
- ✅ **节省存储空间**：物理文件只存储一份，通过 `file_hash` 去重

#### 2. **引用计数机制**

```sql
-- file_metadata 表中的 reference_count 字段
reference_count = COUNT(file_nodes WHERE file_metadata_id = metadata.id)
```

**自动维护**：
- ✅ 创建 file_node 时：`reference_count++`（触发器 `tr_after_insert_file_nodes`）
- ✅ 删除 file_node 时：`reference_count--`（触发器 `tr_before_delete_file_nodes`）
- ✅ 防止误删：当 `reference_count > 0` 时，禁止删除 file_metadata（外键 `ON DELETE RESTRICT`）

#### 3. **使用场景**

##### 场景 1：文件快捷方式/别名
```java
// 用户 A 上传文件
FileMetadata metadata = uploadFile("report.pdf", userIdA);

// 在用户 A 的目录中创建节点
FileNode node1 = new FileNode();
node1.setFolderId(folderA);
node1.setFileMetadataId(metadata.getId());
node1.setName("report.pdf");
fileNodeMapper.insert(node1); // reference_count = 1

// 用户 B 共享同一文件（不复制物理文件）
FileNode node2 = new FileNode();
node2.setFolderId(folderB);
node2.setFileMetadataId(metadata.getId()); // 指向同一元数据
node2.setName("shared_report.pdf"); // 可以有不同的显示名
fileNodeMapper.insert(node2); // reference_count = 2
```

##### 场景 2：文件版本管理
```java
// 用户上传新版本，但保留旧版本的引用
FileMetadata oldVersion = fileMetadataMapper.findById(100);
FileMetadata newVersion = uploadNewVersion(oldVersion.getOriginalFilename(), userId);

// 旧版本仍然被某些目录引用
// 新版本创建新的 file_node
// 两个版本共享相同的 original_filename，但有不同的 stored_filename
```

##### 场景 3：回收站恢复
```java
// 用户删除文件（软删除 file_node）
fileNodeMapper.softDeleteToRecycleBin(nodeId, userId, ...);
// reference_count 不变，因为 file_node 仍然存在（只是标记为删除）

// 用户彻底删除（物理删除 file_node）
fileNodeMapper.deleteById(nodeId);
// reference_count--，如果变为 0，则可以安全删除 file_metadata

// 检查是否可以删除元数据
if (metadata.getReferenceCount() == 0) {
    fileMetadataMapper.permanentlyDelete(metadata.getId());
    // 同时删除物理文件
    deletePhysicalFile(metadata.getStoragePath());
}
```

### 实现要点

#### 1. **外键约束调整**

```sql
-- 修改前：级联删除（不安全）
CONSTRAINT `fk_file_metadata` FOREIGN KEY (`file_metadata_id`) 
    REFERENCES `file_metadata`(`id`) ON DELETE CASCADE

-- 修改后：限制删除（安全）
CONSTRAINT `fk_file_metadata` FOREIGN KEY (`file_metadata_id`) 
    REFERENCES `file_metadata`(`id`) ON DELETE RESTRICT
```

**优势**：
- ❌ 防止误删：当还有 file_node 引用时，无法删除 file_metadata
- ✅ 显式清理：必须先删除所有引用的 file_node，才能删除 file_metadata

#### 2. **触发器自动维护引用计数**

```sql
-- 插入 file_node 时自动增加引用计数
CREATE TRIGGER `tr_after_insert_file_nodes`
AFTER INSERT ON `file_nodes`
FOR EACH ROW
BEGIN
    UPDATE `file_metadata` 
    SET `reference_count` = `reference_count` + 1,
        `updated_at` = NOW()
    WHERE `id` = NEW.`file_metadata_id`;
END;

-- 删除 file_node 时自动减少引用计数
CREATE TRIGGER `tr_before_delete_file_nodes`
BEFORE DELETE ON `file_nodes`
FOR EACH ROW
BEGIN
    UPDATE `file_metadata` 
    SET `reference_count` = GREATEST(`reference_count` - 1, 0),
        `updated_at` = NOW()
    WHERE `id` = OLD.`file_metadata_id`;
END;
```

#### 3. **查询优化**

```sql
-- 查找未被任何目录引用的元数据（可以安全删除）
SELECT * FROM file_metadata 
WHERE reference_count = 0 AND is_deleted = 1;

-- 查找被多个目录引用的文件（热门文件）
SELECT fm.*, COUNT(fn.id) as ref_count 
FROM file_metadata fm
JOIN file_nodes fn ON fm.id = fn.file_metadata_id
WHERE fn.is_deleted = 0
GROUP BY fm.id
HAVING ref_count > 1
ORDER BY ref_count DESC;

-- 查找某个文件的所有引用位置
SELECT fn.id, fn.name, fn.path, fn.folder_id
FROM file_nodes fn
WHERE fn.file_metadata_id = 100 AND fn.is_deleted = 0;
```

### 注意事项

⚠️ **重要规则**：

1. **删除 file_metadata 前必须检查引用计数**
   ```java
   if (metadata.getReferenceCount() > 0) {
       throw new IllegalStateException("文件仍被 " + metadata.getReferenceCount() + " 个目录引用");
   }
   ```

2. **软删除不影响引用计数**
   - 软删除只是标记 `is_deleted = 1`，file_node 仍然存在
   - 只有物理删除 file_node 才会减少 reference_count

3. **移动文件不改变引用关系**
   - 移动只是修改 file_node 的 `folder_id` 和 `path`
   - `file_metadata_id` 保持不变，引用计数不变

4. **重命名文件不改变引用关系**
   - 重命名只是修改 file_node 的 `name`
   - 不影响 file_metadata，其他引用不受影响

---

## 🗑️ 全软删除机制

### 设计理念

本系统采用**全软删除**策略，避免任何物理 DELETE 操作，带来以下优势：

- ✅ **数据安全**：所有数据可追溯、可恢复
- ✅ **性能优化**：UPDATE 比 DELETE 更快，不会触发外键级联
- ✅ **审计友好**：保留完整的删除历史记录
- ✅ **引用安全**：不会因为误删导致孤儿记录

### 文件删除流程

#### 阶段 1：用户删除（移入回收站）

```java
// 用户点击删除按钮
public void deleteFile(Long nodeId, Long userId) {
    // 软删除：标记为回收站状态
    fileNodeMapper.softDeleteToRecycleBin(nodeId, userId, 
        LocalDateTime.now(), 
        LocalDateTime.now().plusDays(30)
    );
    
    // directory_status = 'in_recycle_bin'
    // is_deleted = 1
    // delete_expires_at = 30天后
}
```

**数据库状态**：
```sql
UPDATE file_nodes 
SET is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = DATE_ADD(NOW(), INTERVAL 30 DAY),
    directory_status = 'in_recycle_bin'
WHERE id = #{nodeId};
```

#### 阶段 2：回收站过期（标记为永久删除）

```sql
-- 定时任务：每天凌晨2点执行
CALL sp_cleanup_expired_recycle_bin_files();

-- 存储过程内部逻辑
UPDATE file_nodes 
SET directory_status = 'permanently_deleted',
    is_deleted = 1,
    updated_at = NOW()
WHERE directory_status = 'in_recycle_bin'
  AND delete_expires_at <= NOW();
```

**关键点**：
- ✅ 仍然是 UPDATE 操作，不是 DELETE
- ✅ `reference_count` 不变（因为 file_node 仍然存在）
- ✅ 文件不再显示在回收站中

#### 阶段 3：异步清理（物理删除）

```sql
-- 定时任务：每周日凌晨3点执行
CALL sp_cleanup_permanently_deleted_files();

-- 存储过程内部逻辑
-- 1. 物理删除 file_node（触发器自动减少 reference_count）
DELETE FROM file_nodes WHERE id = #{nodeId};

-- 2. 如果 reference_count = 0，删除 file_metadata
DELETE FROM file_metadata 
WHERE id = #{metadataId} 
  AND reference_count = 0 
  AND is_deleted = 1;

-- 3. 删除物理文件
-- （在应用层执行，根据 storage_path 删除实际文件）
```

**关键点**：
- ⚠️ 这是唯一使用 DELETE 的地方
- ✅ 只有在确认安全时才执行（reference_count = 0）
- ✅ 由定时任务异步执行，不影响用户操作

### 文件夹删除流程

文件夹的删除流程类似，但更简单（没有元数据引用）：

#### 阶段 1：用户删除（移入回收站）

```sql
UPDATE folder_nodes 
SET is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = DATE_ADD(NOW(), INTERVAL 30 DAY),
    directory_status = 'in_recycle_bin'
WHERE id = #{folderId};
```

#### 阶段 2：回收站过期（移入待分配池）

```sql
-- 定时任务执行
CALL sp_cleanup_expired_recycle_bin_folders();

-- 移动到待分配池
UPDATE folder_nodes 
SET directory_status = 'unassigned',
    unassigned_at = NOW(),
    user_id = NULL,
    parent_id = (SELECT id FROM folder_nodes WHERE path = '/_root/_unassigned'),
    path = CONCAT('/_root/_unassigned/deleted_folder_', id),
    is_deleted = 0,  -- 重置为未删除，等待复用
    deleted_at = NULL,
    delete_expires_at = NULL
WHERE directory_status = 'in_recycle_bin'
  AND delete_expires_at <= NOW();
```

**关键点**：
- ✅ 文件夹不会被物理删除
- ✅ 进入待分配池后可以被新用户复用
- ✅ 节省目录创建开销

### 定时任务配置

```java
@Component
public class FileCleanupScheduler {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 每天凌晨2点清理回收站中过期的文件
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredRecycleBinFiles() {
        logger.info("[定时任务] 开始清理回收站中的过期文件");
        jdbcTemplate.execute("CALL sp_cleanup_expired_recycle_bin_files()");
        logger.info("[定时任务] 回收站文件清理完成");
    }
    
    /**
     * 每天凌晨2点清理回收站中过期的文件夹
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredRecycleBinFolders() {
        logger.info("[定时任务] 开始清理回收站中的过期文件夹");
        jdbcTemplate.execute("CALL sp_cleanup_expired_recycle_bin_folders()");
        logger.info("[定时任务] 回收站文件夹清理完成");
    }
    
    /**
     * 每周日凌晨3点清理永久删除的文件（物理删除）
     */
    @Scheduled(cron = "0 0 3 * * SUN")
    public void cleanupPermanentlyDeletedFiles() {
        logger.info("[定时任务] 开始清理永久删除的文件");
        jdbcTemplate.execute("CALL sp_cleanup_permanently_deleted_files()");
        logger.info("[定时任务] 永久删除文件清理完成");
    }
}
```

### 查询视图

为了方便查询不同状态的文件，创建了以下视图：

```sql
-- 活跃文件视图
CREATE VIEW v_active_files AS
SELECT * FROM file_nodes
WHERE is_deleted = 0 AND directory_status = 'active';

-- 回收站文件视图
CREATE VIEW v_recycle_bin_files AS
SELECT fn.*, fm.original_filename, fm.storage_path,
       DATEDIFF(fn.delete_expires_at, NOW()) AS days_remaining
FROM file_nodes fn
JOIN file_metadata fm ON fn.file_metadata_id = fm.id
WHERE directory_status = 'in_recycle_bin'
ORDER BY delete_expires_at ASC;

-- 永久删除文件视图（等待清理）
CREATE VIEW v_permanently_deleted_files AS
SELECT * FROM file_nodes
WHERE directory_status = 'permanently_deleted'
  AND is_deleted = 1;

-- 可安全删除的元数据视图
CREATE VIEW v_orphaned_metadata AS
SELECT * FROM file_metadata
WHERE reference_count = 0
  AND is_deleted = 1;
```

### 最佳实践

1. **用户删除操作**
   - ✅ 始终使用软删除（UPDATE is_deleted=1）
   - ❌ 禁止直接使用 DELETE 语句

2. **管理员清理操作**
   - ✅ 使用存储过程进行批量清理
   - ✅ 先标记为 permanently_deleted，再异步物理删除
   - ❌ 不要直接 DELETE 仍有引用的记录

3. **引用计数维护**
   - ✅ 通过触发器自动维护
   - ✅ 只在物理 DELETE 时减少计数
   - ❌ 软删除不改变引用计数

4. **物理文件删除**
   - ✅ 在应用层执行，确保元数据已删除
   - ✅ 记录删除日志，便于审计
   - ❌ 不要在数据库中直接删除物理文件路径

---

## 🔄 回收站恢复机制

### 设计理念

本系统实现了**智能恢复**功能，删除的文件/文件夹会记录原始位置信息，恢复时根据以下规则处理：

#### 恢复规则

```
用户点击“恢复”按钮
    ↓
检查原始父文件夹是否存在
    ↓
├─ 存在 → 恢复到原位置
│         └─ 更新 folder_id 和 path
│         └─ 清空 original_folder_id 和 original_path
│
└─ 不存在 → 恢复到用户根目录
          └─ 获取用户根目录ID
          └─ 从 original_path 提取文件名
          └─ 构建新路径: /_root/_files/{userID}/{filename}
          └─ 清空 original_folder_id 和 original_path
```

### 实现细节

#### 1. 删除时记录原始位置

```java
// 删除文件时保存原始位置
public void deleteFileToRecycleBin(Long nodeId, Long userId) {
    FileNode node = fileNodeMapper.findById(nodeId);
    
    // 软删除并记录原始位置
    fileNodeMapper.softDeleteToRecycleBin(
        nodeId, 
        userId,
        node.getFolderId(),      // 原始文件夹ID
        node.getPath(),           // 原始完整路径
        LocalDateTime.now(),
        LocalDateTime.now().plusDays(30)
    );
}
```

**数据库操作**：
```sql
UPDATE file_nodes 
SET is_deleted = 1,
    deleted_at = NOW(),
    delete_expires_at = DATE_ADD(NOW(), INTERVAL 30 DAY),
    directory_status = 'in_recycle_bin',
    original_folder_id = #{originalFolderId},  -- 记录原始位置
    original_path = #{originalPath}             -- 记录原始路径
WHERE id = #{nodeId};
```

#### 2. 恢复时的判断逻辑

```java
// 恢复文件
public void restoreFileFromRecycleBin(Long nodeId, Long userId) {
    // 调用存储过程，自动判断恢复位置
    jdbcTemplate.update(
        "CALL sp_restore_file_from_recycle_bin(?, ?)",
        nodeId, userId
    );
}
```

**存储过程逻辑**：
```sql
CREATE PROCEDURE sp_restore_file_from_recycle_bin(
    IN p_node_id BIGINT,
    IN p_user_id BIGINT
)
BEGIN
    DECLARE v_original_folder_id BIGINT;
    DECLARE v_original_path VARCHAR(1000);
    DECLARE v_folder_exists INT;
    
    -- 获取原始位置信息
    SELECT original_folder_id, original_path
    INTO v_original_folder_id, v_original_path
    FROM file_nodes
    WHERE id = p_node_id AND user_id = p_user_id;
    
    -- 检查原始文件夹是否仍然存在
    SELECT COUNT(*) INTO v_folder_exists
    FROM folder_nodes
    WHERE id = v_original_folder_id AND is_deleted = 0;
    
    IF v_folder_exists > 0 THEN
        -- 情况1：原始文件夹存在，恢复到原位置
        UPDATE file_nodes
        SET directory_status = 'active',
            is_deleted = 0,
            deleted_at = NULL,
            delete_expires_at = NULL,
            folder_id = v_original_folder_id,  -- 恢复原文件夹
            path = v_original_path,             -- 恢复原路径
            original_folder_id = NULL,          -- 清空原始信息
            original_path = NULL
        WHERE id = p_node_id;
        
    ELSE
        -- 情况2：原始文件夹已删除，恢复到用户根目录
        DECLARE v_user_root_id BIGINT;
        DECLARE v_filename VARCHAR(255);
        DECLARE v_new_path VARCHAR(1000);
        
        -- 获取用户根目录ID
        SELECT id INTO v_user_root_id
        FROM folder_nodes
        WHERE user_id = p_user_id 
          AND parent_id = (SELECT id FROM folder_nodes WHERE path = '/_root/_files')
        LIMIT 1;
        
        -- 从原始路径提取文件名
        SET v_filename = SUBSTRING_INDEX(v_original_path, '/', -1);
        SET v_new_path = CONCAT('/_root/_files/', p_user_id, '/', v_filename);
        
        UPDATE file_nodes
        SET directory_status = 'active',
            is_deleted = 0,
            deleted_at = NULL,
            delete_expires_at = NULL,
            folder_id = v_user_root_id,        -- 恢复到根目录
            path = v_new_path,                  -- 新路径
            original_folder_id = NULL,
            original_path = NULL
        WHERE id = p_node_id;
        
    END IF;
END;
```

### 恢复场景示例

#### 场景 1：原始文件夹存在

```
删除前：
  /_root/_files/10001/documents/report.pdf
  └─ folder_id: 100, path: /_root/_files/10001/documents/report.pdf

删除后（回收站）：
  original_folder_id: 100
  original_path: /_root/_files/10001/documents/report.pdf
  directory_status: in_recycle_bin

恢复时：
  ✅ 检查 folder_id=100 是否存在 → 存在
  ✅ 恢复到原位置
  ✅ 清空 original_folder_id 和 original_path

恢复后：
  /_root/_files/10001/documents/report.pdf
  └─ folder_id: 100, path: /_root/_files/10001/documents/report.pdf
```

#### 场景 2：原始文件夹已删除

```
删除前：
  /_root/_files/10001/documents/work/report.pdf
  └─ folder_id: 200 (documents/work), path: .../work/report.pdf

删除 documents 文件夹后：
  folder_id=200 被标记为 deleted

恢复 report.pdf 时：
  ❌ 检查 folder_id=200 是否存在 → 不存在
  ✅ 获取用户根目录ID (folder_id=50)
  ✅ 提取文件名: report.pdf
  ✅ 构建新路径: /_root/_files/10001/report.pdf
  ✅ 恢复到根目录

恢复后：
  /_root/_files/10001/report.pdf
  └─ folder_id: 50 (用户根目录), path: /_root/_files/10001/report.pdf
```

### 前端提示逻辑

```javascript
// 查询回收站文件时，显示恢复提示信息
GET /api/files/recycle

响应中包含 restore_info 字段：
{
  "id": 500,
  "name": "report.pdf",
  "originalPath": "/_root/_files/10001/documents/report.pdf",
  "restoreInfo": "可恢复至原位置",  // 或 "将恢复至用户根目录"
  "daysRemaining": 25
}

// 前端根据 restore_info 显示不同提示
if (file.restoreInfo === '可恢复至原位置') {
  showTip('文件将恢复到原来的位置');
} else {
  showWarning('原文件夹已删除，文件将恢复到您的根目录');
}
```

### 视图支持

```sql
-- 回收站文件详细信息视图（包含恢复提示）
CREATE VIEW v_recycle_bin_files_detail AS
SELECT 
    fn.*,
    fm.original_filename,
    fm.storage_path,
    DATEDIFF(fn.delete_expires_at, NOW()) AS days_remaining,
    CASE 
        WHEN fn.original_folder_id IS NOT NULL 
             AND EXISTS (
                 SELECT 1 FROM folder_nodes 
                 WHERE id = fn.original_folder_id AND is_deleted = 0
             )
        THEN '可恢复至原位置'
        ELSE '将恢复至用户根目录'
    END AS restore_info
FROM file_nodes fn
JOIN file_metadata fm ON fn.file_metadata_id = fm.id
WHERE fn.directory_status = 'in_recycle_bin';
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

#### 5. 待分配目录池缓存（新增）

```
Key: dir:unassigned_pool
Value: JSON 数组，包含空闲目录ID列表
TTL: 60秒（高频更新）
```

**示例**：
```json
[1001, 1002, 1003, 1005]
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

// 删除节点后（软删除，移入回收站）
public void deleteNodeToRecycleBin(Long nodeId, Long userId) {
    // 1. 获取节点信息
    DirectoryNode node = directoryMapper.findById(nodeId);
    
    // 2. 软删除：更新状态为 in_recycle_bin
    directoryMapper.softDeleteToRecycleBin(nodeId, userId, LocalDateTime.now().plusDays(30));
    
    // 3. 清除父节点缓存
    if (node.getParentId() != null) {
        String cacheKey = "dir:children:" + node.getParentId() + ":" + userId;
        redisTemplate.delete(cacheKey);
    }
    
    // 4. 清除路径缓存
    redisTemplate.delete("dir:path:" + node.getPath());
    
    logger.info("[删除到回收站] NodeId: {}, UserId: {}, ExpiresAt: {}", 
        nodeId, userId, node.getDeleteExpiresAt());
}

// 彻底删除节点（移入待分配池）
public void permanentlyDeleteNode(Long nodeId) {
    // 1. 获取节点信息
    DirectoryNode node = directoryMapper.findById(nodeId);
    
    // 2. 如果是文件夹，将其状态改为 unassigned
    if ("folder".equals(node.getNodeType())) {
        directoryMapper.moveToUnassignedPool(nodeId);
        
        // 3. 更新待分配池缓存
        redisTemplate.delete("dir:unassigned_pool");
    } else {
        // 文件直接标记为删除
        directoryMapper.permanentlyDeleteFile(node.getFileMetadataId());
    }
    
    // 4. 清除相关缓存
    if (node.getParentId() != null) {
        redisTemplate.delete("dir:children:" + node.getParentId() + ":" + node.getUserId());
    }
    redisTemplate.delete("dir:path:" + node.getPath());
    
    logger.info("[彻底删除] NodeId: {}, Type: {}", nodeId, node.getNodeType());
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
    
    // 2. 缓存未命中，查询数据库（排除已删除的节点）
    logger.debug("[目录浏览] 缓存未命中，查询数据库 - NodeId: {}", nodeId);
    List<DirectoryNodeVO> children = directoryMapper.findChildren(nodeId, userId);
    
    // 3. 存入缓存
    if (!children.isEmpty()) {
        redisTemplate.opsForValue().set(cacheKey, children, 300, TimeUnit.SECONDS);
        logger.info("[目录浏览] 缓存已设置 - NodeId: {}, 子节点数: {}", nodeId, children.size());
    }
    
    return children;
}

/**
 * 从待分配池中获取一个空闲目录
 */
public Long acquireUnassignedDirectory() {
    String cacheKey = "dir:unassigned_pool";
    
    // 1. 尝试从缓存获取
    @SuppressWarnings("unchecked")
    List<Long> pool = (List<Long>) redisTemplate.opsForValue().get(cacheKey);
    
    if (pool == null || pool.isEmpty()) {
        // 2. 缓存未命中，查询数据库
        pool = directoryMapper.findUnassignedDirectories(10); // 最多取10个
        
        if (!pool.isEmpty()) {
            redisTemplate.opsForValue().set(cacheKey, pool, 60, TimeUnit.SECONDS);
        }
    }
    
    // 3. 返回第一个空闲目录ID
    if (pool != null && !pool.isEmpty()) {
        Long directoryId = pool.remove(0);
        redisTemplate.opsForValue().set(cacheKey, pool, 60, TimeUnit.SECONDS);
        return directoryId;
    }
    
    // 4. 没有空闲目录，返回 null（需要新建）
    return null;
}
```

### Redis 实例选择

- **端口 6381**：用于目录树缓存（高频读写）
- **端口 6380**：用于个人资料缓存（低频读写）

---

## 🔌 API 接口设计

### 1. 浏览目录内容（游标分页）

```
GET /files/browse
```

**请求参数**：
- `currentNodeId`: 当前目录节点ID（必填）
- `lastChildrenNode`: 游标分页锚点，上一页最后一个子节点的ID。为空则从第一个开始。
- `lastChildrenType`: 游标分页锚点类型，上一页最后一个子节点的类型（`folder` 或 `file`）。用于解决不同表排序冲突。
- `maxPageSize`: 前端期望的最大返回数量（后端会根据性能限制最大值，如 100）。
- 'sortedBy': 排序字段（0=default(createdAt), 1=name, 2=editedAt）
- 'order': 排序顺序（0=asc, 1=desc）
- 'excludeNewFileIds': 新增文件ID列表，用于排除
- 'excludeNewFolderIds': 新增文件夹ID列表，用于排除

**响应示例 1：获取成功且有数据**
```json
{
  "code": 200,
  "success": true,
  "message": "获取成功", 
  "data": {
    "currentNode": {
      "id": 100,
      "name": "documents",
      "path": "_root/_files/10001/documents",
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
      "lastChildrenNode": 102,
      "lastChildrenType": "file",
      "isEnd": false
    }
  }
}
```

**响应示例 2：获取成功但无子节点（pageSize 为 0）**
```json
{
  "code": 200,
  "success": true,
  "message": "目录为空",
  "data": {
    "currentNode": {
      "id": 105,
      "name": "empty_folder",
      "path": "_root/_files/10001/empty_folder",
      "parentId": 50
    },
    "children": [],
    "pagination": {
      "lastChildrenNode": null,
      "lastChildrenType": null,
      "isEnd": true
    }
  }
}
```

#### 常见失败响应（RESTful 规范）

| 状态码 | Code | 场景描述 | 响应示例 |
| :--- | :--- | :--- | :--- |
| **400** | 40001 | **参数缺失或格式错误** <br> 例如：`currentNodeId` 未传或不是数字。 | `{"code": 40001, "message": "currentNodeId 不能为空"}` |
| **403** | 40301 | **权限不足** <br> 用户尝试访问不属于自己的目录（如 `_root/_system`）。 | `{"code": 40301, "message": "无权访问该目录"}` |
| **404** | 40401 | **目录不存在** <br> `currentNodeId` 在数据库中找不到对应记录。 | `{"code": 40401, "message": "指定的目录节点不存在"}` |
| **404** | 40402 | **游标失效** <br> `lastChildrenNode` 指向的节点已被删除或移动，无法继续分页。 | `{"code": 40402, "message": "分页游标已失效，请刷新页面"}` |
| **500** | 50001 | **服务器内部错误** <br> 数据库连接超时或查询异常。 | `{"code": 50001, "message": "系统繁忙，请稍后重试"}` |
```

### 2. 创建文件夹

```
POST /files/folder
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
    "path": "_root/_files/users/10001/documents/new_folder",
    "reusedFromPool": false
  }
}
```

** 注意 **
- 新建的文件夹会临时存储在前端缓存中，后端加载目录时会从请求中加载排除项，前端刷新时会清空（前端会自动按照创建时间由新到老排序）

### 3. 重命名节点

```
PUT /files/rename/{nodeId}
```

**请求体**：
```json
{
  "newName": "renamed_folder"
}
```

### 4. 移动节点

```
PUT /files/move/{nodeId}
```

**请求体**：
```json
{
  "newParentId": 200
}
```

### 5. 删除节点（软删除，移入回收站）

```
DELETE /files/{nodeId}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "已移入回收站，30天后彻底删除",
  "data": {
    "recycleBinPath": "_root/_recycle_bin/users/10001/deleted_folder_001",
    "expiresAt": "2026-06-04T10:00:00Z"
  }
}
```

### 6. 彻底删除节点（用户回收站或管理员操作）

```
DELETE /files/permanent/{nodeId}
```

**权限**：管理员或文件（夹）已在当前用户回收站中

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "已彻底删除，目录进入待分配池"
}
```

### 7. 恢复回收站中的文件/文件夹

```
POST /files/recycle/restore/{nodeId}
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "恢复成功",
  "data": {
    "restoredPath": "_root/_files/users/10001/documents/restored_folder"
  }
}
```

### 8. 查看回收站内容（用户查看自己的回收站）

**请求方式**：`GET`  
**接口路径**：`/files/recycle`  
**认证要求**：需要 JWT Token

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `currentNodeId` | Long | ✅ 是 | 回收站根节点ID（通常是用户的回收站文件夹ID） |
| `lastChildrenNode` | Long | ❌ 否 | 游标锚点：上一页最后一个子节点的ID |
| `lastChildrenType` | String | ❌ 否 | 游标锚点类型：`folder` 或 `file` |
| `maxPageSize` | Integer | ❌ 否 | 期望的最大返回数量（默认 50，最大 200） |
| `sortedBy` | Integer | ❌ 否 | 排序字段：0=createdAt(默认), 1=name, 2=editedAt |
| `order` | Integer | ❌ 否 | 排序顺序：0=asc, 1=desc（默认 1） |

### 响应格式

与 `/files/browse` 接口完全相同，但返回的是回收站中的内容。

#### 成功响应示例

```json
{
   "code": 200,
   "success": true,
   "message": "获取成功",
   "data": {
      "currentNode": {
         "id": 5000,
         "name": "_recycle_bin",
         "path": "_root/_recycle_bin/10001",
         "parentId": 4
      },
      "children": [
         {
            "id": 339,
            "name": "work.pdf",
            "type": "file",
            "size": 1048576,
            "mimeType": "application/pdf",
            "thumbnail": "/thumbnails/thumb_339.jpg",
            "deletedAt": "2026-05-05T10:05:00Z",
            "expiresAt": "2026-06-04T10:05:00Z",
            "daysRemaining": 29
         },
         {
            "id": 9178,
            "name": "work",
            "type": "folder",
            "hasChildren": true,
            "childCount": 5,
            "deletedAt": "2026-05-05T10:00:00Z",
            "expiresAt": "2026-06-04T10:00:00Z",
            "daysRemaining": 29
         }
      ],
      "pagination": {
         "lastChildrenNode": 9178,
         "lastChildrenType": "folder",
         "isEnd": false
      }
   }
}
```

### 9. 搜索文件/文件夹

```
GET /files/search
```

**请求参数**：
- `keyword`: 搜索关键词
- `userId`: 用户ID
- 'sumFolders': 已显示文件夹数（用于游标分页快速查找）
- 'sumFiles': 已显示文件数（用于游标分页快速查找）
- `lastFoldersNode`: 文件夹游标分页锚点，上一页最后一个文件夹的ID。为空则从第一个开始
- `lastFilesType`: 文件游标分页锚点，上一页最后一个文件的ID。为空则从第一个开始
- `maxPageSize`: 前端期望的最大返回数量（后端会根据性能限制最大值，如 100）。

**响应**：
```json
{
  "code": 200,
  "success": true,
  "data": {
    "results": [
       {
          "id": 339,
          "name": "work.pdf",
          "type": "file",
          "size": 1048576,
          "mimeType": "application/pdf",
          "thumbnail": "/thumbnails/thumb_102.jpg",
          "createdAt": "2026-05-05T10:05:00Z"
       },
       {
          "id": 9178,
          "name": "work",
          "type": "folder",
          "hasChildren": true,
          "childCount": 5,
          "createdAt": "2026-05-05T10:00:00Z"
       },
       {
          "id": 2345,
          "name": "work123.pdf",
          "type": "file",
          "size": 1048576,
          "mimeType": "application/pdf",
          "thumbnail": "/thumbnails/thumb_102.jpg",
          "createdAt": "2026-05-05T10:05:00Z"
       },
       ...
    ],
    "pagination": {
      "lastFolderNode": 91,
      "lastFileNode": 78,
      "isEndFolder": false,
      "isEndFile": true,
      "countFolders": 16, 
      "countFiles": 4
    }
  }
}
```
** 注意 **
- 文件与文件夹相关性相同时，文件优先级高
- 文件判断优先级时不要携带扩展名（扩展名相同时按扩展名字典序排序）

### 10. 获取文件下载链接

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

### 11. 分片上传 - 初始化

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

### 12. 分片上传 - 上传分片

```
POST /api/files/upload/chunk
Content-Type: multipart/form-data
```

**表单数据**：
- `uploadId`: 上传ID
- `chunkIndex`: 分片索引
- `chunkHash`: 分片哈希
- `file`: 分片文件

### 13. 分片上传 - 完成

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

### 14. 断点续传 - 查询进度

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

### 15. 管理员 - 查看待分配目录池

```
GET /api/admin/unassigned-pool?page={page}&pageSize={pageSize}
```

**权限**：仅管理员

**响应**：
```json
{
  "code": 200,
  "success": true,
  "data": {
    "availableCount": 15,
    "directories": [
      {
        "id": 1001,
        "name": "empty_dir_001",
        "path": "/_root/_unassigned/empty_dir_001",
        "unassignedAt": "2026-05-01T10:00:00Z"
      }
    ],
    "pagination": {
      "page": 1,
      "pageSize": 50,
      "total": 15,
      "totalPages": 1
    }
  }
}
```

### 16. 管理员 - 强制物理删除文件（特殊权限）

```
DELETE /api/admin/files/force-delete/file/{nodeId}
```

**权限**：仅超级管理员（SUPER_ADMIN）
**使用时机**：数据低峰期（凌晨 2:00-5:00）

**请求头**：
```
X-Admin-Reason: 存储空间清理 / 数据维护 / 其他原因
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "文件已物理删除",
  "data": {
    "deletedNodeId": 500,
    "deletedMetadataId": 100,
    "storagePathDeleted": "/opt/storage/abc123.pdf",
    "operationLogged": true
  }
}
```

**安全限制**：
- ✅ 只能删除 `directory_status = 'permanently_deleted'` 的文件
- ✅ 必须记录操作日志（管理员ID、操作时间、删除原因）
- ✅ 需要二次确认（前端弹窗确认）
- ❌ 禁止在高峰期使用（9:00-22:00）

### 17. 管理员 - 强制物理删除文件夹（特殊权限）

```
DELETE /api/admin/files/force-delete/folder/{nodeId}
```

**权限**：仅超级管理员（SUPER_ADMIN）
**使用时机**：数据低峰期（凌晨 2:00-5:00）

**请求头**：
```
X-Admin-Reason: 存储空间清理 / 数据维护 / 其他原因
```

**响应**：
```json
{
  "code": 200,
  "success": true,
  "message": "文件夹已物理删除",
  "data": {
    "deletedNodeId": 200,
    "childNodesDeleted": 0,
    "operationLogged": true
  }
}
```

**安全限制**：
- ✅ 只能删除空文件夹（无子文件夹、无文件）
- ✅ 必须记录操作日志
- ✅ 需要二次确认
- ❌ 如果文件夹非空，返回错误提示

---

## 🛠️ 实现步骤

### 阶段 1：数据库层（第 1-2 天）

#### 1.1 创建数据库表

```sql
-- 执行上述 SQL 脚本创建所有表
source /path/to/directory_tree_schema_v2.sql
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
    
    // 软删除支持
    private Boolean isDeleted;
    private LocalDateTime deletedAt;
    private LocalDateTime deleteExpiresAt;
    
    // 目录状态
    private String directoryStatus; // "active", "in_recycle_bin", "unassigned"
    private LocalDateTime unassignedAt;
    
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
    
    // 软删除支持
    private Boolean isDeleted;
    private LocalDateTime deletedAt;
    
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
// src/main/java/com/mizuka/cloudfilesystem/mapper/FolderNodeMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FolderNode;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FolderNodeMapper {
    
    @Select("SELECT * FROM folder_nodes WHERE id = #{id} AND is_deleted = 0")
    FolderNode findById(Long id);
    
    @Select("SELECT * FROM folder_nodes WHERE parent_id = #{parentId} AND user_id = #{userId} AND is_deleted = 0 ORDER BY sort_order, created_at")
    List<FolderNode> findChildren(@Param("parentId") Long parentId, @Param("userId") Long userId);
    
    @Insert("INSERT INTO folder_nodes (parent_id, user_id, name, path, level, sort_order) " +
            "VALUES (#{parentId}, #{userId}, #{name}, #{path}, #{level}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FolderNode node);
    
    @Update("UPDATE folder_nodes SET name = #{name}, path = #{path}, updated_at = NOW() WHERE id = #{id}")
    int update(FolderNode node);
    
    @Delete("DELETE FROM folder_nodes WHERE id = #{id}")
    int deleteById(Long id);
    
    @Select("SELECT * FROM folder_nodes WHERE path = #{path} AND is_deleted = 0")
    FolderNode findByPath(String path);
    
    @Select("SELECT COUNT(*) FROM folder_nodes WHERE parent_id = #{parentId} AND is_deleted = 0")
    int countChildren(Long parentId);
    
    /**
     * 软删除：移入回收站
     */
    @Update("UPDATE folder_nodes SET is_deleted = 1, deleted_at = #{deletedAt}, " +
            "delete_expires_at = #{expiresAt}, directory_status = 'in_recycle_bin', " +
            "updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int softDeleteToRecycleBin(@Param("id") Long id, 
                               @Param("userId") Long userId,
                               @Param("deletedAt") LocalDateTime deletedAt,
                               @Param("expiresAt") LocalDateTime expiresAt);
    
    /**
     * 移动到待分配池
     */
    @Update("UPDATE folder_nodes SET directory_status = 'unassigned', " +
            "unassigned_at = NOW(), user_id = 0, parent_id = " +
            "(SELECT id FROM folder_nodes WHERE path = '/_root/_unassigned' LIMIT 1), " +
            "path = CONCAT('/_root/_unassigned/', name), " +
            "updated_at = NOW() WHERE id = #{id}")
    int moveToUnassignedPool(Long id);
    
    /**
     * 查找待分配的空闲目录
     */
    @Select("SELECT id FROM folder_nodes WHERE directory_status = 'unassigned' " +
            "ORDER BY unassigned_at ASC LIMIT #{limit}")
    List<Long> findUnassignedDirectories(@Param("limit") int limit);
    
    /**
     * 查找回收站中过期的文件夹（超过30天）
     */
    @Select("SELECT id FROM folder_nodes WHERE directory_status = 'in_recycle_bin' " +
            "AND delete_expires_at <= NOW()")
    List<Long> findExpiredRecycleBinFolders();
    
    /**
     * 恢复回收站中的文件夹
     */
    @Update("UPDATE folder_nodes SET is_deleted = 0, deleted_at = NULL, " +
            "delete_expires_at = NULL, directory_status = 'active', " +
            "parent_id = #{newParentId}, path = #{newPath}, updated_at = NOW() " +
            "WHERE id = #{id}")
    int restoreFromRecycleBin(@Param("id") Long id,
                              @Param("newParentId") Long newParentId,
                              @Param("newPath") String newPath);
}
```

```java
// src/main/java/com/mizuka/cloudfilesystem/mapper/FileNodeMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FileNode;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FileNodeMapper {
    
    @Select("SELECT * FROM file_nodes WHERE id = #{id} AND is_deleted = 0")
    FileNode findById(Long id);
    
    @Select("SELECT * FROM file_nodes WHERE folder_id = #{folderId} AND user_id = #{userId} AND is_deleted = 0 ORDER BY sort_order, created_at")
    List<FileNode> findByFolder(@Param("folderId") Long folderId, @Param("userId") Long userId);
    
    @Insert("INSERT INTO file_nodes (folder_id, user_id, file_metadata_id, name, path, " +
            "file_size, mime_type, extension, sort_order) " +
            "VALUES (#{folderId}, #{userId}, #{fileMetadataId}, #{name}, #{path}, " +
            "#{fileSize}, #{mimeType}, #{extension}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(FileNode node);
    
    @Update("UPDATE file_nodes SET name = #{name}, updated_at = NOW() WHERE id = #{id}")
    int update(FileNode node);
    
    @Delete("DELETE FROM file_nodes WHERE id = #{id}")
    int deleteById(Long id);
    
    @Select("SELECT * FROM file_nodes WHERE file_metadata_id = #{fileMetadataId} AND is_deleted = 0")
    FileNode findByFileMetadataId(@Param("fileMetadataId") Long fileMetadataId);
    
    /**
     * 软删除文件：移入回收站
     */
    @Update("UPDATE file_nodes SET is_deleted = 1, deleted_at = #{deletedAt}, " +
            "delete_expires_at = #{expiresAt}, directory_status = 'in_recycle_bin', " +
            "updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int softDeleteToRecycleBin(@Param("id") Long id, 
                               @Param("userId") Long userId,
                               @Param("deletedAt") LocalDateTime deletedAt,
                               @Param("expiresAt") LocalDateTime expiresAt);
    
    /**
     * 查找回收站中过期的文件（超过30天）
     */
    @Select("SELECT id FROM file_nodes WHERE directory_status = 'in_recycle_bin' " +
            "AND delete_expires_at <= NOW()")
    List<Long> findExpiredRecycleBinFiles();
    
    /**
     * 恢复回收站中的文件
     */
    @Update("UPDATE file_nodes SET is_deleted = 0, deleted_at = NULL, " +
            "delete_expires_at = NULL, directory_status = 'active', " +
            "folder_id = #{newFolderId}, path = #{newPath}, updated_at = NOW() " +
            "WHERE id = #{id}")
    int restoreFromRecycleBin(@Param("id") Long id,
                              @Param("newFolderId") Long newFolderId,
                              @Param("newPath") String newPath);
}
```

```java
// src/main/java/com/mizuka/cloudfilesystem/mapper/FileMetadataMapper.java
package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FileMetadata;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FileMetadataMapper {
    
    @Select("SELECT * FROM file_metadata WHERE id = #{id} AND is_deleted = 0")
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
    
    @Select("SELECT * FROM file_metadata WHERE file_hash = #{fileHash} AND is_deleted = 0")
    FileMetadata findByHash(String fileHash);
    
    /**
     * 软删除文件
     */
    @Update("UPDATE file_metadata SET is_deleted = 1, deleted_at = NOW(), " +
            "upload_status = 'deleted' WHERE id = #{id}")
    int softDelete(Long id);
    
    /**
     * 彻底删除文件
     */
    @Delete("DELETE FROM file_metadata WHERE id = #{id}")
    int permanentlyDelete(Long id);
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

import java.time.LocalDateTime;
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
    
    private static final int RECYCLE_BIN_RETENTION_DAYS = 30;
    
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
     * 创建文件夹（优先复用待分配目录）
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
        
        // 尝试从待分配池中获取空闲目录
        Long reusedDirectoryId = acquireUnassignedDirectory();
        
        DirectoryNode newNode;
        boolean reused = false;
        
        if (reusedDirectoryId != null) {
            // 复用现有目录
            logger.info("[创建文件夹] 复用待分配目录 - DirectoryId: {}", reusedDirectoryId);
            
            // 更新目录信息
            DirectoryNode reusedDir = directoryNodeMapper.findById(reusedDirectoryId);
            reusedDir.setName(folderName);
            reusedDir.setParentId(parentId);
            reusedDir.setUserId(userId);
            reusedDir.setPath(parent.getPath() + "/" + folderName);
            reusedDir.setLevel(parent.getLevel() + 1);
            reusedDir.setDirectoryStatus("active");
            reusedDir.setUnassignedAt(null);
            
            directoryNodeMapper.update(reusedDir);
            newNode = reusedDir;
            reused = true;
        } else {
            // 创建新节点
            logger.info("[创建文件夹] 创建新目录 - ParentId: {}", parentId);
            
            newNode = new DirectoryNode();
            newNode.setParentId(parentId);
            newNode.setUserId(userId);
            newNode.setNodeType("folder");
            newNode.setName(folderName);
            newNode.setPath(parent.getPath() + "/" + folderName);
            newNode.setLevel(parent.getLevel() + 1);
            newNode.setSortOrder(siblings.size());
            newNode.setDirectoryStatus("active");
            
            directoryNodeMapper.insert(newNode);
        }
        
        // 清除父节点缓存
        String cacheKey = "dir:children:" + parentId + ":" + userId;
        redisTemplate.delete(cacheKey);
        
        // 清除待分配池缓存（如果有复用）
        if (reused) {
            redisTemplate.delete("dir:unassigned_pool");
        }
        
        logger.info("[创建文件夹] 成功 - UserId: {}, ParentId: {}, FolderName: {}, Reused: {}", 
            userId, parentId, folderName, reused);
        
        return newNode;
    }
    
    /**
     * 删除节点（软删除，移入回收站）
     */
    @Transactional
    public void deleteNodeToRecycleBin(Long nodeId, Long userId) {
        DirectoryNode node = directoryNodeMapper.findById(nodeId);
        if (node == null || !node.getUserId().equals(userId)) {
            throw new IllegalArgumentException("节点不存在或无权限");
        }
        
        // 计算过期时间（30天后）
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(RECYCLE_BIN_RETENTION_DAYS);
        
        // 软删除：移入回收站
        directoryNodeMapper.softDeleteToRecycleBin(nodeId, userId, LocalDateTime.now(), expiresAt);
        
        // 如果是文件，也软删除文件元数据
        if ("file".equals(node.getNodeType()) && node.getFileMetadataId() != null) {
            // TODO: 调用文件服务软删除实际文件
            logger.info("[删除文件到回收站] FileMetadataId: {}", node.getFileMetadataId());
        }
        
        // 清除父节点缓存
        if (node.getParentId() != null) {
            String cacheKey = "dir:children:" + node.getParentId() + ":" + userId;
            redisTemplate.delete(cacheKey);
        }
        
        // 清除路径缓存
        redisTemplate.delete("dir:path:" + node.getPath());
        
        logger.info("[删除到回收站] 成功 - NodeId: {}, UserId: {}, ExpiresAt: {}", 
            nodeId, userId, expiresAt);
    }
    
    /**
     * 彻底删除节点（管理员操作）
     */
    @Transactional
    public void permanentlyDeleteNode(Long nodeId, boolean isAdmin) {
        DirectoryNode node = directoryNodeMapper.findById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在");
        }
        
        // 权限检查：只有管理员可以彻底删除，或者用户删除自己回收站中的内容
        if (!isAdmin && !node.getUserId().equals(getCurrentUserId())) {
            throw new IllegalArgumentException("无权限彻底删除");
        }
        
        // 如果是文件夹，移动到待分配池
        if ("folder".equals(node.getNodeType())) {
            directoryNodeMapper.moveToUnassignedPool(nodeId);
            
            // 更新待分配池缓存
            redisTemplate.delete("dir:unassigned_pool");
            
            logger.info("[彻底删除] 文件夹移入待分配池 - NodeId: {}", nodeId);
        } else {
            // 文件直接彻底删除
            if (node.getFileMetadataId() != null) {
                // TODO: 调用文件服务彻底删除物理文件
                directoryNodeMapper.permanentlyDelete(node.getFileMetadataId());
            }
            directoryNodeMapper.deleteById(nodeId);
            
            logger.info("[彻底删除] 文件已删除 - NodeId: {}", nodeId);
        }
        
        // 清除相关缓存
        if (node.getParentId() != null) {
            redisTemplate.delete("dir:children:" + node.getParentId() + ":" + node.getUserId());
        }
        redisTemplate.delete("dir:path:" + node.getPath());
    }
    
    /**
     * 从待分配池中获取一个空闲目录
     */
    public Long acquireUnassignedDirectory() {
        String cacheKey = "dir:unassigned_pool";
        
        // 1. 尝试从缓存获取
        @SuppressWarnings("unchecked")
        List<Long> pool = (List<Long>) redisTemplate.opsForValue().get(cacheKey);
        
        if (pool == null || pool.isEmpty()) {
            // 2. 缓存未命中，查询数据库
            pool = directoryNodeMapper.findUnassignedDirectories(10); // 最多取10个
            
            if (!pool.isEmpty()) {
                redisTemplate.opsForValue().set(cacheKey, pool, 60, TimeUnit.SECONDS);
            }
        }
        
        // 3. 返回第一个空闲目录ID
        if (pool != null && !pool.isEmpty()) {
            Long directoryId = pool.remove(0);
            redisTemplate.opsForValue().set(cacheKey, pool, 60, TimeUnit.SECONDS);
            return directoryId;
        }
        
        // 4. 没有空闲目录，返回 null（需要新建）
        return null;
    }
    
    /**
     * 定时任务：清理回收站中过期的项目
     */
    @Transactional
    public void cleanupExpiredRecycleBin() {
        List<Long> expiredIds = directoryNodeMapper.findExpiredRecycleBinItems();
        
        if (expiredIds.isEmpty()) {
            logger.debug("[回收站清理] 没有过期项目");
            return;
        }
        
        logger.info("[回收站清理] 发现 {} 个过期项目，开始清理", expiredIds.size());
        
        for (Long id : expiredIds) {
            try {
                permanentlyDeleteNode(id, true); // 管理员权限
            } catch (Exception e) {
                logger.error("[回收站清理] 清理失败 - NodeId: {}, Error: {}", id, e.getMessage());
            }
        }
        
        logger.info("[回收站清理] 完成 - 清理了 {} 个项目", expiredIds.size());
    }
    
    private Long getCurrentUserId() {
        // TODO: 从安全上下文获取当前用户ID
        return 0L;
    }
}
```

#### 2.2 文件上传服务

（保持原有实现，增加软删除支持）

#### 2.3 管理员文件服务（新增）

```java
// src/main/java/com/mizuka/cloudfilesystem/service/AdminFileService.java
package com.mizuka.cloudfilesystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminFileService {
    
    private static final Logger logger = LoggerFactory.getLogger(AdminFileService.class);
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 强制物理删除文件节点
     */
    @Transactional
    public void forceDeleteFileNode(Long nodeId, Long adminId, String reason) {
        logger.warn("[管理员强制删除] 开始 - NodeId: {}, AdminId: {}, Reason: {}", 
            nodeId, adminId, reason);
        
        try {
            // 调用存储过程
            jdbcTemplate.update(
                "CALL sp_admin_force_delete_file_node(?, ?)",
                nodeId, adminId
            );
            
            logger.info("[管理员强制删除] 成功 - NodeId: {}", nodeId);
            
        } catch (Exception e) {
            logger.error("[管理员强制删除] 失败 - NodeId: {}, Error: {}", nodeId, e.getMessage());
            throw new IllegalArgumentException("删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 强制物理删除文件夹节点
     */
    @Transactional
    public void forceDeleteFolderNode(Long nodeId, Long adminId, String reason) {
        logger.warn("[管理员强制删除] 开始 - NodeId: {}, AdminId: {}, Reason: {}", 
            nodeId, adminId, reason);
        
        try {
            // 调用存储过程
            jdbcTemplate.update(
                "CALL sp_admin_force_delete_folder_node(?, ?)",
                nodeId, adminId
            );
            
            logger.info("[管理员强制删除] 成功 - NodeId: {}", nodeId);
            
        } catch (Exception e) {
            logger.error("[管理员强制删除] 失败 - NodeId: {}, Error: {}", nodeId, e.getMessage());
            throw new IllegalArgumentException("删除失败: " + e.getMessage());
        }
    }
}
```

### 阶段 3：Controller 层（第 6-7 天）

（参考 API 接口设计实现 Controller）

#### 3.1 管理员强制删除 Controller 示例

```java
// src/main/java/com/mizuka/cloudfilesystem/controller/AdminFileController.java
package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.service.AdminFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/files")
@PreAuthorize("hasRole('SUPER_ADMIN')") // 仅超级管理员可访问
public class AdminFileController {
    
    private static final Logger logger = LoggerFactory.getLogger(AdminFileController.class);
    
    @Autowired
    private AdminFileService adminFileService;
    
    /**
     * 强制物理删除文件节点
     */
    @DeleteMapping("/force-delete/file/{nodeId}")
    public ResponseEntity<Map<String, Object>> forceDeleteFile(
            @PathVariable Long nodeId,
            @RequestHeader(value = "X-Admin-Reason", required = false) String reason) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 检查是否在低峰期（凌晨 2:00-5:00）
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            if (hour >= 9 && hour < 22) {
                response.put("code", 403);
                response.put("success", false);
                response.put("message", "禁止在高峰期执行物理删除操作，请在凌晨 2:00-5:00 执行");
                return ResponseEntity.status(403).body(response);
            }
            
            // 获取当前管理员ID
            Long adminId = getCurrentAdminId();
            
            // 执行强制删除
            adminFileService.forceDeleteFileNode(nodeId, adminId, reason);
            
            response.put("code", 200);
            response.put("success", true);
            response.put("message", "文件已物理删除");
            response.put("data", Map.of(
                "deletedNodeId", nodeId,
                "operationLogged", true
            ));
            
            logger.warn("[管理员强制删除] 文件 - NodeId: {}, AdminId: {}, Reason: {}", 
                nodeId, adminId, reason);
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("[管理员强制删除] 失败 - NodeId: {}, Error: {}", nodeId, e.getMessage(), e);
            response.put("code", 500);
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    /**
     * 强制物理删除文件夹节点
     */
    @DeleteMapping("/force-delete/folder/{nodeId}")
    public ResponseEntity<Map<String, Object>> forceDeleteFolder(
            @PathVariable Long nodeId,
            @RequestHeader(value = "X-Admin-Reason", required = false) String reason) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 检查是否在低峰期
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            if (hour >= 9 && hour < 22) {
                response.put("code", 403);
                response.put("success", false);
                response.put("message", "禁止在高峰期执行物理删除操作，请在凌晨 2:00-5:00 执行");
                return ResponseEntity.status(403).body(response);
            }
            
            Long adminId = getCurrentAdminId();
            
            adminFileService.forceDeleteFolderNode(nodeId, adminId, reason);
            
            response.put("code", 200);
            response.put("success", true);
            response.put("message", "文件夹已物理删除");
            response.put("data", Map.of(
                "deletedNodeId", nodeId,
                "operationLogged", true
            ));
            
            logger.warn("[管理员强制删除] 文件夹 - NodeId: {}, AdminId: {}, Reason: {}", 
                nodeId, adminId, reason);
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            response.put("code", 400);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("[管理员强制删除] 失败 - NodeId: {}, Error: {}", nodeId, e.getMessage(), e);
            response.put("code", 500);
            response.put("success", false);
            response.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
    
    private Long getCurrentAdminId() {
        // TODO: 从安全上下文获取当前管理员ID
        return 1L;
    }
}
```

### 阶段 4：定时任务（第 8 天）

```java
// src/main/java/com/mizuka/cloudfilesystem/scheduler/RecycleBinCleanupScheduler.java
package com.mizuka.cloudfilesystem.scheduler;

import com.mizuka.cloudfilesystem.service.DirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecycleBinCleanupScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(RecycleBinCleanupScheduler.class);
    
    @Autowired
    private DirectoryService directoryService;
    
    /**
     * 每天凌晨2点清理回收站中过期的项目
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupRecycleBin() {
        logger.info("[定时任务] 开始清理回收站");
        try {
            directoryService.cleanupExpiredRecycleBin();
            logger.info("[定时任务] 回收站清理完成");
        } catch (Exception e) {
            logger.error("[定时任务] 回收站清理失败", e);
        }
    }
}
```

### 阶段 5：前端集成（第 9-10 天）

（参考原设计文档的前端集成部分，增加回收站和待分配池的管理界面）

---

## 🚀 未来扩展

### 1. 分片下载与断点续传

（保持原有设计）

### 2. 文件版本控制

（保持原有设计）

### 3. 文件共享与协作

（保持原有设计）

### 4. 全文搜索（Elasticsearch）

（保持原有设计）

---

## 📊 性能优化建议

### 1. 数据库优化

- **索引优化**：确保常用查询字段有索引
- **分区表**：按用户ID或时间分区
- **读写分离**：主库写，从库读
- **软删除优势**：避免大量级联删除，提升删除性能

### 2. Redis 优化

- **缓存预热**：系统启动时预加载热门目录
- **缓存淘汰策略**：使用 LRU 策略
- **集群部署**：高并发时使用 Redis Cluster
- **待分配池缓存**：高频更新的待分配池使用短TTL（60秒）

### 3. 文件存储优化

- **CDN 加速**：静态文件使用 CDN
- **对象存储**：大文件使用 OSS/S3
- **压缩传输**：启用 Gzip/Brotli
- **目录复用**：减少新建目录的IO开销

### 4. 回收站优化

- **异步清理**：使用定时任务异步清理过期项目，避免阻塞用户操作
- **批量处理**：每次清理批量处理多个项目，减少数据库交互次数
- **物理文件延迟删除**：回收站中的文件物理删除可以进一步延迟，降低IO压力

---

## 📝 总结

本设计文档 v2.0 提供了完整的目录树系统实现方案，包括：

✅ **数据库设计**：5 张核心表，支持软删除、回收站、待分配池（逻辑概念）  
✅ **物理存储解耦**：删除的文件仍存储在 `actual_files`，通过元数据关联，不移动物理文件  
✅ **智能恢复机制**：记录原始位置信息，恢复时自动判断原文件夹是否存在，不存在则恢复到用户根目录  
✅ **待分配目录池**：逻辑概念，彻底删除的文件夹标记为 `unassigned`，无物理存储  
✅ **管理员目录规范**：所有管理员专属目录以 `_` 开头  
✅ **Redis 缓存**：多级缓存策略，提升读取性能  
✅ **API 设计**：RESTful 接口，支持懒加载、分片上传、回收站管理、智能恢复  
✅ **实现步骤**：分 5 个阶段，10 天完成开发  
✅ **定时任务**：自动清理回收站中过期的项目  
✅ **性能优化**：软删除避免级联删除，目录复用减少IO开销  
✅ **引用计数**：支持多目录引用同一文件，防止误删  
✅ **管理员强制删除**：特殊权限接口，低峰期执行物理删除  

**下一步行动**：
1. 执行数据库建表脚本（database_schema_v2.sql）
2. 创建 Entity 和 Mapper
3. 实现 Service 层业务逻辑（含回收站、智能恢复、待分配池）
4. 开发 Controller 接口
5. 配置定时任务
6. 前端集成测试

---

**文档版本**: 2.0  
**创建日期**: 2026-05-06  
**更新日期**: 2026-05-06  
**作者**: AI Assistant  
**状态**: ✅ 已完成
