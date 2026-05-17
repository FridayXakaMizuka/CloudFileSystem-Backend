package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.*;
import com.mizuka.cloudfilesystem.service.DirectoryService;
import com.mizuka.cloudfilesystem.util.Result;
import com.mizuka.cloudfilesystem.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    
    /**
     * 浏览目录内容（游标分页，支持多种排序）
     * 
     * @param currentNodeId 当前节点ID
     * @param lastChildrenNode 游标锚点ID
     * @param lastChildrenType 游标锚点类型
     * @param maxPageSize 期望的最大返回数量
     * @param sortedBy 排序字段：0=createdAt(默认), 1=name, 2=editedAt
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
     * @return 删除结果
     */
    @DeleteMapping("/{nodeId}")
    public Result<DeleteNodeResponse> deleteNode(@PathVariable Long nodeId) {
        
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
            
            DeleteNodeResponse response = directoryService.deleteNode(nodeId, userId);
            
            log.info("用户 {} 删除节点成功 - NodeId: {}, RecycleBinPath: {}, ExpiresAt: {}", 
                userId, nodeId, response.getRecycleBinPath(), response.getExpiresAt());
            
            return Result.success("已移入回收站，30天后彻底删除", response);
            
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
     * 恢复回收站中的节点
     * 
     * @param nodeId 节点ID
     * @return 恢复结果
     */
    @PostMapping("/recycle/restore/{nodeId}")
    public Result<RestoreNodeResponse> restoreNode(@PathVariable Long nodeId) {
        
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
            
            RestoreNodeResponse response = directoryService.restoreNode(nodeId, userId);
            
            log.info("用户 {} 恢复节点成功 - NodeId: {}, RestoredPath: {}", 
                userId, nodeId, response.getRestoredPath());
            
            return Result.success("恢复成功", response);
            
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
     * 彻底删除节点（从回收站中永久删除）
     * 
     * @param nodeId 节点ID
     * @return 操作结果
     */
    @DeleteMapping("/permanent/{nodeId}")
    public Result<Void> permanentDeleteNode(@PathVariable Long nodeId) {
        
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
            
            directoryService.permanentDeleteNode(nodeId, userId);
            
            log.info("用户 {} 彻底删除节点成功 - NodeId: {}", userId, nodeId);
            
            return Result.success("已彻底删除，目录进入待分配池", null);
            
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
     * 浏览回收站内容（游标分页）
     * 
     * @param currentNodeId 回收站根节点ID
     * @param lastChildrenNode 游标锚点ID
     * @param lastChildrenType 游标锚点类型
     * @param maxPageSize 期望的最大返回数量
     * @param sortedBy 排序字段：0=createdAt(默认), 1=name, 2=editedAt
     * @param order 排序顺序：0=asc, 1=desc
     * @return 目录浏览响应
     */
    @GetMapping("/recycle")
    public Result<DirectoryBrowseResponse> browseRecycleBin(
            @RequestParam Long currentNodeId,
            @RequestParam(required = false) Long lastChildrenNode,
            @RequestParam(required = false) String lastChildrenType,
            @RequestParam(required = false) Integer maxPageSize,
            @RequestParam(required = false, defaultValue = "0") Integer sortedBy,
            @RequestParam(required = false, defaultValue = "1") Integer order) {
        
        try {
            // 从JWT令牌中获取当前用户ID
            Long userId = SecurityUtils.getCurrentUserId();
            
            if (userId == null) {
                log.warn("未获取到用户ID，请确认已登录并携带有效的JWT令牌");
                return Result.error(401, "未认证或会话已过期");
            }
            
            // 回收站模式：isRecycleBin=true
            DirectoryBrowseResponse response = directoryService.browseDirectory(
                    currentNodeId,
                    lastChildrenNode,
                    lastChildrenType,
                    maxPageSize,
                    userId,
                    sortedBy,
                    order,
                    null,  // excludeNewFileIds
                    null,  // excludeNewFolderIds
                    true   // isRecycleBin
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
}
