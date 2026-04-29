// src/main/java/com/mizuka/cloudfilesystem/dto/FileUploadResponse.java
package com.mizuka.cloudfilesystem.dto;

/**
 * 文件上传响应 DTO
 */
public class FileUploadResponse {
    private int code;
    private boolean success;
    private String message;
    private String filePath;           // 文件访问路径
    private String uploadId;           // 上传任务ID
    private Boolean needUpload;        // 是否需要上传（秒传判断）
    private Integer uploadedChunks;    // 已上传的分片数
    private boolean[] chunkStatus;     // 各分片上传状态

    public FileUploadResponse() {}

    public FileUploadResponse(int code, boolean success, String message) {
        this.code = code;
        this.success = success;
        this.message = message;
    }

    // Getter 和 Setter
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public Boolean getNeedUpload() { return needUpload; }
    public void setNeedUpload(Boolean needUpload) { this.needUpload = needUpload; }

    public Integer getUploadedChunks() { return uploadedChunks; }
    public void setUploadedChunks(Integer uploadedChunks) { this.uploadedChunks = uploadedChunks; }

    public boolean[] getChunkStatus() { return chunkStatus; }
    public void setChunkStatus(boolean[] chunkStatus) { this.chunkStatus = chunkStatus; }
}
