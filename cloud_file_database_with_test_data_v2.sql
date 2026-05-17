-- ============================================
-- CloudFileSystem 数据库初始化脚本（含测试数据）- v2.0
-- 数据库: cloud_file_database
-- 端口: 3306
-- 字符集: utf8mb4
-- 生成时间: 2026-05-11
-- 说明: 采用文件夹节点表和文件节点表分离的设计
-- ============================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS cloud_file_database 
    DEFAULT CHARACTER SET utf8mb4 
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE cloud_file_database;

-- ============================================
-- 1. 用户表 (users)
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID（从10001开始）',
    
    -- 基本信息
    nickname VARCHAR(100) NOT NULL COMMENT '用户昵称',
    password VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    avatar TEXT DEFAULT NULL COMMENT '头像图片路径（URL或Base64）',
    email VARCHAR(100) UNIQUE COMMENT '邮箱地址',
    phone VARCHAR(20) UNIQUE COMMENT '手机号码',
    
    -- 存储信息
    storage_quota BIGINT NOT NULL DEFAULT 10737418240 COMMENT '空间配额（字节），默认10GB',
    storage_used BIGINT NOT NULL DEFAULT 0 COMMENT '已使用空间（字节）',
    
    -- 账号状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态：0-禁用，1-正常，2-锁定',
    
    -- 安全问题
    security_question_id INT DEFAULT NULL COMMENT '安全问题编号',
    security_answer VARCHAR(255) DEFAULT NULL COMMENT '安全问题答案',
    
    -- 时间戳
    registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    last_login_at DATETIME DEFAULT NULL COMMENT '最后登录时间',
    
    -- 索引
    INDEX idx_email (email),
    INDEX idx_phone (phone),
    INDEX idx_nickname (nickname),
    INDEX idx_status (status)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 设置用户ID起始值为10001
ALTER TABLE users AUTO_INCREMENT = 10001;

