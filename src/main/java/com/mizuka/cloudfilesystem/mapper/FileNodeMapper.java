package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.FileNode;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文件节点Mapper
 */
@Mapper
public interface FileNodeMapper {
    
    /**
     * 游标分页查询子文件
     * 
     * @param folderId 父文件夹ID
     * @param userId 用户ID
     * @param lastNodeId 游标锚点ID（上一页最后一个节点ID）
     * @param limit 查询数量
     * @return 子文件列表
     */
    @Select("<script>" +
            "SELECT * FROM file_nodes " +
            "WHERE folder_id = #{folderId} " +
            "AND user_id = #{userId} " +
            "AND is_deleted = 0 " +
            "AND directory_status = 'active' " +
            "<if test='lastNodeId != null'>" +
            "AND id &gt; #{lastNodeId} " +
            "</if>" +
            "ORDER BY id ASC " +  // 移除 sort_order
            "LIMIT #{limit}" +
            "</script>")
    List<FileNode> findChildrenByCursor(@Param("folderId") Long folderId,
                                        @Param("userId") Long userId,
                                        @Param("lastNodeId") Long lastNodeId,
                                        @Param("limit") int limit);
    
    /**
     * 统计子文件数量
     */
    @Select("SELECT COUNT(*) FROM file_nodes " +
            "WHERE folder_id = #{folderId} AND user_id = #{userId} " +
            "AND is_deleted = 0 AND directory_status = 'active'")
    long countChildren(@Param("folderId") Long folderId, @Param("userId") Long userId);
    
