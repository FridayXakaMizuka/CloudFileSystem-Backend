package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.entity.FileNode;
import com.mizuka.cloudfilesystem.entity.FolderNode;
import com.mizuka.cloudfilesystem.entity.RecycleBinTask;
import com.mizuka.cloudfilesystem.mapper.FileNodeMapper;
import com.mizuka.cloudfilesystem.mapper.FolderNodeMapper;
import com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 异步回收站恢复服务
 * 基于 Redis ZSET 实现按顺序恢复节点
 */
@Service
public class AsyncRecycleBinRestoreService {
    
    private static final Logger log = LoggerFactory.getLogger(AsyncRecycleBinRestoreService.class);
    
    // 默认 IOPS 限制：每秒最多1000次操作
    private static final int DEFAULT_MAX_IOPS = 1000;
    
    // 批处理大小：每批处理的节点数
    private static final int BATCH_SIZE = 100;
    
    @Autowired
    private FolderNodeMapper folderNodeMapper;
    
    @Autowired
    private FileNodeMapper fileNodeMapper;
    
    @Autowired
    private RateLimiterService rateLimiterService;
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    @Autowired
    private RecycleBinRedisService recycleBinRedisService;
    
    @Autowired
    private DirectoryService directoryService;
    
    /**
     * 异步恢复 batch 中的所有节点（后台任务）
     * 
     * @param batchId 批次号
     * @param userId 用户ID
     */
    @Async("deleteTaskExecutor")
    public void asyncRestoreBatch(String batchId, Long userId) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[异步恢复] 开始 - BatchId: {}, UserId: {}", batchId, userId);
            
            // 1. 从 MySQL 查询 batch 信息
            RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
            if (task == null) {
                log.error("[异步恢复] 未找到任务记录 - BatchId: {}", batchId);
                return;
            }
            
            // 验证权限
            if (!userId.equals(task.getUserId())) {
                log.error("[异步恢复] 无权恢复该batch - BatchId: {}, UserId: {}", batchId, userId);
                throw new RuntimeException("无权恢复该节点");
            }
            
            Long rootNodeId = task.getRootNodeId();
            Integer nodeType = task.getNodeType();
            
            // 【新增】2. 检查是否有正在进行的删除任务，如果有则终止
            if (task.getOperationType() == 0 && task.getStatus() == 0) {
                log.info("[异步恢复] 检测到删除任务正在进行，先终止删除 - BatchId: {}", batchId);
                
                // 终止删除任务（status=3 表示已终止）
                recycleBinTaskMapper.updateTask(batchId, 3, LocalDateTime.now(), 
                    "用户主动终止（开始恢复）", null, null);
                
                log.info("[异步恢复] 删除任务已终止 - BatchId: {}", batchId);
            }
            
            // 3. 更新任务操作类型为恢复（operation_type=1），状态为进行中（status=0）
            recycleBinTaskMapper.updateTaskOperationType(batchId, 1, 0, null, 0, null, null);
            log.info("[异步恢复] 任务状态已更新为 restoring - BatchId: {}, OperationType: 1", batchId);
            
            // 3. 从 Redis ZSET 中按顺序取出所有节点
            String nodesKey = "recycle:batch:" + batchId + ":nodes";
            Set<String> members = recycleBinRedisService.getAllNodesFromBatch(batchId).join();
            
            if (members == null || members.isEmpty()) {
                log.warn("[异步恢复] ZSET 为空，可能已被其他操作清空 - BatchId: {}", batchId);
                // 即使ZSET为空，也要处理根节点
                restoreSingleNode(rootNodeId, nodeType, userId, batchId);
                
                // 清理并标记完成
                cleanupAndMarkCompleted(batchId, userId, 1, 1);
                return;
            }
            
            int totalCount = members.size();
            int processedCount = 0;
            int successCount = 0;
            
            log.info("[异步恢复] 待恢复节点总数: {}", totalCount);
            
