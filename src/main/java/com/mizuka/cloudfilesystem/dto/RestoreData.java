package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 恢复数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestoreData {
    /**
     * 恢复后的名称
     */
    private String newName;
    
    /**
     * 节点类型（folder/file）
     */
    private String nodeType;
    
    /**
     * 恢复后的完整路径
     */
    private String restoredPath;
    
    /**
     * 新版本号
     */
    private Long newVersion;
}
