package com.mizuka.cloudfilesystem.dto;

import lombok.Data;

/**
 * 移动节点请求DTO
 */
@Data
public class MoveNodeRequest {
    
    /**
     * 新的父节点ID
     */
    private Long newParentId;
}
