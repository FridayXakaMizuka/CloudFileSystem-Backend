package com.mizuka.cloudfilesystem.dto;

import lombok.Data;

/**
 * 重命名节点请求DTO
 */
@Data
public class RenameNodeRequest {
    
    /**
     * 新名称
     */
    private String newName;
}