            // 4. 按 ZSET 顺序遍历所有节点并恢复
            for (String member : members) {
                try {
                    // 限流控制
                    String rateLimitKey = "rate_limit:restore:" + userId;
                    try {
                        rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("恢复任务被中断", e);
                    }
                    
                    // 解析 nodeType 和 nodeId
                    String[] parts = member.split(":");
                    Integer currentNodeType = Integer.parseInt(parts[0]);  // 0=文件夹, 1=文件
                    Long nodeId = Long.parseLong(parts[1]);
                    
                    log.debug("[异步恢复] 恢复节点 - NodeType: {}, NodeId: {}", currentNodeType, nodeId);
                    
                    // 根据 nodeType 选择不同的恢复逻辑
                    boolean success = restoreSingleNode(nodeId, currentNodeType, userId, batchId);
                    
                    if (success) {
                        successCount++;
                        
                        // 从 ZSET 中移除已恢复的节点
                        recycleBinRedisService.removeNodeFromBatch(batchId, member).join();
                        
                        log.info("[异步恢复] 节点恢复成功 - NodeType: {}, NodeId: {}", currentNodeType, nodeId);
                    } else {
                        log.warn("[异步恢复] 节点恢复失败 - NodeType: {}, NodeId: {}", currentNodeType, nodeId);
                    }
                    
                    processedCount++;
                    
                    // 更新进度（每处理10个节点更新一次）
                    if (processedCount % 10 == 0) {
                        recycleBinTaskMapper.updateProgress(batchId, processedCount, totalCount);
                    }
                    
                } catch (Exception e) {
                    log.error("[异步恢复] 恢复节点异常 - Member: {}", member, e);
                    processedCount++;
                }
            }
            
            // 5. 所有节点处理完成，清理 Redis 并更新 MySQL
            long duration = System.currentTimeMillis() - startTime;
            cleanupAndMarkCompleted(batchId, userId, processedCount, totalCount);
            
            log.info("[异步恢复] 完成 - BatchId: {}, Duration: {}ms, Success: {}/{}", 
                batchId, duration, successCount, totalCount);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[异步恢复] 失败 - BatchId: {}, Duration: {}ms", batchId, duration, e);
            
            // 更新任务状态为失败
            recycleBinTaskMapper.updateTask(batchId, 2, LocalDateTime.now(), e.getMessage(), null, null);
        }
    }
    
    /**
     * 恢复单个节点
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @param userId 用户ID
     * @param batchId 批次号
     * @return 是否成功
     */
    @Transactional
    public boolean restoreSingleNode(Long nodeId, Integer nodeType, Long userId, String batchId) {
        try {
            if (nodeType == 0) {
                // 文件夹恢复
                FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
                if (folder == null) {
                    log.warn("[恢复节点] 文件夹不存在或不在回收站中 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 验证权限
                if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                    log.warn("[恢复节点] 无权恢复该文件夹 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 检查是否已过期
                if (folder.getDeleteExpiresAt() != null && folder.getDeleteExpiresAt().isBefore(LocalDateTime.now())) {
                    log.warn("[恢复节点] 文件夹已过期 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 调用 DirectoryService 的恢复方法
                directoryService.restoreFolderFromRecycleBin(nodeId, userId);
                
            } else if (nodeType == 1) {
                // 文件恢复
                FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
                if (file == null) {
                    log.warn("[恢复节点] 文件不存在或不在回收站中 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 验证权限
                if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                    log.warn("[恢复节点] 无权恢复该文件 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 检查是否已过期
                if (file.getDeleteExpiresAt() != null && file.getDeleteExpiresAt().isBefore(LocalDateTime.now())) {
                    log.warn("[恢复节点] 文件已过期 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 调用 DirectoryService 的恢复方法
                directoryService.restoreFileFromRecycleBin(nodeId, userId);
                
            } else {
                log.error("[恢复节点] 无效的节点类型 - NodeId: {}, NodeType: {}", nodeId, nodeType);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("[恢复节点] 恢复失败 - NodeId: {}, NodeType: {}", nodeId, nodeType, e);
            return false;
        }
    }
    
    /**
     * 清理 Redis 缓存并标记任务为已完成
     * 
     * @param batchId 批次号
     * @param userId 用户ID
     * @param processedCount 已处理数量
     * @param totalCount 总数量
     */
    private void cleanupAndMarkCompleted(String batchId, Long userId, int processedCount, int totalCount) {
        try {
            // 1. 清理 Redis 缓存
            recycleBinRedisService.cleanupBatch(batchId);
            log.info("[异步恢复] Redis 缓存已清理 - BatchId: {}", batchId);
            
            // 2. 更新 MySQL 任务状态为 "Restored" (status=1 表示已完成)
            recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, processedCount, totalCount);
            log.info("[异步恢复] MySQL 任务状态已更新为 Restored - BatchId: {}, Processed: {}/{}", 
                batchId, processedCount, totalCount);
            
        } catch (Exception e) {
            log.error("[异步恢复] 清理或更新状态失败 - BatchId: {}", batchId, e);
        }
    }
}
