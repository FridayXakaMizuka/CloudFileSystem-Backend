package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 删除节点响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeleteNodeResponse {
    
    /**
     * 回收站路径
     */
    private String recycleBinPath;
    
    /**
     * 过期时间（彻底删除时间）
     */
    private LocalDateTime expiresAt;
}
