package com.mizuka.cloudfilesystem.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件下载控制器
 * 提供文件下载服务
 */
@RestController
public class FileDownloadController {

    private static final Logger logger = LoggerFactory.getLogger(FileDownloadController.class);

    // 从配置文件读取文件存储路径
    @Value("${file.storage.local.path:D:/CloudFileSystem/files}")
    private String uploadDir;

    /**
     * 下载文件（兼容 /api/file/download 和 /file/download）
     * GET /file/download/{fileName}
     * GET /api/file/download/{fileName}
     * 
     * @param fileName 文件名
     * @return 文件资源
     */
    @GetMapping({"/file/download/{fileName}", "/api/file/download/{fileName}"})
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        try {
            // 构建文件路径
            Path filePath = Paths.get(uploadDir).resolve("files").resolve(fileName).normalize();
            File file = filePath.toFile();

            // 检查文件是否存在
            if (!file.exists()) {
                logger.warn("[文件下载] 文件不存在 - FileName: {}, ExpectedPath: {}", fileName, filePath.toString());
                return ResponseEntity.notFound().build();
            }

            // 检查是否是目录遍历攻击
            String canonicalPath = file.getCanonicalPath();
            String allowedBasePath = new File(uploadDir + "/files").getCanonicalPath();
            if (!canonicalPath.startsWith(allowedBasePath)) {
                logger.warn("[文件下载] 非法的文件访问尝试 - FileName: {}, Path: {}", fileName, canonicalPath);
                return ResponseEntity.badRequest().build();
            }

            // 创建资源对象
            Resource resource = new FileSystemResource(file);

            // 检测文件类型
            String contentType = getContentType(fileName);

            logger.info("[文件下载] 成功 - FileName: {}, ContentType: {}", fileName, contentType);

            // 返回文件
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                    .body(resource);

        } catch (Exception e) {
            logger.error("[文件下载] 失败 - FileName: {}, Error: {}", fileName, e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 根据文件扩展名获取 Content-Type
     */
    private String getContentType(String fileName) {
        String lowerCaseFileName = fileName.toLowerCase();
        
        if (lowerCaseFileName.endsWith(".jpg") || lowerCaseFileName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerCaseFileName.endsWith(".png")) {
            return "image/png";
        } else if (lowerCaseFileName.endsWith(".gif")) {
            return "image/gif";
        } else if (lowerCaseFileName.endsWith(".webp")) {
            return "image/webp";
        } else if (lowerCaseFileName.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lowerCaseFileName.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lowerCaseFileName.endsWith(".txt")) {
            return "text/plain";
        } else if (lowerCaseFileName.endsWith(".json")) {
            return "application/json";
        } else {
            return "application/octet-stream";
        }
    }
}
