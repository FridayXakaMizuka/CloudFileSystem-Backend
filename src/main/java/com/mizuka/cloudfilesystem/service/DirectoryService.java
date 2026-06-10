package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.*;
import com.mizuka.cloudfilesystem.entity.FileNode;
import com.mizuka.cloudfilesystem.entity.FolderNode;
import com.mizuka.cloudfilesystem.entity.RecycleBinTask;
import com.mizuka.cloudfilesystem.mapper.FileNodeMapper;
import com.mizuka.cloudfilesystem.mapper.FolderNodeMapper;
import com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 目录服务（完整版）
 */
@Slf4j
@Service
public class DirectoryService {
    
    @Autowired
    private FolderNodeMapper folderNodeMapper;
    
    @Autowired
    private FileNodeMapper fileNodeMapper;
    
    @Autowired
    private AsyncDirectoryDeleteService asyncDirectoryDeleteService;
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    @Autowired
    private RecycleBinRedisService recycleBinRedisService;
    
    /**
     * 默认每页最大数量
     */
    private static final int DEFAULT_MAX_PAGE_SIZE = 50;

    
    /**
     * 绝对最大每页数量（防止恶意请求）
     */
    private static final int ABSOLUTE_MAX_PAGE_SIZE = 200;
    
    /**
     * 浏览目录（游标分页，支持多种排序和排除列表）
     * 
     * @param currentNodeId 当前节点ID
     * @param lastChildrenNode 游标锚点ID
     * @param lastChildrenType 游标锚点类型
     * @param maxPageSize 期望的最大返回数量
     * @param userId 用户ID
     * @param sortedBy 排序字段：0=name, 1=size（只对文件起效，文件夹与0等效）, 2=createdAt, 3=updatedAt
     * @param order 排序顺序：0=asc, 1=desc
     * @param excludeNewFileIds 需要排除的新增文件ID列表
     * @param excludeNewFolderIds 需要排除的新增文件夹ID列表
     * @param isRecycleBin 是否为回收站模式
     * @return 目录浏览响应
     */
    public DirectoryBrowseResponse browseDirectory(Long currentNodeId, 
                                                    Long lastChildrenNode,
                                                    String lastChildrenType,
                                                    Integer maxPageSize,
                                                    Long userId,
                                                    Integer sortedBy,
                                                    Integer order,
                                                    List<Long> excludeNewFileIds,
                                                    List<Long> excludeNewFolderIds,
                                                    boolean isRecycleBin) {
        // 1. 参数校验
        if (currentNodeId == null) {
            throw new IllegalArgumentException("currentNodeId 不能为空");
        }
        
        // 默认值处理
        final int finalSortedBy = (sortedBy == null) ? 0 : sortedBy;
        final int finalOrder = (order == null) ? 1 : order;
        
        // 限制每页最大数量
        int pageSize = (maxPageSize == null || maxPageSize <= 0) 
                ? DEFAULT_MAX_PAGE_SIZE 
                : Math.min(maxPageSize, ABSOLUTE_MAX_PAGE_SIZE);
        
        // 2. 查询当前节点信息并校验权限
        FolderNode currentNode;
        if (isRecycleBin) {
            // 回收站模式：查询回收站中的文件夹
            currentNode = folderNodeMapper.findInRecycleBinById(currentNodeId);
        } else {
            // 普通模式：查询活跃文件夹
            currentNode = folderNodeMapper.findById(currentNodeId);
        }
        
        if (currentNode == null) {
            throw new RuntimeException("指定的目录节点不存在");
        }
        
        // 权限校验：验证用户是否有权访问该目录
        if (currentNode.getUserId() != null && !userId.equals(currentNode.getUserId())) {
            throw new RuntimeException("无权访问该目录");
        }
        
        // 3. 解析游标参数
        LocalDateTime lastCreatedAt = null;
        LocalDateTime lastUpdatedAt = null;
        String lastName = null;
        
        if (lastChildrenNode != null && lastChildrenType != null) {
            SortCursorValues cursorValues = getSortCursorValues(lastChildrenNode, lastChildrenType);
            lastCreatedAt = cursorValues.getCreatedAt();
            lastUpdatedAt = cursorValues.getUpdatedAt();
            lastName = cursorValues.getName();
        }

        // 4. 定义结果集
        List<DirectoryNodeVO> resultChildren = new ArrayList<>();
        int remainingSlots = pageSize;
        String orderStr = (finalOrder == 0) ? "asc" : "desc";
        
        // 5. 决定查询顺序：升序时文件夹优先，降序时文件优先
        boolean fileFirst = (finalOrder == 1); // desc 时文件优先
                
        if (fileFirst) {
            // 降序：先查文件
            resultChildren = queryFilesFirst(currentNodeId, userId, lastChildrenNode, lastChildrenType,
                    lastCreatedAt, lastUpdatedAt, lastName, remainingSlots,
                    finalSortedBy, orderStr, isRecycleBin, excludeNewFileIds, excludeNewFolderIds);
        } else {
            // 升序：先查文件夹（原有逻辑）
            resultChildren = queryFoldersFirst(currentNodeId, userId, lastChildrenNode, lastChildrenType,
                    lastCreatedAt, lastUpdatedAt, lastName, remainingSlots,
                    finalSortedBy, orderStr, isRecycleBin, excludeNewFileIds, excludeNewFolderIds);
        }
        
        // 7. 判断是否到达末尾
        boolean isEnd = resultChildren.size() < pageSize;
        
        // 8. 构建响应
        CursorPagination pagination = buildPagination(resultChildren, isEnd);
        
        // 优化：批量查询当前节点的子节点数量
        Map<Long, Integer> childCountMap = new HashMap<>();
        List<Map<String, Object>> countResults = folderNodeMapper.batchCountChildren(
            Collections.singletonList(currentNodeId), userId
        );
        if (!countResults.isEmpty()) {
            Long childCount = ((Number) countResults.get(0).get("child_count")).longValue();
            childCountMap.put(currentNodeId, childCount.intValue());
        }
        DirectoryNodeVO currentNodeVO = convertFolderToVO(currentNode, childCountMap);
        
        DirectoryBrowseResponse response = new DirectoryBrowseResponse();
        response.setCurrentNode(currentNodeVO);
        response.setChildren(resultChildren);
        response.setPagination(pagination);
        
        log.info("用户 {} 浏览目录 {}, 排序={}, 顺序={}, 返回 {} 个子节点", 
            userId, currentNodeId, finalSortedBy, finalOrder, resultChildren.size());
        
        return response;
    }
    
    /**
     * 文件夹优先查询（升序时使用）
     */
    private List<DirectoryNodeVO> queryFoldersFirst(Long currentNodeId, Long userId,
                                                     Long lastChildrenNode, String lastChildrenType,
                                                     LocalDateTime lastCreatedAt, LocalDateTime lastUpdatedAt,
                                                     String lastName, int remainingSlots,
                                                     int sortedBy, String orderStr,
                                                     boolean isRecycleBin,
                                                     List<Long> excludeNewFileIds,
                                                     List<Long> excludeNewFolderIds) {
        List<DirectoryNodeVO> resultChildren = new ArrayList<>();
        
        // 1. 先查文件夹
        if (lastChildrenNode == null || lastChildrenType == null || "folder".equals(lastChildrenType)) {
            int folderLimit = remainingSlots;
            
            List<FolderNode> childFolders;
            if (isRecycleBin) {
                childFolders = folderNodeMapper.findRecycleBinChildrenWithSortCursor(
                        currentNodeId, userId,
                        lastCreatedAt, lastUpdatedAt, lastName,
                        lastChildrenNode, folderLimit,
                        sortedBy, orderStr);
            } else {
                childFolders = folderNodeMapper.findChildrenWithSortCursor(
                        currentNodeId, userId,
                        lastCreatedAt, lastUpdatedAt, lastName,
                        lastChildrenNode, folderLimit,
                        sortedBy, orderStr);
            }
            
            log.debug("[查询文件夹] 原始数量: {}, 排除列表: {}", 
                childFolders.size(), 
                excludeNewFolderIds != null ? excludeNewFolderIds : "null");
            
            List<DirectoryNodeVO> folderVOs = convertFoldersToVO(childFolders, userId);
            
            if (excludeNewFolderIds != null && !excludeNewFolderIds.isEmpty()) {
                int beforeSize = folderVOs.size();
                folderVOs = folderVOs.stream()
                    .filter(vo -> !excludeNewFolderIds.contains(vo.getId()))
                    .toList();
                log.debug("[查询文件夹] 过滤后数量: {} -> {}, 被排除的ID: {}", 
                    beforeSize, folderVOs.size(), excludeNewFolderIds);
            }
            
            resultChildren.addAll(folderVOs);
            remainingSlots -= folderVOs.size();
        }
        
        // 2. 再查文件补充
        if (remainingSlots > 0) {
            Long fileLastId = null;
            LocalDateTime fileLastCreatedAt = null;
            LocalDateTime fileLastUpdatedAt = null;
            String fileLastName = null;
            Long fileLastSize = null;
            
            if (lastChildrenNode == null || lastChildrenType == null) {
                fileLastId = null;
            } else if ("file".equals(lastChildrenType)) {
                fileLastId = lastChildrenNode;
                fileLastCreatedAt = lastCreatedAt;
                fileLastUpdatedAt = lastUpdatedAt;
                fileLastName = lastName;
                // Get size from cursor values
                SortCursorValues cursorValues = getSortCursorValues(lastChildrenNode, lastChildrenType);
                fileLastSize = cursorValues.getSize();
            }
            
            List<FileNode> childFiles;
            if (isRecycleBin) {
                childFiles = fileNodeMapper.findRecycleBinChildrenWithSortCursor(
                        currentNodeId, userId,
                        fileLastCreatedAt, fileLastUpdatedAt, fileLastName,
                        fileLastSize, fileLastId, remainingSlots,
                        sortedBy, orderStr);
            } else {
                childFiles = fileNodeMapper.findChildrenWithSortCursor(
                        currentNodeId, userId,
                        fileLastCreatedAt, fileLastUpdatedAt, fileLastName,
                        fileLastSize, fileLastId, remainingSlots,
                        sortedBy, orderStr);
            }
            
            log.debug("[查询文件] 原始数量: {}, 排除列表: {}", 
                childFiles.size(), 
                excludeNewFileIds != null ? excludeNewFileIds : "null");
            
            List<DirectoryNodeVO> fileVOs = convertFilesToVO(childFiles);
            
            if (excludeNewFileIds != null && !excludeNewFileIds.isEmpty()) {
                int beforeSize = fileVOs.size();
                fileVOs = fileVOs.stream()
                    .filter(vo -> !excludeNewFileIds.contains(vo.getId()))
                    .toList();
                log.debug("[查询文件] 过滤后数量: {} -> {}, 被排除的ID: {}", 
                    beforeSize, fileVOs.size(), excludeNewFileIds);
            }
            
            resultChildren.addAll(fileVOs);
        }
        
        return resultChildren;
    }
    
