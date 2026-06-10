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
import java.util.Set;
import java.util.UUID;

/**
 * 异步彻底删除服务
 * 基于 Redis ZSET 实现按顺序彻底删除节点
 */
@Service
public class AsyncPermanentDeleteService {
    
    private static final Logger log = LoggerFactory.getLogger(AsyncPermanentDeleteService.class);
    
    // 默认 IOPS 限制：每秒最多1000次操作
    private static final int DEFAULT_MAX_IOPS = 1000;
    
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
     * 异步彻底删除 batch 中的所有节点（后台任务）
     * 
     * @param batchId 批次号
     * @param userId 用户ID
     * @param rootNodeId 根节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     */
    @Async("deleteTaskExecutor")
    public void asyncPermanentDeleteBatch(String batchId, Long userId, Long rootNodeId, Integer nodeType) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[异步彻底删除] 开始 - BatchId: {}, UserId: {}, RootNodeId: {}, NodeType: {}", 
                batchId, userId, rootNodeId, nodeType);
            
            // 1. 从 MySQL 查询 batch 信息（验证权限）
            RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
            if (task == null) {
                log.error("[异步彻底删除] 未找到任务记录 - BatchId: {}", batchId);
                return;
            }
            
            // 验证权限
            if (!userId.equals(task.getUserId())) {
                log.error("[异步彻底删除] 无权删除该batch - BatchId: {}, UserId: {}", batchId, userId);
                throw new RuntimeException("无权删除该节点");
            }
            
            // 【关键】2. 检查是否有正在进行的操作（删除或恢复），如果有则终止
            if (task.getStatus() == 0) { // 进行中
                String operationName = task.getOperationType() == 0 ? "删除" : "恢复";
                log.info("[异步彻底删除] 检测到{}任务正在进行，先终止 - BatchId: {}", operationName, batchId);
                
                // 终止当前任务（status=3 表示已终止）
                recycleBinTaskMapper.updateTask(batchId, 3, LocalDateTime.now(), 
                    "用户主动终止（开始彻底删除）", null, null);
                
                log.info("[异步彻底删除] {}任务已终止 - BatchId: {}", operationName, batchId);
            }
            
            // 3. 更新任务操作类型为彻底删除（operation_type=2），状态为进行中（status=0）
            recycleBinTaskMapper.updateTaskOperationType(batchId, 2, 0, null, 0, null, null);
            log.info("[异步彻底删除] 任务状态已更新为 permanently_deleting - BatchId: {}, OperationType: 2", batchId);
            
            // 4. 从 Redis ZSET 中按顺序取出所有节点
            Set<String> members = recycleBinRedisService.getAllNodesFromBatch(batchId).join();
            
            int totalCount = 0;
            int processedCount = 0;
            int successCount = 0;
            
            if (members != null && !members.isEmpty()) {
                totalCount = members.size();
                log.info("[异步彻底删除] 待彻底删除节点总数: {}", totalCount);
                
                // 5. 按 ZSET 顺序遍历所有节点并彻底删除
                for (String member : members) {
                    try {
                        // 限流控制
                        String rateLimitKey = "rate_limit:permanent_delete:" + userId;
                        try {
                            rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("彻底删除任务被中断", e);
                        }
                        
                        // 解析 nodeType 和 nodeId
                        String[] parts = member.split(":");
                        Integer currentNodeType = Integer.parseInt(parts[0]);  // 0=文件夹, 1=文件
                        Long nodeId = Long.parseLong(parts[1]);
                        
                        log.debug("[异步彻底删除] 彻底删除节点 - NodeType: {}, NodeId: {}", currentNodeType, nodeId);
                        
                        // 根据 nodeType 选择不同的彻底删除逻辑
                        boolean success = permanentDeleteSingleNode(nodeId, currentNodeType);
                        
                        if (success) {
                            successCount++;
                            
                            // 从 ZSET 中移除已删除的节点
                            recycleBinRedisService.removeNodeFromBatch(batchId, member).join();
                            
                            log.info("[异步彻底删除] 节点彻底删除成功 - NodeType: {}, NodeId: {}", currentNodeType, nodeId);
                        } else {
                            log.warn("[异步彻底删除] 节点彻底删除失败 - NodeType: {}, NodeId: {}", currentNodeType, nodeId);
                        }
                        
                        processedCount++;
                        
                        // 更新进度（每处理10个节点更新一次）
                        if (processedCount % 10 == 0) {
                            recycleBinTaskMapper.updateProgress(batchId, processedCount, totalCount);
                        }
                        
                    } catch (Exception e) {
                        log.error("[异步彻底删除] 彻底删除节点异常 - Member: {}", member, e);
                        processedCount++;
                    }
                }
            } else {
                // ZSET 为空，只处理根节点
                log.info("[异步彻底删除] ZSET 为空，仅处理根节点 - BatchId: {}", batchId);
                totalCount = 1;
                
                boolean success = permanentDeleteSingleNode(rootNodeId, nodeType);
                if (success) {
                    successCount = 1;
                    processedCount = 1;
                }
            }
            
            // 6. 所有节点处理完成，清理 Redis 并更新 MySQL
            long duration = System.currentTimeMillis() - startTime;
            cleanupAndMarkCompleted(batchId, userId, processedCount, totalCount);
            
            log.info("[异步彻底删除] 完成 - BatchId: {}, Duration: {}ms, Success: {}/{}", 
                batchId, duration, successCount, totalCount);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[异步彻底删除] 失败 - BatchId: {}, Duration: {}ms", batchId, duration, e);
            
            // 更新任务状态为失败
            recycleBinTaskMapper.updateTask(batchId, 2, LocalDateTime.now(), e.getMessage(), null, null);
        }
    }
    
    /**
     * 彻底删除单个节点
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @return 是否成功
     */
    @Transactional
    public boolean permanentDeleteSingleNode(Long nodeId, Integer nodeType) {
        try {
            if (nodeType == 0) {
                // 文件夹彻底删除：标记为 unassigned（进入待分配池）
                FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
                if (folder == null) {
                    log.warn("[彻底删除节点] 文件夹不存在或不在回收站中 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 标记为待分配（unassigned）
                folderNodeMapper.markAsUnassigned(nodeId);
                log.info("[彻底删除节点] 文件夹已标记为待分配 - NodeId: {}", nodeId);
                
            } else if (nodeType == 1) {
                // 文件彻底删除：标记为 permanently_deleted
                FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
                if (file == null) {
                    log.warn("[彻底删除节点] 文件不存在或不在回收站中 - NodeId: {}", nodeId);
                    return false;
                }
                
                // 标记为永久删除
                fileNodeMapper.markAsPermanentlyDeleted(nodeId);
                
                // 减少元数据的引用计数
                fileNodeMapper.decrementMetadataReferenceCount(file.getFileMetadataId());
                
                // 如果引用计数为0，物理删除元数据和分片
                int referenceCount = fileNodeMapper.getMetadataReferenceCount(file.getFileMetadataId());
                if (referenceCount <= 0) {
                    // 删除分片记录
                    fileNodeMapper.deleteFileChunks(file.getFileMetadataId());
                    // 物理删除元数据
                    fileNodeMapper.permanentDeleteFileMetadata(file.getFileMetadataId());
                    log.info("[彻底删除节点] 文件元数据已清理 - FileMetadataId: {}", file.getFileMetadataId());
                }
                
                log.info("[彻底删除节点] 文件已标记为永久删除 - NodeId: {}", nodeId);
                
            } else {
                log.error("[彻底删除节点] 无效的节点类型 - NodeId: {}, NodeType: {}", nodeId, nodeType);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("[彻底删除节点] 彻底删除失败 - NodeId: {}, NodeType: {}", nodeId, nodeType, e);
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
            log.info("[异步彻底删除] Redis 缓存已清理 - BatchId: {}", batchId);
            
            // 2. 更新 MySQL 任务状态为 "PermanentlyDeleted" (status=1 表示已完成)
            recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, processedCount, totalCount);
            log.info("[异步彻底删除] MySQL 任务状态已更新为 PermanentlyDeleted - BatchId: {}, Processed: {}/{}", 
                batchId, processedCount, totalCount);
            
        } catch (Exception e) {
            log.error("[异步彻底删除] 清理或更新状态失败 - BatchId: {}", batchId, e);
        }
    }
    
    /**
     * 创建新的彻底删除任务（用于浏览界面模式）
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @param userId 用户ID
     * @return 新的 batchId
     */
    @Transactional
    public String createPermanentDeleteTask(Long nodeId, Integer nodeType, Long userId) {
        // 1. 生成新的 batchId
        String batchId = UUID.randomUUID().toString();
        
        // 2. 创建回收站任务记录（operation_type=2 表示彻底删除）
        RecycleBinTask task = new RecycleBinTask();
        task.setBatchId(batchId);
        task.setUserId(userId);
        task.setRootNodeId(nodeId);
        task.setNodeType(nodeType);
        task.setOperationType(2); // 彻底删除操作
        task.setStatus(0); // 进行中
        task.setProcessedCount(0);
        task.setTotalCount(0); // 异步扫描后更新
        task.setCreatedAt(LocalDateTime.now());
        
        Long taskId = recycleBinTaskMapper.insert(task);
        log.info("创建彻底删除任务 - BatchId: {}, TaskId: {}, NodeId: {}", batchId, taskId, nodeId);
        
        // 3. 初始化 Redis ZSET 和根目录信息
        recycleBinRedisService.initializeBatch(batchId, nodeId, nodeType, userId);
        
        return batchId;
    }
}
