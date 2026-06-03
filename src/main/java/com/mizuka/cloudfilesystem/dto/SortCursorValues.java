package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 游标排序字段值DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SortCursorValues {
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 名称
     */
    private String name;
    
    /**
     * 文件大小（仅文件有效）
     */
    private Long size;
}
