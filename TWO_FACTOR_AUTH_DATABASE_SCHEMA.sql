-- ============================================
-- 二次验证功能数据库表
-- 数据库: cloud_file_database
-- 端口: 3306
-- ============================================

USE cloud_file_database;

-- 表1: 用户信任设备表
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

-- 表2: 二次验证日志表
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
