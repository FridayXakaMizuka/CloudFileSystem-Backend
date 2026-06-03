package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FolderNode;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件夹节点Mapper（完整版）
 */
@Mapper
public interface FolderNodeMapper {
    
    /**
     * 根据ID查询文件夹
     */
    @Select("SELECT * FROM folder_nodes WHERE id = #{id} AND is_deleted = 0 AND directory_status = 'active'")
    FolderNode findById(@Param("id") Long id);
    
    /**
     * 统一游标分页查询子文件夹（支持多种排序）
     */
    @Select("<script>" +
            "SELECT * FROM folder_nodes " +
            "WHERE parent_id = #{parentId} " +
            "AND user_id = #{userId} " +
            "AND is_deleted = 0 " +
            "AND directory_status = 'active' " +
            "<if test='sortedBy == 0 and lastName != null and order == \"asc\"'>" +
            "AND (name &gt; #{lastName} OR (name = #{lastName} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 0 and lastName != null and order == \"desc\"'>" +
            "AND (name &lt; #{lastName} OR (name = #{lastName} AND id &lt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 2 and lastCreatedAt != null and order == \"asc\"'>" +
            "AND (created_at &gt; #{lastCreatedAt} OR (created_at = #{lastCreatedAt} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 2 and lastCreatedAt != null and order == \"desc\"'>" +
            "AND (created_at &lt; #{lastCreatedAt} OR (created_at = #{lastCreatedAt} AND id &lt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 3 and lastUpdatedAt != null and order == \"asc\"'>" +
            "AND (updated_at &gt; #{lastUpdatedAt} OR (updated_at = #{lastUpdatedAt} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 3 and lastUpdatedAt != null and order == \"desc\"'>" +
            "AND (updated_at &lt; #{lastUpdatedAt} OR (updated_at = #{lastUpdatedAt} AND id &lt; #{lastId})) " +
            "</if>" +
            "ORDER BY " +
            "<choose>" +
            "  <when test='sortedBy == 2'>created_at</when>" +
            "  <when test='sortedBy == 3'>updated_at</when>" +
            "  <otherwise>name</otherwise>" +
            "</choose> " +
            "<choose>" +
            "  <when test='order == \"desc\"'>DESC</when>" +
            "  <otherwise>ASC</otherwise>" +
            "</choose>, " +
            "id " +
            "<choose>" +
            "  <when test='order == \"desc\"'>DESC</when>" +
            "  <otherwise>ASC</otherwise>" +
            "</choose> " +
            "LIMIT #{limit}" +
            "</script>")
    List<FolderNode> findChildrenWithSortCursor(@Param("parentId") Long parentId,
                                                @Param("userId") Long userId,
                                                @Param("lastCreatedAt") java.time.LocalDateTime lastCreatedAt,
                                                @Param("lastUpdatedAt") java.time.LocalDateTime lastUpdatedAt,
                                                @Param("lastName") String lastName,
                                                @Param("lastId") Long lastId,
                                                @Param("limit") int limit,
                                                @Param("sortedBy") Integer sortedBy,
                                                @Param("order") String order);
    
    /**
     * 批量统计多个文件夹的子节点数量
     * 优化：一次性查询所有文件夹的子节点数，避免 N+1 问题
     * 
     * @param folderIds 文件夹ID列表
     * @param userId 用户ID
     * @return Map<folderId, childCount>
     */
    @Select("<script>" +
            "SELECT fn.id AS folder_id, " +
            "(SELECT COUNT(*) FROM folder_nodes WHERE parent_id = fn.id AND user_id = #{userId} AND is_deleted = 0 AND directory_status = 'active') + " +
            "(SELECT COUNT(*) FROM file_nodes WHERE folder_id = fn.id AND user_id = #{userId} AND is_deleted = 0 AND directory_status = 'active') AS child_count " +
            "FROM folder_nodes fn " +
            "WHERE fn.id IN " +
            "<foreach collection='folderIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<Map<String, Object>> batchCountChildren(@Param("folderIds") List<Long> folderIds,
                                                  @Param("userId") Long userId);
    
