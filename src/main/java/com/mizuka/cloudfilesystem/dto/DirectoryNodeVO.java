package com.mizuka.cloudfilesystem.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 目录节点VO（用于前端展示）
 */
@Data
public class DirectoryNodeVO {
    
    /**
     * 节点ID
     */
    private Long id;
    
    /**
     * 节点名称
     */
    private String name;
    
    /**
     * 节点类型：folder 或 file
     */
    private String type;
    
    /**
     * 完整路径
     */
    private String path;
    
    /**
     * 父节点ID
     */
    private Long parentId;
    
    /**
     * 是否为文件夹且有子节点（仅文件夹有效）
     */
    private Boolean hasChildren;
    
    /**
     * 子节点数量（仅文件夹有效）
     */
    private Integer childCount;
    
    /**
     * 文件大小（字节，仅文件有效）
     */
    private Long size;
    
    /**
     * MIME类型（仅文件有效）
     */
    private String mimeType;
    
    /**
     * 文件扩展名（仅文件有效）
     */
    private String extension;
    
    /**
     * 缩略图路径（仅文件有效）
     */
    private String thumbnail;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    // ========== 回收站特有字段 ==========
    
    /**
     * 删除时间（仅回收站中的节点）
     */
    private LocalDateTime deletedAt;
    
    /**
     * 过期时间（仅回收站中的节点，30天后彻底删除）
     */
    private LocalDateTime expiresAt;
    
    /**
     * 剩余天数（仅回收站中的节点）
     */
    private Integer daysRemaining;
}
