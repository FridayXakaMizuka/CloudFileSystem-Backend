package com.mizuka.cloudfilesystem.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultVO {

    /**
     * 文件或文件夹ID
     */
    private Long id;

    /**
     * 文件或文件夹名称
     */
    private String name;

    /**
     * 文件或文件夹类型
     */
    private String type; // file 或 folder

    /**
     * 文件或文件夹路径
     */
    private String path;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 文件MIME类型
     */
    private String mimeType;

    /**
     * 文件扩展名
     */
    private String extension;

    /**
     * 缩略图路径
     */
    private String thumbnail;

    /**
     * 是否有子节点（仅文件夹）
     */
    private Boolean hasChildren;

    /**
     * 子节点数量（仅文件夹）
     */
    private Integer childCount;

    /**
     * 相关性得分
     */
    private Double relevance;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}