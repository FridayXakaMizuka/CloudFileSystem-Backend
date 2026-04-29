// src/main/java/com/mizuka/cloudfilesystem/controller/FileController.java
package com.mizuka.cloudfilesystem.controller;

import com.mizuka.cloudfilesystem.dto.FileMergeRequest;
import com.mizuka.cloudfilesystem.dto.FileUploadRequest;
import com.mizuka.cloudfilesystem.dto.FileUploadResponse;
import com.mizuka.cloudfilesystem.service.FileUploadService;
import com.mizuka.cloudfilesystem.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传控制器
 * 支持秒传、分片上传、断点续传
 */
@RestController
@RequestMapping("/file")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    @Autowired
    private FileUploadService fileUploadService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 初始化上传任务（检查秒传）
     * POST /api/file/upload/init
     */
    @PostMapping("/upload/init")
    public ResponseEntity<FileUploadResponse> initUpload(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody FileUploadRequest request) {
        try {
            // 验证 JWT
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(
                        new FileUploadResponse(401, false, "未提供有效的认证令牌")
                );
            }

            String token = authHeader.substring(7);
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);

            logger.info("[文件上传] 初始化请求 - UserId: {}, FileName: {}", userId, request.getFileName());

            FileUploadResponse response = fileUploadService.initUpload(request, userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("[文件上传] 初始化异常 - {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    new FileUploadResponse(500, false, "服务器错误：" + e.getMessage())
            );
        }
    }

    /**
     * 上传文件分片
     * POST /api/file/upload/chunk
     */
    @PostMapping("/upload/chunk")
    public ResponseEntity<FileUploadResponse> uploadChunk(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") Integer chunkIndex,
            @RequestParam("file") MultipartFile file) {
        try {
            // 验证 JWT
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(
                        new FileUploadResponse(401, false, "未提供有效的认证令牌")
                );
            }

            logger.info("[文件上传] 分片上传 - UploadId: {}, Chunk: {}", uploadId, chunkIndex);

            FileUploadResponse response = fileUploadService.uploadChunk(uploadId, chunkIndex, file);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("[文件上传] 分片上传异常 - {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    new FileUploadResponse(500, false, "服务器错误：" + e.getMessage())
            );
        }
    }

    /**
     * 合并文件分片
     * POST /api/file/upload/merge
     */
    @PostMapping("/upload/merge")
    public ResponseEntity<FileUploadResponse> mergeChunks(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody FileMergeRequest request) {
        try {
            // 验证 JWT
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(
                        new FileUploadResponse(401, false, "未提供有效的认证令牌")
                );
            }

            String token = authHeader.substring(7);
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);

            logger.info("[文件上传] 合并请求 - UploadId: {}, UserId: {}", request.getUploadId(), userId);

            FileUploadResponse response = fileUploadService.mergeChunks(request, userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("[文件上传] 合并异常 - {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    new FileUploadResponse(500, false, "服务器错误：" + e.getMessage())
            );
        }
    }

    /**
     * 查询上传进度
     * GET /api/file/upload/progress/{uploadId}
     */
    @GetMapping("/upload/progress/{uploadId}")
    public ResponseEntity<FileUploadResponse> getUploadProgress(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String uploadId) {
        try {
            // 验证 JWT
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(
                        new FileUploadResponse(401, false, "未提供有效的认证令牌")
                );
            }

            String token = authHeader.substring(7);
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);

            FileUploadResponse response = fileUploadService.getUploadProgress(uploadId, userId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("[文件上传] 查询进度异常 - {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    new FileUploadResponse(500, false, "服务器错误：" + e.getMessage())
            );
        }
    }
}
