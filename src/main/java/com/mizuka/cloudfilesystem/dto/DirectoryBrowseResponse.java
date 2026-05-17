package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 目录浏览响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DirectoryBrowseResponse {
    
    /**
     * 当前节点信息
     */
    private DirectoryNodeVO currentNode;
    
    /**
     * 子节点列表
     */
    private List<DirectoryNodeVO> children;
    
    /**
     * 分页信息
     */
    private CursorPagination pagination;
}
