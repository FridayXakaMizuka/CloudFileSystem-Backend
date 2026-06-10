package com.mizuka.cloudfilesystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件节点实体类
 * 对应数据库中的 file_nodes 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileNode {

    // 文件节点ID
    private Long id;

    // 所属文件夹ID
    private Long folderId;

    // 所属用户ID，NULL表示系统文件（仅管理员可访问）
    private Long userId;

    // 关联文件元数据ID
    private Long fileMetadataId;

    // 显示文件名
    private String name;

    // 完整路径，如 _root/_files/10001/documents/file.pdf
    // @Deprecated 未来版本将移除，改用 folder_id + Redis 动态生成
    private String path;

    // 文件大小（字节）
    private Long fileSize;

    // MIME类型
    private String mimeType;

    // 文件扩展名
    private String extension;

    // 是否隐藏
    private Boolean isHidden;

    // 是否已删除（软删除）
    private Boolean isDeleted;

    // 删除时间
    private LocalDateTime deletedAt;

    // 删除过期时间（回收站30天后彻底删除）
    private LocalDateTime deleteExpiresAt;

    // 文件状态：活跃/回收站中/已彻底删除
    private String directoryStatus;

    // 最后一次删除的UUID批次号（用于追踪异步删除操作）
    private String lastDelUuid;

    // 创建时间
    private LocalDateTime createdAt;

    // 更新时间
    private LocalDateTime updatedAt;

    // 版本号
    private Long version;
}
