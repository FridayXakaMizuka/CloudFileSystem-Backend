// src/main/java/com/mizuka/cloudfilesystem/dto/FileUploadRequest.java
package com.mizuka.cloudfilesystem.dto;

/**
 * 文件上传请求 DTO
 */
public class FileUploadRequest {
    private String fileName;           // 文件名
    private Long fileSize;             // 文件大小（字节）
    private String md5;                // MD5 哈希值
    private String sha256;             // SHA256 哈希值
    private String mimeType;           // MIME 类型
    private Integer totalChunks;       // 总分片数
    private Integer chunkIndex;        // 当前分片索引（从0开始）
    private String uploadId;           // 上传任务ID（用于断点续传）

    // 构造函数、Getter、Setter
    public FileUploadRequest() {}

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
}
