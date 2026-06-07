package com.mizuka.cloudfilesystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回收站任务实体
 * 用于追踪删除/恢复/彻底删除操作
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecycleBinTask {
    
    /**
     * 任务ID
     */
    private Long id;
    
    /**
     * 业务操作批次号（UUID格式）
     */
    private String batchId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 根节点ID（文件夹或文件）
     */
    private Long rootNodeId;
    
    /**
     * 节点类型：0=文件夹，1=文件
     */
    private Integer nodeType;
    
    /**
     * 操作类型：0=删除，1=恢复，2=彻底删除
     */
    private Integer operationType;
    
    /**
     * 总节点数（异步扫描后更新）
     */
    private Integer totalCount;
    
    /**
     * 已处理节点数
     */
    private Integer processedCount;
    
    /**
     * 状态：0=进行中，1=已完成，2=失败，3=已终止
     */
    private Integer status;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
}
