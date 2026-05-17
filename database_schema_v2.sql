-- ============================================
-- 云文件系统 - 目录树系统数据库脚本 v2.0
-- 创建日期: 2026-05-06
-- 说明: 支持回收站、待分配目录池、软删除机制
-- ============================================

-- 设置字符集
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 1. 文件夹节点表 (folder_nodes)
-- ============================================
DROP TABLE IF EXISTS `folder_nodes`;
CREATE TABLE `folder_nodes` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件夹ID',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父文件夹ID，NULL表示根目录',
    `user_id` BIGINT DEFAULT NULL COMMENT '所属用户ID，NULL表示系统/管理员目录（仅管理员可访问）',
    
    -- 基本信息
    `name` VARCHAR(255) NOT NULL COMMENT '文件夹名称',
    `path` VARCHAR(1000) NOT NULL COMMENT '完整路径，如 _root/_files/10001/documents',
    `level` INT DEFAULT 0 COMMENT '层级深度，根目录为0',
    
    -- 排序和显示
    `sort_order` INT DEFAULT 0 COMMENT '同级节点排序顺序',
    `is_hidden` TINYINT(1) DEFAULT 0 COMMENT '是否隐藏',
    
    -- 软删除支持
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '删除时间',
    `delete_expires_at` DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
    
    -- 目录状态（用于回收站）
    `directory_status` ENUM('active', 'in_recycle_bin', 'unassigned') DEFAULT 'active' COMMENT '目录状态：活跃/回收站中/待分配',
    `unassigned_at` DATETIME DEFAULT NULL COMMENT '进入待分配池的时间（逻辑标记，无物理存储）',
    
    -- 原始位置信息（用于恢复）
    `original_parent_id` BIGINT DEFAULT NULL COMMENT '原始父文件夹ID（删除时记录，用于恢复）',
    `original_path` VARCHAR(1000) DEFAULT NULL COMMENT '原始完整路径（删除时记录，用于恢复）',
    
    -- 统计信息
    `file_count` INT DEFAULT 0 COMMENT '直接子文件数量',
    `folder_count` INT DEFAULT 0 COMMENT '直接子文件夹数量',
    `total_size` BIGINT DEFAULT 0 COMMENT '文件夹总大小（字节，包含所有子文件）',
    
    -- 时间戳
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX `idx_parent_id` (`parent_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_path` (`path`(255)),
    INDEX `idx_is_deleted` (`is_deleted`),
    INDEX `idx_directory_status` (`directory_status`),
    INDEX `idx_delete_expires_at` (`delete_expires_at`),
    FULLTEXT INDEX `ft_idx_name` (`name`) COMMENT '全文索引用于搜索',
    
    -- 外键约束
    CONSTRAINT `fk_folder_parent` FOREIGN KEY (`parent_id`) REFERENCES `folder_nodes`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_folder_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件夹节点表';

-- ============================================
-- 2. 文件节点表 (file_nodes)
-- ============================================
DROP TABLE IF EXISTS `file_nodes`;
CREATE TABLE `file_nodes` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件节点ID',
    `folder_id` BIGINT NOT NULL COMMENT '所属文件夹ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '所属用户ID，NULL表示系统文件（仅管理员可访问）',
    `file_metadata_id` BIGINT NOT NULL COMMENT '关联文件元数据ID',
    
    -- 基本信息
    `name` VARCHAR(255) NOT NULL COMMENT '显示文件名',
    `path` VARCHAR(1000) NOT NULL COMMENT '完整路径，如 _root/_files/10001/documents/file.pdf',
    
    -- 文件信息（冗余存储，提升查询性能）
    `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小（字节）',
    `mime_type` VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    `extension` VARCHAR(20) DEFAULT NULL COMMENT '文件扩展名',
    
    -- 排序和显示
    `sort_order` INT DEFAULT 0 COMMENT '同级节点排序顺序',
    `is_hidden` TINYINT(1) DEFAULT 0 COMMENT '是否隐藏',
    
    -- 软删除支持
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '删除时间',
    `delete_expires_at` DATETIME DEFAULT NULL COMMENT '删除过期时间（回收站30天后彻底删除）',
    
    -- 目录状态（用于回收站）
    `directory_status` ENUM('active', 'in_recycle_bin', 'permanently_deleted') DEFAULT 'active' COMMENT '文件状态：活跃/回收站中/已彻底删除',
    
    -- 原始位置信息（用于恢复）
    `original_folder_id` BIGINT DEFAULT NULL COMMENT '原始所属文件夹ID（删除时记录，用于恢复）',
    `original_path` VARCHAR(1000) DEFAULT NULL COMMENT '原始完整路径（删除时记录，用于恢复）',
    
    -- 时间戳
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX `idx_folder_id` (`folder_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_file_metadata_id` (`file_metadata_id`),
    INDEX `idx_path` (`path`(255)),
    INDEX `idx_is_deleted` (`is_deleted`),
    INDEX `idx_directory_status` (`directory_status`),
    INDEX `idx_delete_expires_at` (`delete_expires_at`),
    FULLTEXT INDEX `ft_idx_name` (`name`) COMMENT '全文索引用于搜索',
    
    -- 外键约束
    CONSTRAINT `fk_file_folder` FOREIGN KEY (`folder_id`) REFERENCES `folder_nodes`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_file_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_file_metadata` FOREIGN KEY (`file_metadata_id`) REFERENCES `file_metadata`(`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件节点表';

-- ============================================
-- 3. 文件元数据表 (file_metadata)
-- ============================================
DROP TABLE IF EXISTS `file_metadata`;
CREATE TABLE `file_metadata` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件元数据ID',
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
    
    -- 文件标识
    `file_hash` VARCHAR(64) NOT NULL COMMENT '文件SHA256哈希值（去重用）',
    `original_filename` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `stored_filename` VARCHAR(255) NOT NULL COMMENT '存储文件名（UUID或哈希）',
    
    -- 文件信息
    `file_size` BIGINT NOT NULL COMMENT '文件大小（字节）',
    `mime_type` VARCHAR(100) NOT NULL COMMENT 'MIME类型',
    `extension` VARCHAR(20) DEFAULT NULL COMMENT '文件扩展名',
    
    -- 分片信息（支持断点续传）
    `total_chunks` INT DEFAULT 1 COMMENT '总分片数',
    `uploaded_chunks` INT DEFAULT 0 COMMENT '已上传分片数',
    `chunk_size` BIGINT DEFAULT 5242880 COMMENT '分片大小（默认5MB）',
    
    -- 存储位置
    `storage_path` VARCHAR(500) NOT NULL COMMENT '实际存储路径',
    `storage_type` ENUM('local', 'oss', 's3') DEFAULT 'local' COMMENT '存储类型',
    
    -- 状态
    `upload_status` ENUM('pending', 'uploading', 'completed', 'failed', 'deleted') DEFAULT 'pending' COMMENT '上传状态',
    `is_public` TINYINT(1) DEFAULT 0 COMMENT '是否公开访问',
    
    -- 软删除支持
    `is_deleted` TINYINT(1) DEFAULT 0 COMMENT '是否已删除（软删除）',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '删除时间',
    
    -- 引用计数（支持多目录引用同一文件）
    `reference_count` INT DEFAULT 0 COMMENT '被file_nodes引用的次数',
    
    -- 下载次数统计
    `download_count` INT DEFAULT 0 COMMENT '下载次数',
    `last_download_at` DATETIME DEFAULT NULL COMMENT '最后下载时间',
    
    -- 时间戳
    `uploaded_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传完成时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    -- 索引
    INDEX `idx_user_id` (`user_id`),
    UNIQUE INDEX `idx_file_hash` (`file_hash`),
    INDEX `idx_upload_status` (`upload_status`),
    INDEX `idx_stored_filename` (`stored_filename`),
    INDEX `idx_is_deleted` (`is_deleted`),
    INDEX `idx_reference_count` (`reference_count`),
    
    -- 外键
    CONSTRAINT `fk_metadata_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件元数据表';

-- ============================================
-- 4. 文件分片表 (file_chunks)
-- ============================================
DROP TABLE IF EXISTS `file_chunks`;
CREATE TABLE `file_chunks` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分片ID',
    `file_metadata_id` BIGINT NOT NULL COMMENT '关联文件元数据ID',
    
    -- 分片信息
    `chunk_index` INT NOT NULL COMMENT '分片索引（从0开始）',
    `chunk_hash` VARCHAR(64) NOT NULL COMMENT '分片SHA256哈希值',
    `chunk_size` BIGINT NOT NULL COMMENT '分片大小（字节）',
    
    -- 存储位置
    `chunk_path` VARCHAR(500) NOT NULL COMMENT '分片存储路径',
    
    -- 状态
    `upload_status` ENUM('pending', 'uploaded', 'verified', 'failed') DEFAULT 'pending' COMMENT '上传状态',
    
    -- 时间戳
    `uploaded_at` DATETIME DEFAULT NULL COMMENT '上传完成时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    -- 索引
    UNIQUE INDEX `idx_file_chunk` (`file_metadata_id`, `chunk_index`),
    INDEX `idx_chunk_hash` (`chunk_hash`),
    
    -- 外键
    CONSTRAINT `fk_chunk_file` FOREIGN KEY (`file_metadata_id`) REFERENCES `file_metadata`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件分片表';

-- ============================================
-- 5. 目录权限表 (directory_permissions)
-- ============================================
DROP TABLE IF EXISTS `directory_permissions`;
CREATE TABLE `directory_permissions` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '权限ID',
    `node_id` BIGINT NOT NULL COMMENT '目录节点ID',
    `user_id` BIGINT NOT NULL COMMENT '授权用户ID',
    
    -- 权限类型
    `permission_type` ENUM('read', 'write', 'delete', 'share') NOT NULL COMMENT '权限类型',
    `is_granted` TINYINT(1) DEFAULT 1 COMMENT '是否授予权限',
    
    -- 授权信息
    `granted_by` BIGINT DEFAULT NULL COMMENT '授权者用户ID',
    `granted_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '权限过期时间',
    
    -- 索引
    UNIQUE INDEX `idx_node_user_permission` (`node_id`, `user_id`, `permission_type`),
    INDEX `idx_user_id` (`user_id`),
    
    -- 外键
    CONSTRAINT `fk_perm_folder` FOREIGN KEY (`node_id`) REFERENCES `folder_nodes`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_perm_user` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_perm_granted_by` FOREIGN KEY (`granted_by`) REFERENCES `users`(`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='目录权限表（仅针对文件夹）';

-- ============================================
-- 6. 初始化目录树结构
-- ============================================

-- 6.1 插入根节点（管理员专属，以_开头，无前导斜杠）
INSERT INTO `folder_nodes` (`id`, `parent_id`, `user_id`, `name`, `path`, `level`, `sort_order`) VALUES
(1, NULL, NULL, '_root', '_root', 0, 0);

-- 6.2 插入管理员专属子目录（均以_开头）
INSERT INTO `folder_nodes` (`parent_id`, `user_id`, `name`, `path`, `level`, `sort_order`) VALUES
(1, NULL, '_avatar', '_root/_avatar', 1, 0),
(1, NULL, '_backup', '_root/_backup', 1, 1),
(1, NULL, '_system', '_root/_system', 1, 2),
(1, NULL, '_recycle_bin', '_root/_recycle_bin', 1, 3),
(1, NULL, '_files', '_root/_files', 1, 4);

-- 6.3 为现有用户创建根目录（假设用户ID从10001开始）
-- 注意：新用户的目录优先从 unassigned 池中复用
INSERT INTO `folder_nodes` (`parent_id`, `user_id`, `name`, `path`, `level`, `sort_order`)
SELECT 
    (SELECT id FROM `folder_nodes` WHERE `path` = '_root/_files'),
    u.id,
    CAST(u.id AS CHAR),
    CONCAT('_root/_files/', u.id),
    2,
    0
FROM `users` u
WHERE u.id >= 10001;

-- 6.4 为现有用户在回收站中创建隔离目录（直接在 _recycle_bin 下按用户ID分配）
INSERT INTO `folder_nodes` (`parent_id`, `user_id`, `name`, `path`, `level`, `sort_order`)
SELECT 
    (SELECT id FROM `folder_nodes` WHERE `path` = '_root/_recycle_bin'),
    u.id,
    CAST(u.id AS CHAR),
    CONCAT('_root/_recycle_bin/', u.id),
    2,
    0
FROM `users` u
WHERE u.id >= 10001;

-- ============================================
-- 7. 创建视图（可选，方便查询）
-- ============================================

-- 7.1 活跃文件夹视图（排除已删除的节点）
CREATE OR REPLACE VIEW `v_active_folders` AS
SELECT * FROM `folder_nodes`
WHERE `is_deleted` = 0 AND `directory_status` = 'active';

-- 7.2 活跃文件视图
CREATE OR REPLACE VIEW `v_active_files` AS
SELECT * FROM `file_nodes`
WHERE `is_deleted` = 0 AND `directory_status` = 'active';

-- 7.3 回收站文件夹视图
CREATE OR REPLACE VIEW `v_recycle_bin_folders` AS
SELECT 
    fn.*,
    DATEDIFF(fn.delete_expires_at, NOW()) AS days_remaining
FROM `folder_nodes` fn
WHERE `directory_status` = 'in_recycle_bin'
ORDER BY `delete_expires_at` ASC;

-- 7.4 回收站文件视图
CREATE OR REPLACE VIEW `v_recycle_bin_files` AS
SELECT 
    fn.*,
    DATEDIFF(fn.delete_expires_at, NOW()) AS days_remaining
FROM `file_nodes` fn
WHERE `directory_status` = 'in_recycle_bin'
ORDER BY `delete_expires_at` ASC;

-- 7.5 待分配目录池视图（逻辑概念，查询标记为 unassigned 的文件夹）
CREATE OR REPLACE VIEW `v_unassigned_pool` AS
SELECT * FROM `folder_nodes`
WHERE `directory_status` = 'unassigned'
ORDER BY `unassigned_at` ASC;

-- 7.6 回收站文件详细信息视图（包含原始位置信息）
CREATE OR REPLACE VIEW `v_recycle_bin_files_detail` AS
SELECT 
    fn.*,
    fm.original_filename,
    fm.storage_path,
    fm.file_hash,
    DATEDIFF(fn.delete_expires_at, NOW()) AS days_remaining,
    CASE 
        WHEN fn.original_folder_id IS NOT NULL THEN '可恢复至原位置'
        ELSE '将恢复至用户根目录'
    END AS restore_info
FROM `file_nodes` fn
JOIN `file_metadata` fm ON fn.file_metadata_id = fm.id
WHERE fn.`directory_status` = 'in_recycle_bin'
ORDER BY fn.delete_expires_at ASC;

-- ============================================
-- 8. 创建存储过程（可选）
-- ============================================

-- 8.1 清理回收站中过期的文件夹（标记为 unassigned，逻辑删除）
DELIMITER $$
CREATE PROCEDURE `sp_cleanup_expired_recycle_bin_folders`()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE expired_id BIGINT;
    DECLARE cur CURSOR FOR 
        SELECT `id` FROM `folder_nodes` 
        WHERE `directory_status` = 'in_recycle_bin' 
        AND `delete_expires_at` <= NOW();
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    
    OPEN cur;
    
    read_loop: LOOP
        FETCH cur INTO expired_id;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        -- 标记为待分配状态（逻辑概念，不移动物理位置）
        UPDATE `folder_nodes` 
        SET `directory_status` = 'unassigned',
            `unassigned_at` = NOW(),
            `user_id` = NULL,
            `is_deleted` = 0,
            `deleted_at` = NULL,
            `delete_expires_at` = NULL,
            `updated_at` = NOW()
        WHERE `id` = expired_id;
        
    END LOOP;
    
    CLOSE cur;
END$$
DELIMITER ;

-- 8.2 清理回收站中过期的文件（软删除后移入待处理状态）
DELIMITER $$
CREATE PROCEDURE `sp_cleanup_expired_recycle_bin_files`()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE expired_id BIGINT;
    DECLARE cur CURSOR FOR 
        SELECT `id` FROM `file_nodes` 
        WHERE `directory_status` = 'in_recycle_bin' 
        AND `delete_expires_at` <= NOW();
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    
    OPEN cur;
    
    read_loop: LOOP
        FETCH cur INTO expired_id;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        -- 软删除：标记为已彻底删除状态（不再显示在回收站）
        UPDATE `file_nodes` 
        SET `directory_status` = 'permanently_deleted',
            `is_deleted` = 1,
            `updated_at` = NOW()
        WHERE `id` = expired_id;
        
    END LOOP;
    
    CLOSE cur;
END$$
DELIMITER ;

-- 8.3 清理永久删除的文件节点和元数据（异步清理任务）
DELIMITER $$
CREATE PROCEDURE `sp_cleanup_permanently_deleted_files`()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE node_id BIGINT;
    DECLARE metadata_id BIGINT;
    DECLARE cur CURSOR FOR 
        SELECT fn.`id`, fn.`file_metadata_id` 
        FROM `file_nodes` fn
        WHERE fn.`directory_status` = 'permanently_deleted'
        AND fn.`is_deleted` = 1;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
    
    OPEN cur;
    
    read_loop: LOOP
        FETCH cur INTO node_id, metadata_id;
        IF done THEN
            LEAVE read_loop;
        END IF;
        
        -- 物理删除文件节点（此时引用计数已经为0）
        DELETE FROM `file_nodes` WHERE `id` = node_id;
        
        -- 检查元数据是否可以删除（引用计数为0且已软删除）
        DELETE FROM `file_metadata` 
        WHERE `id` = metadata_id 
        AND `reference_count` = 0 
        AND `is_deleted` = 1;
        
    END LOOP;
    
    CLOSE cur;
END$$
DELIMITER ;

-- 8.4 管理员强制物理删除文件节点（特殊权限，仅在低峰期使用）
DELIMITER $$
CREATE PROCEDURE `sp_admin_force_delete_file_node`(
    IN p_node_id BIGINT,
    IN p_admin_user_id BIGINT
)
BEGIN
    DECLARE v_metadata_id BIGINT;
    DECLARE v_reference_count INT;
    DECLARE v_directory_status VARCHAR(50);
    
    -- 获取文件节点信息
    SELECT `file_metadata_id`, `directory_status` 
    INTO v_metadata_id, v_directory_status
    FROM `file_nodes` 
    WHERE `id` = p_node_id;
    
    -- 检查节点是否存在
    IF v_metadata_id IS NULL THEN
        SIGNAL SQLSTATE '45000' 
        SET MESSAGE_TEXT = '文件节点不存在';
    END IF;
    
    -- 只允许删除已标记为永久删除的节点
    IF v_directory_status != 'permanently_deleted' THEN
        SIGNAL SQLSTATE '45000' 
        SET MESSAGE_TEXT = '只能删除已标记为永久删除的文件节点';
    END IF;
    
    -- 获取引用计数
    SELECT `reference_count` INTO v_reference_count
    FROM `file_metadata`
    WHERE `id` = v_metadata_id;
    
    -- 记录管理员操作日志（假设有操作日志表）
    -- INSERT INTO admin_operation_log (admin_id, operation_type, target_id, created_at)
    -- VALUES (p_admin_user_id, 'FORCE_DELETE_FILE', p_node_id, NOW());
    
    -- 物理删除文件节点
    DELETE FROM `file_nodes` WHERE `id` = p_node_id;
    
    -- 如果引用计数为0，删除元数据
    IF v_reference_count = 0 THEN
        DELETE FROM `file_metadata` 
        WHERE `id` = v_metadata_id;
    END IF;
    
END$$
DELIMITER ;

-- 8.5 管理员强制物理删除文件夹节点（特殊权限，仅在低峰期使用）
DELIMITER $$
CREATE PROCEDURE `sp_admin_force_delete_folder_node`(
    IN p_node_id BIGINT,
    IN p_admin_user_id BIGINT
)
BEGIN
    DECLARE v_child_count INT;
    DECLARE v_directory_status VARCHAR(50);
    DECLARE v_error_message VARCHAR(255);
    
    -- 获取文件夹信息
    SELECT `directory_status` INTO v_directory_status
    FROM `folder_nodes` 
    WHERE `id` = p_node_id;
    
    -- 检查节点是否存在
    IF v_directory_status IS NULL THEN
        SIGNAL SQLSTATE '45000' 
        SET MESSAGE_TEXT = '文件夹节点不存在';
    END IF;
    
    -- 检查是否有子节点
    SELECT COUNT(*) INTO v_child_count
    FROM `folder_nodes`
    WHERE `parent_id` = p_node_id AND `is_deleted` = 0;
    
    IF v_child_count > 0 THEN
        SET v_error_message = CONCAT('文件夹下还有 ', v_child_count, ' 个子节点，无法删除');
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = v_error_message;
    END IF;
    
    -- 检查是否有文件
    SELECT COUNT(*) INTO v_child_count
    FROM `file_nodes`
    WHERE `folder_id` = p_node_id AND `is_deleted` = 0;
    
    IF v_child_count > 0 THEN
        SET v_error_message = CONCAT('文件夹下还有 ', v_child_count, ' 个文件，无法删除');
        SIGNAL SQLSTATE '45000' 
        SET MESSAGE_TEXT = v_error_message;
    END IF;
    
    -- 记录管理员操作日志
    -- INSERT INTO admin_operation_log (admin_id, operation_type, target_id, created_at)
    -- VALUES (p_admin_user_id, 'FORCE_DELETE_FOLDER', p_node_id, NOW());
    
    -- 物理删除文件夹节点
    DELETE FROM `folder_nodes` WHERE `id` = p_node_id;
    
END$$
DELIMITER ;

-- 8.6 恢复回收站中的文件（根据原始位置恢复）
DELIMITER $$
CREATE PROCEDURE `sp_restore_file_from_recycle_bin`(
    IN p_node_id BIGINT,
    IN p_user_id BIGINT
)
BEGIN
    DECLARE v_original_folder_id BIGINT;
    DECLARE v_original_path VARCHAR(1000);
    DECLARE v_folder_exists INT;
    DECLARE v_new_path VARCHAR(1000);
    DECLARE v_folder_name VARCHAR(255);
    
    -- 获取文件节点的原始位置信息
    SELECT `original_folder_id`, `original_path`
    INTO v_original_folder_id, v_original_path
    FROM `file_nodes`
    WHERE `id` = p_node_id AND `user_id` = p_user_id;
    
    -- 检查节点是否存在
    IF v_original_folder_id IS NULL THEN
        SIGNAL SQLSTATE '45000' 
        SET MESSAGE_TEXT = '文件节点不存在或无权限';
    END IF;
    
    -- 检查原始文件夹是否仍然存在
    SELECT COUNT(*) INTO v_folder_exists
    FROM `folder_nodes`
    WHERE `id` = v_original_folder_id AND `is_deleted` = 0;
    
    IF v_folder_exists > 0 THEN
        -- 原始文件夹存在，恢复到原位置
        -- 从原始路径中提取文件名
        SET v_new_path = v_original_path;
        
        UPDATE `file_nodes`
        SET `directory_status` = 'active',
            `is_deleted` = 0,
            `deleted_at` = NULL,
            `delete_expires_at` = NULL,
            `folder_id` = v_original_folder_id,
            `path` = v_new_path,
            `original_folder_id` = NULL,
            `original_path` = NULL,
            `updated_at` = NOW()
        WHERE `id` = p_node_id;
        
    ELSE
        -- 原始文件夹已删除，恢复到用户根目录
        -- 获取用户根目录ID
        SELECT `id` INTO v_original_folder_id
        FROM `folder_nodes`
        WHERE `user_id` = p_user_id 
          AND `parent_id` = (SELECT `id` FROM `folder_nodes` WHERE `path` = '_root/_files')
        LIMIT 1;
        
        -- 从原始路径中提取文件名
        SET v_folder_name = SUBSTRING_INDEX(v_original_path, '/', -1);
        SET v_new_path = CONCAT('_root/_files/', p_user_id, '/', v_folder_name);
        
        UPDATE `file_nodes`
        SET `directory_status` = 'active',
            `is_deleted` = 0,
            `deleted_at` = NULL,
            `delete_expires_at` = NULL,
            `folder_id` = v_original_folder_id,
            `path` = v_new_path,
            `original_folder_id` = NULL,
            `original_path` = NULL,
            `updated_at` = NOW()
        WHERE `id` = p_node_id;
        
    END IF;
    
END$$
DELIMITER ;

-- 8.7 恢复回收站中的文件夹（根据原始位置恢复）
DELIMITER $$
CREATE PROCEDURE `sp_restore_folder_from_recycle_bin`(
    IN p_node_id BIGINT,
    IN p_user_id BIGINT
)
BEGIN
    DECLARE v_original_parent_id BIGINT;
    DECLARE v_original_path VARCHAR(1000);
    DECLARE v_parent_exists INT;
    DECLARE v_new_path VARCHAR(1000);
    DECLARE v_folder_name VARCHAR(255);
    
    -- 获取文件夹的原始位置信息
    SELECT `original_parent_id`, `original_path`
    INTO v_original_parent_id, v_original_path
    FROM `folder_nodes`
    WHERE `id` = p_node_id AND `user_id` = p_user_id;
    
    -- 检查节点是否存在
    IF v_original_parent_id IS NULL THEN
        SIGNAL SQLSTATE '45000' 
        SET MESSAGE_TEXT = '文件夹节点不存在或无权限';
    END IF;
    
    -- 检查原始父文件夹是否仍然存在
    SELECT COUNT(*) INTO v_parent_exists
    FROM `folder_nodes`
    WHERE `id` = v_original_parent_id AND `is_deleted` = 0;
    
    IF v_parent_exists > 0 THEN
        -- 原始父文件夹存在，恢复到原位置
        SET v_new_path = v_original_path;
        
        UPDATE `folder_nodes`
        SET `directory_status` = 'active',
            `is_deleted` = 0,
            `deleted_at` = NULL,
            `delete_expires_at` = NULL,
            `parent_id` = v_original_parent_id,
            `path` = v_new_path,
            `original_parent_id` = NULL,
            `original_path` = NULL,
            `updated_at` = NOW()
        WHERE `id` = p_node_id;
        
    ELSE
        -- 原始父文件夹已删除，恢复到用户根目录
        -- 获取用户根目录ID
        SELECT `id` INTO v_original_parent_id
        FROM `folder_nodes`
        WHERE `user_id` = p_user_id 
          AND `parent_id` = (SELECT `id` FROM `folder_nodes` WHERE `path` = '_root/_files')
        LIMIT 1;
        
        -- 从原始路径中提取文件夹名
        SET v_folder_name = SUBSTRING_INDEX(v_original_path, '/', -1);
        SET v_new_path = CONCAT('_root/_files/', p_user_id, '/', v_folder_name);
        
        UPDATE `folder_nodes`
        SET `directory_status` = 'active',
            `is_deleted` = 0,
            `deleted_at` = NULL,
            `delete_expires_at` = NULL,
            `parent_id` = v_original_parent_id,
            `path` = v_new_path,
            `original_parent_id` = NULL,
            `original_path` = NULL,
            `updated_at` = NOW()
        WHERE `id` = p_node_id;
        
    END IF;
    
END$$
DELIMITER ;

-- ============================================
-- 9. 创建触发器（可选）
-- ============================================

-- 注意：MySQL触发器中不能动态查询父节点路径，建议在应用层处理路径更新
-- 如果确实需要触发器，可以使用以下简化版本（仅记录日志或设置标记）

-- 9.1 文件夹自动更新时间戳触发器（示例）
DELIMITER $$
CREATE TRIGGER `tr_before_update_folder_nodes`
BEFORE UPDATE ON `folder_nodes`
FOR EACH ROW
BEGIN
    -- 如果需要自动更新updated_at，MySQL已自动处理（ON UPDATE CURRENT_TIMESTAMP）
    -- 路径更新建议在应用层Service中处理，以确保数据一致性
    SET NEW.updated_at = NOW();
END$$
DELIMITER ;

-- 9.2 文件节点自动更新时间戳触发器（示例）
DELIMITER $$
CREATE TRIGGER `tr_before_update_file_nodes`
BEFORE UPDATE ON `file_nodes`
FOR EACH ROW
BEGIN
    SET NEW.updated_at = NOW();
END$$
DELIMITER ;

-- 9.3 文件节点插入时增加引用计数
DELIMITER $$
CREATE TRIGGER `tr_after_insert_file_nodes`
AFTER INSERT ON `file_nodes`
FOR EACH ROW
BEGIN
    UPDATE `file_metadata` 
    SET `reference_count` = `reference_count` + 1,
        `updated_at` = NOW()
    WHERE `id` = NEW.`file_metadata_id`;
END$$
DELIMITER ;

-- 9.4 文件节点物理删除时减少引用计数
-- 注意：软删除（UPDATE is_deleted=1）不会触发此触发器
-- 只有真正的 DELETE 语句才会触发，用于清理孤儿元数据
DELIMITER $$
CREATE TRIGGER `tr_before_delete_file_nodes`
BEFORE DELETE ON `file_nodes`
FOR EACH ROW
BEGIN
    -- 减少引用计数
    UPDATE `file_metadata` 
    SET `reference_count` = GREATEST(`reference_count` - 1, 0),
        `updated_at` = NOW()
    WHERE `id` = OLD.`file_metadata_id`;
END$$
DELIMITER ;

-- ============================================
-- 10. 恢复外键检查
-- ============================================
SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 执行完成提示
-- ============================================
SELECT '数据库脚本执行完成！' AS message;
SELECT COUNT(*) AS total_folders FROM folder_nodes;
SELECT COUNT(*) AS active_folders FROM folder_nodes WHERE is_deleted = 0 AND directory_status = 'active';
SELECT COUNT(*) AS recycle_bin_folders FROM folder_nodes WHERE directory_status = 'in_recycle_bin';
SELECT COUNT(*) AS unassigned_folders FROM folder_nodes WHERE directory_status = 'unassigned';
SELECT COUNT(*) AS total_files FROM file_nodes;
SELECT COUNT(*) AS active_files FROM file_nodes WHERE is_deleted = 0 AND directory_status = 'active';
SELECT COUNT(*) AS recycle_bin_files FROM file_nodes WHERE directory_status = 'in_recycle_bin';
