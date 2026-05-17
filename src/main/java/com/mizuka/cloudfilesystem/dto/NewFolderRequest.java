package com.mizuka.cloudfilesystem.dto;

import lombok.Data;

/**
 * 创建文件夹请求DTO
 */
@Data
public class NewFolderRequest {
    
    /**
     * 父节点ID
     */
    private Long parentId;
    
    /**
     * 新文件夹名称
     */
    private String folderName;
}
