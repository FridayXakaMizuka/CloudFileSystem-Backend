package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 恢复进程信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestoreProcessInfo {
    
    /**
     * 业务操作批次号（UUID格式）
     */
    private String batchId;
    
    /**
     * 恢复的根节点ID
     */
    private Long nodeId;
    
    /**
     * 恢复的根节点名称
     */
    private String nodeName;
    
    /**
     * 状态：0=进行中，1=已完成，2=失败，3=已终止
     */
    private Integer status;
    
    /**
     * 总节点数
     */
    private Integer totalCount;
    
    /**
     * 已处理节点数
     */
    private Integer processedCount;
    
    /**
     * 任务创建时间（ISO 8601格式）
     */
    private LocalDateTime createdAt;
}
