package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文件搜索响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {
    
    /**
     * 搜索结果列表
     */
    private List<SearchResultVO> results;
    
    /**
     * 分页信息
     */
    private SearchPagination pagination;
    
    /**
     * 搜索分页信息（双游标）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchPagination {
        
        /**
         * 文件夹游标锚点：最后一个文件夹的ID
         */
        private Long lastFolderNode;
        
        /**
         * 文件游标锚点：最后一个文件的ID
         */
        private Long lastFileNode;
        
        /**
         * 文件夹是否已到达末尾
         */
        private Boolean isEndFolder;
        
        /**
         * 文件是否已到达末尾
         */
        private Boolean isEndFile;
        
        /**
         * 当前返回的文件夹数量
         */
        private Integer countFolders;
        
        /**
         * 当前返回的文件数量
         */
        private Integer countFiles;
    }
}
