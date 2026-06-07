-- ============================================
-- 修复 folder_nodes 表中 path 字段的 NULL 值问题
-- 创建日期: 2026-06-07
-- 说明: 将 directory_status = 'unassigned' 的记录的 path 字段从 NULL 更新为空字符串
-- ============================================

-- 1. 检查是否存在 path 为 NULL 的记录
SELECT COUNT(*) AS null_path_count 
FROM folder_nodes 
WHERE path IS NULL;

-- 2. 将所有 path 为 NULL 的记录更新为空字符串
UPDATE folder_nodes 
SET path = '' 
WHERE path IS NULL;

-- 3. 验证修复结果
SELECT id, directory_status, path, name 
FROM folder_nodes 
WHERE path = '' OR path IS NULL;

-- 4. （可选）如果需要修改表结构允许 path 为 NULL，可以执行以下语句
-- ALTER TABLE folder_nodes MODIFY COLUMN `path` VARCHAR(1000) DEFAULT NULL COMMENT '完整路径，如 _root/_files/10001/documents';
