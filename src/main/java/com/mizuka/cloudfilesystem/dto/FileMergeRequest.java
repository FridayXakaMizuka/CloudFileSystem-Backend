// src/main/java/com/mizuka/cloudfilesystem/dto/FileMergeRequest.java
package com.mizuka.cloudfilesystem.dto;

/**
 * 文件合并请求 DTO
 */
public class FileMergeRequest {
    private String uploadId;           // 上传任务ID
    private String fileName;           // 文件名
    private String md5;                // MD5 校验
    private String sha256;             // SHA256 校验

    public FileMergeRequest() {}

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
}
