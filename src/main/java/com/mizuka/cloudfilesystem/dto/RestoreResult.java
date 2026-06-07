package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 恢复结果（支持 200/204 状态码）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestoreResult {
    /**
     * HTTP 状态码（200 或 204）
     */
    private Integer code;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 消息
     */
    private String message;
    
    /**
     * 数据
     */
    private RestoreData data;
}
