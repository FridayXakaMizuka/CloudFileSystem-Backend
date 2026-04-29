// src/main/java/com/mizuka/cloudfilesystem/entity/FileMetadata.java
package com.mizuka.cloudfilesystem.entity;

import java.time.LocalDateTime;

/**
 * 文件元数据实体
 */
public class FileMetadata {
    private Long id;
    private String fileId;             // 文件唯一标识（基于MD5）
    private String fileName;           // 原始文件名
    private String filePath;           // 存储路径
    private Long fileSize;             // 文件大小
    private String md5;                // MD5 哈希
    private String sha256;             // SHA256 哈希
    private String mimeType;           // MIME 类型
    private Long ownerId;              // 文件所有者ID
    private Integer status;            // 状态：0-上传中，1-已完成，2-已删除
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Getter 和 Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
