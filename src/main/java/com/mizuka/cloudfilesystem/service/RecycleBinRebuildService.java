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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis缓存重建服务
 * 应用启动时从recycle_bin_tasks表重建Redis缓存
 */
@Service
public class RecycleBinRebuildService implements ApplicationRunner {
    
    private static final Logger log = LoggerFactory.getLogger(RecycleBinRebuildService.class);
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    @Autowired
    private FolderNodeMapper folderNodeMapper;
    
    @Autowired
    private FileNodeMapper fileNodeMapper;
    
    @Autowired
    private RecycleBinRedisService recycleBinRedisService;
    
    @Autowired
    private DirectoryService directoryService;
    
    // 异步执行器，用于并发处理多个彻底删除任务
    private final ExecutorService rebuildExecutor = Executors.newFixedThreadPool(5);
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("========== 开始重建回收站Redis缓存 ==========");
        
        try {
            // 【关键】检查是否有索引重建游标（从上次中断处继续）
            Map<String, String> rebuildCursor = recycleBinRedisService.getIndexRebuildCursor();
            Long lastTaskId = null;
            
            if (rebuildCursor != null && !rebuildCursor.isEmpty() && rebuildCursor.containsKey("lastTaskId")) {
                lastTaskId = Long.parseLong(rebuildCursor.get("lastTaskId"));
                log.info("[重建] 检测到索引重建游标，从断点继续 - LastTaskId: {}", lastTaskId);
            }
            
            // 查询所有未完成的任务（如果有限流器，可能需要分批查询）
            List<RecycleBinTask> incompleteTasks;
            if (lastTaskId != null) {
                // 从游标位置继续查询
                incompleteTasks = recycleBinTaskMapper.findIncompleteTasksAfterCursor(lastTaskId);
                log.info("[重建] 从游标后查询到 {} 个任务", incompleteTasks.size());
            } else {
                // 从头开始查询
                incompleteTasks = recycleBinTaskMapper.findAllIncompleteTasks();
                log.info("[重建] 查询到 {} 个未完成的回收站任务", incompleteTasks.size());
            }
            
            if (incompleteTasks == null || incompleteTasks.isEmpty()) {
                log.info("没有需要重建的回收站任务");
                // 清除游标
                recycleBinRedisService.clearIndexRebuildCursor();
                return;
            }
            
            // 分类处理任务
            int processedCount = 0;
            for (RecycleBinTask task : incompleteTasks) {
                try {
                    if (task.getOperationType() == 0) {
                        // 删除操作：重建索引和元数据
                        rebuildDeleteTask(task);
                    } else if (task.getOperationType() == 1) {
                        // 恢复操作：重建索引和元数据
                        rebuildRestoreTask(task);
                    } else if (task.getOperationType() == 2) {
                        // 彻底删除操作：记录任务，稍后异步继续
                        recordPermanentDeleteTask(task);
                    }
                    
                    processedCount++;
                    
                    // 【关键】每处理一个任务就更新游标
                    Map<String, Object> cursorData = new HashMap<>();
                    cursorData.put("lastTaskId", task.getId());
                    cursorData.put("lastBatchId", task.getBatchId());
                    cursorData.put("lastUserId", task.getUserId());
                    cursorData.put("processedCount", processedCount);
                    cursorData.put("updatedAt", LocalDateTime.now().toString());
                    recycleBinRedisService.saveIndexRebuildCursor(cursorData);
                    
                    log.debug("[重建] 已处理 {}/{} 个任务 - BatchId: {}", 
                        processedCount, incompleteTasks.size(), task.getBatchId());
                    
                } catch (Exception e) {
                    log.error("重建任务失败 - BatchId: {}", task.getBatchId(), e);
                    // 即使失败也要更新游标，避免重复处理
                    Map<String, Object> cursorData = new HashMap<>();
                    cursorData.put("lastTaskId", task.getId());
                    cursorData.put("lastBatchId", task.getBatchId());
                    cursorData.put("lastUserId", task.getUserId());
                    cursorData.put("processedCount", processedCount);
                    cursorData.put("error", e.getMessage());
                    cursorData.put("updatedAt", LocalDateTime.now().toString());
                    recycleBinRedisService.saveIndexRebuildCursor(cursorData);
                }
            }
            
            // 所有遍历完成后，异步继续彻底删除任务和恢复任务
            asyncContinuePermanentDeleteTasks(incompleteTasks);
            asyncContinueRestoreTasks(incompleteTasks);
            
            // 【关键】所有任务处理完成后，清除游标
            recycleBinRedisService.clearIndexRebuildCursor();
            
            log.info("========== 回收站Redis缓存重建完成，共处理 {} 个任务 ==========", processedCount);
            
        } catch (Exception e) {
            log.error("回收站Redis缓存重建失败", e);
            // 注意：不游标，下次启动时可以从断点继续
        }
    }
    
    /**
     * 重建删除任务的索引和元数据
     */
    @Transactional
    protected void rebuildDeleteTask(RecycleBinTask task) {
        String batchId = task.getBatchId();
        Long userId = task.getUserId();
        Long rootNodeId = task.getRootNodeId();
        Integer nodeType = task.getNodeType();
        
        log.info("[重建] 处理删除任务 - BatchId: {}, RootNodeId: {}, NodeType: {}", 
            batchId, rootNodeId, nodeType);
        
        // 1. 【关键】从 MySQL 查询节点信息，获取 name、version 和 size（使用 findInRecycleBinById）
        String nodeName = null;
        Long nodeVersion = null;
        Long nodeSize = null;
        if (nodeType == 0) {
            // 文件夹 - 使用 findInRecycleBinById 查询回收站中的节点
            com.mizuka.cloudfilesystem.entity.FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
            if (folder != null) {
                nodeName = folder.getName();
                nodeVersion = folder.getVersion();
                // 文件夹没有size字段
                log.info("[重建] 查询到文件夹 - RootNodeId: {}, Name: {}, Version: {}", rootNodeId, nodeName, nodeVersion);
            } else {
                log.error("[重建] 文件夹不存在或不在回收站中 - RootNodeId: {}", rootNodeId);
            }
        } else {
            // 文件 - 使用 findInRecycleBinById 查询回收站中的节点
            com.mizuka.cloudfilesystem.entity.FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
            if (file != null) {
                nodeName = file.getName();
                nodeVersion = file.getVersion();
                nodeSize = file.getFileSize();
                log.info("[重建] 查询到文件 - RootNodeId: {}, Name: {}, Size: {}, Version: {}", 
                    rootNodeId, nodeName, nodeSize, nodeVersion);
            } else {
                log.error("[重建] 文件不存在或不在回收站中 - RootNodeId: {}", rootNodeId);
            }
        }
        
        // 2. 构建元数据层（包含name、size、version、deletedAt、expiresAt）
        Map<String, String> metadata = new HashMap<>();
        metadata.put("rootNodeId", String.valueOf(rootNodeId));
        metadata.put("nodeType", String.valueOf(nodeType));
        metadata.put("userId", String.valueOf(userId));
        if (nodeName != null) {
            metadata.put("name", nodeName);
        }
        if (nodeSize != null) {
            metadata.put("size", String.valueOf(nodeSize));
        }
        if (nodeVersion != null) {
            metadata.put("version", String.valueOf(nodeVersion));
        }
        
        // 使用 task 的创建时间作为 deletedAt
        if (task.getCreatedAt() != null) {
            long deletedAtMillis = task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            metadata.put("deletedAt", String.valueOf(deletedAtMillis));
        }
        
        // 计算 expiresAt（删除时间 + 30天）
        if (task.getCreatedAt() != null) {
            long expiresAtMillis = task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 30L * 24 * 3600 * 1000;
            metadata.put("expiresAt", String.valueOf(expiresAtMillis));
        }
        
        metadata.put("createdAt", String.valueOf(System.currentTimeMillis()));
        
        recycleBinRedisService.saveBatchMetadata(batchId, metadata);
        
        // 3. 【关键】添加 batchId 到用户索引（会自动创建索引）
        recycleBinRedisService.addBatchToUserList(userId, batchId, task.getCreatedAt());
        
        log.info("[重建] 删除任务索引和元数据已重建 - BatchId: {}, Name: {}, Version: {}", batchId, nodeName, nodeVersion);
    }
    
    /**
     * 重建恢复任务的索引和元数据
     */
    @Transactional
    protected void rebuildRestoreTask(RecycleBinTask task) {
        String batchId = task.getBatchId();
        Long userId = task.getUserId();
        Long rootNodeId = task.getRootNodeId();
        Integer nodeType = task.getNodeType();
        
        log.info("[重建] 处理恢复任务 - BatchId: {}, RootNodeId: {}, NodeType: {}", 
            batchId, rootNodeId, nodeType);
        
        // 1. 【关键】从 MySQL 查询节点信息，获取 name、version 和 size（使用 findInRecycleBinById）
        String nodeName = null;
        Long nodeVersion = null;
        Long nodeSize = null;
        if (nodeType == 0) {
            // 文件夹 - 使用 findInRecycleBinById 查询回收站中的节点
            com.mizuka.cloudfilesystem.entity.FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
            if (folder != null) {
                nodeName = folder.getName();
                nodeVersion = folder.getVersion();
                // 文件夹没有size字段
                log.info("[重建] 查询到文件夹 - RootNodeId: {}, Name: {}, Version: {}", rootNodeId, nodeName, nodeVersion);
            } else {
                log.error("[重建] 文件夹不存在或不在回收站中 - RootNodeId: {}", rootNodeId);
            }
        } else {
            // 文件 - 使用 findInRecycleBinById 查询回收站中的节点
            com.mizuka.cloudfilesystem.entity.FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
            if (file != null) {
                nodeName = file.getName();
                nodeVersion = file.getVersion();
                nodeSize = file.getFileSize();
                log.info("[重建] 查询到文件 - RootNodeId: {}, Name: {}, Size: {}, Version: {}", 
                    rootNodeId, nodeName, nodeSize, nodeVersion);
            } else {
                log.error("[重建] 文件不存在或不在回收站中 - RootNodeId: {}", rootNodeId);
            }
        }
        
        // 2. 构建元数据层（包含name、size、version、deletedAt、expiresAt）
        Map<String, String> metadata = new HashMap<>();
        metadata.put("rootNodeId", String.valueOf(rootNodeId));
        metadata.put("nodeType", String.valueOf(nodeType));
        metadata.put("userId", String.valueOf(userId));
        if (nodeName != null) {
            metadata.put("name", nodeName);
        }
        if (nodeSize != null) {
            metadata.put("size", String.valueOf(nodeSize));
        }
        if (nodeVersion != null) {
            metadata.put("version", String.valueOf(nodeVersion));
        }
        
        // 使用 task 的创建时间作为 deletedAt
        if (task.getCreatedAt() != null) {
            long deletedAtMillis = task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            metadata.put("deletedAt", String.valueOf(deletedAtMillis));
        }
        
        // 计算 expiresAt（删除时间 + 30天）
        if (task.getCreatedAt() != null) {
            long expiresAtMillis = task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 30L * 24 * 3600 * 1000;
            metadata.put("expiresAt", String.valueOf(expiresAtMillis));
        }
        
        metadata.put("createdAt", String.valueOf(System.currentTimeMillis()));
        
        recycleBinRedisService.saveBatchMetadata(batchId, metadata);
        
        // 3. 【关键】添加 batchId 到用户索引（会自动创建索引）
        recycleBinRedisService.addBatchToUserList(userId, batchId, task.getCreatedAt());
        
        log.info("[重建] 恢复任务索引和元数据已重建 - BatchId: {}, Name: {}, Version: {}", batchId, nodeName, nodeVersion);
    }
    
    /**
     * 记录彻底删除任务（等待后续异步继续）
     */
    @Transactional
    protected void recordPermanentDeleteTask(RecycleBinTask task) {
        String batchId = task.getBatchId();
        Long userId = task.getUserId();
        Long rootNodeId = task.getRootNodeId();
        Integer nodeType = task.getNodeType();
        
        log.info("[重建] 记录彻底删除任务 - BatchId: {}, RootNodeId: {}, NodeType: {}, Status: {}", 
            batchId, rootNodeId, nodeType, task.getStatus());
        
        // 1. 【关键】从 MySQL 查询节点信息，获取 name、version 和 size（使用 findInRecycleBinById）
        String nodeName = null;
        Long nodeVersion = null;
        Long nodeSize = null;
        if (nodeType == 0) {
            // 文件夹 - 使用 findInRecycleBinById 查询回收站中的节点
            com.mizuka.cloudfilesystem.entity.FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
            if (folder != null) {
                nodeName = folder.getName();
                nodeVersion = folder.getVersion();
                // 文件夹没有size字段
                log.info("[重建] 查询到文件夹 - RootNodeId: {}, Name: {}, Version: {}", rootNodeId, nodeName, nodeVersion);
            } else {
                log.error("[重建] 文件夹不存在或不在回收站中 - RootNodeId: {}", rootNodeId);
            }
        } else {
            // 文件 - 使用 findInRecycleBinById 查询回收站中的节点
            com.mizuka.cloudfilesystem.entity.FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
            if (file != null) {
                nodeName = file.getName();
                nodeVersion = file.getVersion();
                nodeSize = file.getFileSize();
                log.info("[重建] 查询到文件 - RootNodeId: {}, Name: {}, Size: {}, Version: {}", 
                    rootNodeId, nodeName, nodeSize, nodeVersion);
            } else {
                log.error("[重建] 文件不存在或不在回收站中 - RootNodeId: {}", rootNodeId);
            }
        }
        
        // 2. 构建元数据层（包含name、size、version、deletedAt、expiresAt）
        Map<String, String> metadata = new HashMap<>();
        metadata.put("rootNodeId", String.valueOf(rootNodeId));
        metadata.put("nodeType", String.valueOf(nodeType));
        metadata.put("userId", String.valueOf(userId));
        if (nodeName != null) {
            metadata.put("name", nodeName);
        }
        if (nodeSize != null) {
            metadata.put("size", String.valueOf(nodeSize));
        }
        if (nodeVersion != null) {
            metadata.put("version", String.valueOf(nodeVersion));
        }
        
        // 使用 task 的创建时间作为 deletedAt
        if (task.getCreatedAt() != null) {
            long deletedAtMillis = task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            metadata.put("deletedAt", String.valueOf(deletedAtMillis));
        }
        
        // 计算 expiresAt（删除时间 + 30天）
        if (task.getCreatedAt() != null) {
            long expiresAtMillis = task.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 30L * 24 * 3600 * 1000;
            metadata.put("expiresAt", String.valueOf(expiresAtMillis));
        }
        
        metadata.put("createdAt", String.valueOf(System.currentTimeMillis()));
        
        recycleBinRedisService.saveBatchMetadata(batchId, metadata);
        
        // 3. 【关键】添加 batchId 到用户索引（会自动创建索引）
        recycleBinRedisService.addBatchToUserList(userId, batchId, task.getCreatedAt());
        
        // 4. 【关键】尝试从 Redis 获取游标数据（如果存在）
        Map<String, String> cursorData = recycleBinRedisService.getCursorData(batchId);
        if (cursorData != null && !cursorData.isEmpty() && cursorData.containsKey("cursorNodeId")) {
            log.info("[重建] 检测到游标数据 - BatchId: {}, CursorNodeId: {}, CursorNodeType: {}", 
                batchId, cursorData.get("cursorNodeId"), cursorData.get("cursorNodeType"));
        } else {
            log.info("[重建] 未找到游标数据（可能已完成或Redis已失效） - BatchId: {}", batchId);
        }
        
        log.info("[重建] 彻底删除任务已记录 - BatchId: {}, Name: {}, Version: {}", batchId, nodeName, nodeVersion);
    }
    
    /**
     * 异步继续所有彻底删除任务
     */
    private void asyncContinuePermanentDeleteTasks(List<RecycleBinTask> allTasks) {
        // 过滤出彻底删除任务
        List<RecycleBinTask> permanentDeleteTasks = allTasks.stream()
            .filter(task -> task.getOperationType() == 2)
            .toList();
        
        if (permanentDeleteTasks.isEmpty()) {
            log.info("[重建] 没有需要继续的彻底删除任务");
            return;
        }
        
        log.info("[重建] 开始异步继续 {} 个彻底删除任务", permanentDeleteTasks.size());
        
        // 并发处理每个彻底删除任务
        for (RecycleBinTask task : permanentDeleteTasks) {
            CompletableFuture.runAsync(() -> {
                try {
                    continuePermanentDeleteTask(task);
                } catch (Exception e) {
                    log.error("[重建] 继续彻底删除任务失败 - BatchId: {}", task.getBatchId(), e);
                }
            }, rebuildExecutor);
        }
    }
    
    /**
     * 继续执行单个彻底删除任务（从游标断点开始）
     */
    private void continuePermanentDeleteTask(RecycleBinTask task) {
        String batchId = task.getBatchId();
        Long userId = task.getUserId();
        Long rootNodeId = task.getRootNodeId();
        Integer nodeType = task.getNodeType();
        
        log.info("[重建-继续] 开始继续彻底删除任务 - BatchId: {}, RootNodeId: {}, NodeType: {}", 
            batchId, rootNodeId, nodeType);
        
        // 调用DirectoryService的同步彻底删除方法
        // 该方法会自动从游标断点继续（如果存在游标）
        try {
            directoryService.permanentDeleteBatch(batchId, userId);
            
            log.info("[重建-继续] 彻底删除任务完成 - BatchId: {}", batchId);
            
        } catch (Exception e) {
            log.error("[重建-继续] 彻底删除任务失败 - BatchId: {}", batchId, e);
            
            // 更新任务状态为失败
            recycleBinTaskMapper.updateTask(batchId, 2, LocalDateTime.now(), 
                e.getMessage(), null, null);
        }
    }
    
    /**
     * 异步继续所有恢复任务
     */
    private void asyncContinueRestoreTasks(List<RecycleBinTask> allTasks) {
        // 过滤出恢复任务
        List<RecycleBinTask> restoreTasks = allTasks.stream()
            .filter(task -> task.getOperationType() == 1)
            .toList();
        
        if (restoreTasks.isEmpty()) {
            log.info("[重建] 没有需要继续的恢复任务");
            return;
        }
        
        log.info("[重建] 开始异步继续 {} 个恢复任务", restoreTasks.size());
        
        // 并发处理每个恢复任务
        for (RecycleBinTask task : restoreTasks) {
            CompletableFuture.runAsync(() -> {
                try {
                    continueRestoreTask(task);
                } catch (Exception e) {
                    log.error("[重建] 继续恢复任务失败 - BatchId: {}", task.getBatchId(), e);
                }
            }, rebuildExecutor);
        }
    }
    
    /**
     * 继续执行单个恢复任务
     */
    private void continueRestoreTask(RecycleBinTask task) {
        String batchId = task.getBatchId();
        Long userId = task.getUserId();
        
        log.info("[重建-继续] 开始继续恢复任务 - BatchId: {}, UserId: {}", batchId, userId);
        
        // 调用DirectoryService的恢复方法
        try {
            directoryService.restoreNode(batchId, userId);
            
            log.info("[重建-继续] 恢复任务完成 - BatchId: {}", batchId);
            
        } catch (Exception e) {
            log.error("[重建-继续] 恢复任务失败 - BatchId: {}", batchId, e);
            
            // 更新任务状态为失败
            recycleBinTaskMapper.updateTask(batchId, 2, LocalDateTime.now(), 
                e.getMessage(), null, null);
        }
    }
}
