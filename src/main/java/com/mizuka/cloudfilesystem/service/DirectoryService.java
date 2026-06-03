package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.*;
import com.mizuka.cloudfilesystem.entity.FileNode;
import com.mizuka.cloudfilesystem.entity.FolderNode;
import com.mizuka.cloudfilesystem.mapper.FileNodeMapper;
import com.mizuka.cloudfilesystem.mapper.FolderNodeMapper;
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
     * 
     * @param nodeId 节点ID
     * @param userId 用户ID
     * @return 删除响应
     */
    @Transactional
    public DeleteNodeResponse deleteNode(Long nodeId, Long userId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        // 2. 先尝试查找文件夹
        FolderNode folder = folderNodeMapper.findById(nodeId);
        
        if (folder != null) {
            // 是文件夹，验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权删除该文件夹");
            }
            
            // 计算回收站路径和过期时间
            String recycleBinPath = calculateRecycleBinPath(folder.getPath(), userId);
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(30); // 30天后过期
            
            // 执行软删除（包括所有子节点）
            softDeleteFolder(nodeId, recycleBinPath, expiresAt);
            
            log.info("用户 {} 软删除文件夹 - NodeId: {}, RecycleBinPath: {}", userId, nodeId, recycleBinPath);
            
            return new DeleteNodeResponse(recycleBinPath, expiresAt);
            
        } else {
            // 尝试查找文件
            FileNode file = fileNodeMapper.findById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("节点不存在");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权删除该文件");
            }
            
            // 计算回收站路径和过期时间
            String recycleBinPath = calculateRecycleBinPath(file.getPath(), userId);
            LocalDateTime expiresAt = LocalDateTime.now().plusDays(30); // 30天后过期
            
            // 执行软删除
            softDeleteFile(nodeId, recycleBinPath, expiresAt);
            
            log.info("用户 {} 软删除文件 - NodeId: {}, RecycleBinPath: {}", userId, nodeId, recycleBinPath);
            
            return new DeleteNodeResponse(recycleBinPath, expiresAt);
        }
    }
    
    /**
     * 恢复回收站中的节点
     * 
     * @param nodeId 节点ID
     * @param userId 用户ID
     * @return 恢复响应
     */
    @Transactional
    public RestoreNodeResponse restoreNode(Long nodeId, Long userId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        // 2. 先尝试查找文件夹
        FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
        
        if (folder != null) {
            // 是文件夹，验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权恢复该文件夹");
            }
            
            // 检查是否已过期
            if (folder.getDeleteExpiresAt() != null && folder.getDeleteExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("该节点已过期，无法恢复");
            }
            
            // 恢复到原始位置或用户根目录
            String restoredPath = restoreFolderFromRecycleBin(nodeId, userId);
            
            log.info("用户 {} 恢复文件夹 - NodeId: {}, RestoredPath: {}", userId, nodeId, restoredPath);
            
            return new RestoreNodeResponse(restoredPath);
            
        } else {
            // 尝试查找文件
            FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("节点不存在或不在回收站中");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权恢复该文件");
            }
            
            // 检查是否已过期
            if (file.getDeleteExpiresAt() != null && file.getDeleteExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("该节点已过期，无法恢复");
            }
            
            // 恢复到原始位置或用户根目录
            String restoredPath = restoreFileFromRecycleBin(nodeId, userId);
            
            log.info("用户 {} 恢复文件 - NodeId: {}, RestoredPath: {}", userId, nodeId, restoredPath);
            
            return new RestoreNodeResponse(restoredPath);
        }
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
     * 软删除文件夹（包括所有子节点）
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
     * 从回收站恢复文件夹
     */
    private String restoreFolderFromRecycleBin(Long folderId, Long userId) {
        // 获取文件夹的原始位置信息
        FolderNode folder = folderNodeMapper.findInRecycleBinById(folderId);
        
        String restoredPath;
        
        // 检查原始父文件夹是否仍然存在
        if (folder.getOriginalParentId() != null) {
            FolderNode originalParent = folderNodeMapper.findById(folder.getOriginalParentId());
            
            if (originalParent != null && !Boolean.TRUE.equals(originalParent.getIsDeleted())) {
                // 原始父文件夹存在，恢复到原位置
                restoredPath = folder.getOriginalPath();
                folderNodeMapper.restoreFolder(folderId, folder.getOriginalParentId(), restoredPath);
            } else {
                // 原始父文件夹已删除，恢复到用户根目录
                restoredPath = restoreToUserRoot(folder, userId);
            }
        } else {
            // 没有原始位置信息，恢复到用户根目录
            restoredPath = restoreToUserRoot(folder, userId);
        }
        
        return restoredPath;
    }
    
    /**
     * 从回收站恢复文件
     */
    private String restoreFileFromRecycleBin(Long fileId, Long userId) {
        // 获取文件的原始位置信息
        FileNode file = fileNodeMapper.findInRecycleBinById(fileId);
        
        String restoredPath;
        
        // 检查原始文件夹是否仍然存在
        if (file.getOriginalFolderId() != null) {
            FolderNode originalFolder = folderNodeMapper.findById(file.getOriginalFolderId());
            
            if (originalFolder != null && !Boolean.TRUE.equals(originalFolder.getIsDeleted())) {
                // 原始文件夹存在，恢复到原位置
                restoredPath = file.getOriginalPath();
                fileNodeMapper.restoreFile(fileId, file.getOriginalFolderId(), restoredPath);
            } else {
                // 原始文件夹已删除，恢复到用户根目录
                restoredPath = restoreFileToUserRoot(file, userId);
            }
        } else {
            // 没有原始位置信息，恢复到用户根目录
            restoredPath = restoreFileToUserRoot(file, userId);
        }
        
        return restoredPath;
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
     * 彻底删除节点（从回收站中永久删除）
     * 
     * @param nodeId 节点ID
     * @param userId 用户ID
     */
    @Transactional
    public void permanentDeleteNode(Long nodeId, Long userId) {
        // 1. 参数校验
        if (nodeId == null) {
            throw new IllegalArgumentException("节点ID不能为空");
        }
        
        // 2. 先尝试查找文件夹
        FolderNode folder = folderNodeMapper.findInRecycleBinById(nodeId);
        
        if (folder != null) {
            // 是文件夹，验证权限
            if (folder.getUserId() != null && !userId.equals(folder.getUserId())) {
                throw new RuntimeException("无权删除该文件夹");
            }
            
            // 递归彻底删除所有子节点
            permanentDeleteFolder(nodeId);
            
            log.info("用户 {} 彻底删除文件夹 - NodeId: {}", userId, nodeId);
            
        } else {
            // 尝试查找文件
            FileNode file = fileNodeMapper.findInRecycleBinById(nodeId);
            
            if (file == null) {
                throw new RuntimeException("节点不存在或不在回收站中");
            }
            
            // 验证权限
            if (file.getUserId() != null && !userId.equals(file.getUserId())) {
                throw new RuntimeException("无权删除该文件");
            }
            
            // 彻底删除文件
            permanentDeleteFile(nodeId, file.getFileMetadataId());
            
            log.info("用户 {} 彻底删除文件 - NodeId: {}", userId, nodeId);
        }
    }
    
    /**
     * 递归彻底删除文件夹及其所有子节点
     */
    private void permanentDeleteFolder(Long folderId) {
        // 1. 先递归删除所有子文件夹
        List<FolderNode> childFolders = folderNodeMapper.findChildrenInRecycleBin(folderId);
        for (FolderNode childFolder : childFolders) {
            permanentDeleteFolder(childFolder.getId());
        }
        
        // 2. 删除所有子文件
        List<FileNode> childFiles = fileNodeMapper.findChildrenInRecycleBin(folderId);
        for (FileNode childFile : childFiles) {
            permanentDeleteFile(childFile.getId(), childFile.getFileMetadataId());
        }
        
        // 3. 最后删除当前文件夹（标记为 unassigned，进入待分配池）
        folderNodeMapper.markAsUnassigned(folderId);
    }
    
    /**
     * 彻底删除文件节点和元数据
     */
    private void permanentDeleteFile(Long fileId, Long fileMetadataId) {
        // 1. 物理删除文件节点
        fileNodeMapper.permanentDeleteFileNode(fileId);
        
        // 2. 减少元数据的引用计数
        fileNodeMapper.decrementMetadataReferenceCount(fileMetadataId);
        
        // 3. 如果引用计数为0，物理删除元数据和分片
        int referenceCount = fileNodeMapper.getMetadataReferenceCount(fileMetadataId);
        if (referenceCount <= 0) {
            // 删除分片记录
            fileNodeMapper.deleteFileChunks(fileMetadataId);
            // 删除元数据
            fileNodeMapper.permanentDeleteFileMetadata(fileMetadataId);
        }
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

            return vo;
        }).collect(Collectors.toList());
    }
}