-- ============================================
-- 2. 管理员表 (administrators)
-- ============================================
CREATE TABLE IF NOT EXISTS administrators (
    id INT PRIMARY KEY AUTO_INCREMENT COMMENT '管理员ID（1-9999）',
    
    -- 基本信息
    nickname VARCHAR(100) NOT NULL COMMENT '管理员昵称',
    password VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    avatar TEXT DEFAULT NULL COMMENT '头像图片路径（URL或Base64）',
    email VARCHAR(100) UNIQUE COMMENT '邮箱地址',
    phone VARCHAR(20) UNIQUE COMMENT '手机号码',
    
    -- 账号状态
    status TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态：0-禁用，1-正常，2-锁定',
    
    -- 安全问题
    security_question_id INT DEFAULT NULL COMMENT '安全问题编号',
    security_answer VARCHAR(255) DEFAULT NULL COMMENT '安全问题答案',
    
    -- 时间戳
    registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    last_login_at DATETIME DEFAULT NULL COMMENT '最后登录时间',
    
    -- 索引
    INDEX idx_email (email),
    INDEX idx_phone (phone)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员表';

-- ============================================
-- 3. 安全问题表 (security_questions)
-- ============================================
CREATE TABLE IF NOT EXISTS security_questions (
    id INT PRIMARY KEY AUTO_INCREMENT COMMENT '问题ID',
    
    question_text VARCHAR(255) NOT NULL COMMENT '问题内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    INDEX idx_created_at (created_at)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全问题表';

-- 插入默认的安全问题
INSERT INTO security_questions (question_text) VALUES
('您的出生地是哪里？'),
('您第一所学校的名字是什么？'),
('您母亲的姓名是什么？'),
('您最喜欢的颜色是什么？'),
('您宠物的名字是什么？'),
('您父亲的中间名是什么？'),
('您童年最好的朋友叫什么名字？'),
('您第一次工作的公司名称是什么？');

-- ============================================
-- 4. 用户信任设备表 (user_trusted_devices)
-- ============================================
CREATE TABLE IF NOT EXISTS user_trusted_devices (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    
    -- 用户关联
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 设备标识
    device_uuid VARCHAR(36) NOT NULL UNIQUE COMMENT '设备UUID（后端生成）',
    device_fingerprint VARCHAR(255) NOT NULL COMMENT '设备指纹哈希',
    hardware_id VARCHAR(255) DEFAULT NULL COMMENT '硬件唯一标识（仅客户端可用）',
    
    -- 客户端信息
    client_type VARCHAR(50) NOT NULL COMMENT '客户端类型: electron/android/ios',
    client_identifier VARCHAR(100) NOT NULL COMMENT '详细标识: electron-windows-x64',
    platform VARCHAR(50) NOT NULL COMMENT '操作系统: windows/macos/linux/android/ios',
    
    -- 设备信息
    device_name VARCHAR(100) DEFAULT NULL COMMENT '设备名称（用户自定义）',
    device_model VARCHAR(100) DEFAULT NULL COMMENT '设备型号（移动端）',
    
    -- 信任状态
    is_trusted BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否信任（此表只存信任设备）',
    trust_level TINYINT NOT NULL DEFAULT 1 COMMENT '信任等级: 1=普通信任, 2=完全信任',
    
    -- 登录统计
    last_login_time DATETIME NOT NULL COMMENT '最后登录时间',
    last_login_ip VARCHAR(45) DEFAULT NULL COMMENT '最后登录IP',
    last_login_location VARCHAR(255) DEFAULT NULL COMMENT '最后登录地点',
    login_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '登录次数',
    
    -- 时间戳
    first_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次信任时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_user_id (user_id),
    INDEX idx_device_uuid (device_uuid),
    INDEX idx_fingerprint (device_fingerprint),
    INDEX idx_client_type (client_type),
    
    -- 外键
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信任设备表（仅客户端永久设备）';

-- ============================================
-- 5. 二次验证日志表 (two_factor_verification_logs)
-- ============================================
CREATE TABLE IF NOT EXISTS two_factor_verification_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    
    user_id BIGINT NOT NULL COMMENT '用户ID',
    device_uuid VARCHAR(36) DEFAULT NULL COMMENT '设备UUID（如果是信任设备）',
    device_fingerprint VARCHAR(255) NOT NULL COMMENT '设备指纹',
    
    -- 验证信息
    verify_method VARCHAR(50) NOT NULL COMMENT '验证方式: email/phone/security_answer',
    verify_result VARCHAR(50) NOT NULL COMMENT '验证结果: success/failed',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    
    -- 上下文信息
    client_type VARCHAR(50) DEFAULT NULL COMMENT '客户端类型',
    client_platform VARCHAR(50) DEFAULT NULL COMMENT '平台',
    ip_address VARCHAR(45) DEFAULT NULL COMMENT 'IP地址',
    user_agent TEXT DEFAULT NULL COMMENT 'User-Agent',
    
    -- 时间戳
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '验证时间',
    
    -- 索引
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at),
    INDEX idx_verify_result (verify_result),
    
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='二次验证操作日志';

-- ============================================
-- 6. 文件元数据表 (file_metadata)
-- ============================================
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

    -- 引用计数（支持多目录引用同一文件）
    reference_count INT DEFAULT 0 COMMENT '被file_nodes引用的次数',

    -- 下载次数统计
    download_count INT DEFAULT 0 COMMENT '下载次数',
    last_download_at DATETIME DEFAULT NULL COMMENT '最后下载时间',

    -- 软删除支持
    is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',

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

-- ============================================
-- 7. 上传任务表 (upload_tasks)
-- ============================================
CREATE TABLE IF NOT EXISTS upload_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    
    -- 任务标识
    upload_id VARCHAR(64) NOT NULL UNIQUE COMMENT '上传任务ID',
    file_id VARCHAR(64) NOT NULL COMMENT '文件ID',
    
    -- 用户信息
    user_id BIGINT NOT NULL COMMENT '用户ID',
    
    -- 文件信息
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    
    -- 分片信息
    total_chunks INT NOT NULL COMMENT '总分片数',
    uploaded_chunks INT NOT NULL DEFAULT 0 COMMENT '已上传分片数',
    chunk_status TEXT DEFAULT NULL COMMENT '分片状态（JSON数组）',
    
    -- 任务状态
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-进行中，1-已完成，2-已取消',
    
    -- 时间戳
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    expire_at DATETIME NOT NULL COMMENT '过期时间',
    
    -- 索引
    INDEX idx_upload_id (upload_id),
    INDEX idx_file_id (file_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_expire_at (expire_at)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传任务表（用于断点续传）';

-- ============================================
-- 8. 文件夹节点表 (folder_nodes)
-- ============================================
CREATE TABLE folder_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件夹ID',
    parent_id BIGINT DEFAULT NULL COMMENT '父文件夹ID，NULL表示根目录',
    user_id BIGINT DEFAULT NULL COMMENT '所属用户ID，NULL表示系统/管理员目录',
    
    -- 基本信息
    name VARCHAR(255) NOT NULL COMMENT '文件夹名称',
    path VARCHAR(1000) NOT NULL COMMENT '完整路径，如 _root/_files/10001/documents',
    level INT DEFAULT 0 COMMENT '层级深度，根目录为0',
    
    -- 排序和显示
    sort_order INT DEFAULT 0 COMMENT '同级节点排序顺序',
    is_hidden TINYINT(1) DEFAULT 0 COMMENT '是否隐藏',
    
    -- 软删除支持
    is_deleted TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    deleted_at DATETIME DEFAULT NULL COMMENT '删除时间',
    delete_expires_at DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
    
    -- 目录状态（用于回收站）
    directory_status ENUM('active', 'in_recycle_bin', 'unassigned') DEFAULT 'active' COMMENT '目录状态：活跃/回收站中/待分配',
    unassigned_at DATETIME DEFAULT NULL COMMENT '进入待分配池的时间',
    
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

-- ============================================
-- 9. 文件节点表 (file_nodes)
-- ============================================
CREATE TABLE file_nodes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件节点ID',
    folder_id BIGINT NOT NULL COMMENT '所属文件夹ID',
    user_id BIGINT DEFAULT NULL COMMENT '所属用户ID，NULL表示系统文件',
    file_metadata_id BIGINT NOT NULL COMMENT '关联文件元数据ID',
    
    -- 基本信息
    name VARCHAR(255) NOT NULL COMMENT '显示文件名',
    path VARCHAR(1000) NOT NULL COMMENT '完整路径，如 _root/_files/10001/documents/file.pdf',
    
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

-- ============================================
-- 10. 文件分片表 (file_chunks)
-- ============================================
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

-- ============================================
-- 11. 目录权限表 (directory_permissions)
-- ============================================
CREATE TABLE directory_permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '权限ID',
    node_id BIGINT NOT NULL COMMENT '目录节点ID（文件夹ID）',
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
    FOREIGN KEY (node_id) REFERENCES folder_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='目录权限表（仅针对文件夹）';

-- ============================================
-- 插入测试数据
-- ============================================

-- 插入测试用户数据（来自备份文件）
INSERT INTO users (id, nickname, password, avatar, email, phone, storage_quota, storage_used, status, security_question_id, security_answer, registered_at, last_login_at) VALUES
(10001, 'mizuka', '$2a$10$2Sxwl8cNSGXpzJTzfP8JQ.Y6AEXtLFcn2WmbGRdPb7rV.ZwK.HNqK', '/file/download/13b2eb67-498c-4ad1-be20-74bddc8815c6_0FBBEDD958EAD11E0F652958469642A6.jpg', '467915465@qq.com', '13854280627', 10737418240, 0, 1, 1, '$2a$10$daB/7daJ1arpoFkqG83R0uB5Uaigh5XXB4gf7wsOyitylQnPoCsD2', '2026-04-29 19:59:40', '2026-05-05 00:50:26'),
(10002, 'test_02', '$2a$10$8WYkXIuTQ1Iqrb5p/UIlkuJSvF37SzY4r0EFf0NaaAoMfWAJSf6Fq', '/file/download/e1e41484-58e3-44de-8558-9b03fa750b2f_0.jpg', '780347773@qq.com', '18390838406', 10737418240, 0, 1, 2, '$2a$10$n1/GTkh3GKrdbDCGXnKBMOGHgrOfT48XojWDhIQ903GPf5sadUpw6', '2026-04-29 20:05:51', '2026-05-05 00:32:58'),
(10003, 'test03030303', '$2a$10$dzLp7/8oMCP7f2Jw3a0LreytZgqur4jr4gHM2Oe/CwgsgFZy3Z4Ea', NULL, '12345678@123.com', '15522222222', 10737418240, 0, 1, 4, '$2a$10$gGhsX7mjz7xAMsUlkZso5.nOFScDQZawzIBtj4eWr7WB8L1zhe35W', '2026-04-29 22:54:41', '2026-05-05 00:30:02'),
(10004, 'ayako', '$2a$10$8bnOAX9rtrz.o4jwus5.QOMuo8pt8HWeiv7/qorgwZN9.pA5bHl8.', '/file/download/10030ce0-b780-4c07-9299-2201c4e42d6b_570FBA77FED53ADCCAFD9279377E40C2.png', '3032574954@qq.com', '', 10737418240, 0, 1, 3, '$2a$10$AulMmDsa/9whqJpzvFr3gO6m8MSt5Y2ifvGIZ3S9Obc0c4H/PS8Ee', '2026-05-03 20:43:10', '2026-05-05 00:34:13');


-- 插入根节点（管理员专属，以_开头，无前导斜杠）
INSERT INTO folder_nodes (id, parent_id, user_id, name, path, level, sort_order) VALUES
(1, NULL, NULL, '_root', '_root', 0, 0);

-- 插入管理员专属子目录（均以_开头）
INSERT INTO folder_nodes (parent_id, user_id, name, path, level, sort_order) VALUES
(1, NULL, '_avatar', '_root/_avatar', 1, 0),
(1, NULL, '_backup', '_root/_backup', 1, 1),
(1, NULL, '_system', '_root/_system', 1, 2),
(1, NULL, '_recycle_bin', '_root/_recycle_bin', 1, 3),
(1, NULL, '_files', '_root/_files', 1, 4);

-- 为现有用户创建根目录（在 _files 下按用户ID分配）
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

-- 为现有用户在回收站中创建隔离目录（直接在 _recycle_bin 下按用户ID分配）
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

-- 插入头像文件元数据（确保头像可以下载）
INSERT INTO file_metadata (user_id, file_hash, original_filename, stored_filename, file_size, mime_type, extension, storage_path, storage_type, upload_status, is_public, download_count, reference_count, uploaded_at, created_at, updated_at) VALUES
(10001, '0FBBEDD958EAD11E0F652958469642A6', 'avatar.jpg', '13b2eb67-498c-4ad1-be20-74bddc8815c6_0FBBEDD958EAD11E0F652958469642A6.jpg', 102400, 'image/jpeg', 'jpg', '/_root/_avatar/13b2eb67-498c-4ad1-be20-74bddc8815c6_0FBBEDD958EAD11E0F652958469642A6.jpg', 'local', 'completed', 0, 0, 1, '2026-04-29 19:59:40', '2026-04-29 19:59:40', '2026-04-29 19:59:40'),
(10002, 'e1e41484-58e3-44de-8558-9b03fa750b2f_0', 'avatar.jpg', 'e1e41484-58e3-44de-8558-9b03fa750b2f_0.jpg', 85600, 'image/jpeg', 'jpg', '/_root/_avatar/e1e41484-58e3-44de-8558-9b03fa750b2f_0.jpg', 'local', 'completed', 0, 0, 1, '2026-04-29 20:05:51', '2026-04-29 20:05:51', '2026-04-29 20:05:51'),
(10004, '570FBA77FED53ADCCAFD9279377E40C2', 'avatar.png', '10030ce0-b780-4c07-9299-2201c4e42d6b_570FBA77FED53ADCCAFD9279377E40C2.png', 125800, 'image/png', 'png', '/_root/_avatar/10030ce0-b780-4c07-9299-2201c4e42d6b_570FBA77FED53ADCCAFD9279377E40C2.png', 'local', 'completed', 0, 0, 1, '2026-05-03 20:43:10', '2026-05-03 20:43:10', '2026-05-03 20:43:10');

-- 为头像文件创建文件节点（存储在 _avatar 目录下）
INSERT INTO file_nodes (folder_id, user_id, file_metadata_id, name, path, file_size, mime_type, extension, sort_order, directory_status)
SELECT
    (SELECT id FROM folder_nodes WHERE path = '_root/_avatar'),
    fm.user_id,
    fm.id,
    fm.stored_filename,
    CONCAT('_root/_avatar/', fm.stored_filename),
    fm.file_size,
    fm.mime_type,
    fm.extension,
    0,
    'active'
FROM file_metadata fm
WHERE fm.original_filename LIKE 'avatar.%';

-- ============================================
-- 完成提示
-- ============================================
SELECT '数据库表创建完成！' AS message;
SELECT '已创建以下表格：' AS info;
SELECT '1. users - 用户表' AS table1;
SELECT '2. administrators - 管理员表' AS table2;
SELECT '3. security_questions - 安全问题表' AS table3;
SELECT '4. user_trusted_devices - 用户信任设备表' AS table4;
SELECT '5. two_factor_verification_logs - 二次验证日志表' AS table5;
SELECT '6. file_metadata - 文件元数据表' AS table6;
SELECT '7. upload_tasks - 上传任务表' AS table7;
SELECT '8. folder_nodes - 文件夹节点表' AS table8;
SELECT '9. file_nodes - 文件节点表' AS table9;
SELECT '10. file_chunks - 文件分片表' AS table10;
SELECT '11. directory_permissions - 目录权限表' AS table11;
SELECT '已插入4个测试用户及其头像元数据和文件节点' AS data_info;
