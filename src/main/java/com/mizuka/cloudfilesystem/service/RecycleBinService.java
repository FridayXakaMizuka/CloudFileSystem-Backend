package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.RecycleBinBrowseResponse;
import com.mizuka.cloudfilesystem.dto.RecycleBinItemDTO;
import com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回收站服务
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class RecycleBinService {
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    @Autowired
    private RecycleBinRedisService recycleBinRedisService;
    
    /**
     * 默认每页最大数量
     */
    private static final int DEFAULT_MAX_PAGE_SIZE = 20;
    
    /**
     * 绝对最大每页数量
     */
    private static final int ABSOLUTE_MAX_PAGE_SIZE = 100;
    
    /**
     * 浏览回收站（从 Redis 查询）
     * 
     * @param userId 用户ID
     * @param maxPageSize 每页数量
     * @param lastBatchId 游标锚点（上一批最后一条的 batchId）
     * @return 浏览响应
     */
    public RecycleBinBrowseResponse browseRecycleBin(Long userId, Integer maxPageSize, String lastBatchId) {
        // 1. 参数校验
        if (maxPageSize == null || maxPageSize <= 0) {
            maxPageSize = DEFAULT_MAX_PAGE_SIZE;
        }
        if (maxPageSize > ABSOLUTE_MAX_PAGE_SIZE) {
            maxPageSize = ABSOLUTE_MAX_PAGE_SIZE;
        }
        
        try {
            // 2. 【关键】从 Redis 索引层获取 batchId 列表
            Double lastScore = null;
            if (lastBatchId != null && !lastBatchId.isEmpty()) {
                // 如果提供了 lastBatchId，需要获取其 score 作为游标
                lastScore = getBatchScore(userId, lastBatchId);
            }
            
            java.util.List<String> batchIds = recycleBinRedisService.getUserBatches(
                userId, maxPageSize, lastScore
            ).join();
            
            // 3. 如果没有数据，降级到 MySQL
            if (batchIds == null || batchIds.isEmpty()) {
                log.info("[浏览回收站] Redis 中无数据，降级到 MySQL - UserId: {}", userId);
                return browseFromMySQL(userId, maxPageSize, lastBatchId);
            }
            
            // 4. 【关键】从 Redis 元数据层批量获取 batch 详细信息
            java.util.Map<String, java.util.Map<String, String>> batchInfos = 
                recycleBinRedisService.getBatchInfos(batchIds).join();
            
            // 5. 转换为 DTO 列表
            java.util.List<RecycleBinItemDTO> items = new java.util.ArrayList<>();
            for (String batchId : batchIds) {
                java.util.Map<String, String> info = batchInfos.get(batchId);
                if (info != null && !info.isEmpty()) {
                    RecycleBinItemDTO item = convertToDTO(batchId, info);
                    items.add(item);
                } else {
                    // 如果 Redis 中没有详细信息，尝试从 MySQL 获取
                    log.warn("[浏览回收站] Redis 中缺少 batch 信息，从 MySQL 获取 - BatchId: {}", batchId);
                    RecycleBinItemDTO item = getFromMySQL(batchId);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            
            // 6. 计算分页信息
            String newLastBatchId = null;
            Boolean isEnd = true;
            
            if (!items.isEmpty()) {
                // 获取最后一项的 batchId
                newLastBatchId = items.get(items.size() - 1).getBatchId();
                
                // 检查是否还有更多数据
                isEnd = !hasMoreItemsInRedis(userId, maxPageSize, newLastBatchId);
            }
            
            // 7. 构建响应
            RecycleBinBrowseResponse.PaginationInfo pagination = 
                new RecycleBinBrowseResponse.PaginationInfo(newLastBatchId, isEnd);
            
            log.info("[浏览回收站] 从 Redis 查询成功 - UserId: {}, Count: {}", userId, items.size());
            return new RecycleBinBrowseResponse(items, pagination);
            
        } catch (Exception e) {
            log.error("[浏览回收站] Redis 查询失败，降级到 MySQL - UserId: {}", userId, e);
            // 降级到 MySQL
            return browseFromMySQL(userId, maxPageSize, lastBatchId);
        }
    }
    
    /**
     * 从 MySQL 查询回收站列表（降级方案）
     */
    private RecycleBinBrowseResponse browseFromMySQL(Long userId, Integer maxPageSize, String lastBatchId) {
        log.info("[浏览回收站] 开始从 MySQL 查询 - UserId: {}, MaxPageSize: {}, LastBatchId: {}", 
            userId, maxPageSize, lastBatchId);
        
        // 【关键】检查 Redis 索引是否已建立完成
        boolean indexComplete = isIndexRebuildComplete(userId);
        
        if (indexComplete) {
            // 索引已建立完成，但 Redis 中没有数据，说明用户确实没有回收站项目
            log.info("[浏览回收站] Redis 索引已建立完成但无数据 - UserId: {}", userId);
            
            // 【关键】为该用户创建空的索引 Key（带 30 天 TTL）
            ensureRedisIndexExists(userId);
            
            return new RecycleBinBrowseResponse(new java.util.ArrayList<>(), 
                new RecycleBinBrowseResponse.PaginationInfo(null, true));
        }
        
        // 索引未建立完成，从 MySQL 查询
        log.info("[浏览回收站] Redis 索引未建立完成，从 MySQL 查询 - UserId: {}", userId);
        
        // 1. 查询回收站列表
        java.util.List<RecycleBinItemDTO> items = recycleBinTaskMapper.browseRecycleBin(
            userId, maxPageSize, lastBatchId
        );
        
        log.info("[浏览回收站] MySQL 查询结果 - UserId: {}, Count: {}", userId, 
            items != null ? items.size() : 0);
        
        // 2. 如果 MySQL 有数据，则回填 Redis 缓存
        if (items != null && !items.isEmpty()) {
            log.info("[Redis回填] 准备回填缓存 - UserId: {}, Count: {}", userId, items.size());
            warmupRedisCacheWithCheck(userId, items);
            log.info("[Redis回填] 回填完成 - UserId: {}", userId);
        }
        
        // 3. 计算分页信息
        String newLastBatchId = null;
        Boolean isEnd = true;
        
        if (!items.isEmpty()) {
            // 获取最后一项的 batchId
            newLastBatchId = items.get(items.size() - 1).getBatchId();
            
            // 检查是否还有更多数据
            isEnd = !hasMoreItems(userId, maxPageSize, newLastBatchId);
        }
        
        // 4. 构建响应
        RecycleBinBrowseResponse.PaginationInfo pagination = 
            new RecycleBinBrowseResponse.PaginationInfo(newLastBatchId, isEnd);
        
        log.info("[浏览回收站] 从 MySQL 查询成功 - UserId: {}, Count: {}", userId, items.size());
        return new RecycleBinBrowseResponse(items, pagination);
    }
    
    /**
     * 获取 batch 的 score（用于游标分页）
     */
    private Double getBatchScore(Long userId, String batchId) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            // 这里需要使用同步的 RedisCommands，但当前只有异步的
            // 暂时返回 null，让前端传入 lastBatchId 对应的 created_at 时间戳
            // TODO: 如果需要精确的游标，可以添加一个同步查询方法
            return null;
        } catch (Exception e) {
            log.error("[浏览回收站] 获取 batch score 失败 - UserId: {}, BatchId: {}", userId, batchId, e);
            return null;
        }
    }
    
    /**
     * 将 Redis Hash 信息转换为 DTO
     */
    private RecycleBinItemDTO convertToDTO(String batchId, java.util.Map<String, String> info) {
        RecycleBinItemDTO dto = new RecycleBinItemDTO();
        dto.setBatchId(batchId);
        
        // 解析基本信息
        try {
            if (info.containsKey("rootNodeId")) {
                dto.setId(Long.parseLong(info.get("rootNodeId")));
            }
            if (info.containsKey("name")) {
                dto.setName(info.get("name"));
            }
            if (info.containsKey("nodeType")) {
                Integer nodeType = Integer.parseInt(info.get("nodeType"));
                dto.setType(nodeType == 0 ? "folder" : "file");
            }
            if (info.containsKey("size")) {
                dto.setSize(Long.parseLong(info.get("size")));
            }
            if (info.containsKey("createdAt")) {
                long timestamp = Long.parseLong(info.get("createdAt"));
                dto.setCreatedAt(java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestamp),
                    java.time.ZoneId.systemDefault()
                ));
            }
            if (info.containsKey("deletedAt")) {
                long timestamp = Long.parseLong(info.get("deletedAt"));
                dto.setDeletedAt(java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestamp),
                    java.time.ZoneId.systemDefault()
                ));
            }
            if (info.containsKey("version")) {
                dto.setVersion(Long.parseLong(info.get("version")));
            }
            
        } catch (Exception e) {
            log.error("[浏览回收站] 转换 DTO 失败 - BatchId: {}", batchId, e);
        }
        
        return dto;
    }
    
    /**
     * 从 MySQL 获取单个 batch 的信息（当 Redis 缺失时）
     */
    private RecycleBinItemDTO getFromMySQL(String batchId) {
        try {
            java.util.List<RecycleBinItemDTO> items = recycleBinTaskMapper.browseRecycleBin(
                null, 1, batchId  // 这里需要特殊处理，暂时返回 null
            );
            return items != null && !items.isEmpty() ? items.get(0) : null;
        } catch (Exception e) {
            log.error("[浏览回收站] 从 MySQL 获取 batch 失败 - BatchId: {}", batchId, e);
            return null;
        }
    }
    
    /**
     * 检查 Redis 中是否还有更多数据
     */
    private boolean hasMoreItemsInRedis(Long userId, Integer maxPageSize, String lastBatchId) {
        try {
            // 【关键】使用 ZRANGEBYSCORE 查询 lastBatchId 之后的数据
            // 如果 lastBatchId 为 null，说明是第一页，不需要检查
            if (lastBatchId == null || lastBatchId.isEmpty()) {
                return false;
            }
            
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 获取 lastBatchId 的 score
            Double lastScore = recycleBinRedisService.getMemberScore(userBatchesKey, lastBatchId);
            
            if (lastScore == null) {
                log.warn("[浏览回收站] 无法获取 lastBatchId 的 score - UserId: {}, LastBatchId: {}", userId, lastBatchId);
                return false;
            }
            
            // 查询 score > lastScore 的第一个元素
            java.util.List<String> nextBatchIds = recycleBinRedisService.getBatchesAfterScore(
                userId, lastScore, 1
            ).join();
            
            boolean hasMore = nextBatchIds != null && !nextBatchIds.isEmpty();
            log.debug("[浏览回收站] 检查是否有更多数据 - UserId: {}, HasMore: {}", userId, hasMore);
            return hasMore;
            
        } catch (Exception e) {
            log.error("[浏览回收站] 检查更多数据失败 - UserId: {}", userId, e);
            return false;
        }
    }
    
    /**
     * 检查 MySQL 中是否还有更多数据
     */
    private boolean hasMoreItems(Long userId, Integer maxPageSize, String lastBatchId) {
        Boolean result = recycleBinTaskMapper.hasMoreItems(userId, maxPageSize, lastBatchId);
        return result != null && result;
    }
    
    /**
     * 回填 Redis 缓存（从 MySQL 查询后）
     * 
     * @param userId 用户ID
     * @param items 回收站项目列表
     */
    private void warmupRedisCache(Long userId, java.util.List<RecycleBinItemDTO> items) {
        try {
            log.info("[Redis回填] 开始回填缓存 - UserId: {}, Count: {}", userId, items.size());
            
            int successCount = 0;
            for (int i = 0; i < items.size(); i++) {
                RecycleBinItemDTO item = items.get(i);
                log.info("[Redis回填] 处理第 {}/{} 项 - BatchId: {}, Name: {}", 
                    i + 1, items.size(), item.getBatchId(), item.getName());
                
                // 1. 添加 batchId 到用户索引列表
                recycleBinRedisService.addBatchToUserList(userId, item.getBatchId(), item.getDeletedAt());
                
                // 2. 构建 batch 详细信息
                java.util.Map<String, String> info = new java.util.HashMap<>();
                info.put("rootNodeId", String.valueOf(item.getId()));
                info.put("nodeType", item.getType().equals("folder") ? "0" : "1");
                info.put("name", item.getName());
                info.put("size", String.valueOf(item.getSize()));
                info.put("batchId", item.getBatchId());
                
                // 时间戳转换
                if (item.getCreatedAt() != null) {
                    info.put("createdAt", String.valueOf(
                        item.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    ));
                }
                if (item.getDeletedAt() != null) {
                    info.put("deletedAt", String.valueOf(
                        item.getDeletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    ));
                }
                if (item.getVersion() != null) {
                    info.put("version", String.valueOf(item.getVersion()));
                }
                
                // 3. 缓存 batch 详细信息
                recycleBinRedisService.cacheBatchInfo(item.getBatchId(), info);
                successCount++;
            }
            
            log.info("[Redis回填] 所有项目处理完成 - UserId: {}, SuccessCount: {}/{}", 
                userId, successCount, items.size());
            
            // 4. 【关键】刷新用户索引的 TTL（重置为 30 天）
            refreshUserIndexTTL(userId);
            
            log.info("[Redis回填] 完成 - UserId: {}, Count: {}", userId, items.size());
            
        } catch (Exception e) {
            log.error("[Redis回填] 失败 - UserId: {}", userId, e);
            // 不抛出异常，避免影响主流程
        }
    }
    
    /**
     * 刷新用户索引的 TTL（重置为 30 天）
     * 
     * @param userId 用户ID
     */
    private void refreshUserIndexTTL(Long userId) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            log.info("[Redis TTL] 准备刷新用户索引 TTL - UserId: {}, Key: {}", userId, userBatchesKey);
            
            // 使用 EXPIRE 命令重置 TTL 为 30 天
            recycleBinRedisService.refreshKeyTTL(userBatchesKey);
            
            log.info("[Redis TTL] 刷新用户索引 TTL 完成 - UserId: {}", userId);
            
        } catch (Exception e) {
            log.error("[Redis TTL] 刷新用户索引 TTL 失败 - UserId: {}", userId, e);
        }
    }
    
    /**
     * 确保 Redis 索引存在（即使为空）
     * 如果索引不存在，则创建空的 ZSET 并设置 TTL
     * 如果索引已存在，则刷新 TTL
     * 
     * @param userId 用户ID
     */
    private void ensureRedisIndexExists(Long userId) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            log.info("[Redis索引] 检查索引是否存在 - UserId: {}, Key: {}", userId, userBatchesKey);
            
            // 无论索引是否存在，都刷新 TTL 为 30 天
            // 如果索引不存在，Redis 会创建它
            recycleBinRedisService.ensureIndexExists(userBatchesKey);
            
            log.info("[Redis索引] 索引检查完成 - UserId: {}", userId);
            
        } catch (Exception e) {
            log.error("[Redis索引] 检查索引失败 - UserId: {}", userId, e);
        }
    }
    
    /**
     * 检查 Redis 索引是否已建立完成
     * 通过检查是否有索引重建游标来判断
     * 
     * @param userId 用户ID
     * @return true=索引已建立完成，false=索引未建立完成
     */
    private boolean isIndexRebuildComplete(Long userId) {
        try {
            // 检查是否有索引重建游标
            java.util.Map<String, String> rebuildCursor = recycleBinRedisService.getIndexRebuildCursor();
            
            if (rebuildCursor == null || rebuildCursor.isEmpty()) {
                // 没有游标，说明索引重建已完成或从未开始
                log.debug("[索引检查] 无重建游标，索引已建立完成 - UserId: {}", userId);
                return true;
            }
            
            // 有游标，检查是否是当前用户的重建任务
            if (rebuildCursor.containsKey("lastUserId")) {
                Long cursorUserId = Long.parseLong(rebuildCursor.get("lastUserId"));
                if (cursorUserId.equals(userId)) {
                    // 是当前用户的重建任务，索引未完成
                    log.debug("[索引检查] 检测到当前用户的重建游标，索引未完成 - UserId: {}, LastTaskId: {}", 
                        userId, rebuildCursor.get("lastTaskId"));
                    return false;
                }
            }
            
            // 不是当前用户的重建任务，视为索引已完成
            log.debug("[索引检查] 非当前用户的重建游标，视为索引已完成 - UserId: {}", userId);
            return true;
            
        } catch (Exception e) {
            log.error("[索引检查] 检查索引状态失败 - UserId: {}", userId, e);
            // 出错时保守处理，视为索引未完成，从 MySQL 查询
            return false;
        }
    }
    
    /**
     * 回填 Redis 缓存（从 MySQL 查询后），先检查元数据是否存在
     * 
     * @param userId 用户ID
     * @param items 回收站项目列表
     */
    private void warmupRedisCacheWithCheck(Long userId, java.util.List<RecycleBinItemDTO> items) {
        try {
            log.info("[Redis回填] 开始回填缓存（带检查） - UserId: {}, Count: {}", userId, items.size());
            
            int successCount = 0;
            int skipCount = 0;
            
            for (int i = 0; i < items.size(); i++) {
                RecycleBinItemDTO item = items.get(i);
                String batchId = item.getBatchId();
                
                log.debug("[Redis回填] 处理第 {}/{} 项 - BatchId: {}, Name: {}", 
                    i + 1, items.size(), batchId, item.getName());
                
                // 【关键】先检查元数据是否已存在
                String metadataKey = "recycle:batch:" + batchId + ":info";
                Boolean metadataExists = recycleBinRedisService.exists(metadataKey);
                
                if (Boolean.TRUE.equals(metadataExists)) {
                    log.debug("[Redis回填] 元数据已存在，跳过 - BatchId: {}", batchId);
                    skipCount++;
                    continue;
                }
                
                // 元数据不存在，进行回填
                // 1. 添加 batchId 到用户索引列表
                recycleBinRedisService.addBatchToUserList(userId, batchId, item.getDeletedAt());
                
                // 2. 构建 batch 详细信息
                java.util.Map<String, String> info = new java.util.HashMap<>();
                info.put("rootNodeId", String.valueOf(item.getId()));
                info.put("nodeType", item.getType().equals("folder") ? "0" : "1");
                info.put("name", item.getName());
                info.put("size", String.valueOf(item.getSize()));
                info.put("batchId", batchId);
                
                // 时间戳转换
                if (item.getCreatedAt() != null) {
                    info.put("createdAt", String.valueOf(
                        item.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    ));
                }
                if (item.getDeletedAt() != null) {
                    info.put("deletedAt", String.valueOf(
                        item.getDeletedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    ));
                }
                if (item.getVersion() != null) {
                    info.put("version", String.valueOf(item.getVersion()));
                }
                
                // 3. 缓存 batch 详细信息
                recycleBinRedisService.cacheBatchInfo(batchId, info);
                successCount++;
                
                log.debug("[Redis回填] 回填成功 - BatchId: {}", batchId);
            }
            
            log.info("[Redis回填] 所有项目处理完成 - UserId: {}, SuccessCount: {}, SkipCount: {}, Total: {}", 
                userId, successCount, skipCount, items.size());
            
            // 4. 【关键】刷新用户索引的 TTL（重置为 30 天）
            refreshUserIndexTTL(userId);
            
            log.info("[Redis回填] 完成 - UserId: {}, Count: {}", userId, items.size());
            
        } catch (Exception e) {
            log.error("[Redis回填] 失败 - UserId: {}", userId, e);
            // 不抛出异常，避免影响主流程
        }
    }
}
