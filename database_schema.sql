-- ============================================
-- CloudFileSystem 完整数据库建表脚本
-- 数据库: cloud_file_database
-- 端口: 3306
-- 字符集: utf8mb4
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
CREATE TABLE IF NOT EXISTS file_metadata (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    
    -- 文件标识
    file_id VARCHAR(64) NOT NULL UNIQUE COMMENT '文件唯一标识（基于MD5）',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    file_path VARCHAR(500) NOT NULL COMMENT '存储路径',
    
    -- 文件信息
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    md5 VARCHAR(32) NOT NULL COMMENT 'MD5哈希值',
    sha256 VARCHAR(64) NOT NULL COMMENT 'SHA256哈希值',
    mime_type VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    
    -- 所有者信息
    owner_id BIGINT NOT NULL COMMENT '文件所有者ID',
    
    -- 状态
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-上传中，1-已完成，2-已删除',
    
    -- 时间戳
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX idx_file_id (file_id),
    INDEX idx_owner_id (owner_id),
    INDEX idx_md5 (md5),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
    
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';

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
