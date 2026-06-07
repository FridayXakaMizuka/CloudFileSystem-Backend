package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 删除会话信息
 * 用于追踪异步删除任务的进度和状态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeleteSession {
    
    /**
     * 会话唯一标识
     * 格式: sess_del_{timestamp}_{random}
     */
    private String sessionId;
    
    /**
     * 被删除的节点ID
     */
    private Long nodeId;
    
    /**
     * 节点类型 (0=文件夹, 1=文件)
     */
    private Integer nodeType;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 状态: running/completed/failed
     */
    private String status;
    
    /**
     * 总节点数（仅文件夹删除时有意义）
     */
    private Integer totalNodes;
    
    /**
     * 已处理节点数（仅文件夹删除时有意义）
     */
    private Integer processedNodes;
    
    /**
     * 错误消息（失败时记录）
     */
    private String errorMessage;
    
    /**
     * 回收站路径
     */
    private String recycleBinPath;
    
    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;
    
    /**
     * 当前处理的父文件夹ID（用于断点续传）
     */
    private Long currentParentId;
    
    /**
     * 最后处理的子节点ID（用于断点续传）
     */
    private Long lastProcessedNodeId;
}
