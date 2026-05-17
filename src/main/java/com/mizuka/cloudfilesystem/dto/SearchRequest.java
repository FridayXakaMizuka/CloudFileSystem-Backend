package com.mizuka.cloudfilesystem.dto;

import lombok.Data;

/**
 * 文件搜索请求参数DTO
 */
@Data
public class SearchRequest {
    
    /**
     * 搜索关键词（必填）
     */
    private String keyword;
    
    /**
     * 类型过滤：file/folder/all（默认all）
     */
    private String type = "all";
    
    /**
     * 已显示文件夹数（用于游标分页快速查找）
     */
    private Integer sumFolders;
    
    /**
     * 已显示文件数（用于游标分页快速查找）
     */
    private Integer sumFiles;
    
    /**
     * 文件夹游标分页锚点：上一页最后一个文件夹的ID
     */
    private Long lastFoldersNode;
    
    /**
     * 文件游标分页锚点：上一页最后一个文件的ID
     */
    private Long lastFilesNode;
    
    /**
     * 前端期望的最大返回数量
     */
    private Integer maxPageSize;
}
