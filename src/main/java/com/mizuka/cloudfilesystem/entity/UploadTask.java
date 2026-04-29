// src/main/java/com/mizuka/cloudfilesystem/entity/UploadTask.java
package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

/**
 * 上传任务实体（用于断点续传）
 */
public class UploadTask {
    private Long id;
    private String uploadId;           // 上传任务ID
    private String fileId;             // 文件ID
    private Long userId;               // 用户ID
    private String fileName;           // 文件名
    private Long fileSize;             // 文件大小
    private Integer totalChunks;       // 总分片数
    private Integer uploadedChunks;    // 已上传分片数
    private String chunkStatus;        // 分片状态（JSON数组）
    private Integer status;            // 状态：0-进行中，1-已完成，2-已取消
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expireAt;    // 过期时间

    // Getter 和 Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }

    public Integer getUploadedChunks() { return uploadedChunks; }
    public void setUploadedChunks(Integer uploadedChunks) { this.uploadedChunks = uploadedChunks; }

    public String getChunkStatus() { return chunkStatus; }
    public void setChunkStatus(String chunkStatus) { this.chunkStatus = chunkStatus; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
}
