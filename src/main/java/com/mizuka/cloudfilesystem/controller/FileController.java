package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.*;
import com.mizuka.cloudfilesystem.entity.FileNode;
import com.mizuka.cloudfilesystem.entity.FolderNode;
import com.mizuka.cloudfilesystem.entity.RecycleBinTask;
import com.mizuka.cloudfilesystem.exception.OptimisticLockException;
import com.mizuka.cloudfilesystem.mapper.FileNodeMapper;
import com.mizuka.cloudfilesystem.mapper.FolderNodeMapper;
import com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper;
import com.mizuka.cloudfilesystem.service.AsyncPermanentDeleteService;
import com.mizuka.cloudfilesystem.service.AsyncRecycleBinRestoreService;
import com.mizuka.cloudfilesystem.service.DirectoryService;
import com.mizuka.cloudfilesystem.service.RecycleBinService;
import com.mizuka.cloudfilesystem.util.Result;
import com.mizuka.cloudfilesystem.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件目录控制器
 */
@Slf4j
@RestController
@RequestMapping("/files")
public class FileController {
    
    @Autowired
    private DirectoryService directoryService;
    
    @Autowired
    private RecycleBinService recycleBinService;
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    @Autowired
    private AsyncRecycleBinRestoreService asyncRecycleBinRestoreService;
    
    @Autowired
    private AsyncPermanentDeleteService asyncPermanentDeleteService;
    
    @Autowired
    private FolderNodeMapper folderNodeMapper;
    
    @Autowired
    private FileNodeMapper fileNodeMapper;
    
    /**
     * 浏览目录内容（游标分页，支持多种排序）
     * 
     * @param currentNodeId 当前节点ID
     * @param lastChildrenNode 游标锚点ID
     * @param lastChildrenType 游标锚点类型
     * @param maxPageSize 期望的最大返回数量
     * @param sortedBy 排序字段：0=name, 1=size（只对文件起效，文件夹与0等效）, 2=createdAt, 3=updatedAt
     * @param order 排序顺序：0=asc, 1=desc
     * @param excludeNewFileIds 需要排除的新增文件ID列表
     * @param excludeNewFolderIds 需要排除的新增文件夹ID列表
     * @param isRecycleBin 是否为回收站模式（默认false）
     * @return 目录浏览响应
     */
    @GetMapping("/browse")
    public Result<DirectoryBrowseResponse> browse(
            @RequestParam Long currentNodeId,
            @RequestParam(required = false) Long lastChildrenNode,
            @RequestParam(required = false) String lastChildrenType,
            @RequestParam(required = false) Integer maxPageSize,
            @RequestParam(required = false, defaultValue = "0") Integer sortedBy,
            @RequestParam(required = false, defaultValue = "1") Integer order,
            @RequestParam(required = false) List<Long> excludeNewFileIds,
            @RequestParam(required = false) List<Long> excludeNewFolderIds,
            @RequestParam(required = false, defaultValue = "false") boolean isRecycleBin) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            DirectoryBrowseResponse response = directoryService.browseDirectory(
                    currentNodeId,
                    lastChildrenNode,
                    lastChildrenType,
                    maxPageSize,
                    userId,
                    sortedBy,
                    order,
                    excludeNewFileIds,
                    excludeNewFolderIds,
                    isRecycleBin
            );
            
