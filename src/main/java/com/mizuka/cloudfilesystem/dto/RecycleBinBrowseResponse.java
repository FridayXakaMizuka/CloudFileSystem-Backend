package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 浏览回收站响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecycleBinBrowseResponse {
    private List<RecycleBinItemDTO> children;
    private PaginationInfo pagination;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaginationInfo {
        private String lastBatchId;
        private Boolean isEnd;
    }
}
