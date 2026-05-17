package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 游标分页信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CursorPagination {
    
    /**
     * 最后一个子节点ID（用于下一页的游标）
     */
    private Long lastChildrenNode;
    
    /**
     * 最后一个子节点类型（folder/file）
     */
    private String lastChildrenType;
    
    /**
     * 是否已到达末尾
     */
    private Boolean isEnd;
}
