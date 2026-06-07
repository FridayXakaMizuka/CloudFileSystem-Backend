package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.DeleteNodeResponse;
import com.mizuka.cloudfilesystem.dto.DeleteSession;
import com.mizuka.cloudfilesystem.entity.FileNode;
import com.mizuka.cloudfilesystem.entity.FolderNode;
import com.mizuka.cloudfilesystem.mapper.FileNodeMapper;
import com.mizuka.cloudfilesystem.mapper.FolderNodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 异步目录删除服务
 * 支持后台异步递归删除文件夹及其子节点，带限流控制
 */
@Service
public class AsyncDirectoryDeleteService {
    
    private static final Logger log = LoggerFactory.getLogger(AsyncDirectoryDeleteService.class);
    
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
    private DeleteSessionService deleteSessionService;
    
    @Autowired
    private com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper recycleBinTaskMapper;
    
    /**
     * 异步递归删除文件夹（后台任务）
     * 
     * @param folderId 文件夹ID
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @param recycleBinPath 回收站路径
     * @param expiresAt 过期时间
     */
    @Async("deleteTaskExecutor")
    public void asyncDeleteFolder(Long folderId, String sessionId, Long userId, 
                                   String recycleBinPath, LocalDateTime expiresAt) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[异步删除] 开始 - FolderId: {}, SessionId: {}, UserId: {}", 
                folderId, sessionId, userId);
            
            // 1. 创建或删除会话
            DeleteSession session = new DeleteSession();
            session.setSessionId(sessionId);
            session.setNodeId(folderId);
            session.setNodeType(0); // 文件夹
            session.setUserId(userId);
            session.setStartTime(LocalDateTime.now());
            session.setStatus("running");
            session.setRecycleBinPath(recycleBinPath);
            session.setExpiresAt(expiresAt);
            
            deleteSessionService.createSession(session);
            
            // 2. 统计需要删除的节点总数
            int totalNodes = countNodesToDelete(folderId);
            session.setTotalNodes(totalNodes);
            
            log.info("[异步删除] 待删除节点总数: {}", totalNodes);
            
            // 3. 检查是否有断点信息（从 Redis 恢复会话）
            DeleteSession existingSession = deleteSessionService.getSession(userId, sessionId);
            Long resumeFromParentId = null;
            Long resumeFromNodeId = null;
            
            if (existingSession != null && existingSession.getCurrentParentId() != null) {
                resumeFromParentId = existingSession.getCurrentParentId();
                resumeFromNodeId = existingSession.getLastProcessedNodeId();
                log.info("[异步删除] 检测到断点，从 ParentId={}, LastNodeId={} 继续", 
                    resumeFromParentId, resumeFromNodeId);
            }
            
            // 4. 分批递归删除所有子节点
            int processedNodes = existingSession != null && existingSession.getProcessedNodes() != null 
                ? existingSession.getProcessedNodes() : 0;
            
            // 先删除所有子文件夹
            processedNodes += deleteChildFoldersRecursive(folderId, sessionId, userId, 
                recycleBinPath, expiresAt, processedNodes, totalNodes,
                resumeFromParentId, resumeFromNodeId);
            
            // 再删除所有子文件
            processedNodes += deleteChildFiles(folderId, sessionId, userId, 
                recycleBinPath, expiresAt, processedNodes, totalNodes);
            
            // 5. 更新会话状态为完成
            long duration = System.currentTimeMillis() - startTime;
            deleteSessionService.updateSessionStatus(userId, sessionId, "completed", 
                processedNodes, totalNodes, null);
            
            log.info("[异步删除] 完成 - FolderId: {}, Duration: {}ms, Processed: {}/{}", 
                folderId, duration, processedNodes, totalNodes);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[异步删除] 失败 - FolderId: {}, SessionId: {}, Duration: {}ms", 
                folderId, sessionId, duration, e);
            
            deleteSessionService.updateSessionStatus(userId, sessionId, "failed", 
                null, null, e.getMessage());
        }
    }
    
    /**
     * 递归删除子文件夹（支持断点续传）
     */
    private int deleteChildFoldersRecursive(Long parentFolderId, String sessionId, Long userId,
                                             String recycleBinPath, LocalDateTime expiresAt,
                                             int processedNodes, int totalNodes,
                                             Long resumeFromParentId, Long resumeFromNodeId) {
        // 查询直接子文件夹
        List<FolderNode> childFolders = folderNodeMapper.findChildren(parentFolderId);
        
        int deletedCount = 0;
        boolean shouldResume = (resumeFromParentId != null && resumeFromParentId.equals(parentFolderId));
        
        for (FolderNode childFolder : childFolders) {
            // 断点续传：跳过已处理的节点
            if (shouldResume && resumeFromNodeId != null && childFolder.getId() <= resumeFromNodeId) {
                log.debug("[断点续传] 跳过已处理节点 - NodeId: {}", childFolder.getId());
                continue;
            }
            
            // 限流控制
            String rateLimitKey = "rate_limit:delete:" + userId;
            try {
                rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("删除任务被中断", e);
            }
            
            // 计算子文件夹的回收站路径
            String childRecyclePath = recycleBinPath + "/" + childFolder.getName();
            
            // 软删除当前子文件夹
            folderNodeMapper.softDeleteFolder(childFolder.getId(), childRecyclePath, expiresAt);
            deletedCount++;
            processedNodes++;
            
            // 更新进度和游标位置
            deleteSessionService.updateSessionWithCursor(userId, sessionId, "running", 
                processedNodes, totalNodes, null, parentFolderId, childFolder.getId());
            
            // 递归删除子文件夹的子节点
            deletedCount += deleteChildFoldersRecursive(childFolder.getId(), sessionId, userId,
                childRecyclePath, expiresAt, processedNodes, totalNodes,
                null, null);  // 子节点不需要断点，从头开始
            
            // 删除子文件夹中的文件
            deletedCount += deleteChildFiles(childFolder.getId(), sessionId, userId,
                childRecyclePath, expiresAt, processedNodes, totalNodes);
        }
        
        return deletedCount;
    }
    
    /**
     * 批量删除文件夹中的文件
     */
    private int deleteChildFiles(Long folderId, String sessionId, Long userId,
                                  String recycleBinPath, LocalDateTime expiresAt,
                                  int processedNodes, int totalNodes) {
        // 查询该文件夹下的所有文件
        List<FileNode> files = fileNodeMapper.findActiveChildren(folderId);
        
        int deletedCount = 0;
        
        // 分批处理
        for (int i = 0; i < files.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, files.size());
            List<FileNode> batch = files.subList(i, endIndex);
            
            for (FileNode file : batch) {
                // 限流控制
                String rateLimitKey = "rate_limit:delete:" + userId;
                try {
                    rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("删除任务被中断", e);
                }
                
                // 软删除文件
                fileNodeMapper.softDeleteFile(file.getId(), 
                    recycleBinPath + "/" + file.getName(), expiresAt);
                deletedCount++;
                processedNodes++;
            }
            
            // 更新进度
            deleteSessionService.updateSessionStatus(userId, sessionId, "running", 
                processedNodes, totalNodes, null);
        }
        
        return deletedCount;
    }
    
    /**
     * 统计需要删除的节点总数
     */
    private int countNodesToDelete(Long folderId) {
        // 统计子文件夹数量
        List<FolderNode> childFolders = folderNodeMapper.findAllDescendants(folderId);
        int folderCount = childFolders.size();
        
        // 统计子文件数量
        int fileCount = 0;
        for (FolderNode folder : childFolders) {
            List<FileNode> files = fileNodeMapper.findActiveChildren(folder.getId());
            fileCount += files.size();
        }
        
        // 加上当前文件夹直接包含的文件
        List<FileNode> directFiles = fileNodeMapper.findActiveChildren(folderId);
        fileCount += directFiles.size();
        
        return folderCount + fileCount;
    }
    
    /**
     * 异步递归删除文件夹（支持 batchId）
     * 
     * @param folderId 文件夹ID
     * @param batchId 批次号
     * @param userId 用户ID
     * @param recycleBinPath 回收站路径
     * @param expiresAt 过期时间
     */
    @Async("deleteTaskExecutor")
    public void asyncDeleteFolderWithBatchId(Long folderId, String batchId, Long userId,
                                              String recycleBinPath, LocalDateTime expiresAt) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("[异步删除] 开始 - FolderId: {}, BatchId: {}, UserId: {}", 
                folderId, batchId, userId);
            
            // 1. 统计需要删除的节点总数
            int totalNodes = countNodesToDelete(folderId);
            recycleBinTaskMapper.updateProgress(batchId, 0, totalNodes);
            
            log.info("[异步删除] 待删除节点总数: {}", totalNodes);
            
            // 2. 分批递归删除所有子节点
            int processedNodes = 0;
            
            // 先删除所有子文件夹
            processedNodes += deleteChildFoldersWithBatchId(folderId, batchId, userId, 
                recycleBinPath, expiresAt, processedNodes, totalNodes);
            
            // 再删除所有子文件
            processedNodes += deleteChildFilesWithBatchId(folderId, batchId, userId, 
                recycleBinPath, expiresAt, processedNodes, totalNodes);
            
            // 3. 更新任务状态为完成
            long duration = System.currentTimeMillis() - startTime;
            recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, 
                processedNodes, totalNodes);
            
            log.info("[异步删除] 完成 - FolderId: {}, BatchId: {}, Duration: {}ms, Processed: {}/{}", 
                folderId, batchId, duration, processedNodes, totalNodes);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[异步删除] 失败 - FolderId: {}, BatchId: {}, Duration: {}ms", 
                folderId, batchId, duration, e);
            
            recycleBinTaskMapper.updateTask(batchId, 2, LocalDateTime.now(), 
                e.getMessage(), null, null);
        }
    }
    
    /**
     * 递归删除子文件夹（支持 batchId）
     */
    private int deleteChildFoldersWithBatchId(Long parentFolderId, String batchId, Long userId,
                                               String recycleBinPath, LocalDateTime expiresAt,
                                               int processedNodes, int totalNodes) {
        List<FolderNode> childFolders = folderNodeMapper.findChildren(parentFolderId);
        
        int deletedCount = 0;
        
        for (FolderNode childFolder : childFolders) {
            // 限流控制
            String rateLimitKey = "rate_limit:delete:" + userId;
            try {
                rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("删除任务被中断", e);
            }
            
            // 计算子文件夹的回收站路径
            String childRecyclePath = recycleBinPath + "/" + childFolder.getName();
            
            // 软删除当前子文件夹
            folderNodeMapper.softDeleteFolder(childFolder.getId(), childRecyclePath, expiresAt);
            deletedCount++;
            processedNodes++;
            
            // 更新进度
            recycleBinTaskMapper.updateProgress(batchId, processedNodes, totalNodes);
            
            // 递归删除子文件夹的子节点
            deletedCount += deleteChildFoldersWithBatchId(childFolder.getId(), batchId, userId,
                childRecyclePath, expiresAt, processedNodes, totalNodes);
            
            // 删除子文件夹中的文件
            deletedCount += deleteChildFilesWithBatchId(childFolder.getId(), batchId, userId,
                childRecyclePath, expiresAt, processedNodes, totalNodes);
        }
        
        return deletedCount;
    }
    
    /**
     * 批量删除文件夹中的文件（支持 batchId）
     */
    private int deleteChildFilesWithBatchId(Long folderId, String batchId, Long userId,
                                             String recycleBinPath, LocalDateTime expiresAt,
                                             int processedNodes, int totalNodes) {
        List<FileNode> files = fileNodeMapper.findActiveChildren(folderId);
        
        int deletedCount = 0;
        
        for (FileNode file : files) {
            // 限流控制
            String rateLimitKey = "rate_limit:delete:" + userId;
            try {
                rateLimiterService.acquireWithBackoff(rateLimitKey, DEFAULT_MAX_IOPS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("删除任务被中断", e);
            }
            
            // 计算文件的回收站路径
            String fileRecyclePath = recycleBinPath + "/" + file.getName();
            
            // 软删除文件
            fileNodeMapper.softDeleteFile(file.getId(), fileRecyclePath, expiresAt);
            deletedCount++;
            processedNodes++;
            
            // 更新进度
            recycleBinTaskMapper.updateProgress(batchId, processedNodes, totalNodes);
        }
        
        return deletedCount;
    }
}
