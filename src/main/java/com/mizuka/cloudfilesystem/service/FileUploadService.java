// src/main/java/com/mizuka/cloudfilesystem/service/FileUploadService.java
package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.FileMergeRequest;
import com.mizuka.cloudfilesystem.dto.FileUploadRequest;
import com.mizuka.cloudfilesystem.dto.FileUploadResponse;
import com.mizuka.cloudfilesystem.entity.FileMetadata;
import com.mizuka.cloudfilesystem.entity.UploadTask;
import com.mizuka.cloudfilesystem.mapper.FileMetadataMapper;
import com.mizuka.cloudfilesystem.mapper.UploadTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文件上传服务
 * 支持秒传、分片上传、断点续传
 */
@Service
public class FileUploadService {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadService.class);

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private UploadTaskMapper uploadTaskMapper;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String UPLOAD_TASK_PREFIX = "upload:task:";
    private static final long TASK_EXPIRE_HOURS = 24;

    /**
     * 初始化上传任务（检查秒传）
     */
    public FileUploadResponse initUpload(FileUploadRequest request, Long userId) {
        try {
            // 1. 检查文件是否已存在（秒传）
            FileMetadata existingFile = fileMetadataMapper.findByMd5(request.getMd5());
            if (existingFile != null) {
                logger.info("[文件上传] 秒传成功 - UserId: {}, MD5: {}", userId, request.getMd5());

                FileUploadResponse response = new FileUploadResponse();
                response.setCode(200);
                response.setSuccess(true);
                response.setMessage("秒传成功");
                response.setFilePath(fileStorageService.getFileAccessUrl(existingFile.getFilePath()));
                response.setNeedUpload(false);

                return response;
            }

            // 2. 创建上传任务
            String uploadId = UUID.randomUUID().toString();
            UploadTask task = new UploadTask();
            task.setUploadId(uploadId);
            task.setFileId(request.getMd5());
            task.setUserId(userId);
            task.setFileName(request.getFileName());
            task.setFileSize(request.getFileSize());
            task.setTotalChunks(request.getTotalChunks() != null ? request.getTotalChunks() : 1);
            task.setUploadedChunks(0);
            task.setChunkStatus(generateChunkStatus(task.getTotalChunks()));
            task.setStatus(0);
            task.setExpireAt(LocalDateTime.now().plusHours(TASK_EXPIRE_HOURS));

            uploadTaskMapper.insert(task);

            // 缓存到 Redis
            redisTemplate.opsForValue().set(
                    UPLOAD_TASK_PREFIX + uploadId,
                    task,
                    TASK_EXPIRE_HOURS,
                    TimeUnit.HOURS
            );

            logger.info("[文件上传] 任务创建成功 - UploadId: {}, UserId: {}", uploadId, userId);

            FileUploadResponse response = new FileUploadResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("上传任务创建成功");
            response.setUploadId(uploadId);
            response.setNeedUpload(true);
            response.setUploadedChunks(0);

            return response;

        } catch (Exception e) {
            logger.error("[文件上传] 初始化失败 - {}", e.getMessage());
            e.printStackTrace();
            return new FileUploadResponse(500, false, "初始化失败：" + e.getMessage());
        }
    }

    /**
     * 上传文件分片
     */
    public FileUploadResponse uploadChunk(String uploadId, Integer chunkIndex, MultipartFile chunk) {
        try {
            // 1. 获取上传任务
            UploadTask task = (UploadTask) redisTemplate.opsForValue().get(UPLOAD_TASK_PREFIX + uploadId);
            if (task == null) {
                task = uploadTaskMapper.findByUploadId(uploadId);
                if (task == null) {
                    return new FileUploadResponse(404, false, "上传任务不存在或已过期");
                }
            }

            // 2. 检查分片是否已上传
            boolean[] chunkStatus = parseChunkStatus(task.getChunkStatus());
            if (chunkStatus[chunkIndex]) {
                logger.info("[文件上传] 分片已存在 - UploadId: {}, Chunk: {}", uploadId, chunkIndex);

                FileUploadResponse response = new FileUploadResponse();
                response.setCode(200);
                response.setSuccess(true);
                response.setMessage("分片已上传");
                response.setUploadedChunks(task.getUploadedChunks());
                response.setChunkStatus(chunkStatus);

                return response;
            }

            // 3. 保存分片
            fileStorageService.saveChunk(uploadId, chunkIndex, chunk);

            // 4. 更新进度
            task.setUploadedChunks(task.getUploadedChunks() + 1);
            chunkStatus[chunkIndex] = true;
            task.setChunkStatus(generateChunkStatusString(chunkStatus));

            uploadTaskMapper.updateProgress(uploadId, task.getUploadedChunks(), task.getChunkStatus());

            // 更新 Redis
            redisTemplate.opsForValue().set(
                    UPLOAD_TASK_PREFIX + uploadId,
                    task,
                    TASK_EXPIRE_HOURS,
                    TimeUnit.HOURS
            );

            logger.info("[文件上传] 分片上传成功 - UploadId: {}, Chunk: {}/{}, Progress: {}%",
                    uploadId, chunkIndex + 1, task.getTotalChunks(),
                    (task.getUploadedChunks() * 100 / task.getTotalChunks()));

            FileUploadResponse response = new FileUploadResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("分片上传成功");
            response.setUploadedChunks(task.getUploadedChunks());
            response.setChunkStatus(chunkStatus);

            // 如果所有分片都已上传，标记为可合并
            if (task.getUploadedChunks().equals(task.getTotalChunks())) {
                response.setMessage("所有分片上传完成，请调用合并接口");
            }

            return response;

        } catch (Exception e) {
            logger.error("[文件上传] 分片上传失败 - {}", e.getMessage());
            e.printStackTrace();
            return new FileUploadResponse(500, false, "分片上传失败：" + e.getMessage());
        }
    }

    /**
     * 合并文件分片
     */
    public FileUploadResponse mergeChunks(FileMergeRequest request, Long userId) {
        try {
            // 1. 获取上传任务
            UploadTask task = uploadTaskMapper.findByUploadId(request.getUploadId());
            if (task == null) {
                return new FileUploadResponse(404, false, "上传任务不存在");
            }

            if (!task.getUserId().equals(userId)) {
                return new FileUploadResponse(403, false, "无权操作此上传任务");
            }

            if (!task.getUploadedChunks().equals(task.getTotalChunks())) {
                return new FileUploadResponse(400, false,
                        "分片未全部上传：" + task.getUploadedChunks() + "/" + task.getTotalChunks());
            }

            // 2. 合并文件
            String mergedFilePath = fileStorageService.mergeChunks(
                    request.getUploadId(),
                    request.getFileName(),
                    task.getTotalChunks()
            );

            // 3. 保存文件元数据
            FileMetadata metadata = new FileMetadata();
            metadata.setFileId(request.getUploadId());
            metadata.setFileName(request.getFileName());
            metadata.setFilePath(mergedFilePath);
            metadata.setFileSize(task.getFileSize());
            metadata.setMd5(request.getMd5());
            metadata.setSha256(request.getSha256());
            metadata.setOwnerId(userId);
            metadata.setStatus(1);

            fileMetadataMapper.insert(metadata);

            // 4. 更新任务状态
            uploadTaskMapper.updateStatus(request.getUploadId(), 1);

            // 5. 清理 Redis
            redisTemplate.delete(UPLOAD_TASK_PREFIX + request.getUploadId());

            String accessUrl = fileStorageService.getFileAccessUrl(mergedFilePath);

            logger.info("[文件上传] 文件合并成功 - UploadId: {}, UserId: {}, Path: {}",
                    request.getUploadId(), userId, accessUrl);

            FileUploadResponse response = new FileUploadResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("文件上传成功");
            response.setFilePath(accessUrl);

            return response;

        } catch (Exception e) {
            logger.error("[文件上传] 合并失败 - {}", e.getMessage());
            e.printStackTrace();
            return new FileUploadResponse(500, false, "合并失败：" + e.getMessage());
        }
    }

    /**
     * 查询上传进度
     */
    public FileUploadResponse getUploadProgress(String uploadId, Long userId) {
        try {
            UploadTask task = uploadTaskMapper.findByUploadId(uploadId);
            if (task == null) {
                return new FileUploadResponse(404, false, "上传任务不存在");
            }

            if (!task.getUserId().equals(userId)) {
                return new FileUploadResponse(403, false, "无权查看此上传任务");
            }

            boolean[] chunkStatus = parseChunkStatus(task.getChunkStatus());

            FileUploadResponse response = new FileUploadResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("查询成功");
            response.setUploadedChunks(task.getUploadedChunks());
            response.setChunkStatus(chunkStatus);

            return response;

        } catch (Exception e) {
            logger.error("[文件上传] 查询进度失败 - {}", e.getMessage());
            return new FileUploadResponse(500, false, "查询失败：" + e.getMessage());
        }
    }

    // ========== 辅助方法 ==========

    private String generateChunkStatus(int totalChunks) {
        boolean[] status = new boolean[totalChunks];
        Arrays.fill(status, false);
        return generateChunkStatusString(status);
    }

    private String generateChunkStatusString(boolean[] status) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < status.length; i++) {
            sb.append(status[i] ? "1" : "0");
            if (i < status.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private boolean[] parseChunkStatus(String statusStr) {
        // 简化解析，实际应使用 JSON 解析
        String[] parts = statusStr.replaceAll("[\\[\\]]", "").split(",");
        boolean[] status = new boolean[parts.length];
        for (int i = 0; i < parts.length; i++) {
            status[i] = "1".equals(parts[i].trim());
        }
        return status;
    }
}
