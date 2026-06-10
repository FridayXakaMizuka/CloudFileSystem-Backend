package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回收站项目 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecycleBinItemDTO {
    private String batchId;
    private Long id;
    private String name;
    private String type; // "folder" or "file"
    private Long size;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private Long version;
}