    /**
     * 统一游标分页查询回收站中的子文件夹（支持多种排序）
     */
    @Select("<script>" +
            "SELECT * FROM folder_nodes " +
            "WHERE parent_id = #{parentId} " +
            "AND user_id = #{userId} " +
            "AND directory_status = 'in_recycle_bin' " +
            "<if test='sortedBy == 0 and lastName != null and order == \"asc\"'>" +
            "AND (name &gt; #{lastName} OR (name = #{lastName} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 0 and lastName != null and order == \"desc\"'>" +
            "AND (name &lt; #{lastName} OR (name = #{lastName} AND id &lt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 2 and lastCreatedAt != null and order == \"asc\"'>" +
            "AND (created_at &gt; #{lastCreatedAt} OR (created_at = #{lastCreatedAt} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 2 and lastCreatedAt != null and order == \"desc\"'>" +
            "AND (created_at &lt; #{lastCreatedAt} OR (created_at = #{lastCreatedAt} AND id &lt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 3 and lastUpdatedAt != null and order == \"asc\"'>" +
            "AND (updated_at &gt; #{lastUpdatedAt} OR (updated_at = #{lastUpdatedAt} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 3 and lastUpdatedAt != null and order == \"desc\"'>" +
            "AND (updated_at &lt; #{lastUpdatedAt} OR (updated_at = #{lastUpdatedAt} AND id &lt; #{lastId})) " +
            "</if>" +
            "ORDER BY " +
            "<choose>" +
            "  <when test='sortedBy == 2'>created_at</when>" +
            "  <when test='sortedBy == 3'>updated_at</when>" +
            "  <otherwise>name</otherwise>" +
            "</choose> " +
            "<choose>" +
            "  <when test='order == \"desc\"'>DESC</when>" +
            "  <otherwise>ASC</otherwise>" +
            "</choose>, " +
            "id " +
            "<choose>" +
            "  <when test='order == \"desc\"'>DESC</when>" +
            "  <otherwise>ASC</otherwise>" +
            "</choose> " +
            "LIMIT #{limit}" +
            "</script>")
    List<FolderNode> findRecycleBinChildrenWithSortCursor(@Param("parentId") Long parentId,
                                                          @Param("userId") Long userId,
                                                          @Param("lastCreatedAt") java.time.LocalDateTime lastCreatedAt,
                                                          @Param("lastUpdatedAt") java.time.LocalDateTime lastUpdatedAt,
                                                          @Param("lastName") String lastName,
                                                          @Param("lastId") Long lastId,
                                                          @Param("limit") int limit,
                                                          @Param("sortedBy") Integer sortedBy,
                                                          @Param("order") String order);
    
    /**
     * 统计子文件夹数量
     */
    @Select("SELECT COUNT(*) FROM folder_nodes " +
            "WHERE parent_id = #{parentId} AND user_id = #{userId} " +
            "AND is_deleted = 0 AND directory_status = 'active'")
    long countChildren(@Param("parentId") Long parentId, @Param("userId") Long userId);
    
    /**
     * 获取文件夹的排序字段
     */
    @Select("SELECT created_at, updated_at, name FROM folder_nodes WHERE id = #{id}")
    Map<String, Object> findSortFieldsById(@Param("id") Long id);
    
    /**
     * 查找并认领一个待分配的文件夹（user_id = NULL）
     * 使用 FOR UPDATE 锁定行以防止并发冲突
     * 
     * @param userId 要分配的用户ID
     * @return 待分配的文件夹，如果没有则返回 null
     */
    @Select("SELECT * FROM folder_nodes " +
            "WHERE directory_status = 'unassigned' " +
            "AND is_deleted = 0 " +
            "ORDER BY id ASC " +
            "LIMIT 1 " +
            "FOR UPDATE")
    FolderNode findAndClaimUnassignedFolder(@Param("userId") Long userId);
    
    /**
     * 更新文件夹信息（用于复用待分配文件夹）
     */
    @Update("UPDATE folder_nodes SET " +
            "parent_id = #{parentId}, " +
            "user_id = #{userId}, " +
            "name = #{name}, " +
            "path = #{path}, " +
            "updated_at = #{updatedAt} " +
            "WHERE id = #{id}")
    void updateFolderInfo(FolderNode folder);
    
    /**
     * 插入新文件夹
     */
    @Insert("INSERT INTO folder_nodes (" +
            "parent_id, user_id, name, path, level, sort_order, is_hidden, " +
            "is_deleted, deleted_at, delete_expires_at, directory_status, " +
            "created_at, updated_at" +
            ") VALUES (" +
            "#{parentId}, #{userId}, #{name}, #{path}, #{level}, #{sortOrder}, #{isHidden}, " +
            "#{isDeleted}, #{deletedAt}, #{deleteExpiresAt}, #{directoryStatus}, " +
            "#{createdAt}, #{updatedAt}" +
            ")")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertFolder(FolderNode folder);
    
