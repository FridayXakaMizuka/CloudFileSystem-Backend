package com.mizuka.cloudfilesystem.service;

import io.lettuce.core.api.async.RedisAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 回收站Redis服务
 * 负责管理batchId相关的ZSET存储、根目录信息和游标
 * 
 * Redis键设计：
 * - recycle:batch:{batchId}:nodes - ZSET存储所有删除的节点ID，score为时间戳序号
 * - recycle:batch:{batchId}:root - Hash存储根目录信息（O(1)查询）
 * - recycle:batch:{batchId}:cursor - String存储当前处理的游标位置（用于断点续传）
 */
@Service
public class RecycleBinRedisService {
    
    private static final Logger log = LoggerFactory.getLogger(RecycleBinRedisService.class);
    
    // Redis键前缀
    private static final String BATCH_NODES_PREFIX = "recycle:batch:";
    private static final String BATCH_ROOT_SUFFIX = ":root";
    private static final String BATCH_CURSOR_SUFFIX = ":cursor";
    
    // ZSET过期时间：30天（与回收站保留时间一致）
    private static final long EXPIRE_SECONDS = 30 * 24 * 60 * 60;
    
    private final RedisAsyncCommands<String, String> deleteRedisCommands;
    
    public RecycleBinRedisService(
            @Qualifier("deleteRedisCommands") RedisAsyncCommands<String, String> deleteRedisCommands) {
        this.deleteRedisCommands = deleteRedisCommands;
    }
    
    /**
     * 初始化batchId的Redis存储结构
     * 在删除操作开始时调用
     * 
     * @param batchId 批次号
     * @param rootNodeId 根节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @param userId 用户ID
     */
    public void initializeBatch(String batchId, Long rootNodeId, Integer nodeType, Long userId) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            String rootKey = BATCH_NODES_PREFIX + batchId + BATCH_ROOT_SUFFIX;
            String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 1. 创建空的ZSET（用于存储所有删除的节点）
            // 【关键】添加根节点时使用 {nodeType}:{nodeId} 格式，score使用当前时间戳
            long timestamp = System.currentTimeMillis();
            String rootMember = nodeType + ":" + rootNodeId;  // 例如："0:12345" 或 "1:67890"
            deleteRedisCommands.zadd(nodesKey, timestamp, rootMember);
            
            // 2. 存储根目录信息到Hash（O(1)查询）
            Map<String, String> rootInfo = new HashMap<>();
            rootInfo.put("rootNodeId", String.valueOf(rootNodeId));
            rootInfo.put("nodeType", String.valueOf(nodeType));
            rootInfo.put("userId", String.valueOf(userId));
            rootInfo.put("createdAt", String.valueOf(timestamp));
            
            deleteRedisCommands.hset(rootKey, rootInfo);
            
            // 3. 【关键】所有 Key 设置相同的过期时间（30天），确保同步过期触发彻底删除
            deleteRedisCommands.expire(nodesKey, EXPIRE_SECONDS);
            deleteRedisCommands.expire(rootKey, EXPIRE_SECONDS);
            deleteRedisCommands.expire(infoKey, EXPIRE_SECONDS);
            deleteRedisCommands.expire(userBatchesKey, EXPIRE_SECONDS);
            