    /**
     * 文件优先查询（降序时使用）
     */
    private List<DirectoryNodeVO> queryFilesFirst(Long currentNodeId, Long userId,
                                                   Long lastChildrenNode, String lastChildrenType,
                                                   LocalDateTime lastCreatedAt, LocalDateTime lastUpdatedAt,
                                                   String lastName, int remainingSlots,
                                                   int sortedBy, String orderStr,
                                                   boolean isRecycleBin,
                                                   List<Long> excludeNewFileIds,
                                                   List<Long> excludeNewFolderIds) {
        List<DirectoryNodeVO> resultChildren = new ArrayList<>();
        
        // 1. 先查文件
        if (lastChildrenNode == null || lastChildrenType == null || "file".equals(lastChildrenType)) {
            int fileLimit = remainingSlots;
            
            Long fileLastId = null;
            LocalDateTime fileLastCreatedAt = null;
            LocalDateTime fileLastUpdatedAt = null;
            String fileLastName = null;
            Long fileLastSize = null;
            
            if (lastChildrenNode == null || lastChildrenType == null) {
                fileLastId = null;
            } else if ("file".equals(lastChildrenType)) {
                fileLastId = lastChildrenNode;
                fileLastCreatedAt = lastCreatedAt;
                fileLastUpdatedAt = lastUpdatedAt;
                fileLastName = lastName;
                // Get size from cursor values
                SortCursorValues cursorValues = getSortCursorValues(lastChildrenNode, lastChildrenType);
                fileLastSize = cursorValues.getSize();
            }
            
            List<FileNode> childFiles;
            if (isRecycleBin) {
                childFiles = fileNodeMapper.findRecycleBinChildrenWithSortCursor(
                        currentNodeId, userId,
                        fileLastCreatedAt, fileLastUpdatedAt, fileLastName,
                        fileLastSize, fileLastId, fileLimit,
                        sortedBy, orderStr);
            } else {
                childFiles = fileNodeMapper.findChildrenWithSortCursor(
                        currentNodeId, userId,
                        fileLastCreatedAt, fileLastUpdatedAt, fileLastName,
                        fileLastSize, fileLastId, fileLimit,
                        sortedBy, orderStr);
            }
            
            List<DirectoryNodeVO> fileVOs = convertFilesToVO(childFiles);
            
            if (excludeNewFileIds != null && !excludeNewFileIds.isEmpty()) {
                fileVOs = fileVOs.stream()
                    .filter(vo -> !excludeNewFileIds.contains(vo.getId()))
                    .toList();
            }
            
            resultChildren.addAll(fileVOs);
            remainingSlots -= fileVOs.size();
        }
        
        // 2. 再查文件夹补充
        if (remainingSlots > 0) {
            Long folderLastId = null;
            LocalDateTime folderLastCreatedAt = null;
            LocalDateTime folderLastUpdatedAt = null;
            String folderLastName = null;
            
            if (lastChildrenNode == null || lastChildrenType == null) {
                folderLastId = null;
            } else if ("folder".equals(lastChildrenType)) {
                folderLastId = lastChildrenNode;
                folderLastCreatedAt = lastCreatedAt;
                folderLastUpdatedAt = lastUpdatedAt;
                folderLastName = lastName;
            }
            
            List<FolderNode> childFolders;
            if (isRecycleBin) {
                childFolders = folderNodeMapper.findRecycleBinChildrenWithSortCursor(
                        currentNodeId, userId,
                        folderLastCreatedAt, folderLastUpdatedAt, folderLastName,
                        folderLastId, remainingSlots,
                        sortedBy, orderStr);
            } else {
                childFolders = folderNodeMapper.findChildrenWithSortCursor(
                        currentNodeId, userId,
                        folderLastCreatedAt, folderLastUpdatedAt, folderLastName,
                        folderLastId, remainingSlots,
                        sortedBy, orderStr);
            }
            
            List<DirectoryNodeVO> folderVOs = convertFoldersToVO(childFolders, userId);
            
            if (excludeNewFolderIds != null && !excludeNewFolderIds.isEmpty()) {
                folderVOs = folderVOs.stream()
                    .filter(vo -> !excludeNewFolderIds.contains(vo.getId()))
                    .toList();
            }
            
            resultChildren.addAll(folderVOs);
        }
        
        return resultChildren;
    }
    
    /**
     * 创建文件夹（支持从待分配池复用）
     * 
     * @param parentId 父节点ID
     * @param folderName 文件夹名称
     * @param userId 用户ID
     * @return 创建结果
     */
    @Transactional
    public NewFolderResponse createFolder(Long parentId, String folderName, Long userId) {
        // 1. 参数校验
        if (parentId == null) {
            throw new IllegalArgumentException("parentId 不能为空");
        }
        
        if (folderName == null || folderName.trim().isEmpty()) {
            throw new IllegalArgumentException("文件夹名称不能为空");
        }
        
        // 2. 验证父节点存在且有权访问
        FolderNode parentNode = folderNodeMapper.findById(parentId);
        if (parentNode == null) {
            throw new RuntimeException("父目录不存在");
        }
        
        if (parentNode.getUserId() != null && !userId.equals(parentNode.getUserId())) {
            throw new RuntimeException("无权在该目录下创建文件夹");
        }
        
        // 3. 检查当前目录下是否存在同名文件夹
        FolderNode existingFolder = folderNodeMapper.findByNameAndParent(parentId, folderName.trim(), userId);
        if (existingFolder != null) {
            throw new RuntimeException("当前目录下已存在同名文件夹: " + folderName.trim());
        }
        
        // 4. 尝试从待分配池复用文件夹
        FolderNode reusedFolder = folderNodeMapper.findAndClaimUnassignedFolder(userId);
        
        if (reusedFolder != null) {
            // 复用了待分配文件夹，更新其信息
            reusedFolder.setParentId(parentId);
            reusedFolder.setName(folderName);
            reusedFolder.setUserId(userId);
            reusedFolder.setUpdatedAt(LocalDateTime.now());
            
            // 更新路径（需要根据父路径重新计算）
            String parentPath = parentNode.getPath();
            String newPath = parentPath + "/" + folderName;
            reusedFolder.setPath(newPath);
            
            folderNodeMapper.updateFolderInfo(reusedFolder);
            
            log.info("用户 {} 复用待分配文件夹 - FolderId: {}", userId, reusedFolder.getId());
            
            return new NewFolderResponse(
                reusedFolder.getId(),
                reusedFolder.getName(),
                reusedFolder.getPath(),
                true  // 从池中复用
            );
        }
        
        // 5. 如果没有可复用的文件夹，则创建新的
        FolderNode newFolder = new FolderNode();
        newFolder.setParentId(parentId);
        newFolder.setUserId(userId);
        newFolder.setName(folderName);
        newFolder.setLevel(parentNode.getLevel() + 1);
        newFolder.setIsHidden(false);
        newFolder.setIsDeleted(false);
        newFolder.setDirectoryStatus("active");
        newFolder.setCreatedAt(LocalDateTime.now());
        newFolder.setUpdatedAt(LocalDateTime.now());
        
        // 构建路径
        String parentPath = parentNode.getPath();
        String newPath = parentPath + "/" + folderName;
        newFolder.setPath(newPath);
        
        // 插入数据库
        folderNodeMapper.insertFolder(newFolder);
        
        log.info("用户 {} 创建新文件夹 - FolderId: {}", userId, newFolder.getId());
        
        return new NewFolderResponse(
            newFolder.getId(),
            newFolder.getName(),
            newFolder.getPath(),
            false  // 新建而非复用
        );
    }
    
    /**
     * 重命名节点（文件夹或文件）
     * 
     * @param nodeId 节点ID
     * @param newName 新名称
     * @param userId 用户ID
     */
    @Transactional
    public void renameNode(Long nodeId, String newName, Long userId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("新名称不能为空");
        }
        
        // 2. 先尝试查找文件夹
        FolderNode folder = folderNodeMapper.findById(nodeId);
        
        if (folder != null) {
            // 是文件夹，验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权重命名该文件夹");
            }
            
            // 更新文件夹名称和路径
            String oldPath = folder.getPath();
            String parentPath = oldPath.substring(0, oldPath.lastIndexOf("/"));
            String newPath = parentPath + "/" + newName;
            
            folderNodeMapper.renameFolder(nodeId, newName, newPath);
            
            // 如果是文件夹，需要递归更新所有子节点的路径
            updateChildrenPaths(oldPath, newPath);
            