    /**
     * 重命名文件夹
     * 
     * @param id 文件夹ID
     * @param newName 新名称
     * @param newPath 新路径
     */
    @Update("UPDATE folder_nodes SET name = #{newName}, path = #{newPath}, updated_at = NOW() WHERE id = #{id}")
    void renameFolder(@Param("id") Long id, 
                      @Param("newName") String newName, 
                      @Param("newPath") String newPath);
    
    /**
     * 移动文件夹
     * 
     * @param id 文件夹ID
     * @param newParentId 新父节点ID
     * @param newPath 新路径
     */
    @Update("UPDATE folder_nodes SET parent_id = #{newParentId}, path = #{newPath}, updated_at = NOW() WHERE id = #{id}")
    void moveFolder(@Param("id") Long id,
                    @Param("newParentId") Long newParentId,
                    @Param("newPath") String newPath);
    
    /**
     * 批量更新子节点路径（用于重命名或移动文件夹时）
     * 
     * @param oldPathPrefix 旧路径前缀
     * @param newPathPrefix 新路径前缀
     */
    @Update("UPDATE folder_nodes SET path = REPLACE(path, #{oldPathPrefix}, #{newPathPrefix}), updated_at = NOW() " +
            "WHERE path LIKE CONCAT(#{oldPathPrefix}, '%')")
    void updateChildrenPaths(@Param("oldPathPrefix") String oldPathPrefix,
                             @Param("newPathPrefix") String newPathPrefix);
    
    /**
     * 查询节点的所有祖先节点ID
     * 
     * @param nodeId 节点ID
     * @return 祖先节点ID列表
     */
    @Select("WITH RECURSIVE ancestors AS (" +
            "  SELECT id, parent_id FROM folder_nodes WHERE id = #{nodeId}" +
            "  UNION ALL" +
            "  SELECT f.id, f.parent_id FROM folder_nodes f INNER JOIN ancestors a ON f.id = a.parent_id" +
            ")" +
            "SELECT id FROM ancestors WHERE id != #{nodeId}")
    List<Long> findAncestorIds(@Param("nodeId") Long nodeId);
    
    /**
     * 从回收站中根据ID查询文件夹
     */
    @Select("SELECT * FROM folder_nodes WHERE id = #{id} AND directory_status = 'in_recycle_bin'")
    FolderNode findInRecycleBinById(@Param("id") Long id);
    
    /**
     * 软删除文件夹
     */
    @Update("UPDATE folder_nodes SET " +
            "directory_status = 'in_recycle_bin', " +
            "is_deleted = 1, " +
            "deleted_at = NOW(), " +
            "delete_expires_at = #{expiresAt}, " +
            "path = #{recycleBinPath}, " +
            "original_parent_id = parent_id, " +
            "original_path = path, " +
            "parent_id = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    void softDeleteFolder(@Param("id") Long id,
                          @Param("recycleBinPath") String recycleBinPath,
                          @Param("expiresAt") LocalDateTime expiresAt);
    
    /**
     * 递归软删除所有子文件夹
     */
    @Update("UPDATE folder_nodes SET " +
            "directory_status = 'in_recycle_bin', " +
            "is_deleted = 1, " +
            "deleted_at = NOW(), " +
            "delete_expires_at = #{expiresAt}, " +
            "path = CONCAT(#{recycleBinPath}, SUBSTRING(path, LENGTH(#{oldPathPrefix}) + 1)), " +
            "updated_at = NOW() " +
            "WHERE parent_id = #{folderId} AND is_deleted = 0")
    void softDeleteAllChildrenFolders(@Param("folderId") Long folderId,
                                      @Param("recycleBinPath") String recycleBinPath,
                                      @Param("expiresAt") LocalDateTime expiresAt);
    
    /**
     * 恢复文件夹
     */
    @Update("UPDATE folder_nodes SET " +
            "directory_status = 'active', " +
            "is_deleted = 0, " +
            "deleted_at = NULL, " +
            "delete_expires_at = NULL, " +
            "parent_id = #{parentId}, " +
            "path = #{path}, " +
            "original_parent_id = NULL, " +
            "original_path = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    void restoreFolder(@Param("id") Long id,
                       @Param("parentId") Long parentId,
                       @Param("path") String path);
    