            log.info("[Redis] 初始化batch成功 - BatchId: {}, RootNodeId: {}, NodeType: {}, TTL: {}s", 
                batchId, rootNodeId, nodeType, EXPIRE_SECONDS);
            
        } catch (Exception e) {
            log.error("[Redis] 初始化batch失败 - BatchId: {}", batchId, e);
            // 不抛出异常，避免影响主流程
        }
    }
    
    /**
     * 添加节点到ZSET集合
     * 在异步删除子节点时调用
     * 
     * @param batchId 批次号
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @return CompletableFuture用于异步追踪
     */
    public CompletableFuture<Long> addNodeToBatch(String batchId, Long nodeId, Integer nodeType) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            // 【关键】使用 {nodeType}:{nodeId} 格式作为 ZSET member
            // 例如："0:12345"（文件夹）或 "1:67890"（文件）
            String member = nodeType + ":" + nodeId;
            
            // 使用时间戳作为score，保证有序
            long timestamp = System.currentTimeMillis();
            
            CompletableFuture<Long> future = deleteRedisCommands.zadd(nodesKey, timestamp, member)
                .toCompletableFuture();
            
            return future.thenApply(added -> {
                    log.debug("[Redis] 添加节点到batch - BatchId: {}, NodeType: {}, NodeId: {}", 
                        batchId, nodeType, nodeId);
                    return added;
                })
                .exceptionally(ex -> {
                    log.error("[Redis] 添加节点失败 - BatchId: {}, NodeType: {}, NodeId: {}", 
                        batchId, nodeType, nodeId, ex);
                    return 0L;
                });
                
        } catch (Exception e) {
            log.error("[Redis] 添加节点异常 - BatchId: {}, NodeType: {}, NodeId: {}", 
                batchId, nodeType, nodeId, e);
            return CompletableFuture.completedFuture(0L);
        }
    }
    
    /**
     * 批量添加节点到ZSET集合
     * 
     * @param batchId 批次号
     * @param nodeIds 节点ID列表
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @return CompletableFuture
     */
    public CompletableFuture<Long> addNodesToBatch(String batchId, java.util.List<Long> nodeIds, Integer nodeType) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            // 批量添加，每个节点使用递增的时间戳
            long baseTimestamp = System.currentTimeMillis();
            long count = 0;
            
            for (int i = 0; i < nodeIds.size(); i++) {
                long timestamp = baseTimestamp + i; // 保证顺序
                // 【关键】使用 {nodeType}:{nodeId} 格式
                String member = nodeType + ":" + nodeIds.get(i);
                deleteRedisCommands.zadd(nodesKey, timestamp, member);
                count++;
            }
            
            log.debug("[Redis] 批量添加节点到batch - BatchId: {}, NodeType: {}, Count: {}", 
                batchId, nodeType, count);
            return CompletableFuture.completedFuture(count);
            
        } catch (Exception e) {
            log.error("[Redis] 批量添加节点失败 - BatchId: {}, NodeType: {}", batchId, nodeType, e);
            return CompletableFuture.completedFuture(0L);
        }
    }
    
    /**
     * O(1)时间获取根目录信息
     * 
     * @param batchId 批次号
     * @return 根目录信息Map，包含rootNodeId、nodeType、userId等
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, String>> getRootInfo(String batchId) {
        try {
            String rootKey = BATCH_NODES_PREFIX + batchId + BATCH_ROOT_SUFFIX;
            
            return deleteRedisCommands.hgetall(rootKey)
                .toCompletableFuture()
                .thenApply(map -> {
                    if (map == null || map.isEmpty()) {
                        log.warn("[Redis] 未找到根目录信息 - BatchId: {}", batchId);
                        return new HashMap<String, String>();
                    }
                    Map<String, String> result = new HashMap<>();
                    map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
                    return result;
                })
                .exceptionally(ex -> {
                    log.error("[Redis] 获取根目录信息失败 - BatchId: {}", batchId, ex);
                    return new HashMap<>();
                });
                
        } catch (Exception e) {
            log.error("[Redis] 获取根目录信息异常 - BatchId: {}", batchId, e);
            return CompletableFuture.completedFuture(new HashMap<>());
        }
    }
    
    /**
     * 获取batch元数据信息（新架构）
     * 从 recycle:batch:{batchId}:info Hash 中获取
     * 
     * @param batchId 批次号
     * @return batch元数据Map，包含rootNodeId、nodeType、userId等
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getBatchInfo(String batchId) {
        try {
            String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
            
            // 同步获取Hash所有字段
            Map<String, String> result = deleteRedisCommands.hgetall(infoKey)
                .toCompletableFuture()
                .join();
            
            if (result == null || result.isEmpty()) {
                log.warn("[Redis] 未找到batch元数据 - BatchId: {}", batchId);
                return new HashMap<>();
            }
            
            Map<String, String> convertedResult = new HashMap<>();
            result.forEach((k, v) -> convertedResult.put(String.valueOf(k), String.valueOf(v)));
            
            log.debug("[Redis] 获取batch元数据成功 - BatchId: {}, Fields: {}", batchId, convertedResult.size());
            return convertedResult;
            
        } catch (Exception e) {
            log.error("[Redis] 获取batch元数据失败 - BatchId: {}", batchId, e);
            return new HashMap<>();
        }
    }
    
    /**
     * 将文件夹节点添加到数据层 ZSET（只存文件夹ID，无需 nodeType 前缀）
     * 
     * @param batchId 批次号
     * @param folderId 文件夹ID
     * @param score 分数（BFS遍历顺序）
     */
    public void addFolderToBatch(String batchId, Long folderId, double score) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            // 同步等待 ZADD 完成
            deleteRedisCommands.zadd(nodesKey, score, String.valueOf(folderId))
                .toCompletableFuture()
                .join();
            
            log.debug("[Redis] 添加文件夹到批处理 - BatchId: {}, FolderId: {}, Score: {}", batchId, folderId, score);
            
        } catch (Exception e) {
            log.error("[Redis] 添加文件夹失败 - BatchId: {}, FolderId: {}", batchId, folderId, e);
        }
    }
    
    /**
     * 从数据层 ZSET 中弹出 score 最大的 N 个成员（用于彻底删除的栈操作）
     * 【兼容方案】使用 ZREVRANGE + ZREM 替代 ZPOPMAX，支持 Redis < 5.0
     * 
     * @param batchId 批次号
     * @param count 弹出数量
     * @return 弹出的成员集合（文件夹ID字符串）
     */
    @SuppressWarnings("unchecked")
    public Set<String> popMaxFromBatch(String batchId, int count) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            // 【兼容方案】先获取 score 最大的 count 个成员（不删除）
            List<String> members = deleteRedisCommands.zrevrange(nodesKey, 0, count - 1)
                .toCompletableFuture()
                .join();
            
            if (members == null || members.isEmpty()) {
                return new java.util.HashSet<>();
            }
            
            // 【关键】从 ZSET 中删除这些成员
            // zrem 接受可变参数，需要将 List 转换为数组
            String[] membersArray = members.toArray(new String[0]);
            if (membersArray.length > 0) {
                Long removedCount = deleteRedisCommands.zrem(nodesKey, membersArray)
                    .toCompletableFuture()
                    .join();
                log.debug("[Redis] 已删除 {} 个成员", removedCount);
            }
            
            Set<String> result = new java.util.HashSet<>(members);
            
            log.debug("[Redis] 弹栈成功 - BatchId: {}, Count: {}", batchId, result.size());
            return result;
            
        } catch (Exception e) {
            log.error("[Redis] 弹栈失败 - BatchId: {}", batchId, e);
            return new java.util.HashSet<>();
        }
    }
    
    /**
     * 获取根节点ID（快捷方法）
     * 
     * @param batchId 批次号
     * @return 根节点ID，如果不存在返回null
     */
    public CompletableFuture<Long> getRootNodeId(String batchId) {
        return getRootInfo(batchId).thenApply(rootInfo -> {
            String rootNodeIdStr = rootInfo.get("rootNodeId");
            if (rootNodeIdStr != null) {
                try {
                    return Long.parseLong(rootNodeIdStr);
                } catch (NumberFormatException e) {
                    log.error("[Redis] 解析根节点ID失败 - BatchId: {}", batchId, e);
                }
            }
            return null;
        });
    }
    
    /**
     * 更新游标位置（用于断点续传）
     * 
     * @param batchId 批次号
     * @param lastProcessedNodeId 最后处理的节点ID
     */
    public void updateCursor(String batchId, Long lastProcessedNodeId) {
        try {
            String cursorKey = BATCH_NODES_PREFIX + batchId + BATCH_CURSOR_SUFFIX;
            
            deleteRedisCommands.set(cursorKey, String.valueOf(lastProcessedNodeId));
            deleteRedisCommands.expire(cursorKey, EXPIRE_SECONDS);
            
            log.debug("[Redis] 更新游标 - BatchId: {}, LastNodeId: {}", batchId, lastProcessedNodeId);
            
        } catch (Exception e) {
            log.error("[Redis] 更新游标失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 获取游标位置
     * 
     * @param batchId 批次号
     * @return 最后处理的节点ID，如果没有游标返回null
     */
    public CompletableFuture<Long> getCursor(String batchId) {
        try {
            String cursorKey = BATCH_NODES_PREFIX + batchId + BATCH_CURSOR_SUFFIX;
            
            CompletableFuture<String> stage = deleteRedisCommands.get(cursorKey)
                .toCompletableFuture();
            
            return stage.thenApply(cursorValue -> {
                    if (cursorValue != null) {
                        try {
                            return Long.parseLong(cursorValue);
                        } catch (NumberFormatException e) {
                            log.error("[Redis] 解析游标失败 - BatchId: {}", batchId, e);
                        }
                    }
                    return null;
                })
                .exceptionally(ex -> {
                    log.error("[Redis] 获取游标失败 - BatchId: {}", batchId, ex);
                    return null;
                });
                
        } catch (Exception e) {
            log.error("[Redis] 获取游标异常 - BatchId: {}", batchId, e);
            return CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * 获取ZSET中节点总数
     * 
     * @param batchId 批次号
     * @return 节点总数
     */
    public CompletableFuture<Long> getNodeCount(String batchId) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            return deleteRedisCommands.zcard(nodesKey)
                .toCompletableFuture()
                .exceptionally(ex -> {
                    log.error("[Redis] 获取节点数失败 - BatchId: {}", batchId, ex);
                    return 0L;
                });
                
        } catch (Exception e) {
            log.error("[Redis] 获取节点数异常 - BatchId: {}", batchId, e);
            return CompletableFuture.completedFuture(0L);
        }
    }
    
    /**
     * 根据游标范围查询节点（支持分页和断点续传）
     * 
     * @param batchId 批次号
     * @param minScore 最小score（不包含），传null表示从头开始
     * @param maxScore 最大score（包含），传null表示到末尾
     * @param offset 偏移量
     * @param count 数量限制
     * @return 节点ID列表
     */
    public CompletableFuture<java.util.List<String>> getNodesByRange(
            String batchId, Double minScore, Double maxScore, long offset, long count) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            // 使用zrangebyscore进行范围查询
            return deleteRedisCommands.zrangebyscore(nodesKey, 
                    minScore != null ? String.valueOf(minScore) : "-inf",
                    maxScore != null ? String.valueOf(maxScore) : "+inf",
                    offset, count)
                .toCompletableFuture()
                .exceptionally(ex -> {
                    log.error("[Redis] 范围查询节点失败 - BatchId: {}", batchId, ex);
                    return java.util.Collections.emptyList();
                });
                
        } catch (Exception e) {
            log.error("[Redis] 范围查询节点异常 - BatchId: {}", batchId, e);
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }
    
    /**
     * 清理batch相关的所有Redis数据
     * 在操作完成或失败时调用
     * 
     * @param batchId 批次号
     */
    /**
     * 清理 batch 的 Redis 缓存（恢复或彻底删除完成后调用）
     * 主动删除数据层和元数据层的 Key，及时释放内存
     * 
     * @param batchId 批次号
     */
    public void cleanupBatch(String batchId) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            String rootKey = BATCH_NODES_PREFIX + batchId + BATCH_ROOT_SUFFIX;
            String cursorKey = BATCH_NODES_PREFIX + batchId + BATCH_CURSOR_SUFFIX;
            String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
            
            // 【关键】先获取 userId，用于从用户索引中移除 batchId
            java.util.Map<String, String> info = deleteRedisCommands.hgetall(infoKey)
                .toCompletableFuture()
                .join();
            
            Long userId = null;
            if (info != null && info.containsKey("userId")) {
                try {
                    userId = Long.parseLong(info.get("userId"));
                } catch (NumberFormatException e) {
                    log.warn("[Redis] 解析 userId 失败 - BatchId: {}", batchId);
                }
            }
            
            // 【关键】同步等待删除完成，确保立即释放内存
            deleteRedisCommands.del(nodesKey, rootKey, cursorKey, infoKey)
                .toCompletableFuture()
                .join();
            
            // 【关键】从用户索引 ZSET 中移除该 batchId
            if (userId != null) {
                String userBatchesKey = "recycle:user:" + userId + ":batches";
                deleteRedisCommands.zrem(userBatchesKey, batchId)
                    .toCompletableFuture()
                    .join();
                log.info("[Redis] 从用户索引中移除 batchId - UserId: {}, BatchId: {}", userId, batchId);
                
                // 【关键】检查 ZSET 中是否还有真实的 batchId（排除标记成员）
                java.util.List<String> allMembers = deleteRedisCommands.zrange(userBatchesKey, 0, -1)
                    .toCompletableFuture()
                    .join();
                
                boolean hasRealBatches = false;
                if (allMembers != null && !allMembers.isEmpty()) {
                    for (Object member : allMembers) {
                        String memberStr = String.valueOf(member);
                        if (!memberStr.startsWith("__index_placeholder__") && 
                            !memberStr.startsWith("_index_marker_") && 
                            !memberStr.startsWith("_marker_")) {
                            hasRealBatches = true;
                            break;
                        }
                    }
                }
                
                // 如果没有真实的 batchId，确保标记成员存在并刷新 TTL
                if (!hasRealBatches) {
                    String markerMember = "__index_placeholder__";
                    double markerScore = 0.0;
                    
                    // 添加标记成员（如果已存在则不会重复添加）
                    deleteRedisCommands.zadd(userBatchesKey, markerScore, markerMember)
                        .toCompletableFuture()
                        .join();
                    
                    // 刷新 TTL
                    deleteRedisCommands.expire(userBatchesKey, EXPIRE_SECONDS)
                        .toCompletableFuture()
                        .join();
                    
                    log.info("[Redis] 回收站为空，保留标记成员并刷新 TTL - UserId: {}", userId);
                }
            }
            
            log.info("[Redis] 主动销毁数据层和元数据层 - BatchId: {}", batchId);
            
        } catch (Exception e) {
            log.error("[Redis] 清理batch数据失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 获取batch中的所有节点（按score排序）
     * 
     * @param batchId 批次号
     * @return 节点Member列表（格式：{nodeType}:{nodeId}）
     */
    public CompletableFuture<Set<String>> getAllNodesFromBatch(String batchId) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            // 使用zrange获取所有节点（按score升序）
            CompletableFuture<Set<String>> future = deleteRedisCommands.zrange(nodesKey, 0, -1)
                .toCompletableFuture()
                .thenApply(members -> {
                    Set<String> resultSet = new java.util.HashSet<>();
                    if (members != null && !members.isEmpty()) {
                        for (Object member : members) {
                            resultSet.add(String.valueOf(member));
                        }
                    }
                    return resultSet;
                });
            
            return future.exceptionally(ex -> {
                log.error("[Redis] 获取所有节点失败 - BatchId: {}", batchId, ex);
                return java.util.Collections.emptySet();
            });
                
        } catch (Exception e) {
            log.error("[Redis] 获取所有节点异常 - BatchId: {}", batchId, e);
            return CompletableFuture.completedFuture(java.util.Collections.emptySet());
        }
    }
    
    /**
     * 从batch中移除指定节点
     * 
     * @param batchId 批次号
     * @param member 节点Member（格式：{nodeType}:{nodeId}）
     * @return CompletableFuture
     */
    public CompletableFuture<Long> removeNodeFromBatch(String batchId, String member) {
        try {
            String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
            
            return deleteRedisCommands.zrem(nodesKey, member)
                .toCompletableFuture()
                .thenApply(removed -> {
                    log.debug("[Redis] 移除节点成功 - BatchId: {}, Member: {}", batchId, member);
                    return removed;
                })
                .exceptionally(ex -> {
                    log.error("[Redis] 移除节点失败 - BatchId: {}, Member: {}", batchId, member, ex);
                    return 0L;
                });
                
        } catch (Exception e) {
            log.error("[Redis] 移除节点异常 - BatchId: {}, Member: {}", batchId, member, e);
            return CompletableFuture.completedFuture(0L);
        }
    }
    
    /**
     * 获取用户的回收站批次列表（从索引层ZSET查询）
     * 
     * @param userId 用户ID
     * @param maxPageSize 每页数量
     * @param lastScore 游标锚点（上一批最后一条的score），null表示从头开始
     * @return batchId列表（按删除时间倒序）
     */
    public CompletableFuture<java.util.List<String>> getUserBatches(Long userId, Integer maxPageSize, Double lastScore) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 使用 ZREVRANGEBYSCORE 按 score 降序查询
            // lastScore 为 null 时，从 +inf 开始；否则从 lastScore 开始（不包含）
            String min = "-inf";
            String max = (lastScore == null) ? "+inf" : String.valueOf(lastScore);
            
            // 如果 lastScore 不为 null，需要排除等于 lastScore 的那一条（游标分页特性）
            long offset = (lastScore != null) ? 1 : 0;
            
            return deleteRedisCommands.zrevrangebyscore(userBatchesKey, max, min, offset, maxPageSize)
                .toCompletableFuture()
                .thenCompose(batchIds -> {
                    // 【关键】查询成功后，异步重置索引层 Key 的 TTL
                    return deleteRedisCommands.expire(userBatchesKey, EXPIRE_SECONDS)
                        .toCompletableFuture()
                        .thenApply(expired -> {
                            java.util.List<String> result = new java.util.ArrayList<>();
                            if (batchIds != null && !batchIds.isEmpty()) {
                                for (Object batchId : batchIds) {
                                    String batchIdStr = String.valueOf(batchId);
                                    // 【关键】过滤掉标记成员（如果存在）
                                    if (!batchIdStr.startsWith("__index_placeholder__") && 
                                        !batchIdStr.startsWith("_index_marker_") && 
                                        !batchIdStr.startsWith("_marker_")) {
                                        result.add(batchIdStr);
                                    }
                                }
                            }
                            log.info("[Redis] 获取用户批次列表 - UserId: {}, Count: {}", userId, result.size());
                            return result;
                        });
                })
                .exceptionally(ex -> {
                    log.error("[Redis] 获取用户批次列表失败 - UserId: {}", userId, ex);
                    return java.util.Collections.emptyList();
                });
                
        } catch (Exception e) {
            log.error("[Redis] 获取用户批次列表异常 - UserId: {}", userId, e);
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }
    
    /**
     * 批量获取batch的详细信息（从元数据层Hash查询）
     * 
     * @param batchIds batchId列表
     * @return batch详细信息Map（key=batchId, value=info Hash）
     */
    public CompletableFuture<java.util.Map<String, java.util.Map<String, String>>> getBatchInfos(java.util.List<String> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return CompletableFuture.completedFuture(new java.util.HashMap<>());
        }
        
        try {
            java.util.Map<String, CompletableFuture<java.util.Map<String, String>>> futures = new java.util.HashMap<>();
            
            for (String batchId : batchIds) {
                String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
                futures.put(batchId, deleteRedisCommands.hgetall(infoKey).toCompletableFuture()
                    .thenApply(map -> {
                        if (map == null || map.isEmpty()) {
                            return new java.util.HashMap<String, String>();
                        }
                        java.util.Map<String, String> result = new java.util.HashMap<>();
                        map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
                        return result;
                    })
                    .exceptionally(ex -> {
                        log.warn("[Redis] 获取batch信息失败 - BatchId: {}", batchId, ex);
                        return new java.util.HashMap<String, String>();
                    }));
            }
            
            // 等待所有异步操作完成
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0])
            );
            
            return allFutures.thenApply(v -> {
                java.util.Map<String, java.util.Map<String, String>> result = new java.util.HashMap<>();
                futures.forEach((batchId, future) -> {
                    try {
                        result.put(batchId, future.join());
                    } catch (Exception e) {
                        log.warn("[Redis] 获取batch信息异常 - BatchId: {}", batchId, e);
                    }
                });
                log.debug("[Redis] 批量获取batch信息 - Count: {}", result.size());
                return result;
            });
            
        } catch (Exception e) {
            log.error("[Redis] 批量获取batch信息异常", e);
            return CompletableFuture.completedFuture(new java.util.HashMap<>());
        }
    }
    
    /**
     * 添加batchId到用户索引列表
     * 
     * @param userId 用户ID
     * @param batchId 批次号
     * @param deletedAt 删除时间（用于计算 score）
     */
    public void addBatchToUserList(Long userId, String batchId, java.time.LocalDateTime deletedAt) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 使用 deleted_at 的时间戳作为 score（如果 deletedAt 为 null，则使用当前时间）
            long score;
            if (deletedAt != null) {
                score = deletedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } else {
                score = System.currentTimeMillis();
            }
            
            // 【关键】同步等待 ZADD 完成
            deleteRedisCommands.zadd(userBatchesKey, score, batchId)
                .toCompletableFuture()
                .join();  // 阻塞等待完成
            
            // 【关键】同步等待 EXPIRE 完成
            deleteRedisCommands.expire(userBatchesKey, EXPIRE_SECONDS)
                .toCompletableFuture()
                .join();  // 阻塞等待完成
            
            log.info("[Redis] 添加batch到用户列表 - UserId: {}, BatchId: {}, Score: {}", userId, batchId, score);
            
        } catch (Exception e) {
            log.error("[Redis] 添加batch到用户列表失败 - UserId: {}, BatchId: {}", userId, batchId, e);
        }
    }
    
    /**
     * 缓存batch的详细信息
     * 
     * @param batchId 批次号
     * @param info 详细信息Map
     */
    public void cacheBatchInfo(String batchId, java.util.Map<String, String> info) {
        try {
            String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
            
            log.info("[Redis-debug] cacheBatchInfo被调用 - BatchId: {}, Info是否为null: {}, Info大小: {}", 
                batchId, info == null, info != null ? info.size() : 0);
            
            if (info != null && !info.isEmpty()) {
                // 【关键】打印所有字段
                log.info("[Redis-debug] 即将保存的字段 - Keys: {}, Values: {}", info.keySet(), info.values());
                
                // 【关键】使用 HMSET 命令批量设置 Hash 字段
                deleteRedisCommands.hmset(infoKey, info)
                    .toCompletableFuture()
                    .join();  // 阻塞等待完成
                
                log.info("[Redis-debug] HMSET执行完成");
                
                // 【关键】同步等待 EXPIRE 完成
                deleteRedisCommands.expire(infoKey, EXPIRE_SECONDS)
                    .toCompletableFuture()
                    .join();  // 阻塞等待完成
                
                log.info("[Redis-debug] EXPIRE执行完成");
                
                log.info("[Redis] 缓存batch信息 - BatchId: {}, Fields: {}", batchId, info.size());
                
                // 【验证】立即读取并验证
                Map<String, String> verifyData = deleteRedisCommands.hgetall(infoKey)
                    .toCompletableFuture()
                    .join();
                log.info("[Redis-debug] 验证读取 - BatchId: {}, 读取到字段数: {}, Keys: {}", 
                    batchId, verifyData != null ? verifyData.size() : 0, 
                    verifyData != null ? verifyData.keySet() : "null");
            } else {
                log.error("[Redis-debug] info为null或empty!");
            }
            
        } catch (Exception e) {
            log.error("[Redis] 缓存batch信息失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 从用户列表中移除batchId
     * 
     * @param userId 用户ID
     * @param batchId 批次号
     */
    public void removeBatchFromUserList(Long userId, String batchId) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 【关键】同步等待 ZREM 完成
            deleteRedisCommands.zrem(userBatchesKey, batchId)
                .toCompletableFuture()
                .join();
            
            log.info("[Redis] 从用户列表移除batch - UserId: {}, BatchId: {}", userId, batchId);
            
        } catch (Exception e) {
            log.error("[Redis] 从用户列表移除batch失败 - UserId: {}, BatchId: {}", userId, batchId, e);
        }
    }
    
    /**
     * 刷新 Key 的 TTL（重置为 30 天）
     * 
     * @param key Redis Key
     */
    public void refreshKeyTTL(String key) {
        try {
            // 【关键】同步等待 EXPIRE 完成
            deleteRedisCommands.expire(key, EXPIRE_SECONDS)
                .toCompletableFuture()
                .join();  // 阻塞等待完成
            
            log.info("[Redis] 刷新 Key TTL - Key: {}", key);
            
        } catch (Exception e) {
            log.error("[Redis] 刷新 Key TTL 失败 - Key: {}", key, e);
        }
    }
    
    /**
     * 确保索引存在（即使为空）
     * 如果 Key 已存在，则刷新 TTL
     * 如果 Key 不存在，创建空的 ZSET 并设置 TTL（使用标记成员防止自动删除）
     * 
     * @param key Redis Key（用户索引 Key）
     */
    public void ensureIndexExists(String key) {
        try {
            // 1. 先检查 Key 是否存在
            Long exists = deleteRedisCommands.exists(key)
                .toCompletableFuture()
                .join();
            
            if (exists != null && exists > 0) {
                // Key 已存在，只需刷新 TTL
                deleteRedisCommands.expire(key, EXPIRE_SECONDS)
                    .toCompletableFuture()
                    .join();
                
                log.info("[Redis] 索引已存在，刷新 TTL - Key: {}", key);
            } else {
                // 【关键】Key 不存在，创建空的 ZSET 并设置 TTL
                // 使用特殊标记成员 "__index_placeholder__" 来保持 ZSET 存在
                // 这个标记成员的 score 设为 0，不会影响正常的游标分页
                String markerMember = "__index_placeholder__";
                double markerScore = 0.0;
                
                deleteRedisCommands.zadd(key, markerScore, markerMember)
                    .toCompletableFuture()
                    .join();
                
                // 设置过期时间
                deleteRedisCommands.expire(key, EXPIRE_SECONDS)
                    .toCompletableFuture()
                    .join();
                
                log.info("[Redis] 创建空索引并设置 TTL（含标记成员）- Key: {}, Marker: {}", key, markerMember);
            }
            
        } catch (Exception e) {
            log.error("[Redis] 确保索引存在失败 - Key: {}", key, e);
        }
    }
    
    /**
     * 检查Key是否存在
     * 
     * @param key Redis Key
     * @return 是否存在
     */
    public Boolean exists(String key) {
        try {
            Long result = deleteRedisCommands.exists(key)
                .toCompletableFuture()
                .join();
            return result != null && result > 0;
        } catch (Exception e) {
            log.error("[Redis] 检查Key存在性失败 - Key: {}", key, e);
            return false;
        }
    }
    
    /**
     * 创建用户索引（ZSET）
     * 
     * @param userId 用户ID
     */
    public void createUserIndex(Long userId) {
        try {
            String userIndexKey = "recycle:user:" + userId + ":batches";
            
            // 【修复】直接设置过期时间，不添加/移除成员
            // Redis 允许对不存在的 key 设置过期时间，当添加第一个成员时会自动创建 ZSET
            deleteRedisCommands.expire(userIndexKey, EXPIRE_SECONDS)
                .toCompletableFuture()
                .join();
            
            log.info("[Redis] 用户索引已创建 - UserId: {}, Key: {}", userId, userIndexKey);
            
        } catch (Exception e) {
            log.error("[Redis] 创建用户索引失败 - UserId: {}", userId, e);
        }
    }
    
    /**
     * 保存batch元数据
     * 
     * @param batchId 批次号
     * @param metadata 元数据Map
     */
    public void saveBatchMetadata(String batchId, Map<String, String> metadata) {
        try {
            String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
            
            // 【修复】使用 hmset 命令批量设置 Hash 字段
            deleteRedisCommands.hmset(infoKey, metadata)
                .toCompletableFuture()
                .join();
            
            // 设置过期时间
            deleteRedisCommands.expire(infoKey, EXPIRE_SECONDS)
                .toCompletableFuture()
                .join();
            
            log.info("[Redis] batch元数据已保存 - BatchId: {}", batchId);
            
        } catch (Exception e) {
            log.error("[Redis] 保存batch元数据失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 保存游标数据（用于彻底删除断点续传）
     * 
     * @param batchId 批次号
     * @param cursorData 游标数据Map，包含cursorNodeId、cursorNodeType、cursorParentId
     */
    public void saveCursorData(String batchId, Map<String, Object> cursorData) {
        try {
            String cursorKey = BATCH_NODES_PREFIX + batchId + ":cursor_data";
            
            Map<String, String> stringData = new HashMap<>();
            cursorData.forEach((k, v) -> stringData.put(k, String.valueOf(v)));
            
            // 【修复】使用 hmset 命令批量设置 Hash 字段
            deleteRedisCommands.hmset(cursorKey, stringData)
                .toCompletableFuture()
                .join();
            
            // 设置过期时间
            deleteRedisCommands.expire(cursorKey, EXPIRE_SECONDS)
                .toCompletableFuture()
                .join();
            
            log.debug("[Redis] 游标数据已保存 - BatchId: {}, Data: {}", batchId, cursorData);
            
        } catch (Exception e) {
            log.error("[Redis] 保存游标数据失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 获取游标数据（用于彻底删除断点续传）
     * 
     * @param batchId 批次号
     * @return 游标数据Map，包含cursorNodeId、cursorNodeType、cursorParentId
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getCursorData(String batchId) {
        try {
            String cursorKey = BATCH_NODES_PREFIX + batchId + ":cursor_data";
            
            Map<String, String> result = deleteRedisCommands.hgetall(cursorKey)
                .toCompletableFuture()
                .join();
            
            if (result == null || result.isEmpty()) {
                log.debug("[Redis] 未找到游标数据 - BatchId: {}", batchId);
                return new HashMap<>();
            }
            
            Map<String, String> convertedResult = new HashMap<>();
            result.forEach((k, v) -> convertedResult.put(String.valueOf(k), String.valueOf(v)));
            
            log.debug("[Redis] 获取游标数据成功 - BatchId: {}, Data: {}", batchId, convertedResult);
            return convertedResult;
            
        } catch (Exception e) {
            log.error("[Redis] 获取游标数据失败 - BatchId: {}", batchId, e);
            return new HashMap<>();
        }
    }
    
    /**
     * 清除游标数据
     * 
     * @param batchId 批次号
     */
    public void clearCursorData(String batchId) {
        try {
            String cursorKey = BATCH_NODES_PREFIX + batchId + ":cursor_data";
            
            deleteRedisCommands.del(cursorKey)
                .toCompletableFuture()
                .join();
            
            log.info("[Redis] 游标数据已清除 - BatchId: {}", batchId);
            
        } catch (Exception e) {
            log.error("[Redis] 清除游标数据失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 保存索引层游标数据（用于重建索引时的断点续传）
     * Key: recycle:index_rebuild:cursor
     * 
     * @param cursorData 游标数据Map，包含lastTaskId、lastBatchId、lastUserId等
     */
    public void saveIndexRebuildCursor(Map<String, Object> cursorData) {
        try {
            String cursorKey = "recycle:index_rebuild:cursor";
            
            Map<String, String> stringData = new HashMap<>();
            cursorData.forEach((k, v) -> stringData.put(k, String.valueOf(v)));
            
            // 【修复】使用 hmset 命令批量设置 Hash 字段
            deleteRedisCommands.hmset(cursorKey, stringData)
                .toCompletableFuture()
                .join();
            
            // 设置过期时间：7天
            deleteRedisCommands.expire(cursorKey, 7 * 24 * 3600)
                .toCompletableFuture()
                .join();
            
            log.debug("[Redis] 索引重建游标已保存 - Data: {}", cursorData);
            
        } catch (Exception e) {
            log.error("[Redis] 保存索引重建游标失败", e);
        }
    }
    
    /**
     * 获取索引层游标数据
     * 
     * @return 游标数据Map，包含lastTaskId、lastBatchId、lastUserId等
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getIndexRebuildCursor() {
        try {
            String cursorKey = "recycle:index_rebuild:cursor";
            
            Map<String, String> result = deleteRedisCommands.hgetall(cursorKey)
                .toCompletableFuture()
                .join();
            
            if (result == null || result.isEmpty()) {
                log.debug("[Redis] 未找到索引重建游标");
                return new HashMap<>();
            }
            
            Map<String, String> convertedResult = new HashMap<>();
            result.forEach((k, v) -> convertedResult.put(String.valueOf(k), String.valueOf(v)));
            
            log.debug("[Redis] 获取索引重建游标成功 - Data: {}", convertedResult);
            return convertedResult;
            
        } catch (Exception e) {
            log.error("[Redis] 获取索引重建游标失败", e);
            return new HashMap<>();
        }
    }
    
    /**
     * 清除索引层游标数据
     */
    public void clearIndexRebuildCursor() {
        try {
            String cursorKey = "recycle:index_rebuild:cursor";
            
            deleteRedisCommands.del(cursorKey)
                .toCompletableFuture()
                .join();
            
            log.info("[Redis] 索引重建游标已清除");
            
        } catch (Exception e) {
            log.error("[Redis] 清除索引重建游标失败", e);
        }
    }
    
    /**
     * 获取 ZSET 中成员的 score
     * 
     * @param key ZSET的key
     * @param member 成员名称（batchId）
     * @return score值，如果不存在则返回null
     */
    public Double getMemberScore(String key, String member) {
        try {
            Double score = deleteRedisCommands.zscore(key, member)
                .toCompletableFuture()
                .join();
            
            log.debug("[Redis] 获取成员score - Key: {}, Member: {}, Score: {}", key, member, score);
            return score;
            
        } catch (Exception e) {
            log.error("[Redis] 获取成员score失败 - Key: {}, Member: {}", key, member, e);
            return null;
        }
    }
    
    /**
     * 查询 ZSET 中 score 大于指定值的成员（用于游标分页）
     * 
     * @param userId 用户ID
     * @param lastScore 上一个成员的score
     * @param limit 限制数量
     * @return batchId列表
     */
    public CompletableFuture<List<String>> getBatchesAfterScore(Long userId, Double lastScore, int limit) {
        try {
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 使用 ZRANGEBYSCORE 查询 score > lastScore 的成员
            // (lastScore 表示开区间，不包含lastScore本身
            List<String> members = deleteRedisCommands.zrangebyscore(
                userBatchesKey, 
                "(" + lastScore,  // 开区间，大于lastScore
                "+inf",           // 到正无穷
                0,                // offset
                limit             // count
            ).toCompletableFuture().join();
            
            log.debug("[Redis] 查询score后的成员 - UserId: {}, LastScore: {}, Count: {}", 
                userId, lastScore, members != null ? members.size() : 0);
            
            return CompletableFuture.completedFuture(members);
            
        } catch (Exception e) {
            log.error("[Redis] 查询score后的成员失败 - UserId: {}, LastScore: {}", userId, lastScore, e);
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
    }
}