            return Result.success(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问")) {
                log.warn("权限不足: {}", e.getMessage());
                return Result.error(40301, e.getMessage());
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return Result.error(40401, e.getMessage());
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return Result.error(50001, "系统繁忙，请稍后重试");
            }
        }
    }
    
    /**
     * 创建文件夹
     * 
     * @param request 创建文件夹请求
     * @return 创建结果
     */
    @PostMapping("/folder")
    public Result<NewFolderResponse> createFolder(@RequestBody NewFolderRequest request) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 参数校验
            if (request.getParentId() == null) {
                return Result.error(40001, "parentId 不能为空");
            }
            
            if (request.getFolderName() == null || request.getFolderName().trim().isEmpty()) {
                return Result.error(40001, "文件夹名称不能为空");
            }
            
            NewFolderResponse response = directoryService.createFolder(
                    request.getParentId(),
                    request.getFolderName(),
                    userId
            );
            
            log.info("用户 {} 创建文件夹成功 - ParentId: {}, FolderName: {}, ReusedFromPool: {}", 
                userId, request.getParentId(), request.getFolderName(), response.getReusedFromPool());
            
            return Result.success("文件夹创建成功", response);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问")) {
                log.warn("权限不足: {}", e.getMessage());
                return Result.error(40301, e.getMessage());
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return Result.error(40401, e.getMessage());
            } else if (e.getMessage().contains("已存在同名文件夹")) {
                log.warn("文件夹重名: {}", e.getMessage());
                return Result.error(40901, e.getMessage());
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return Result.error(50001, "系统繁忙，请稍后重试");
            }
        }
    }
    
    /**
     * 重命名节点（文件夹或文件）
     * 
     * @param nodeId 节点ID
     * @param request 重命名请求
     * @return 操作结果
     */
    @PutMapping("/rename/{nodeId}")
    public Result<Void> renameNode(@PathVariable Long nodeId, @RequestBody RenameNodeRequest request) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 参数校验
            if (nodeId == null) {
                return Result.error(40001, "节点ID不能为空");
            }
            
            if (request.getNewName() == null || request.getNewName().trim().isEmpty()) {
                return Result.error(40001, "新名称不能为空");
            }
            
            directoryService.renameNode(nodeId, request.getNewName(), userId);
            
            log.info("用户 {} 重命名节点成功 - NodeId: {}, NewName: {}", userId, nodeId, request.getNewName());
            
            return Result.success("重命名成功", null);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问")) {
                log.warn("权限不足: {}", e.getMessage());
                return Result.error(40301, e.getMessage());
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return Result.error(40401, e.getMessage());
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return Result.error(50001, "系统繁忙，请稍后重试");
            }
        }
    }
    
    /**
     * 移动节点（文件夹或文件）
     * 
     * @param nodeId 节点ID
     * @param request 移动请求
     * @return 操作结果
     */
    @PutMapping("/move/{nodeId}")
    public Result<Void> moveNode(@PathVariable Long nodeId, @RequestBody MoveNodeRequest request) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 参数校验
            if (nodeId == null) {
                return Result.error(40001, "节点ID不能为空");
            }
            
            if (request.getNewParentId() == null) {
                return Result.error(40001, "新父节点ID不能为空");
            }
            
            directoryService.moveNode(nodeId, request.getNewParentId(), userId);
            
            log.info("用户 {} 移动节点成功 - NodeId: {}, NewParentId: {}", userId, nodeId, request.getNewParentId());
            
            return Result.success("移动成功", null);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问")) {
                log.warn("权限不足: {}", e.getMessage());
                return Result.error(40301, e.getMessage());
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return Result.error(40401, e.getMessage());
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return Result.error(50001, "系统繁忙，请稍后重试");
            }
        }
    }
    
    /**
     * 删除节点（软删除，移入回收站）
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0为文件夹，1为文件）
     * @param version 乐观锁版本号
     * @param batchId 批次号（UUID格式，可选）
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    public Result<DeleteNodeResponse> deleteNode(
            @RequestParam Long nodeId,
            @RequestParam Integer nodeType,
            @RequestParam Long version,
            @RequestParam(required = false) String batchId) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 参数校验
            if (nodeId == null) {
                return Result.error(40001, "节点ID不能为空");
            }
            
            if (nodeType == null) {
                return Result.error(40001, "节点类型不能为空");
            }
            
            if (version == null) {
                return Result.error(40001, "版本号不能为空");
            }
            
            // 如果前端没有传 batchId，后端生成一个 UUID
            if (batchId == null || batchId.trim().isEmpty()) {
                batchId = java.util.UUID.randomUUID().toString();
            }
            
            DeleteNodeResponse response = directoryService.deleteNodeWithBatchId(nodeId, nodeType, userId, version, batchId);
            
            log.info("用户 {} 删除节点成功 - NodeId: {}, BatchId: {}, RecycleBinPath: {}, ExpiresAt: {}", 
                userId, nodeId, batchId, response.getRecycleBinPath(), response.getExpiresAt());
            
            return Result.success("已移入回收站，30天后彻底删除", response);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (OptimisticLockException e) {
            log.warn("版本冲突: {}", e.getMessage());
            return Result.error(409, e.getMessage());
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问")) {
                log.warn("权限不足: {}", e.getMessage());
                return Result.error(40301, e.getMessage());
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return Result.error(40401, e.getMessage());
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return Result.error(50001, "系统繁忙，请稍后重试");
            }
        }
    }
    
    /**
     * 恢复回收站中的节点（新架构 - 同步完成）
     * POST /recycle/restore
     * 
     * @param batchId 业务操作批次号（UUID格式）
     * @return 恢复结果
     */
    @PostMapping("/recycle/restore")
    public ResponseEntity<?> restoreNode(
            @RequestParam String batchId) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.error(401, "未认证或会话已过期"));
            }
            
            // 参数校验
            if (batchId == null || batchId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Result.error(40001, "batchId不能为空"));
            }
            
            // 验证任务存在性和权限
            RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
            if (task == null) {
                log.warn("[恢复回收站] 任务不存在 - BatchId: {}", batchId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(40401, "回收站任务不存在或已处理"));
            }
            
            log.info("[恢复回收站] 查询到任务 - BatchId: {}, Status: {}, OperationType: {}, RootNodeId: {}",
                batchId, task.getStatus(), task.getOperationType(), task.getRootNodeId());
            
            // 验证权限
            if (!userId.equals(task.getUserId())) {
                log.warn("[恢复回收站] 权限不足 - UserId: {}, TaskUserId: {}", userId, task.getUserId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(40301, "无权恢复该节点"));
            }
            
            // 【关键】检查 operation_type 是否为 0（在回收站中）
            if (task.getOperationType() != 0) {
                log.warn("[恢复回收站] 任务不是删除任务，无法恢复 - BatchId: {}, OperationType: {}", 
                    batchId, task.getOperationType());
                return ResponseEntity.badRequest()
                    .body(Result.error(40001, "该节点不在回收站中或已被其他操作处理"));
            }
            
            // 【关键】如果任务状态是 1（已完成），说明之前恢复过，需要重置状态
            if (task.getStatus() == 1) {
                log.info("[恢复回收站] 任务之前已完成，重置状态为进行中 - BatchId: {}", batchId);
                recycleBinTaskMapper.updateTaskStatus(batchId, 0); // 重置为进行中
            }
            
            // 【新架构】调用同步恢复方法
            log.info("[恢复回收站] 开始恢复 - UserId: {}, BatchId: {}", userId, batchId);
            com.mizuka.cloudfilesystem.dto.RestoreResult result = directoryService.restoreNode(batchId, userId);
            
            log.info("用户 {} 恢复节点成功（新架构）- BatchId: {}, RootNodeId: {}, Result: {}", 
                userId, batchId, task.getRootNodeId(), result.getMessage());
            
            return ResponseEntity.ok(Result.success("恢复成功", null));
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(Result.error(40001, e.getMessage()));
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问") || e.getMessage().contains("无权恢复")) {
                log.warn("权限不足: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.error(40301, e.getMessage()));
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(40401, e.getMessage()));
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error(50001, e.getMessage()));
            }
        }
    }
    
    /**
     * 彻底删除节点（从回收站中永久删除，支持两种模式）
     * DELETE /files/delete/permanent
     * 
     * @param mode 模式：true=回收站模式，false=浏览界面模式
     * @param batchId 业务操作批次号（mode=true时需填写）
     * @param nodeId 节点ID（mode=false时需填写）
     * @param nodeType 节点类型（mode=false时需填写，0=文件夹，1=文件）
     * @param version 乐观锁版本号（mode=false时需填写）
     * @return 操作结果
     */
    @DeleteMapping("/delete/permanent")
    public Result<Void> permanentDeleteNode(
            @RequestParam Boolean mode,
            @RequestParam(required = false) String batchId,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) Integer nodeType,
            @RequestParam(required = false) Long version) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 参数校验
            if (mode == null) {
                return Result.error(40001, "mode参数不能为空");
            }
            
            String targetBatchId;
            
            if (mode) {
                // 【回收站模式】：需要 batchId
                if (batchId == null || batchId.trim().isEmpty()) {
                    return Result.error(40001, "回收站模式必须提供 batchId");
                }
                
                // 通过 batchId 查找任务
                RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
                if (task == null) {
                    return Result.error(40401, "回收站任务不存在或已处理");
                }
                
                // 验证权限
                if (!userId.equals(task.getUserId())) {
                    return Result.error(40301, "无权删除该节点");
                }
                
                // 【关键】检查 operation_type 是否为 0（在回收站中）
                if (task.getOperationType() != 0) {
                    log.warn("[彻底删除] 节点不在回收站中 - BatchId: {}, OperationType: {}", 
                        batchId, task.getOperationType());
                    return Result.error(40001, "该节点不在回收站中或已被其他操作处理");
                }
                
                // 【关键】如果任务状态是 1（已完成），说明之前处理过，需要重置状态
                if (task.getStatus() == 1) {
                    log.info("[彻底删除] 任务之前已完成，重置状态为进行中 - BatchId: {}", batchId);
                    recycleBinTaskMapper.updateTaskStatus(batchId, 0);
                }
                
                // 【关键】将 operation_type 更新为 2（标记为彻底删除任务）
                log.info("[彻底删除] 更新 operation_type 为 2（彻底删除中）- BatchId: {}", batchId);
                recycleBinTaskMapper.updateOperationType(batchId, 2);
                
                targetBatchId = batchId;  // 沿用旧的 batchId
                
                log.info("用户 {} 回收站模式彻底删除 - BatchId: {}, NodeId: {}, NodeType: {}", 
                    userId, targetBatchId, task.getRootNodeId(), task.getNodeType());
                
            } else {
                // 【浏览界面模式】：需要 nodeId, nodeType 和 version
                if (nodeId == null) {
                    return Result.error(40001, "浏览界面模式必须提供 nodeId");
                }
                
                if (nodeType == null) {
                    return Result.error(40001, "浏览界面模式必须提供 nodeType");
                }
                
                // 检查是否有正在进行的异步操作（删除或恢复）
                RecycleBinTask existingTask = recycleBinTaskMapper.findByRootNodeId(nodeId);
                if (existingTask != null && existingTask.getStatus() == 0) { // 进行中
                    String operationName = existingTask.getOperationType() == 0 ? "删除" : "恢复";
                    log.info("用户 {} 检测到{}任务正在进行，先终止 - BatchId: {}, NodeId: {}", 
                        userId, operationName, existingTask.getBatchId(), nodeId);
                    
                    // 终止异步操作
                    recycleBinTaskMapper.updateTask(existingTask.getBatchId(), 3, LocalDateTime.now(), 
                        "用户主动终止（开始彻底删除）", null, null);
                    
                    targetBatchId = existingTask.getBatchId();  // 沿用旧的 batchId
                    
                    // 【关键】重置状态并更新 operation_type
                    recycleBinTaskMapper.updateTaskStatus(targetBatchId, 0);
                    recycleBinTaskMapper.updateOperationType(targetBatchId, 2);
                    
                } else {
                    // 没有正在进行的任务，创建新的彻底删除任务
                    targetBatchId = asyncPermanentDeleteService.createPermanentDeleteTask(
                        nodeId, nodeType, userId);
                    
                    // 【关键】将 operation_type 更新为 2（标记为彻底删除任务）
                    log.info("[彻底删除] 更新 operation_type 为 2（彻底删除中）- BatchId: {}", targetBatchId);
                    recycleBinTaskMapper.updateOperationType(targetBatchId, 2);
                    
                    log.info("用户 {} 浏览界面模式彻底删除（新建任务）- BatchId: {}, NodeId: {}, NodeType: {}", 
                        userId, targetBatchId, nodeId, nodeType);
                }
            }
            
            // 【新架构】调用同步彻底删除方法
            directoryService.permanentDeleteBatch(targetBatchId, userId);
            
            log.info("用户 {} 彻底删除完成（新架构）- BatchId: {}", userId, targetBatchId);
            
            return Result.success("彻底删除成功", null);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (RuntimeException e) {
            if (e.getMessage().contains("无权访问") || e.getMessage().contains("无权删除")) {
                log.warn("权限不足: {}", e.getMessage());
                return Result.error(40301, e.getMessage());
            } else if (e.getMessage().contains("不存在")) {
                log.warn("资源不存在: {}", e.getMessage());
                return Result.error(40401, e.getMessage());
            } else {
                log.error("服务器错误: {}", e.getMessage(), e);
                return Result.error(50001, e.getMessage());
            }
        }
    }


    /**
     * 搜索文件或文件夹（统一游标分页版本）
     */
    @GetMapping("/search")
    public Result<SearchResponse> search(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "all") String type,
            @RequestParam(required = false) Integer sumFolders,
            @RequestParam(required = false) Integer sumFiles,
            @RequestParam(required = false) Long lastFoldersNode,
            @RequestParam(required = false) Long lastFilesNode,
            @RequestParam(required = false) Integer maxPageSize) {

        try {
            Long userId = SecurityUtils.getCurrentUserId();

            if (userId == null) {
                return Result.error(401, "未认证或会话已过期");
            }

            SearchResponse response = directoryService.searchWithCursor(
                    keyword, userId, type, sumFolders, sumFiles,
                    lastFoldersNode, lastFilesNode, maxPageSize
            );
            return Result.success(response);

        } catch (IllegalArgumentException e) {
            log.warn("搜索参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("搜索失败: {}", e.getMessage(), e);
            return Result.error(50001, "搜索失败，请稍后重试");
        }
    }
    
    /**
     * 搜索回收站内容（统一游标分页版本）
     */
    @GetMapping("/recycle/search")
    public Result<SearchResponse> searchRecycleBin(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "all") String type,
            @RequestParam(required = false) Integer sumFolders,
            @RequestParam(required = false) Integer sumFiles,
            @RequestParam(required = false) Long lastFoldersNode,
            @RequestParam(required = false) Long lastFilesNode,
            @RequestParam(required = false) Integer maxPageSize) {

        try {
            Long userId = SecurityUtils.getCurrentUserId();

            if (userId == null) {
                return Result.error(401, "未认证或会话已过期");
            }

            // 回收站搜索模式：isRecycleBin=true
            SearchResponse response = directoryService.searchWithCursor(
                    keyword, userId, type, sumFolders, sumFiles,
                    lastFoldersNode, lastFilesNode, maxPageSize, true
            );
            return Result.success(response);

        } catch (IllegalArgumentException e) {
            log.warn("回收站搜索参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
        } catch (Exception e) {
            log.error("回收站搜索失败: {}", e.getMessage(), e);
            return Result.error(50001, "搜索失败，请稍后重试");
        }
    }
    
    /**
     * 浏览回收站（基于 recycle_bin_tasks 表）
     * 
     * @param maxPageSize 每页数量
     * @param lastBatchId 游标锚点（batch_id）
     * @return 回收站浏览响应
     */
    @GetMapping("/recycle")
    public Result<RecycleBinBrowseResponse> browseRecycleBin(
            @RequestParam(required = false, defaultValue = "20") Integer maxPageSize,
            @RequestParam(required = false) String lastBatchId) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 调用新的 RecycleBinService
            RecycleBinBrowseResponse response = recycleBinService.browseRecycleBin(
                userId, maxPageSize, lastBatchId
            );
            
            return Result.success(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error(40001, e.getMessage());
            
        } catch (RuntimeException e) {
            log.error("服务器错误: {}", e.getMessage(), e);
            return Result.error(50001, "系统繁忙，请稍后重试");
        }
    }
    
    /**
     * 获取恢复进程列表
     * GET /files/recycle/restore/processes
     * 
     * @return 恢复进程列表
     */
    @GetMapping("/recycle/restore/processes")
    public Result<List<RestoreProcessInfo>> getRestoreProcesses() {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 查询当前用户的恢复任务（operation_type=1 表示恢复）
            List<RecycleBinTask> tasks = recycleBinTaskMapper.findInProgressRestoreTasks(userId);
            
            // 转换为响应格式
            List<RestoreProcessInfo> processes = tasks.stream()
                .map(task -> {
                    RestoreProcessInfo info = new RestoreProcessInfo();
                    info.setBatchId(task.getBatchId());
                    info.setNodeId(task.getRootNodeId());
                    
                    // 获取节点名称
                    FolderNode folder = folderNodeMapper.findById(task.getRootNodeId());
                    if (folder != null) {
                        info.setNodeName(folder.getName());
                    } else {
                        FileNode file = fileNodeMapper.findById(task.getRootNodeId());
                        info.setNodeName(file != null ? file.getName() : "未知节点");
                    }
                    
                    info.setStatus(task.getStatus());
                    info.setTotalCount(task.getTotalCount());
                    info.setProcessedCount(task.getProcessedCount());
                    info.setCreatedAt(task.getCreatedAt());
                    
                    return info;
                })
                .toList();
            
            log.info("用户 {} 获取恢复进程列表 - 数量: {}", userId, processes.size());
            
            return Result.success("获取成功", processes);
            
        } catch (Exception e) {
            log.error("获取恢复进程列表失败: {}", e.getMessage(), e);
            return Result.error(50001, "系统繁忙，请稍后重试");
        }
    }
}