    /**
     * 查询用户根目录ID
     */
    @Select("SELECT id FROM folder_nodes " +
            "WHERE user_id = #{userId} " +
            "AND parent_id = (SELECT id FROM folder_nodes WHERE path = '_root/_files') " +
            "LIMIT 1")
    Long findUserRootId(@Param("userId") Long userId);
    
    /**
     * 查询用户回收站ID
     */
    @Select("SELECT id FROM folder_nodes " +
            "WHERE user_id = #{userId} " +
            "AND parent_id = (SELECT id FROM folder_nodes WHERE path = '_root/_recycle_bin') " +
            "LIMIT 1")
    Long findRecycleBinId(@Param("userId") Long userId);
    
    /**
     * 获取_files目录的ID（用于创建用户根目录的父目录）
     */
    @Select("SELECT id FROM folder_nodes WHERE path = '_root/_files' LIMIT 1")
    Long getFilesDirectoryId();
    
    /**
     * 查询回收站中的子文件夹
     */
    @Select("SELECT * FROM folder_nodes " +
            "WHERE parent_id = #{parentId} " +
            "AND directory_status = 'in_recycle_bin' " +
            "ORDER BY id ASC")
    List<FolderNode> findChildrenInRecycleBin(@Param("parentId") Long parentId);
    
    /**
     * 标记文件夹为待分配状态（进入待分配池）
     */
    @Update("UPDATE folder_nodes SET " +
            "directory_status = 'unassigned', " +
            "user_id = NULL, " +
            "parent_id = NULL, " +
            "path = NULL, " +
            "is_deleted = 0, " +
            "deleted_at = NULL, " +
            "delete_expires_at = NULL, " +
            "original_parent_id = NULL, " +
            "original_path = NULL, " +
            "unassigned_at = NOW(), " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    void markAsUnassigned(@Param("id") Long id);

    /**
     * 搜索文件夹（基础版本，无分页）
     */
    @Select("<script>" +
            "SELECT *, 'folder' AS node_type, " +
            "       MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) AS relevance " +
            "FROM folder_nodes " +
            "WHERE user_id = #{userId} " +
            "  AND is_deleted = 0 " +
            "  AND directory_status = 'active' " +
            "  AND MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) " +
            "<if test='type != null and type == \"folder\"'>" +
            "  AND type = 'folder' " +
            "</if>" +
            "ORDER BY relevance DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> searchFolders(@Param("keyword") String keyword,
                                            @Param("userId") Long userId,
                                            @Param("type") String type,
                                            @Param("limit") int limit);
    
    /**
     * 搜索文件夹（支持游标分页，按相关性+ID排序）
     * 
     * @param keyword 搜索关键词
     * @param userId 用户ID
     * @param lastRelevance 上一页最后一条的相关性得分
     * @param lastName 上一页最后一条的名称
     * @param lastId 上一页最后一条的ID
     * @param limit 查询数量
     * @return 搜索结果列表
     */
    @Select("<script>" +
            "SELECT *, 'folder' AS node_type, " +
            "       MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) AS relevance " +
            "FROM folder_nodes " +
            "WHERE user_id = #{userId} " +
            "  AND is_deleted = 0 " +
            "  AND directory_status = 'active' " +
            "  AND MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) " +
            "<if test='lastRelevance != null'>" +
            "  AND (MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) &lt; #{lastRelevance} " +
            "       OR (MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) = #{lastRelevance} " +
            "           AND (name &lt; #{lastName} OR (name = #{lastName} AND id &lt; #{lastId}))) " +
            "</if>" +
            "ORDER BY relevance DESC, name ASC, id DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> searchFoldersWithCursor(@Param("keyword") String keyword,
                                                       @Param("userId") Long userId,
                                                       @Param("lastRelevance") Double lastRelevance,
                                                       @Param("lastName") String lastName,
                                                       @Param("lastId") Long lastId,
                                                       @Param("limit") int limit);
    
    /**
     * 检查指定父目录下是否存在同名文件夹
     * 
     * @param parentId 父目录ID
     * @param folderName 文件夹名称
     * @param userId 用户ID
     * @return 如果存在返回文件夹对象，否则返回null
     */
    @Select("SELECT * FROM folder_nodes " +
            "WHERE parent_id = #{parentId} " +
            "AND name = #{folderName} " +
            "AND user_id = #{userId} " +
            "AND is_deleted = 0 " +
            "AND directory_status = 'active' " +
            "LIMIT 1")
    FolderNode findByNameAndParent(@Param("parentId") Long parentId,
                                    @Param("folderName") String folderName,
                                    @Param("userId") Long userId);
}
