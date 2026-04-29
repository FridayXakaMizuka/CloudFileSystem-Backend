// src/main/java/com/mizuka/cloudfilesystem/service/FileStorageService.java
package com.mizuka.cloudfilesystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 文件存储服务
 * 支持本地存储和远程服务器存储
 */
@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    @Value("${file.storage.type:local}")
    private String storageType;  // local 或 remote

    @Value("${file.storage.local.path:/data/files}")
    private String localStoragePath;

    @Value("${file.storage.remote.host:}")
    private String remoteHost;

    @Value("${file.storage.remote.port:22}")
    private int remotePort;

    @Value("${file.storage.remote.username:}")
    private String remoteUsername;

    @Value("${file.storage.remote.password:}")
    private String remotePassword;

    @Value("${file.storage.remote.path:/data/files}")
    private String remotePath;

    /**
     * 保存文件分片
     */
    public String saveChunk(String uploadId, Integer chunkIndex, MultipartFile chunk) throws IOException {
        if ("remote".equals(storageType)) {
            return saveChunkToRemote(uploadId, chunkIndex, chunk);
        } else {
            return saveChunkToLocal(uploadId, chunkIndex, chunk);
        }
    }

    /**
     * 保存到本地
     */
    private String saveChunkToLocal(String uploadId, Integer chunkIndex, MultipartFile chunk) throws IOException {
        String chunkDir = localStoragePath + "/chunks/" + uploadId;
        Path dirPath = Paths.get(chunkDir);
        Files.createDirectories(dirPath);

        String chunkFileName = chunkIndex + ".part";
        Path chunkPath = dirPath.resolve(chunkFileName);

        chunk.transferTo(chunkPath.toFile());

        logger.info("[文件存储] 分片保存成功 - UploadId: {}, Chunk: {}, Path: {}",
                uploadId, chunkIndex, chunkPath.toString());

        return chunkPath.toString();
    }

    /**
     * 保存到远程服务器（简化版，实际需要使用 SSH/SFTP）
     */
    private String saveChunkToRemote(String uploadId, Integer chunkIndex, MultipartFile chunk) throws IOException {
        // TODO: 实现 SFTP 上传逻辑
        // 这里使用伪代码，实际需要集成 JSch 或其他 SFTP 库

        logger.warn("[文件存储] 远程存储暂未实现，使用本地存储");
        return saveChunkToLocal(uploadId, chunkIndex, chunk);
    }

    /**
     * 合并文件分片
     */
    public String mergeChunks(String uploadId, String fileName, Integer totalChunks) throws IOException {
        if ("remote".equals(storageType)) {
            return mergeChunksOnRemote(uploadId, fileName, totalChunks);
        } else {
            return mergeChunksOnLocal(uploadId, fileName, totalChunks);
        }
    }

    /**
     * 在本地合并分片
     */
    private String mergeChunksOnLocal(String uploadId, String fileName, Integer totalChunks) throws IOException {
        String chunkDir = localStoragePath + "/chunks/" + uploadId;
        String finalDir = localStoragePath + "/files";

        Path finalDirPath = Paths.get(finalDir);
        Files.createDirectories(finalDirPath);

        // 生成最终文件路径
        String finalFileName = UUID.randomUUID().toString() + "_" + fileName;
        Path finalFilePath = finalDirPath.resolve(finalFileName);

        // 合并所有分片
        try (OutputStream outputStream = new FileOutputStream(finalFilePath.toFile())) {
            for (int i = 0; i < totalChunks; i++) {
                Path chunkPath = Paths.get(chunkDir, i + ".part");
                if (!Files.exists(chunkPath)) {
                    throw new IOException("分片 " + i + " 不存在");
                }

                byte[] chunkData = Files.readAllBytes(chunkPath);
                outputStream.write(chunkData);
            }
        }

        // 删除临时分片目录
        deleteDirectory(new File(chunkDir));

        logger.info("[文件存储] 文件合并成功 - UploadId: {}, Path: {}", uploadId, finalFilePath.toString());

        return finalFilePath.toString();
    }

    /**
     * 在远程服务器合并分片
     */
    private String mergeChunksOnRemote(String uploadId, String fileName, Integer totalChunks) throws IOException {
        // TODO: 实现远程合并逻辑
        logger.warn("[文件存储] 远程合并暂未实现，使用本地合并");
        return mergeChunksOnLocal(uploadId, fileName, totalChunks);
    }

    /**
     * 获取文件访问路径
     */
    public String getFileAccessUrl(String filePath) {
        if ("remote".equals(storageType)) {
            return "http://" + remoteHost + ":" + remotePort + "/files/" +
                    new File(filePath).getName();
        } else {
            return "/file/download/" + new File(filePath).getName();
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        directory.delete();
    }
}
