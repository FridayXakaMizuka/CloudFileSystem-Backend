package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 恢复节点响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestoreNodeResponse {
    
    /**
     * 恢复后的路径
     */
    private String restoredPath;
}