    /**
     * 统一游标分页查询子文件（支持多种排序）
     * 
     * @param folderId 父文件夹ID
     * @param userId 用户ID
     * @param lastCreatedAt 游标：上一页最后一条的创建时间
     * @param lastUpdatedAt 游标：上一页最后一条的更新时间
     * @param lastName 游标：上一页最后一条的名称
     * @param lastId 游标：上一页最后一条的ID
     * @param limit 查询数量
     * @param sortedBy 排序字段：0=name, 1=size, 2=createdAt, 3=updatedAt
     * @param order 排序顺序：asc/desc
     * @return 子文件列表
     */
    @Select("<script>" +
            "SELECT * FROM file_nodes " +
            "WHERE folder_id = #{folderId} " +
            "AND user_id = #{userId} " +
            "AND is_deleted = 0 " +
            "AND directory_status = 'active' " +
            "<if test='sortedBy == 0 and lastName != null and order == \"asc\"'>" +
            "AND (name &gt; #{lastName} OR (name = #{lastName} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 0 and lastName != null and order == \"desc\"'>" +
            "AND (name &lt; #{lastName} OR (name = #{lastName} AND id &lt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 1 and lastSize != null and order == \"asc\"'>" +
            "AND (file_size &gt; #{lastSize} OR (file_size = #{lastSize} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 1 and lastSize != null and order == \"desc\"'>" +
            "AND (file_size &lt; #{lastSize} OR (file_size = #{lastSize} AND id &lt; #{lastId})) " +
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
            "  <when test='sortedBy == 1'>file_size</when>" +
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
    List<FileNode> findChildrenWithSortCursor(@Param("folderId") Long folderId,
                                              @Param("userId") Long userId,
                                              @Param("lastCreatedAt") java.time.LocalDateTime lastCreatedAt,
                                              @Param("lastUpdatedAt") java.time.LocalDateTime lastUpdatedAt,
                                              @Param("lastName") String lastName,
                                              @Param("lastSize") Long lastSize,
                                              @Param("lastId") Long lastId,
                                              @Param("limit") int limit,
                                              @Param("sortedBy") Integer sortedBy,
                                              @Param("order") String order);
    
    /**
     * 统一游标分页查询回收站中的子文件（支持多种排序）
     * 
     * @param folderId 父文件夹ID
     * @param userId 用户ID
     * @param lastCreatedAt 游标：上一页最后一条的创建时间
     * @param lastUpdatedAt 游标：上一页最后一条的更新时间
     * @param lastName 游标：上一页最后一条的名称
     * @param lastId 游标：上一页最后一条的ID
     * @param limit 查询数量
     * @param sortedBy 排序字段：0=name, 1=size, 2=createdAt, 3=updatedAt
     * @param order 排序顺序：asc/desc
     * @return 子文件列表
     */
    @Select("<script>" +
            "SELECT * FROM file_nodes " +
            "WHERE folder_id = #{folderId} " +
            "AND user_id = #{userId} " +
            "AND directory_status = 'in_recycle_bin' " +
            "<if test='sortedBy == 0 and lastName != null and order == \"asc\"'>" +
            "AND (name &gt; #{lastName} OR (name = #{lastName} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 0 and lastName != null and order == \"desc\"'>" +
            "AND (name &lt; #{lastName} OR (name = #{lastName} AND id &lt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 1 and lastSize != null and order == \"asc\"'>" +
            "AND (file_size &gt; #{lastSize} OR (file_size = #{lastSize} AND id &gt; #{lastId})) " +
            "</if>" +
            "<if test='sortedBy == 1 and lastSize != null and order == \"desc\"'>" +
            "AND (file_size &lt; #{lastSize} OR (file_size = #{lastSize} AND id &lt; #{lastId})) " +
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
            "  <when test='sortedBy == 1'>file_size</when>" +
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
    List<FileNode> findRecycleBinChildrenWithSortCursor(@Param("folderId") Long folderId,
                                                        @Param("userId") Long userId,
                                                        @Param("lastCreatedAt") java.time.LocalDateTime lastCreatedAt,
                                                        @Param("lastUpdatedAt") java.time.LocalDateTime lastUpdatedAt,
                                                        @Param("lastName") String lastName,
                                                        @Param("lastSize") Long lastSize,
                                                        @Param("lastId") Long lastId,
                                                        @Param("limit") int limit,
                                                        @Param("sortedBy") Integer sortedBy,
                                                        @Param("order") String order);

    /**
     * 获取文件的排序字段
     *
     * @param id 文件ID
     * @return 排序字段
     */
    @Select("SELECT created_at, updated_at, name, file_size FROM file_nodes WHERE id = #{id}")
    Map<String, Object> findSortFieldsById(@Param("id") Long id);
    
    /**
     * 根据ID查询文件
     */
    @Select("SELECT * FROM file_nodes WHERE id = #{id} AND is_deleted = 0 AND directory_status = 'active'")
    FileNode findById(@Param("id") Long id);
    
    /**
     * 重命名文件
     * 
     * @param id 文件ID
     * @param newName 新名称
     * @param newPath 新路径
     */
    @Update("UPDATE file_nodes SET name = #{newName}, path = #{newPath}, updated_at = NOW() WHERE id = #{id}")
    void renameFile(@Param("id") Long id,
                    @Param("newName") String newName,
                    @Param("newPath") String newPath);
    
    /**
     * 移动文件
     * 
     * @param id 文件ID
     * @param newFolderId 新父文件夹ID
     * @param newPath 新路径
     */
    @Update("UPDATE file_nodes SET folder_id = #{newFolderId}, path = #{newPath}, updated_at = NOW() WHERE id = #{id}")
    void moveFile(@Param("id") Long id,
                  @Param("newFolderId") Long newFolderId,
                  @Param("newPath") String newPath);
    
    /**
     * 批量更新子节点路径（用于父文件夹重命名或移动时）
     * 
     * @param oldPathPrefix 旧路径前缀
     * @param newPathPrefix 新路径前缀
     */
    @Update("UPDATE file_nodes SET path = REPLACE(path, #{oldPathPrefix}, #{newPathPrefix}), updated_at = NOW() " +
            "WHERE path LIKE CONCAT(#{oldPathPrefix}, '%')")
    void updateChildrenPaths(@Param("oldPathPrefix") String oldPathPrefix,
                             @Param("newPathPrefix") String newPathPrefix);
    
    /**
     * 从回收站中根据ID查询文件
     */
    @Select("SELECT * FROM file_nodes WHERE id = #{id} AND directory_status = 'in_recycle_bin'")
    FileNode findInRecycleBinById(@Param("id") Long id);
    
    /**
     * 软删除文件
     */
    @Update("UPDATE file_nodes SET " +
            "directory_status = 'in_recycle_bin', " +
            "is_deleted = 1, " +
            "deleted_at = NOW(), " +
            "delete_expires_at = #{expiresAt}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    void softDeleteFile(@Param("id") Long id,
                        @Param("recycleBinPath") String recycleBinPath,
                        @Param("expiresAt") LocalDateTime expiresAt);
    
    /**
     * 软删除文件夹中的所有文件
     */
    @Update("UPDATE file_nodes SET " +
            "directory_status = 'in_recycle_bin', " +
            "is_deleted = 1, " +
            "deleted_at = NOW(), " +
            "delete_expires_at = #{expiresAt}, " +
            "updated_at = NOW() " +
            "WHERE folder_id = #{folderId} AND is_deleted = 0")
    void softDeleteAllFilesInFolder(@Param("folderId") Long folderId,
                                    @Param("recycleBinPath") String recycleBinPath,
                                    @Param("expiresAt") LocalDateTime expiresAt);
    
    /**
     * 恢复文件
     */
    @Update("UPDATE file_nodes SET " +
            "directory_status = 'active', " +
            "is_deleted = 0, " +
            "deleted_at = NULL, " +
            "delete_expires_at = NULL, " +
            "folder_id = #{folderId}, " +
            "path = #{path}, " +
            "updated_at = NOW() " +
            "WHERE id = #{id}")
    void restoreFile(@Param("id") Long id,
                     @Param("folderId") Long folderId,
                     @Param("path") String path);
    
    /**
     * 查询回收站中的子文件
     */
    @Select("SELECT * FROM file_nodes " +
            "WHERE folder_id = #{folderId} " +
            "AND directory_status = 'in_recycle_bin' " +
            "ORDER BY id ASC")
    List<FileNode> findChildrenInRecycleBin(@Param("folderId") Long folderId);
    
    /**
     * 物理删除文件节点
     */
    @Delete("DELETE FROM file_nodes WHERE id = #{id}")
    void permanentDeleteFileNode(@Param("id") Long id);
    
    /**
     * 减少元数据的引用计数
     */
    @Update("UPDATE file_metadata SET reference_count = GREATEST(reference_count - 1, 0), updated_at = NOW() WHERE id = #{id}")
    void decrementMetadataReferenceCount(@Param("id") Long id);
    
    /**
     * 获取元数据的引用计数
     */
    @Select("SELECT reference_count FROM file_metadata WHERE id = #{id}")
    int getMetadataReferenceCount(@Param("id") Long id);
    
    /**
     * 删除文件分片记录
     */
    @Delete("DELETE FROM file_chunks WHERE file_metadata_id = #{fileMetadataId}")
    void deleteFileChunks(@Param("fileMetadataId") Long fileMetadataId);
    
    /**
     * 物理删除文件元数据
     */
    @Delete("DELETE FROM file_metadata WHERE id = #{id}")
    void permanentDeleteFileMetadata(@Param("id") Long id);

    /**
     * 搜索文件（基础版本，无分页）
     */
    @Select("<script>" +
            "SELECT *, 'file' AS node_type, " +
            "       MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) AS relevance " +
            "FROM file_nodes " +
            "WHERE user_id = #{userId} " +
            "  AND is_deleted = 0 " +
            "  AND directory_status = 'active' " +
            "  AND MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) " +
            "<if test='type != null and type == \"file\"'>" +
            "  AND type = 'file' " +
            "</if>" +
            "ORDER BY relevance DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> searchFiles(@Param("keyword") String keyword,
                                          @Param("userId") Long userId,
                                          @Param("type") String type,
                                          @Param("limit") int limit);
    
    /**
     * 搜索文件（支持游标分页，按相关性+ID排序）
     * 
     * @param keyword 搜索关键词
     * @param userId 用户ID
     * @param lastRelevance 上一页最后一条的相关性得分
     * @param lastNameWithoutExt 上一页最后一条的名称（不含扩展名）
     * @param lastExtension 上一页最后一条的扩展名
     * @param lastId 上一页最后一条的ID
     * @param limit 查询数量
     * @return 搜索结果列表
     */
    @Select("<script>" +
            "SELECT *, 'file' AS node_type, " +
            "       MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) AS relevance " +
            "FROM file_nodes " +
            "WHERE user_id = #{userId} " +
            "  AND is_deleted = 0 " +
            "  AND directory_status = 'active' " +
            "  AND MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) " +
            "<if test='lastRelevance != null'>" +
            "  AND (MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) &lt; #{lastRelevance} " +
            "       OR (MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) = #{lastRelevance} " +
            "           AND (SUBSTRING_INDEX(name, '.', -1) != #{lastExtension} OR name != #{lastNameWithoutExt}) " +
            "           AND (SUBSTRING_INDEX(name, '.', -1) &lt; #{lastExtension} OR (SUBSTRING_INDEX(name, '.', -1) = #{lastExtension} AND name &lt; #{lastNameWithoutExt})) " +
            "           OR (SUBSTRING_INDEX(name, '.', -1) = #{lastExtension} AND name = #{lastNameWithoutExt} AND id &lt; #{lastId})) " +
            "       OR (MATCH(name) AGAINST(#{keyword} IN BOOLEAN MODE) = #{lastRelevance} AND id &lt; #{lastId})) " +
            "</if>" +
            "ORDER BY relevance DESC, extension ASC, name ASC, id DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> searchFilesWithCursor(@Param("keyword") String keyword,
                                                     @Param("userId") Long userId,
                                                     @Param("lastRelevance") Double lastRelevance,
                                                     @Param("lastNameWithoutExt") String lastNameWithoutExt,
                                                     @Param("lastExtension") String lastExtension,
                                                     @Param("lastId") Long lastId,
                                                     @Param("limit") int limit);
    
    /**
     * 查询文件夹中的活跃文件（用于异步删除）
     */
    @Select("SELECT * FROM file_nodes " +
            "WHERE folder_id = #{folderId} " +
            "AND is_deleted = 0 " +
            "AND directory_status = 'active' " +
            "ORDER BY id ASC")
    List<FileNode> findActiveChildren(@Param("folderId") Long folderId);
    
    /**
     * 更新节点的 last_del_uuid 字段
     */
    @Update("UPDATE file_nodes SET last_del_uuid = #{batchId}, version = version + 1 WHERE id = #{id}")
    void updateLastDelUuid(@Param("id") Long id, @Param("batchId") String batchId);
    
    /**
     * 标记文件为永久删除（彻底删除）
     */
    @Update("UPDATE file_nodes SET directory_status = 'permanently_deleted', " +
            "is_deleted = 1, last_del_uuid = NULL, deleted_at = NULL, delete_expires_at = NULL, " +
            "version = version + 1 WHERE id = #{nodeId}")
    int markAsPermanentlyDeleted(@Param("nodeId") Long nodeId);
    
    /**
     * 检查指定父目录下是否存在同名文件（不考虑删除状态）
     */
    @Select("SELECT COUNT(*) > 0 FROM file_nodes " +
            "WHERE folder_id = #{folderId} " +
            "AND name = #{name} " +
            "AND is_deleted = 0")
    Boolean existsByNameAndParentId(@Param("name") String name, @Param("folderId") Long folderId);
    
    /**
     * 根据条件查询子文件（用于恢复操作）
     * 条件：directory_status = 'active' OR 
     *       (directory_status = 'in_recycle_bin' AND 
     *        (last_del_uuid = batchId OR last_del_uuid IS NULL))
     */
    @Select("SELECT * FROM file_nodes WHERE folder_id = #{folderId} AND (" +
            "  directory_status = 'active' OR " +
            "  (directory_status = 'in_recycle_bin' AND " +
            "   (last_del_uuid = #{batchId} OR last_del_uuid IS NULL))" +
            ") ORDER BY id ASC")
    List<FileNode> findChildrenByConditions(@Param("folderId") Long folderId,
                                            @Param("batchId") String batchId);
    
    /**
     * 根据条件查询子文件（用于彻底删除的 BFS 遍历，支持断点续传）
     * 
     * @param folderId 父文件夹ID
     * @param batchId 批次号
     * @param lastFileId 断点：上次处理的最后一个文件ID
     * @param limit 查询数量
     * @return 子文件列表
     */
    @Select("SELECT * FROM file_nodes WHERE folder_id = #{folderId} AND (" +
            "  directory_status = 'active' OR " +
            "  (directory_status = 'in_recycle_bin' AND " +
            "   (last_del_uuid = #{batchId} OR last_del_uuid IS NULL))" +
            ") AND id > #{lastFileId} " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<FileNode> findChildrenByConditionsWithCursor(@Param("folderId") Long folderId,
                                                       @Param("batchId") String batchId,
                                                       @Param("lastFileId") Long lastFileId,
                                                       @Param("limit") int limit);
    
    /**
     * 将文件移入待分配池（清空除 id 和 directory_status 外的所有信息）
     */
    @Update("UPDATE file_nodes SET " +
            "name = NULL, path = NULL, folder_id = NULL, user_id = NULL, " +
            "file_metadata_id = NULL, file_size = 0, mime_type = NULL, " +
            "extension = NULL, is_hidden = 0, is_deleted = 0, " +
            "deleted_at = NULL, delete_expires_at = NULL, " +
            "last_del_uuid = NULL, directory_status = 'unassigned', " +
            "unassigned_at = NOW(), version = version + 1 " +
            "WHERE id = #{id}")
    void moveToUnassignedPool(@Param("id") Long id);

}
