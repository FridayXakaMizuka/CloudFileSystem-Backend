package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建文件夹响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NewFolderResponse {
    
    /**
     * 本文件夹ID
     */
    private Long id;
    
    /**
     * 本文件夹名称
     */
    private String name;
    
    /**
     * 本文件夹路径
     */
    private String path;
    
    /**
     * 是否从待分配池中取出
     */
    private Boolean reusedFromPool;
}