            log.info("用户 {} 重命名文件夹 - NodeId: {}, OldName: {}, NewName: {}", 
                userId, nodeId, folder.getName(), newName);
            
        } else {
            // 尝试查找文件
            FileNode file = fileNodeMapper.findById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("节点不存在");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权重命名该文件");
            }
            
            // 更新文件名称和路径
            String oldPath = file.getPath();
            String parentPath = oldPath.substring(0, oldPath.lastIndexOf("/"));
            String newPath = parentPath + "/" + newName;
            
            fileNodeMapper.renameFile(nodeId, newName, newPath);
            
            log.info("用户 {} 重命名文件 - NodeId: {}, OldName: {}, NewName: {}", 
                userId, nodeId, file.getName(), newName);
        }
    }
    
    /**
     * 移动节点（文件夹或文件）
     * 
     * @param nodeId 节点ID
     * @param newParentId 新父节点ID
     * @param userId 用户ID
     */
    @Transactional
    public void moveNode(Long nodeId, Long newParentId, Long userId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        if (newParentId == null) {
            throw new IllegalArgumentException("新父节点ID不能为空");
        }
        
        // 2. 验证新父节点存在且有权访问
        FolderNode newParent = folderNodeMapper.findById(newParentId);
        if (newParent == null) {
            throw new RuntimeException("目标目录不存在");
        }
        
        if (newParent.getUserId() != null && !userId.equals(newParent.getUserId())) {
            throw new RuntimeException("无权移动到该目录");
        }
        
        // 3. 防止将文件夹移动到自己或其子文件夹下
        if (isDescendant(nodeId, newParentId)) {
            throw new RuntimeException("不能将文件夹移动到自己或其子文件夹下");
        }
        
        // 4. 先尝试查找文件夹
        FolderNode folder = folderNodeMapper.findById(nodeId);
        
        if (folder != null) {
            // 是文件夹，验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权移动该文件夹");
            }
            
            // 更新文件夹的父节点和路径
            String oldPath = folder.getPath();
            String newPath = newParent.getPath() + "/" + folder.getName();
            
            folderNodeMapper.moveFolder(nodeId, newParentId, newPath);
            
            // 递归更新所有子节点的路径
            updateChildrenPaths(oldPath, newPath);
            
            log.info("用户 {} 移动文件夹 - NodeId: {}, OldParentId: {}, NewParentId: {}", 
                userId, nodeId, folder.getParentId(), newParentId);
            
        } else {
            // 尝试查找文件
            FileNode file = fileNodeMapper.findById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("节点不存在");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权移动该文件");
            }
            
            // 更新文件的父节点和路径
            String oldPath = file.getPath();
            String newPath = newParent.getPath() + "/" + file.getName();
            
            fileNodeMapper.moveFile(nodeId, newParentId, newPath);
            
            log.info("用户 {} 移动文件 - NodeId: {}, OldParentId: {}, NewParentId: {}", 
                userId, nodeId, file.getFolderId(), newParentId);
        }
    }
    
    /**
     * 递归更新子节点的路径
     * 
     * @param oldPathPrefix 旧路径前缀
     * @param newPathPrefix 新路径前缀
     */
    private void updateChildrenPaths(String oldPathPrefix, String newPathPrefix) {
        // 更新所有以 oldPathPrefix 开头的文件夹路径
        folderNodeMapper.updateChildrenPaths(oldPathPrefix, newPathPrefix);
        
        // 更新所有以 oldPathPrefix 开头的文件路径
        fileNodeMapper.updateChildrenPaths(oldPathPrefix, newPathPrefix);
    }
    
    /**
     * 检查 targetId 是否是 nodeId 的后代节点
     * 
     * @param nodeId 节点ID
     * @param targetId 目标节点ID
     * @return true 如果 targetId 是 nodeId 的后代
     */
    private boolean isDescendant(Long nodeId, Long targetId) {
        // 如果相等，说明是同一个节点
        if (nodeId.equals(targetId)) {
            return true;
        }
        
        // 查询 targetId 的所有祖先节点
        List<Long> ancestors = folderNodeMapper.findAncestorIds(targetId);
        
        // 检查 nodeId 是否在祖先列表中
        return ancestors.contains(nodeId);
    }
    
    /**
     * 构建分页信息
     */
    private CursorPagination buildPagination(List<DirectoryNodeVO> children, boolean isEnd) {
        if (children.isEmpty()) {
            return new CursorPagination(null, null, true);
        }
        
        DirectoryNodeVO lastNode = children.getLast();
        return new CursorPagination(
                lastNode.getId(),
                lastNode.getType(),
                isEnd
        );
    }
    
    /**
     * 转换文件夹列表为VO
     * 优化版：批量查询子节点数量，避免 N+1 问题
     */
    private List<DirectoryNodeVO> convertFoldersToVO(List<FolderNode> folders, Long userId) {
        if (folders == null || folders.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 1. 收集所有文件夹 ID
        List<Long> folderIds = folders.stream()
            .map(FolderNode::getId)
            .toList();
        
        // 2. 批量查询子节点数量
        List<Map<String, Object>> countResults = folderNodeMapper.batchCountChildren(folderIds, userId);
        
        // 3. 构建 Map<folderId, childCount>
        Map<Long, Integer> childCountMap = new HashMap<>();
        for (Map<String, Object> result : countResults) {
            Long folderId = ((Number) result.get("folder_id")).longValue();
            Long childCount = ((Number) result.get("child_count")).longValue();
            childCountMap.put(folderId, childCount.intValue());
        }
        
        log.debug("[批量统计] 文件夹数量: {}, 查询结果数: {}", folderIds.size(), countResults.size());
        
        // 4. 转换为 VO
        return folders.stream()
            .map(folder -> convertFolderToVO(folder, childCountMap))
            .collect(Collectors.toList());
    }
    
    /**
     * 转换单个文件夹为VO
     * 优化版：从缓存的 childCountMap 中获取子节点数量，避免 N+1 查询
     */
    private DirectoryNodeVO convertFolderToVO(FolderNode folder, Map<Long, Integer> childCountMap) {
        DirectoryNodeVO vo = new DirectoryNodeVO();
        vo.setId(folder.getId());
        vo.setName(folder.getName());
        vo.setType("folder");
        vo.setPath(folder.getPath());
        vo.setParentId(folder.getParentId());
        vo.setCreatedAt(folder.getCreatedAt());
        vo.setUpdatedAt(folder.getUpdatedAt());
        vo.setVersion(folder.getVersion());
        
        // 从缓存的 Map 中获取子节点数量
        int childCount = childCountMap.getOrDefault(folder.getId(), 0);
        vo.setChildCount(childCount);
        vo.setHasChildren(childCount > 0);
        
        // 回收站特有字段
        if ("in_recycle_bin".equals(folder.getDirectoryStatus())) {
            vo.setDeletedAt(folder.getDeletedAt());
            vo.setExpiresAt(folder.getDeleteExpiresAt());
            if (folder.getDeleteExpiresAt() != null) {
                long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDateTime.now(), folder.getDeleteExpiresAt()
                );
                vo.setDaysRemaining((int) Math.max(0, daysRemaining));
            }
        }
        
        return vo;
    }
    
    /**
     * 转换文件列表为VO
     */
    private List<DirectoryNodeVO> convertFilesToVO(List<FileNode> files) {
        return files.stream().map(this::convertFileToVO).collect(Collectors.toList());
    }
    
    /**
     * 转换单个文件为VO
     */
    private DirectoryNodeVO convertFileToVO(FileNode file) {
        DirectoryNodeVO vo = new DirectoryNodeVO();
        vo.setId(file.getId());
        vo.setName(file.getName());
        vo.setType("file");
        vo.setPath(file.getPath());
        vo.setParentId(file.getFolderId());
        vo.setSize(file.getFileSize());
        vo.setMimeType(file.getMimeType());
        vo.setExtension(file.getExtension());
        vo.setCreatedAt(file.getCreatedAt());
        vo.setUpdatedAt(file.getUpdatedAt());
        vo.setVersion(file.getVersion());
        
        // 回收站特有字段
        if ("in_recycle_bin".equals(file.getDirectoryStatus())) {
            vo.setDeletedAt(file.getDeletedAt());
            vo.setExpiresAt(file.getDeleteExpiresAt());
            if (file.getDeleteExpiresAt() != null) {
                long daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDateTime.now(), file.getDeleteExpiresAt()
                );
                vo.setDaysRemaining((int) Math.max(0, daysRemaining));
            }
        }
        
        return vo;
    }
    
    /**
     * 获取排序字段值
     */
    private SortCursorValues getSortCursorValues(Long lastChildrenNode, String lastChildrenType) {
        if (lastChildrenNode == null || lastChildrenType == null) {
            return new SortCursorValues(null, null, null, null);
        }

        Map<String, Object> result;
        if ("folder".equals(lastChildrenType)) {
            result = folderNodeMapper.findSortFieldsById(lastChildrenNode);
        } else {
            result = fileNodeMapper.findSortFieldsById(lastChildrenNode);
        }

        if (result == null) {
            throw new RuntimeException("分页游标已失效，请刷新页面");
        }

        Long size = null;
        if (result.get("file_size") != null) {
            size = ((Number) result.get("file_size")).longValue();
        }

        return new SortCursorValues(
                (LocalDateTime) result.get("created_at"),
                (LocalDateTime) result.get("updated_at"),
                (String) result.get("name"),
                size
        );
    }
    
    /**
     * 删除节点（软删除，移入回收站）
     * 文件夹将启动后台异步递归删除，文件直接标记删除
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0为文件夹，1为文件）
     * @param userId 用户ID
     * @param version 乐观锁版本号
     * @param sessionId 会话ID（用于追踪异步删除进度）
     * @return 删除响应
     */
    @Transactional
    public DeleteNodeResponse deleteNode(Long nodeId, Integer nodeType, Long userId, Long version, String sessionId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        if (nodeType == null) {
            throw new IllegalArgumentException("节点类型不能为空");
        }
        
        // 2. 根据 nodeType 分别处理
        if (nodeType == 0) {
            // 文件夹删除逻辑
            FolderNode folder = folderNodeMapper.findById(nodeId);
            
            if (folder == null) {
                throw new RuntimeException("文件夹不存在");
            }
            
            // 验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权删除该文件夹");
            }
            
            // 乐观锁校验：检查版本号
            if (!folder.getVersion().equals(version)) {
                throw new com.mizuka.cloudfilesystem.exception.OptimisticLockException(
                    "文件夹已被其他人修改，请刷新后重试"
                );
            }
            
            // 计算回收站路径和过期时间
            String recycleBinPath = calculateRecycleBinPath(folder.getPath(), userId);
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(30); // 30天后过期
            
            // 1. 先标记根目录为删除状态（立即移入回收站）
            softDeleteFolderRoot(nodeId, recycleBinPath, expiresAt);
            
            log.info("用户 {} 软删除文件夹根节点 - NodeId: {}, RecycleBinPath: {}", userId, nodeId, recycleBinPath);
            
            // 2. 启动后台异步任务递归删除子节点
            asyncDirectoryDeleteService.asyncDeleteFolder(nodeId, sessionId, userId, recycleBinPath, expiresAt);
            
            return new DeleteNodeResponse(recycleBinPath, expiresAt);
            
        } else if (nodeType == 1) {
            // 文件删除逻辑
            FileNode file = fileNodeMapper.findById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("文件不存在");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权删除该文件");
            }
            
            // 乐观锁校验：检查版本号
            if (!file.getVersion().equals(version)) {
                throw new com.mizuka.cloudfilesystem.exception.OptimisticLockException(
                    "文件已被其他人修改，请刷新后重试"
                );
            }
            
            // 计算回收站路径和过期时间
            String recycleBinPath = calculateRecycleBinPath(file.getPath(), userId);
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(30); // 30天后过期
            
            // 执行软删除
            softDeleteFile(nodeId, recycleBinPath, expiresAt);
            
            log.info("用户 {} 软删除文件 - NodeId: {}, RecycleBinPath: {}", userId, nodeId, recycleBinPath);
            
            return new DeleteNodeResponse(recycleBinPath, expiresAt);
            
        } else {
            throw new IllegalArgumentException("无效的节点类型，0为文件夹，1为文件");
        }
    }
    
    /**
     * 恢复回收站中的节点
     * 
     * @param nodeId 节点ID
     * @param userId 用户ID
     * @return 恢复响应
     */
    /**
     * 恢复回收站项目（新架构）
     * 
     * 核心逻辑：
     * 1. 从 Redis 元数据层获取根节点信息
     * 2. 验证权限
     * 3. 找到对应的根节点（MySQL）
     * 4. 【关键】逐级检查父目录是否在回收站中
     * 5. 执行恢复操作
     * 6. 如果是文件夹，递归恢复所有子节点
     * 7. 清理 Redis 缓存
     * 8. 更新 MySQL 任务状态
     * 
     * @param batchId 批次号
     * @param userId 用户ID
     * @return 恢复结果
     */
    @Transactional
    public RestoreResult restoreNode(String batchId, Long userId) {
        // 0. 【关键】将 operation_type 更新为 1（标记为恢复任务）
        log.info("[恢复回收站] 开始更新 operation_type 为 1（恢复中）- BatchId: {}", batchId);
        recycleBinTaskMapper.updateOperationType(batchId, 1);
        
        // 1. 从 Redis 获取元数据
        Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
        if (info == null || info.isEmpty()) {
            throw new RuntimeException("batch 不存在或已过期");
        }
        
        Long rootNodeId;
        Integer nodeType;
        try {
            rootNodeId = Long.parseLong(info.get("rootNodeId"));
            nodeType = Integer.parseInt(info.get("nodeType"));
        } catch (NumberFormatException e) {
            throw new RuntimeException("batch 元数据格式错误");
        }
        
        // 2. 验证权限
        Long batchUserId;
        try {
            batchUserId = Long.parseLong(info.get("userId"));
        } catch (NumberFormatException e) {
            throw new RuntimeException("batch 元数据格式错误");
        }
        
        if (!userId.equals(batchUserId)) {
            throw new RuntimeException("无权恢复该节点");
        }
        
        // 3. 找到根节点并校验
        if (nodeType == 0) {
            // 文件夹恢复
            FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
            if (folder == null) {
                throw new RuntimeException("文件夹不存在或不在回收站中");
            }
            
            // 校验 last_del_uuid
            if (!batchId.equals(folder.getLastDelUuid())) {
                throw new RuntimeException("文件夹已被其他操作删除");
            }
            
            // 4. 逐级检查父目录
            checkParentDirectories(folder.getParentId(), batchId, userId);
            
            // 5. 递归恢复文件夹及其子节点
            restoreFolderRecursive(rootNodeId, batchId, userId);
            
        } else if (nodeType == 1) {
            // 文件恢复
            FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
            if (file == null) {
                throw new RuntimeException("文件不存在或不在回收站中");
            }
            
            // 校验 last_del_uuid
            if (!batchId.equals(file.getLastDelUuid())) {
                throw new RuntimeException("文件已被其他操作删除");
            }
            
            // 4. 逐级检查父目录
            checkParentDirectories(file.getFolderId(), batchId, userId);
            
            // 5. 恢复文件
            restoreFile(rootNodeId, batchId, userId);
            
        } else {
            throw new IllegalArgumentException("无效的节点类型");
        }
        
        // 6. 清理 Redis 缓存
        log.info("[恢复回收站] 开始清理 Redis 缓存 - BatchId: {}", batchId);
        recycleBinRedisService.cleanupBatch(batchId);
        
        // 7. 更新任务状态
        log.info("[恢复回收站] 开始更新任务状态 - BatchId: {}, Status: 1 (已完成)", batchId);
        recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, 1, 1);
        log.info("[恢复回收站] 任务状态已更新 - BatchId: {}", batchId);
        
        log.info("用户 {} 恢复节点成功（新架构）- BatchId: {}, RootNodeId: {}", userId, batchId, rootNodeId);
        
        return new RestoreResult(true, "恢复成功");
    }
    
    /**
     * 逐级检查父目录是否在回收站中
     * 
     * @param parentId 父目录ID
     * @param batchId 批次号
     * @param userId 用户ID
     */
    private void checkParentDirectories(Long parentId, String batchId, Long userId) {
        if (parentId == null) {
            return; // 到达根目录
        }
        
        // 查询用户根目录ID
        Long userRootId = folderNodeMapper.findUserRootId(userId);
        if (userRootId != null && parentId.equals(userRootId)) {
            return; // 到达用户根目录，停止检查
        }
        
        FolderNode parent = folderNodeMapper.findById(parentId);
        if (parent == null) {
            throw new RuntimeException("父目录不存在");
        }
        
        if ("in_recycle_bin".equals(parent.getDirectoryStatus())) {
            // 父目录在回收站中，检查 last_del_uuid
            if (!batchId.equals(parent.getLastDelUuid()) && parent.getLastDelUuid() != null) {
                throw new RuntimeException("父目录已被其他操作删除，无法恢复");
            }
            
            // 递归检查上一级
            checkParentDirectories(parent.getParentId(), batchId, userId);
        }
        // 如果父目录是 active 状态，继续向上检查
    }
    
    /**
     * 递归恢复文件夹及其子节点
     * 【新架构】不使用 original_parent_id，直接使用当前的 parent_id
     * 
     * @param folderId 文件夹ID
     * @param batchId 批次号
     * @param userId 用户ID
     */
    private void restoreFolderRecursive(Long folderId, String batchId, Long userId) {
        // 1. 恢复当前文件夹
        FolderNode folder = folderNodeMapper.findInRecycleBinById(folderId);
        if (folder == null) {
            log.warn("[恢复] 文件夹不存在 - FolderId: {}", folderId);
            return;
        }
        
        // 【新架构】直接恢复到当前位置（parent_id 在软删除时保持不变）
        String restoredPath;
        if (folder.getParentId() != null) {
            FolderNode parent = folderNodeMapper.findById(folder.getParentId());
            
            if (parent != null && !Boolean.TRUE.equals(parent.getIsDeleted())) {
                // 父目录存在且未删除，直接恢复
                restoredPath = folder.getPath();
                folderNodeMapper.restoreFolder(folderId, folder.getParentId(), restoredPath);
            } else {
                // 父目录已删除或不存在，恢复到用户根目录
                restoredPath = restoreToUserRoot(folder, userId);
            }
        } else {
            // 没有父目录信息，恢复到用户根目录
            restoredPath = restoreToUserRoot(folder, userId);
        }
        
        log.debug("[恢复] 文件夹恢复成功 - FolderId: {}, Path: {}", folderId, restoredPath);
        
        // 2. 查询所有子文件夹（根据条件）
        List<FolderNode> childFolders = folderNodeMapper.findChildrenByConditions(folderId, batchId);
        for (FolderNode childFolder : childFolders) {
            // 校验 last_del_uuid
            if (batchId.equals(childFolder.getLastDelUuid()) || childFolder.getLastDelUuid() == null) {
                restoreFolderRecursive(childFolder.getId(), batchId, userId);
            }
        }
        
        // 3. 查询所有子文件（根据条件）
        List<FileNode> childFiles = fileNodeMapper.findChildrenByConditions(folderId, batchId);
        for (FileNode childFile : childFiles) {
            // 校验 last_del_uuid
            if (batchId.equals(childFile.getLastDelUuid()) || childFile.getLastDelUuid() == null) {
                restoreFile(childFile.getId(), batchId, userId);
            }
        }
    }
    
    /**
     * 恢复单个文件
     * 【新架构】不使用 original_folder_id，直接使用当前的 folder_id
     * 
     * @param fileId 文件ID
     * @param batchId 批次号
     * @param userId 用户ID
     */
    private void restoreFile(Long fileId, String batchId, Long userId) {
        FileNode file = fileNodeMapper.findInRecycleBinById(fileId);
        if (file == null) {
            log.warn("[恢复] 文件不存在 - FileId: {}", fileId);
            return;
        }
        
        // 【新架构】直接恢复到当前位置（folder_id 在软删除时保持不变）
        String restoredPath;
        if (file.getFolderId() != null) {
            FolderNode folder = folderNodeMapper.findById(file.getFolderId());
            
            if (folder != null && !Boolean.TRUE.equals(folder.getIsDeleted())) {
                // 文件夹存在且未删除，直接恢复
                restoredPath = file.getPath();
                fileNodeMapper.restoreFile(fileId, file.getFolderId(), restoredPath);
            } else {
                // 文件夹已删除或不存在，恢复到用户根目录
                restoredPath = restoreFileToUserRoot(file, userId);
            }
        } else {
            // 没有文件夹信息，恢复到用户根目录
            restoredPath = restoreFileToUserRoot(file, userId);
        }
        
        log.debug("[恢复] 文件恢复成功 - FileId: {}, Path: {}", fileId, restoredPath);
    }
    
    /**
     * 计算回收站路径
     */
    private String calculateRecycleBinPath(String originalPath, Long userId) {
        // 提取节点名称
        String nodeName = originalPath.substring(originalPath.lastIndexOf("/") + 1);
        
        // 构建回收站路径：_root/_recycle_bin/{userId}/{nodeName}_{timestamp}
        String timestamp = String.valueOf(System.currentTimeMillis());
        return "_root/_recycle_bin/" + userId + "/" + nodeName + "_" + timestamp;
    }
    
    /**
     * 软删除文件夹根节点（不包括子节点）
     */
    private void softDeleteFolderRoot(Long folderId, String recycleBinPath, LocalDateTime expiresAt) {
        // 仅更新当前文件夹，不递归处理子节点
        folderNodeMapper.softDeleteFolder(folderId, recycleBinPath, expiresAt);
    }
    
    /**
     * 软删除文件夹（包括所有子节点）- 保留用于兼容
     */
    private void softDeleteFolder(Long folderId, String recycleBinPath, LocalDateTime expiresAt) {
        // 更新当前文件夹
        folderNodeMapper.softDeleteFolder(folderId, recycleBinPath, expiresAt);
        
        // 递归软删除所有子文件夹
        folderNodeMapper.softDeleteAllChildrenFolders(folderId, recycleBinPath, expiresAt);
        
        // 软删除所有子文件
        fileNodeMapper.softDeleteAllFilesInFolder(folderId, recycleBinPath, expiresAt);
    }
    
    /**
     * 软删除文件
     */
    private void softDeleteFile(Long fileId, String recycleBinPath, LocalDateTime expiresAt) {
        fileNodeMapper.softDeleteFile(fileId, recycleBinPath, expiresAt);
    }
    
    /**
     * 从回收站恢复文件夹（公开方法，供 AsyncRecycleBinRestoreService 调用）
     * 【新架构】不使用 original_parent_id，直接使用当前的 parent_id
     */
    public void restoreFolderFromRecycleBin(Long folderId, Long userId) {
        // 获取文件夹信息
        FolderNode folder = folderNodeMapper.findInRecycleBinById(folderId);
        
        String restoredPath;
        
        // 【新架构】检查当前父文件夹是否仍然存在
        if (folder.getParentId() != null) {
            FolderNode parent = folderNodeMapper.findById(folder.getParentId());
            
            if (parent != null && !Boolean.TRUE.equals(parent.getIsDeleted())) {
                // 父文件夹存在，直接恢复
                restoredPath = folder.getPath();
                folderNodeMapper.restoreFolder(folderId, folder.getParentId(), restoredPath);
            } else {
                // 父文件夹已删除，恢复到用户根目录
                restoredPath = restoreToUserRoot(folder, userId);
            }
        } else {
            // 没有父目录信息，恢复到用户根目录
            restoredPath = restoreToUserRoot(folder, userId);
        }
        
        log.info("用户 {} 恢复文件夹 - NodeId: {}, RestoredPath: {}", userId, folderId, restoredPath);
    }
    
    /**
     * 从回收站恢复文件（公开方法，供 AsyncRecycleBinRestoreService 调用）
     * 【新架构】不使用 original_folder_id，直接使用当前的 folder_id
     */
    public void restoreFileFromRecycleBin(Long fileId, Long userId) {
        // 获取文件信息
        FileNode file = fileNodeMapper.findInRecycleBinById(fileId);
        
        String restoredPath;
        
        // 【新架构】检查当前文件夹是否仍然存在
        if (file.getFolderId() != null) {
            FolderNode folder = folderNodeMapper.findById(file.getFolderId());
            
            if (folder != null && !Boolean.TRUE.equals(folder.getIsDeleted())) {
                // 文件夹存在，直接恢复
                restoredPath = file.getPath();
                fileNodeMapper.restoreFile(fileId, file.getFolderId(), restoredPath);
            } else {
                // 文件夹已删除，恢复到用户根目录
                restoredPath = restoreFileToUserRoot(file, userId);
            }
        } else {
            // 没有文件夹信息，恢复到用户根目录
            restoredPath = restoreFileToUserRoot(file, userId);
        }
        
        log.info("用户 {} 恢复文件 - NodeId: {}, RestoredPath: {}", userId, fileId, restoredPath);
    }
    
    /**
     * 恢复文件夹到用户根目录
     */
    private String restoreToUserRoot(FolderNode folder, Long userId) {
        // 获取用户根目录ID
        Long userRootId = folderNodeMapper.findUserRootId(userId);
        
        if (userRootId == null) {
            throw new RuntimeException("用户根目录不存在");
        }
        
        // 构建新路径
        String nodeName = folder.getName();
        String newPath = "_root/_files/" + userId + "/" + nodeName;
        
        // 更新文件夹
        folderNodeMapper.restoreFolder(folder.getId(), userRootId, newPath);
        
        return newPath;
    }
    
    /**
     * 恢复文件到用户根目录
     */
    private String restoreFileToUserRoot(FileNode file, Long userId) {
        // 获取用户根目录ID
        Long userRootId = folderNodeMapper.findUserRootId(userId);
        
        if (userRootId == null) {
            throw new RuntimeException("用户根目录不存在");
        }
        
        // 构建新路径
        String fileName = file.getName();
        String newPath = "_root/_files/" + userId + "/" + fileName;
        
        // 更新文件
        fileNodeMapper.restoreFile(file.getId(), userRootId, newPath);
        
        return newPath;
    }
    
    /**
     * 彻底删除batch（新架构）
     * 
     * 核心逻辑：
     * 1. 从 Redis 元数据层获取根节点信息
     * 2. 验证权限
     * 3. 【关键】BFS 遍历文件夹树，构建临时缓存栈
     *    - 将符合条件的**文件夹节点**压入数据层 ZSET（只存 nodeId）
     *    - **文件节点不入栈**，直接在遍历时处理（断点续传优化）
     * 4. 【关键】从栈顶逐个弹栈，清空文件夹信息
     * 5. 清理 Redis 缓存
     * 6. 更新 MySQL 任务状态
     * 
     * @param batchId 批次号
     * @param userId 用户ID
     */
    @Transactional
    public void permanentDeleteBatch(String batchId, Long userId) {
        // 1. 从 Redis 获取元数据
        Map<String, String> info = recycleBinRedisService.getBatchInfo(batchId);
        if (info == null || info.isEmpty()) {
            throw new RuntimeException("batch 不存在或已过期");
        }
        
        Long rootNodeId;
        Integer nodeType;
        Long version;  // 【关键】获取版本号
        try {
            rootNodeId = Long.parseLong(info.get("rootNodeId"));
            nodeType = Integer.parseInt(info.get("nodeType"));
            version = info.containsKey("version") ? Long.parseLong(info.get("version")) : null;
        } catch (NumberFormatException e) {
            throw new RuntimeException("batch 元数据格式错误");
        }
        
        // 2. 验证权限
        Long batchUserId;
        try {
            batchUserId = Long.parseLong(info.get("userId"));
        } catch (NumberFormatException e) {
            throw new RuntimeException("batch 元数据格式错误");
        }
        
        if (!userId.equals(batchUserId)) {
            throw new RuntimeException("无权删除该节点");
        }
        
        // 3. 【关键】验证版本号、directory_status 和 last_del_uuid
        if (nodeType == 0) {
            // 文件夹验证
            FolderNode folder = folderNodeMapper.findInRecycleBinById(rootNodeId);
            if (folder == null) {
                throw new RuntimeException("文件夹不存在或不在回收站中");
            }
            
            // 验证版本号
            if (version != null && !folder.getVersion().equals(version)) {
                log.warn("[彻底删除] 版本号不匹配 - NodeId: {}, Expected: {}, Actual: {}", 
                    rootNodeId, version, folder.getVersion());
                throw new RuntimeException("文件夹版本已变更，请刷新后重试");
            }
            
            // 验证 directory_status
            if (!"in_recycle_bin".equals(folder.getDirectoryStatus())) {
                log.warn("[彻底删除] 目录状态不符合 - NodeId: {}, Status: {}", 
                    rootNodeId, folder.getDirectoryStatus());
                throw new RuntimeException("文件夹状态异常，无法彻底删除");
            }
            
            // 验证 last_del_uuid
            if (folder.getLastDelUuid() != null && !batchId.equals(folder.getLastDelUuid())) {
                log.warn("[彻底删除] last_del_uuid 不匹配 - NodeId: {}, Expected: {}, Actual: {}", 
                    rootNodeId, batchId, folder.getLastDelUuid());
                throw new RuntimeException("文件夹已被其他操作处理，无法彻底删除");
            }
            
            log.info("[彻底删除] 文件夹验证通过 - NodeId: {}, Version: {}, Status: {}, LastDelUuid: {}",
                rootNodeId, folder.getVersion(), folder.getDirectoryStatus(), folder.getLastDelUuid());
            
        } else if (nodeType == 1) {
            // 文件验证
            FileNode file = fileNodeMapper.findInRecycleBinById(rootNodeId);
            if (file == null) {
                throw new RuntimeException("文件不存在或不在回收站中");
            }
            
            // 验证版本号
            if (version != null && !file.getVersion().equals(version)) {
                log.warn("[彻底删除] 版本号不匹配 - NodeId: {}, Expected: {}, Actual: {}", 
                    rootNodeId, version, file.getVersion());
                throw new RuntimeException("文件版本已变更，请刷新后重试");
            }
            
            // 验证 directory_status
            if (!"in_recycle_bin".equals(file.getDirectoryStatus())) {
                log.warn("[彻底删除] 目录状态不符合 - NodeId: {}, Status: {}", 
                    rootNodeId, file.getDirectoryStatus());
                throw new RuntimeException("文件状态异常，无法彻底删除");
            }
            
            // 验证 last_del_uuid
            if (file.getLastDelUuid() != null && !batchId.equals(file.getLastDelUuid())) {
                log.warn("[彻底删除] last_del_uuid 不匹配 - NodeId: {}, Expected: {}, Actual: {}", 
                    rootNodeId, batchId, file.getLastDelUuid());
                throw new RuntimeException("文件已被其他操作处理，无法彻底删除");
            }
            
            log.info("[彻底删除] 文件验证通过 - NodeId: {}, Version: {}, Status: {}, LastDelUuid: {}",
                rootNodeId, file.getVersion(), file.getDirectoryStatus(), file.getLastDelUuid());
        } else {
            throw new IllegalArgumentException("无效的节点类型");
        }
        
        // 4. 执行彻底删除
        if (nodeType == 0) {
            // 文件夹：BFS 遍历
            bfsPermanentDeleteFolder(rootNodeId, batchId, userId);
        } else if (nodeType == 1) {
            // 文件：直接移入待分配池
            permanentDeleteFile(rootNodeId, batchId, userId);
        }
        
        // 5. 清理 Redis 缓存
        recycleBinRedisService.cleanupBatch(batchId);
        
        // 6. 更新任务状态
        recycleBinTaskMapper.updateTask(batchId, 1, LocalDateTime.now(), null, 1, 1);
        
        log.info("用户 {} 彻底删除完成（新架构）- BatchId: {}, RootNodeId: {}", userId, batchId, rootNodeId);
    }
    
    /**
     * BFS 遍历文件夹树，构建临时缓存栈（只存文件夹ID）
     * 
     * 核心流程：
     * 1. 第一阶段：BFS遍历，将所有文件夹入栈
     * 2. 第二阶段：从栈顶弹栈，清空文件夹信息
     * 3. 第三阶段：处理所有文件（断点续传优化）
     * 
     * @param rootFolderId 根文件夹ID
     * @param batchId 批次号
     * @param userId 用户ID
     */
    private void bfsPermanentDeleteFolder(Long rootFolderId, String batchId, Long userId) {
        // ========== 检查是否有游标断点 ==========
        Map<String, String> cursorData = recycleBinRedisService.getCursorData(batchId);
        if (cursorData != null && !cursorData.isEmpty() && cursorData.containsKey("cursorNodeId")) {
            try {
                Long cursorNodeId = Long.parseLong(cursorData.get("cursorNodeId"));
                Integer cursorNodeType = Integer.parseInt(cursorData.get("cursorNodeType"));
                Long cursorParentId = cursorData.containsKey("cursorParentId") ? 
                    Long.parseLong(cursorData.get("cursorParentId")) : null;
                
                log.info("[彻底删除] 检测到游标断点，从断点继续 - BatchId: {}, CursorNodeId: {}, CursorNodeType: {}",
                    batchId, cursorNodeId, cursorNodeType);
                
                // 根据游标类型决定从哪里继续
                if (cursorNodeType == 0) {
                    // 游标为文件夹，说明还在BFS阶段
                    resumeBFSFromCursor(rootFolderId, batchId, userId, cursorNodeId, cursorParentId);
                } else if (cursorNodeType == 1) {
                    // 游标为文件，说明BFS已完成，正在处理文件
                    resumeFileProcessingFromCursor(batchId, userId, cursorNodeId, cursorParentId);
                }
                return;
            } catch (NumberFormatException e) {
                log.warn("[彻底删除] 游标数据格式错误，重新从头开始 - BatchId: {}", batchId, e);
            }
        }
        
        // ========== 第一阶段：BFS遍历，将所有文件夹入栈 ==========
        Queue<Long> queue = new LinkedList<>();
        queue.offer(rootFolderId);
        
        long timestamp = System.currentTimeMillis();
        int order = 0;
        
        // 收集所有需要处理的文件夹ID（用于后续文件处理）
        List<Long> allFolderIds = new ArrayList<>();
        allFolderIds.add(rootFolderId);
        
        // 【关键】先将根节点压入栈中
        double rootScore = timestamp + order++;
        recycleBinRedisService.addFolderToBatch(batchId, rootFolderId, rootScore);
        log.info("[彻底删除] 根节点已入栈 - RootFolderId: {}, Score: {}", rootFolderId, rootScore);
        
        while (!queue.isEmpty()) {
            Long currentFolderId = queue.poll();
            
            // 查询一级子文件夹
            List<FolderNode> childFolders = folderNodeMapper.findChildrenByConditions(
                currentFolderId, batchId
            );
            
            for (FolderNode childFolder : childFolders) {
                // 【关键】对每个符合条件的子文件夹执行“移入回收站”操作
                // 更新状态为 in_recycle_bin，并设置 last_del_uuid = batchId
                folderNodeMapper.markAsInRecycleBin(childFolder.getId(), batchId);
                
                log.debug("[彻底删除] 子文件夹已移入回收站 - FolderId: {}, BatchId: {}", 
                    childFolder.getId(), batchId);
                
                // 压入数据层 ZSET（只存文件夹ID，无需 nodeType 前缀）
                double score = timestamp + order++;
                recycleBinRedisService.addFolderToBatch(batchId, childFolder.getId(), score);
                
                // 加入队列
                queue.offer(childFolder.getId());
                
                // 记录文件夹ID
                allFolderIds.add(childFolder.getId());
                
                // 【关键】每处理一个文件夹就更新游标到Redis（滑动窗口限流）
                Map<String, Object> cursorInfo = new HashMap<>();
                cursorInfo.put("cursorNodeId", childFolder.getId());
                cursorInfo.put("cursorNodeType", 0); // 0=文件夹
                cursorInfo.put("cursorParentId", currentFolderId);
                recycleBinRedisService.saveCursorData(batchId, cursorInfo);
            }
        }
        
        log.info("[彻底删除] 第一阶段完成 - 所有文件夹已入栈，共 {} 个文件夹", allFolderIds.size());
        
        // ========== 第二阶段：从栈顶逐个弹栈，清空文件夹信息 ==========
        while (true) {
            Set<String> members = recycleBinRedisService.popMaxFromBatch(batchId, 10);
            if (members == null || members.isEmpty()) {
                break;
            }
            
            for (String memberId : members) {
                Long folderId = Long.parseLong(memberId); // 直接解析为文件夹ID
                
                // 清空文件夹信息
                folderNodeMapper.clearFolderInfo(folderId);
                
                // 【关键】文件夹弹栈成功后，将游标更新为空
                // 表示下一个目录将从第一个文件开始检索
                recycleBinRedisService.clearCursorData(batchId);
                
                log.debug("[彻底删除] 文件夹已弹栈并清空，游标已重置 - FolderId: {}", folderId);
            }
        }
        
        log.info("[彻底删除] 第二阶段完成 - 所有文件夹已清空");
        
        // ========== 第三阶段：处理所有文件（断点续传优化）==========
        for (Long folderId : allFolderIds) {
            // 【关键】开始处理新文件夹时，游标应该为空（从第一个文件开始）
            // 如果Redis中有游标且cursorParentId等于当前folderId，说明是从断点恢复
            Map<String, String> existingCursor = recycleBinRedisService.getCursorData(batchId);
            Long lastFileId = null; // 断点：上次处理的最后一个文件ID
            
            if (existingCursor != null && !existingCursor.isEmpty() && 
                existingCursor.containsKey("cursorParentId") &&
                existingCursor.containsKey("cursorNodeType")) {
                
                Integer cursorNodeType = Integer.parseInt(existingCursor.get("cursorNodeType"));
                Long cursorParentId = Long.parseLong(existingCursor.get("cursorParentId"));
                
                // 如果游标的父节点ID等于当前文件夹ID，且游标类型为文件，说明是断点恢复
                if (cursorParentId.equals(folderId) && cursorNodeType == 1) {
                    lastFileId = Long.parseLong(existingCursor.get("cursorNodeId"));
                    log.info("[彻底删除] 从断点恢复文件处理 - FolderId: {}, LastFileId: {}", folderId, lastFileId);
                }
                // 否则，lastFileId保持null，从第一个文件开始
            }
            
            while (true) {
                // 分批查询文件，每次最多 100 个，以 lastFileId 为断点
                List<FileNode> childFiles = fileNodeMapper.findChildrenByConditionsWithCursor(
                    folderId, batchId, lastFileId, 100
                );
                
                if (childFiles == null || childFiles.isEmpty()) {
                    break; // 没有更多文件，退出循环
                }
                
                for (FileNode childFile : childFiles) {
                    // 直接移入待分配文件池（清空所有信息）
                    fileNodeMapper.moveToUnassignedPool(childFile.getId());
                    
                    // 【关键】每处理一个文件就更新游标到Redis
                    Map<String, Object> cursorInfo = new HashMap<>();
                    cursorInfo.put("cursorNodeId", childFile.getId());
                    cursorInfo.put("cursorNodeType", 1); // 1=文件
                    cursorInfo.put("cursorParentId", folderId);
                    recycleBinRedisService.saveCursorData(batchId, cursorInfo);
                }
                
                // 更新断点：最后一个处理的文件ID
                lastFileId = childFiles.get(childFiles.size() - 1).getId();
                
                // 如果本次查询不足 100 个，说明已经处理完所有文件
                if (childFiles.size() < 100) {
                    break;
                }
            }
            
            // 【关键】当前文件夹的所有文件处理完成后，清除游标
            // 这样下一个文件夹会从第一个文件开始
            recycleBinRedisService.clearCursorData(batchId);
            log.debug("[彻底删除] 文件夹文件处理完成，游标已重置 - FolderId: {}", folderId);
        }
        
        // 【关键】所有处理完成后，清除Redis中的游标
        recycleBinRedisService.clearCursorData(batchId);
        
        log.info("[彻底删除] 第三阶段完成 - 所有文件已处理");
        log.info("[彻底删除] BFS 遍历完成 - RootFolderId: {}, BatchId: {}", rootFolderId, batchId);
    }
    
    /**
     * 从游标断点恢复BFS遍历
     */
    private void resumeBFSFromCursor(Long rootFolderId, String batchId, Long userId,
                                      Long cursorNodeId, Long cursorParentId) {
        log.info("[彻底删除-恢复] 从BFS游标继续 - CursorNodeId: {}, ParentId: {}", cursorNodeId, cursorParentId);
        
        // TODO: 实现从游标继续BFS的逻辑
        // 这里需要根据cursorParentId找到父节点，然后从该节点的下一个子节点继续
        
        // 简化实现：重新从头开始BFS（因为BFS复杂度不高）
        bfsPermanentDeleteFolder(rootFolderId, batchId, userId);
    }
    
    /**
     * 从游标断点恢复文件处理
     */
    private void resumeFileProcessingFromCursor(String batchId, Long userId,
                                                  Long cursorNodeId, Long cursorParentId) {
        log.info("[彻底删除-恢复] 从文件处理游标继续 - CursorNodeId: {}, ParentId: {}", cursorNodeId, cursorParentId);
        
        // TODO: 实现从游标继续文件处理的逻辑
        // 需要从cursorParentId对应的文件夹开始，从cursorNodeId之后继续处理文件
        
        // 简化实现：重新从头开始处理文件（因为文件处理已经有lastFileId断点）
        // 这里需要重新获取allFolderIds列表，然后从cursorParentId开始处理
    }
    
    /**
     * 彻底删除单个文件
     * 
     * @param fileId 文件ID
     * @param batchId 批次号
     * @param userId 用户ID
     */
    private void permanentDeleteFile(Long fileId, String batchId, Long userId) {
        // 移入待分配文件池
        fileNodeMapper.moveToUnassignedPool(fileId);
        
        log.info("[彻底删除] 文件已移入待分配池 - FileId: {}, BatchId: {}", fileId, batchId);
    }


    /**
     * 搜索文件或文件夹
     */
    /**
     * 搜索文件或文件夹（基础版本，无分页）
     */
    public List<SearchResultVO> search(String keyword, Long userId, String type) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }

        // 清理关键词，防止 SQL 注入
        keyword = keyword.trim().replaceAll("[+\\-><()~*\"']", "");

        List<SearchResultVO> results = new ArrayList<>();

        // 搜索文件
        if (!"folder".equals(type)) {
            List<Map<String, Object>> files = fileNodeMapper.searchFiles(
                    keyword, userId, type, DEFAULT_MAX_PAGE_SIZE
            );
            results.addAll(convertToSearchResults(files));
        }

        // 搜索文件夹
        if (!"file".equals(type)) {
            List<Map<String, Object>> folders = folderNodeMapper.searchFolders(
                    keyword, userId, type, DEFAULT_MAX_PAGE_SIZE
            );
            results.addAll(convertToSearchResults(folders));
        }

        // 按相关性排序
        results.sort((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()));

        log.info("用户 {} 搜索 '{}', 类型: {}, 结果数: {}",
                userId, keyword, type, results.size());

        return results;
    }
    
    /**
     * 搜索文件或文件夹（支持游标分页，双游标）
     * 
     * @param keyword 搜索关键词
     * @param userId 用户ID
     * @param type 类型过滤（file/folder/all）
     * @param sumFolders 已显示文件夹数
     * @param sumFiles 已显示文件数
     * @param lastFoldersNode 文件夹游标锚点ID
     * @param lastFilesNode 文件游标锚点ID
     * @param maxPageSize 最大返回数量
     * @return 搜索响应
     */
    public SearchResponse searchWithCursor(String keyword, Long userId, String type,
                                           Integer sumFolders, Integer sumFiles,
                                           Long lastFoldersNode, Long lastFilesNode,
                                           Integer maxPageSize) {
        return searchWithCursor(keyword, userId, type, sumFolders, sumFiles,
                lastFoldersNode, lastFilesNode, maxPageSize, false);
    }
    
    /**
     * 搜索文件或文件夹（支持游标分页，双游标，支持回收站）
     * 
     * @param keyword 搜索关键词
     * @param userId 用户ID
     * @param type 类型过滤（file/folder/all）
     * @param sumFolders 已显示文件夹数
     * @param sumFiles 已显示文件数
     * @param lastFoldersNode 文件夹游标锚点ID
     * @param lastFilesNode 文件游标锚点ID
     * @param maxPageSize 最大返回数量
     * @param isRecycleBin 是否为回收站模式
     * @return 搜索响应
     */
    public SearchResponse searchWithCursor(String keyword, Long userId, String type,
                                           Integer sumFolders, Integer sumFiles,
                                           Long lastFoldersNode, Long lastFilesNode,
                                           Integer maxPageSize, boolean isRecycleBin) {
        // 1. 参数校验
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        
        // 清理关键词，防止 SQL 注入
        keyword = keyword.trim().replaceAll("[+\\-><()~*\"']", "");
        
        if (keyword.length() < 1) {
            throw new IllegalArgumentException("搜索关键词太短");
        }
        
        // 2. 默认值处理
        String finalType = (type == null || type.trim().isEmpty()) ? "all" : type;
        int pageSize = (maxPageSize == null || maxPageSize <= 0) 
                ? DEFAULT_MAX_PAGE_SIZE 
                : Math.min(maxPageSize, ABSOLUTE_MAX_PAGE_SIZE);
        
        // 3. 查询文件夹
        List<SearchResultVO> folderResults = new ArrayList<>();
        boolean isEndFolder = false;
        
        if (!"file".equals(finalType)) {
            // 获取文件夹游标的名称
            String lastFolderName = null;
            Double lastFolderRelevance = null;
            if (lastFoldersNode != null) {
                Map<String, Object> folderInfo = folderNodeMapper.findSortFieldsById(lastFoldersNode);
                if (folderInfo != null) {
                    lastFolderName = (String) folderInfo.get("name");
                    // 注意：相关性需要重新计算，这里暂时传 null
                    lastFolderRelevance = null;
                }
            }
            
            List<Map<String, Object>> folders = folderNodeMapper.searchFoldersWithCursor(
                    keyword, userId, lastFolderRelevance, lastFolderName, lastFoldersNode, pageSize
            );
            
            folderResults = convertToSearchResults(folders);
            isEndFolder = folders.size() < pageSize;
        }
        
        // 4. 查询文件
        List<SearchResultVO> fileResults = new ArrayList<>();
        boolean isEndFile = false;
        
        if (!"folder".equals(finalType)) {
            // 获取文件游标的名称和扩展名
            String lastFileName = null;
            String lastFileExtension = null;
            Double lastFileRelevance = null;
            if (lastFilesNode != null) {
                Map<String, Object> fileInfo = fileNodeMapper.findSortFieldsById(lastFilesNode);
                if (fileInfo != null) {
                    lastFileName = (String) fileInfo.get("name");
                    lastFileExtension = (String) fileInfo.get("extension");
                    // 注意：相关性需要重新计算，这里暂时传 null
                    lastFileRelevance = null;
                }
            }
            
            List<Map<String, Object>> files = fileNodeMapper.searchFilesWithCursor(
                    keyword, userId, lastFileRelevance, lastFileName, lastFileExtension, lastFilesNode, pageSize
            );
            
            fileResults = convertToSearchResults(files);
            isEndFile = files.size() < pageSize;
        }
        
        // 5. 合并结果：按相关性排序，相同时文件优先
        List<SearchResultVO> mergedResults = mergeAndSortResults(folderResults, fileResults);
        
        // 6. 限制返回数量
        if (mergedResults.size() > pageSize) {
            mergedResults = mergedResults.subList(0, pageSize);
        }
        
        // 7. 构建分页信息
        Long lastFolderNodeId = null;
        Long lastFileNodeId = null;
        int countFolders = 0;
        int countFiles = 0;
        
        for (SearchResultVO item : mergedResults) {
            if ("folder".equals(item.getType())) {
                lastFolderNodeId = item.getId();
                countFolders++;
            } else {
                lastFileNodeId = item.getId();
                countFiles++;
            }
        }
        
        SearchResponse.SearchPagination pagination = new SearchResponse.SearchPagination(
                lastFolderNodeId,
                lastFileNodeId,
                isEndFolder && countFolders < folderResults.size(),
                isEndFile && countFiles < fileResults.size(),
                countFolders,
                countFiles
        );
        
        // 8. 构建响应
        SearchResponse response = new SearchResponse();
        response.setResults(mergedResults);
        response.setPagination(pagination);
        
        log.info("用户 {} 搜索 '{}', 类型: {}, 返回 {} 条结果 (文件夹: {}, 文件: {})",
                userId, keyword, finalType, mergedResults.size(), countFolders, countFiles);
        
        return response;
    }
    
    /**
     * 合并文件夹和文件结果，按相关性排序，相同时文件优先
     */
    private List<SearchResultVO> mergeAndSortResults(List<SearchResultVO> folders, List<SearchResultVO> files) {
        List<SearchResultVO> merged = new ArrayList<>();
        merged.addAll(folders);
        merged.addAll(files);
        
        // 排序规则：
        // 1. 相关性降序
        // 2. 相关性相同时，文件优先（type='file' < type='folder'）
        // 3. 文件之间：扩展名升序 -> 名称升序 -> ID降序
        // 4. 文件夹之间：名称升序 -> ID降序
        merged.sort((a, b) -> {
            // 相关性比较
            if (a.getRelevance() == null && b.getRelevance() == null) return 0;
            if (a.getRelevance() == null) return 1;
            if (b.getRelevance() == null) return -1;
            
            int relevanceCmp = Double.compare(b.getRelevance(), a.getRelevance());
            if (relevanceCmp != 0) {
                return relevanceCmp;
            }
            
            // 相关性相同，文件优先
            if (!"file".equals(a.getType()) && "file".equals(b.getType())) {
                return 1; // a是文件夹，b是文件，b优先
            }
            if ("file".equals(a.getType()) && !"file".equals(b.getType())) {
                return -1; // a是文件，b是文件夹，a优先
            }
            
            // 同为文件或同为文件夹
            if ("file".equals(a.getType()) && "file".equals(b.getType())) {
                // 文件：扩展名升序 -> 名称升序 -> ID降序
                int extCmp = compareNullSafe(a.getExtension(), b.getExtension());
                if (extCmp != 0) return extCmp;
                
                int nameCmp = compareNullSafe(a.getName(), b.getName());
                if (nameCmp != 0) return nameCmp;
                
                return Long.compare(b.getId(), a.getId());
            } else {
                // 文件夹：名称升序 -> ID降序
                int nameCmp = compareNullSafe(a.getName(), b.getName());
                if (nameCmp != 0) return nameCmp;
                
                return Long.compare(b.getId(), a.getId());
            }
        });
        
        return merged;
    }
    
    /**
     * 安全的字符串比较（null 值处理）
     */
    private int compareNullSafe(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private List<SearchResultVO> convertToSearchResults(List<Map<String, Object>> records) {
        return records.stream().map(record -> {
            SearchResultVO vo = new SearchResultVO();
            vo.setId(((Number) record.get("id")).longValue());
            vo.setName((String) record.get("name"));
            vo.setType((String) record.get("node_type"));
            vo.setPath((String) record.get("path"));
            vo.setRelevance(((Number) record.get("relevance")).doubleValue());

            if ("file".equals(vo.getType())) {
                Object fileSizeObj = record.get("file_size");
                if (fileSizeObj != null) {
                    vo.setFileSize(((Number) fileSizeObj).longValue());
                }
                vo.setExtension((String) record.get("extension"));
                vo.setMimeType((String) record.get("mime_type"));
            } else if ("folder".equals(vo.getType())) {
                // 文件夹特有字段
                Object folderCountObj = record.get("folder_count");
                Object fileCountObj = record.get("file_count");
                if (folderCountObj != null || fileCountObj != null) {
                    int totalChildren = 0;
                    if (folderCountObj != null) {
                        totalChildren += ((Number) folderCountObj).intValue();
                    }
                    if (fileCountObj != null) {
                        totalChildren += ((Number) fileCountObj).intValue();
                    }
                    vo.setHasChildren(totalChildren > 0);
                    vo.setChildCount(totalChildren);
                }
            }

            // 创建时间
            Object createdAtObj = record.get("created_at");
            if (createdAtObj instanceof java.time.LocalDateTime) {
                vo.setCreatedAt((java.time.LocalDateTime) createdAtObj);
            }
            
            // 版本号
            Object versionObj = record.get("version");
            if (versionObj != null) {
                vo.setVersion(((Number) versionObj).longValue());
            }

            return vo;
        }).collect(Collectors.toList());
    }
    
    /**
     * 恢复节点（支持 204 状态码和新响应格式）
     * 
     * @param nodeId 节点ID
     * @param userId 用户ID
     * @return 恢复结果
     */
    @Transactional
    public RestoreResult restoreNodeWithNewFormat(Long nodeId, Long userId) {
        // 1. 先尝试查找文件夹
        FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
        boolean isFolder = true;
        
        if (folder == null) {
            // 尝试查找文件
            FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
            if (file == null) {
                throw new RuntimeException("节点不存在或不在回收站中");
            }
            isFolder = false;
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权恢复该文件");
            }
            
            // 检查是否已过期
            if (file.getDeleteExpiresAt() != null && file.getDeleteExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("该节点已过期，无法恢复");
            }
            
            // 执行文件恢复
            return restoreFileWithNewFormat(file, userId);
        } else {
            // 是文件夹
            // 验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权恢复该文件夹");
            }
            
            // 检查是否已过期
            if (folder.getDeleteExpiresAt() != null && folder.getDeleteExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("该节点已过期，无法恢复");
            }
            
            // 执行文件夹恢复
            return restoreFolderWithNewFormat(folder, userId);
        }
    }
    
    /**
     * 恢复文件夹（新格式）
     */
    private RestoreResult restoreFolderWithNewFormat(FolderNode folder, Long userId) {
        String restorePath;
        String newName;
        int httpCode;
        String message;
        
        // 判断原始位置是否仍存在
        // 注意：软删除时 parent_id 保持不变，直接使用当前的 parentId
        FolderNode parentNode = null;
        if (folder.getParentId() != null) {
            parentNode = folderNodeMapper.findById(folder.getParentId());
        }
        
        boolean originalLocationExists = (parentNode != null && "active".equals(parentNode.getDirectoryStatus()));
        
        if (originalLocationExists) {
            // 恢复到原位置
            restorePath = buildPath(parentNode.getPath(), folder.getName());
            newName = folder.getName();
            httpCode = 200;
            message = "恢复成功";
            
            // 执行恢复
            folderNodeMapper.restoreFolder(folder.getId(), folder.getParentId(), restorePath);
        } else {
            // 恢复到用户根目录并重命名
            FolderNode userRoot = folderNodeMapper.findUserRoot(userId);
            if (userRoot == null) {
                throw new RuntimeException("用户根目录不存在");
            }
            
            newName = generateUniqueName(folder.getName(), userRoot.getId(), true);
            restorePath = buildPath(userRoot.getPath(), newName);
            httpCode = 204;
            message = "原父目录不存在或已删除，已恢复到用户根目录";
            
            // 执行恢复并重命名
            folderNodeMapper.restoreFolder(folder.getId(), userRoot.getId(), restorePath);
        }
        
        // 更新 last_del_uuid 为恢复批次号
        String restoreBatchId = java.util.UUID.randomUUID().toString();
        folderNodeMapper.updateLastDelUuid(folder.getId(), restoreBatchId);
        
        // 更新任务状态
        if (folder.getLastDelUuid() != null) {
            recycleBinTaskMapper.updateTask(folder.getLastDelUuid(), 1, LocalDateTime.now(), null, null, null);
        }
        
        // 构建响应
        RestoreData data = new RestoreData();
        data.setNewName(sanitizeFileName(newName));
        data.setNodeType("folder");
        data.setRestoredPath(restorePath);
        data.setNewVersion(folder.getVersion() + 1);
        
        return new RestoreResult(httpCode, true, message, data);
    }
    
    /**
     * 恢复文件（新格式）
     */
    private RestoreResult restoreFileWithNewFormat(FileNode file, Long userId) {
        String restorePath;
        String newName;
        int httpCode;
        String message;
        
        // 判断原始位置是否仍存在
        // 注意：软删除时 folder_id 保持不变，直接使用当前的 folderId
        FolderNode parentFolder = null;
        if (file.getFolderId() != null) {
            parentFolder = folderNodeMapper.findById(file.getFolderId());
        }
        
        boolean originalLocationExists = (parentFolder != null && "active".equals(parentFolder.getDirectoryStatus()));
        
        if (originalLocationExists) {
            // 恢复到原位置
            restorePath = buildPath(parentFolder.getPath(), file.getName());
            newName = file.getName();
            httpCode = 200;
            message = "恢复成功";
            
            // 执行恢复
            fileNodeMapper.restoreFile(file.getId(), file.getFolderId(), restorePath);
        } else {
            // 恢复到用户根目录并重命名
            FolderNode userRoot = folderNodeMapper.findUserRoot(userId);
            if (userRoot == null) {
                throw new RuntimeException("用户根目录不存在");
            }
            
            newName = generateUniqueName(file.getName(), userRoot.getId(), false);
            restorePath = buildPath(userRoot.getPath(), newName);
            httpCode = 204;
            message = "原父目录不存在或已删除，已恢复到用户根目录";
            
            // 执行恢复并重命名
            fileNodeMapper.restoreFile(file.getId(), userRoot.getId(), restorePath);
        }
        
        // 更新 last_del_uuid 为恢复批次号
        String restoreBatchId = java.util.UUID.randomUUID().toString();
        fileNodeMapper.updateLastDelUuid(file.getId(), restoreBatchId);
        
        // 更新任务状态
        if (file.getLastDelUuid() != null) {
            recycleBinTaskMapper.updateTask(file.getLastDelUuid(), 1, LocalDateTime.now(), null, null, null);
        }
        
        // 构建响应
        RestoreData data = new RestoreData();
        data.setNewName(sanitizeFileName(newName));
        data.setNodeType("file");
        data.setRestoredPath(restorePath);
        data.setNewVersion(file.getVersion() + 1);
        
        return new RestoreResult(httpCode, true, message, data);
    }
    
    /**
     * 生成唯一文件名（避免重名）
     */
    private String generateUniqueName(String originalName, Long parentId, boolean isFolder) {
        String baseName = originalName;
        String extension = "";
        
        // 分离文件名和扩展名
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = originalName.substring(0, dotIndex);
            extension = originalName.substring(dotIndex);
        }
        
        // 尝试生成唯一名称
        int counter = 1;
        String newName = originalName;
        while ((isFolder && folderNodeMapper.existsByNameAndParentId(newName, parentId)) ||
               (!isFolder && fileNodeMapper.existsByNameAndParentId(newName, parentId))) {
            newName = baseName + "(" + counter + ")" + extension;
            counter++;
            
            // 防止无限循环
            if (counter > 1000) {
                newName = java.util.UUID.randomUUID().toString() + extension;
                break;
            }
        }
        
        return newName;
    }
    
    /**
     * 构建完整路径
     */
    private String buildPath(String parentPath, String name) {
        if (parentPath == null || parentPath.isEmpty()) {
            return name;
        }
        return parentPath.endsWith("/") ? parentPath + name : parentPath + "/" + name;
    }
    
    /**
     * 清理文件名中的特殊字符
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        return fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
    }
    
    /**
     * 删除节点（支持 batchId 和 recycle_bin_tasks 表）
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0为文件夹，1为文件）
     * @param userId 用户ID
     * @param version 乐观锁版本号
     * @param batchId 批次号（UUID格式）
     * @return 删除响应
     */
    /**
     * 删除节点（移入回收站）- 新架构版本
     * 
     * 核心逻辑：
     * 1. 只更新根节点的 directory_status 和 last_del_uuid
     * 2. 不扫描子节点，子节点保持原状
     * 3. 初始化 Redis 元数据层（只需知道是文件夹还是文件类型）
     * 4. 添加 batchId 到用户索引列表
     * 5. 创建任务记录（status=1 表示已完成，因为不需要异步扫描）
     * 
     * @param nodeId 节点ID
     * @param nodeType 节点类型（0=文件夹，1=文件）
     * @param userId 用户ID
     * @param version 版本号（乐观锁）
     * @param batchId 批次号（UUID格式）
     * @return 删除响应
     */
    @Transactional
    public DeleteNodeResponse deleteNodeWithBatchId(Long nodeId, Integer nodeType, Long userId, Long version, String batchId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        if (nodeType == null) {
            throw new IllegalArgumentException("节点类型不能为空");
        }
        
        if (version == null) {
            throw new IllegalArgumentException("版本号不能为空");
        }
        
        // 【关键】提前声明变量，用于返回响应
        String recycleBinPath = null;
        LocalDateTime expiresAt = null;
        
        // 2. 根据 nodeType 分别处理
        if (nodeType == 0) {
            // 文件夹删除逻辑
            FolderNode folder = folderNodeMapper.findById(nodeId);
            
            if (folder == null) {
                throw new RuntimeException("文件夹不存在");
            }
            
            // 验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权删除该文件夹");
            }
            
            // 乐观锁校验
            if (!folder.getVersion().equals(version)) {
                throw new com.mizuka.cloudfilesystem.exception.OptimisticLockException(
                    "文件夹已被其他人修改，请刷新后重试"
                );
            }
            
            // 计算回收站路径和过期时间
            recycleBinPath = calculateRecycleBinPath(folder.getPath(), userId);
            expiresAt = LocalDateTime.now().plusDays(30);
            
            // 【关键】只更新根节点状态，不扫描子节点
            softDeleteFolderRoot(nodeId, recycleBinPath, expiresAt);
            
            // 更新节点的 last_del_uuid
            folderNodeMapper.updateLastDelUuid(nodeId, batchId);
            
            log.info("用户 {} 软删除文件夹根节点 - NodeId: {}, BatchId: {}", userId, nodeId, batchId);
            
        } else if (nodeType == 1) {
            // 文件删除逻辑
            FileNode file = fileNodeMapper.findById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("文件不存在");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权删除该文件");
            }
            
            // 乐观锁校验
            if (!file.getVersion().equals(version)) {
                throw new com.mizuka.cloudfilesystem.exception.OptimisticLockException(
                    "文件已被其他人修改，请刷新后重试"
                );
            }
            
            // 计算回收站路径和过期时间
            recycleBinPath = calculateRecycleBinPath(file.getPath(), userId);
            expiresAt = LocalDateTime.now().plusDays(30);
            
            // 执行软删除
            softDeleteFile(nodeId, recycleBinPath, expiresAt);
            
            // 更新文件的 last_del_uuid
            fileNodeMapper.updateLastDelUuid(nodeId, batchId);
            
            log.info("用户 {} 软删除文件 - NodeId: {}, BatchId: {}", userId, nodeId, batchId);
            
        } else {
            throw new IllegalArgumentException("无效的节点类型，0为文件夹，1为文件");
        }
        
        // 3. 初始化 Redis 元数据层（包含name和version字段）
        java.util.Map<String, String> rootInfo = new java.util.HashMap<>();
        rootInfo.put("rootNodeId", String.valueOf(nodeId));
        rootInfo.put("nodeType", String.valueOf(nodeType));
        rootInfo.put("userId", String.valueOf(userId));
        rootInfo.put("batchId", batchId);
        
        log.info("[删除操作] 开始查询节点信息 - NodeId: {}, NodeType: {}", nodeId, nodeType);
        
        // 【关键】保存 name、size 和 version 字段
        if (nodeType == 0) {
            // 【修复】使用 findInRecycleBinById 查询已软删除的节点
            FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
            if (folder != null) {
                rootInfo.put("name", folder.getName());
                log.info("[删除操作] 文件夹信息 - NodeId: {}, Name: {}, Version: {}", 
                    nodeId, folder.getName(), folder.getVersion());
                if (folder.getVersion() != null) {
                    rootInfo.put("version", String.valueOf(folder.getVersion()));
                }
            } else {
                log.error("[删除操作] 文件夹不存在 - NodeId: {}", nodeId);
                throw new RuntimeException("文件夹不存在");
            }
        } else {
            // 【修复】使用 findInRecycleBinById 查询已软删除的节点
            FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
            if (file != null) {
                rootInfo.put("name", file.getName());
                rootInfo.put("size", String.valueOf(file.getFileSize()));
                log.info("[删除操作] 文件信息 - NodeId: {}, Name: {}, Size: {}, Version: {}", 
                    nodeId, file.getName(), file.getFileSize(), file.getVersion());
                if (file.getVersion() != null) {
                    rootInfo.put("version", String.valueOf(file.getVersion()));
                }
            } else {
                log.error("[删除操作] 文件不存在 - NodeId: {}", nodeId);
                throw new RuntimeException("文件不存在");
            }
        }
        
        rootInfo.put("createdAt", String.valueOf(System.currentTimeMillis()));
        rootInfo.put("deletedAt", String.valueOf(System.currentTimeMillis()));
        long expiresAtMillis = System.currentTimeMillis() + 30L * 24 * 3600 * 1000;
        rootInfo.put("expiresAt", String.valueOf(expiresAtMillis));
        
        // 计算剩余天数
        int daysRemaining = 30;
        rootInfo.put("daysRemaining", String.valueOf(daysRemaining));
        
        log.info("[删除操作] Redis元数据准备完成 - BatchId: {}, 总字段数: {}, Keys: {}", 
            batchId, rootInfo.size(), rootInfo.keySet());
        
        recycleBinRedisService.cacheBatchInfo(batchId, rootInfo);
        
        log.info("[删除操作] Redis元数据已保存 - BatchId: {}", batchId);
        
        // 4. 添加 batchId 到用户索引列表
        recycleBinRedisService.addBatchToUserList(userId, batchId, LocalDateTime.now());
        
        // 5. 创建任务记录（status=1 表示已完成，因为不需要异步扫描）
        RecycleBinTask task = new RecycleBinTask();
        task.setBatchId(batchId);
        task.setUserId(userId);
        task.setRootNodeId(nodeId);
        task.setNodeType(nodeType);
        task.setOperationType(0); // 删除操作
        task.setStatus(1); // 已完成（不再需要异步扫描）
        task.setProcessedCount(1); // 始终为1
        task.setTotalCount(1); // 始终为1
        task.setCreatedAt(LocalDateTime.now());
        task.setCompletedAt(LocalDateTime.now());
        
        Long taskId = recycleBinTaskMapper.insert(task);
        log.info("创建回收站任务 - BatchId: {}, TaskId: {}, Status: 已完成", batchId, taskId);
        
        // 6. 返回成功响应（使用之前计算的 recycleBinPath 和 expiresAt）
        return new DeleteNodeResponse(recycleBinPath, expiresAt);
    }
    
    /**
     * 根据节点ID获取 batchId
     * 
     * @param nodeId 节点ID
     * @return batchId，如果不存在则返回 null
     */
    public String getBatchIdByNodeId(Long nodeId) {
        // 先尝试从文件夹表中查找
        FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
        if (folder != null && folder.getLastDelUuid() != null) {
            return folder.getLastDelUuid();
        }
        
        // 再尝试从文件表中查找
        FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
        if (file != null && file.getLastDelUuid() != null) {
            return file.getLastDelUuid();
        }
        
        return null;
    }
}
