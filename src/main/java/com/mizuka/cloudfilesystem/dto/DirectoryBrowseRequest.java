package com.mizuka.cloudfilesystem.dto;

import lombok.Data;

import java.util.List;

/**
 * 目录浏览请求参数DTO
 */
@Data
public class DirectoryBrowseRequest {
    
    /**
     * 当前目录节点ID（必填）
     */
    private Long currentNodeId;
    
    /**
     * 游标分页锚点，上一页最后一个子节点的ID
     */
    private Long lastChildrenNode;
    
    /**
     * 游标分页锚点类型，上一页最后一个子节点的类型（folder 或 file）
     */
    private String lastChildrenType;
    
    /**
     * 前端期望的最大返回数量
     */
    private Integer maxPageSize;
    
    /**
     * 排序字段：0=createdAt(默认), 1=name, 2=editedAt
     */
    private Integer sortedBy;
    
    /**
     * 排序顺序：0=asc, 1=desc
     */
    private Integer order;
    
    /**
     * 需要排除的新增文件ID列表
     */
    private List<Long> excludeNewFileIds;
    
    /**
     * 需要排除的新增文件夹ID列表
     */
    private List<Long> excludeNewFolderIds;
}
