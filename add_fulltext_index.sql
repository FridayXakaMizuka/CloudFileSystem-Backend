-- ============================================
-- 为文件搜索功能添加全文索引
-- 执行日期: 2026-05-10
-- 说明: 使用 ngram 分词器支持中文搜索
-- ============================================

SET NAMES utf8mb4;

-- ============================================
-- 1. 配置 ngram 分词器（支持中文单字分词）
-- ============================================

-- 设置 ngram 分词大小为 1（单字分词，适合中文）
SET GLOBAL ngram_token_size = 1;

-- 设置 InnoDB 全文索引最小词长为 1
SET GLOBAL innodb_ft_min_token_size = 1;

-- 注意：以上配置需要重启 MySQL 服务才能生效
-- 或者在 my.cnf/my.ini 中添加：
-- [mysqld]
-- ngram_token_size=1
-- innodb_ft_min_token_size=1

-- ============================================
-- 2. 为 file_nodes 表添加全文索引
-- ============================================

-- 检查是否已存在全文索引
SELECT COUNT(*) AS existing_index_count
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'file_nodes'
  AND INDEX_TYPE = 'FULLTEXT';

-- 添加全文索引（如果不存在）
-- 注意：如果索引已存在，以下语句会报错，可以忽略
ALTER TABLE file_nodes 
ADD FULLTEXT INDEX ft_idx_name (name) WITH PARSER ngram;

-- ============================================
-- 3. 为 folder_nodes 表添加全文索引
-- ============================================

-- 检查是否已存在全文索引
SELECT COUNT(*) AS existing_index_count
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'folder_nodes'
  AND INDEX_TYPE = 'FULLTEXT';

-- 添加全文索引（如果不存在）
ALTER TABLE folder_nodes 
ADD FULLTEXT INDEX ft_idx_name (name) WITH PARSER ngram;

-- ============================================
-- 4. 验证索引创建成功
-- ============================================

SHOW INDEX FROM file_nodes WHERE Index_type = 'FULLTEXT';
SHOW INDEX FROM folder_nodes WHERE Index_type = 'FULLTEXT';

-- ============================================
-- 5. 测试搜索功能
-- ============================================

-- 测试文件搜索
SELECT *, 'file' AS node_type,
       MATCH(name) AGAINST('测试' IN BOOLEAN MODE) AS relevance
FROM file_nodes 
WHERE user_id = 10001
  AND is_deleted = 0
  AND directory_status = 'active'
  AND MATCH(name) AGAINST('测试' IN BOOLEAN MODE)
ORDER BY relevance DESC
LIMIT 10;

-- 测试文件夹搜索
SELECT *, 'folder' AS node_type,
       MATCH(name) AGAINST('测试' IN BOOLEAN MODE) AS relevance
FROM folder_nodes 
WHERE user_id = 10001
  AND is_deleted = 0
  AND directory_status = 'active'
  AND MATCH(name) AGAINST('测试' IN BOOLEAN MODE)
ORDER BY relevance DESC
LIMIT 10;

-- ============================================
-- 6. 优化表（重建索引，提升性能）
-- ============================================

OPTIMIZE TABLE file_nodes;
OPTIMIZE TABLE folder_nodes;

-- ============================================
-- 完成提示
-- ============================================

SELECT '全文索引创建完成！搜索功能已就绪。' AS message;
SELECT '请重启 MySQL 服务以使 ngram 配置生效。' AS note;
