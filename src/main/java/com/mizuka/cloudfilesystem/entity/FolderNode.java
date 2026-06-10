package com.mizuka.cloudfilesystem.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件夹节点实体类
 * 对应数据库中的 folder_nodes 表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FolderNode {

    // 主键ID
    private Long id;

    // 父文件夹ID，NULL表示根目录
    private Long parentId;

    // 所属用户ID，NULL表示系统/管理员目录（仅管理员可访问）
    private Long userId;

    // 文件夹名称
    private String name;

    // 完整路径，如 _root/_files/10001/documents
    // @Deprecated 未来版本将移除，改用 parent_ids + Redis 动态生成
    private String path;

    // 层级深度，根目录为0
    private Integer level;

    // 是否隐藏
    private Boolean isHidden;

    // 是否已删除（软删除）
    private Boolean isDeleted;

    // 删除时间
    private LocalDateTime deletedAt;

    // 删除过期时间（回收站30天后彻底删除）
    private LocalDateTime deleteExpiresAt;

    // 目录状态：活跃/回收站中/待分配
    private String directoryStatus;

    // 进入待分配池的时间（逻辑标记，无物理存储）
    private LocalDateTime unassignedAt;

    // 最后一次删除的UUID批次号（用于追踪异步删除操作）
    private String lastDelUuid;

    // 直接子文件数量
    private Integer fileCount;

    // 直接子文件夹数量
    private Integer folderCount;

    // 文件夹总大小（字节，包含所有子文件）
    private Long totalSize;

    // 创建时间
    private LocalDateTime createdAt;

    // 更新时间
    private LocalDateTime updatedAt;

    // 版本号
    private Long version;
}
