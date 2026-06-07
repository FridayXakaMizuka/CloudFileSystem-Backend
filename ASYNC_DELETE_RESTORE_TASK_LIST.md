# 企业级网盘异步删除与恢复 - 后端实施任务清单

## 📋 项目概述

基于 Redis 队列驱动和滑动窗口限流机制，实现大规模文件/文件夹的异步删除与恢复功能，确保数据库 I/O 平滑、用户体验极致、并发安全可控。

### 核心架构要点
- **Redis 实例**: 新建独立实例，端口 `6381`
- **Redis 数据库分配**:
  - DB 0: 删除操作会话存储
  - DB 1: 恢复操作会话存储
- **限流策略**: 滑动窗口算法，各限速 1000 IOPS
- **事务拆分**: 批量处理 1000-5000 条记录/批次
- **并发控制**: 状态前置校验 + 数据库乐观锁

---

## 🗂️ 第一阶段：基础设施准备

### 1.1 Redis 实例部署与配置
- [ ] **安装 Redis 6381 实例**
  - [ ] 下载并安装 Redis（推荐版本 7.x）
  - [ ] 配置文件：`redis-6381.conf`
  - [ ] 设置端口：`port 6381`
  - [ ] 启用持久化：RDB + AOF 双写
  - [ ] 配置内存限制：建议 2GB+
  - [ ] 设置密码认证（生产环境必需）

- [ ] **Redis 配置优化**
  ```conf
  # redis-6381.conf
  port 6381
  bind 127.0.0.1
  requirepass your_strong_password
  maxmemory 2gb
  maxmemory-policy allkeys-lru
  save 900 1
  save 300 10
  save 60 10000
  appendonly yes
  appendfsync everysec
  ```

- [ ] **启动 Redis 服务**
  ```bash
  redis-server /path/to/redis-6381.conf --daemonize yes
  ```

- [ ] **验证 Redis 连接**
  ```bash
  redis-cli -p 6381 -a your_password ping
  # 应返回 PONG
  ```

### 1.2 Spring Boot Redis 配置
- [ ] **添加依赖**（pom.xml）
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>
  ```

- [ ] **配置多数据源**（application.yaml）
  ```yaml
  directo:
    redis:
      delete:
        host: localhost
        port: 6381
        password: your_password
        database: 0
        lettuce:
          pool:
            max-active: 50
            max-idle: 20
            min-idle: 5
      restore:
        host: localhost
        port: 6381
        password: your_password
        database: 1
        lettuce:
          pool:
            max-active: 50
            max-idle: 20
            min-idle: 5
  ```

- [ ] **创建 Redis 配置类**
  - [ ] `DeleteRedisConfig.java` - 删除操作 RedisTemplate
  - [ ] `RestoreRedisConfig.java` - 恢复操作 RedisTemplate
  - [ ] 分别注入不同的 RedisConnectionFactory

---

## 🔒 第二阶段：数据库 schema 升级（乐观锁支持）

### 2.1 创建新版本 SQL 文件
- [ ] **文件名**: `database_schema_v3_optimistic_lock.sql`
- [ ] **编码**: UTF-8 (utf8mb4)
- [ ] **位置**: 项目根目录

### 2.2 修改表结构 - 添加乐观锁字段
- [ ] **folder_nodes 表**
  ```sql
  ALTER TABLE `folder_nodes` 
  ADD COLUMN `version` BIGINT DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`,
  ADD INDEX `idx_version` (`version`);
  ```

- [ ] **file_nodes 表**
  ```sql
  ALTER TABLE `file_nodes` 
  ADD COLUMN `version` BIGINT DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`,
  ADD INDEX `idx_version` (`version`);
  ```

- [ ] **file_metadata 表**（可选，用于引用计数保护）
  ```sql
  ALTER TABLE `file_metadata` 
  ADD COLUMN `version` BIGINT DEFAULT 0 COMMENT '乐观锁版本号' AFTER `updated_at`;
  ```

### 2.3 更新存储过程以支持乐观锁
- [ ] **sp_restore_file_from_recycle_bin**
  - [ ] 在 UPDATE 语句中添加 `AND version = #{oldVersion}`
  - [ ] 增加版本号自增：`SET NEW.version = OLD.version + 1`

- [ ] **sp_restore_folder_from_recycle_bin**
  - [ ] 同上，添加乐观锁条件

- [ ] **sp_cleanup_expired_recycle_bin_folders**
  - [ ] 添加版本号检查

- [ ] **sp_cleanup_expired_recycle_bin_files**
  - [ ] 添加版本号检查

### 2.4 执行 SQL 迁移脚本
- [ ] **备份现有数据库**
  ```bash
  mysqldump -u root -p cloud_file_database > backup_before_v3_$(date +%Y%m%d).sql
  ```

- [ ] **执行迁移脚本**
  ```bash
  mysql -u root -p cloud_file_database < database_schema_v3_optimistic_lock.sql
  ```

- [ ] **验证迁移结果**
  ```sql
  SHOW COLUMNS FROM folder_nodes LIKE 'version';
  SHOW COLUMNS FROM file_nodes LIKE 'version';
  ```

---

## ⚙️ 第三阶段：滑动窗口限流器实现

### 3.1 Lua 脚本开发
- [ ] **创建 Lua 脚本目录**: `src/main/resources/lua/`

- [ ] **滑动窗口限流脚本** (`sliding_window_rate_limiter.lua`)
  ```lua
  -- KEYS[1]: 限流键名
  -- ARGV[1]: 当前时间戳（毫秒）
  -- ARGV[2]: 窗口大小（毫秒），如 1000ms
  -- ARGV[3]: 最大请求数，如 1000
  
  local key = KEYS[1]
  local now = tonumber(ARGV[1])
  local window_size = tonumber(ARGV[2])
  local max_requests = tonumber(ARGV[3])
  
  -- 清理窗口外的旧记录
  local window_start = now - window_size
  redis.call('ZREMRANGEBYSCORE', key, '-inf', window_start)
  
  -- 统计当前窗口内的请求数
  local current_count = redis.call('ZCARD', key)
  
  if current_count < max_requests then
      -- 未超限，允许通过
      redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))
      redis.call('EXPIRE', key, math.ceil(window_size / 1000) + 1)
      return 1  -- 允许
  else
      return 0  -- 拒绝
  end
  ```

- [ ] **注册 Lua 脚本到 Spring**
  - [ ] 创建 `RateLimiterLuaScript.java`
  - [ ] 使用 `DefaultRedisScript<Long>` 加载脚本

### 3.2 限流器服务实现
- [ ] **创建接口**: `RateLimiterService.java`
  ```java
  public interface RateLimiterService {
      boolean tryAcquire(String key, int maxIops);
      void acquireWithBackoff(String key, int maxIops);
  }
  ```

- [ ] **实现类**: `RedisSlidingWindowRateLimiter.java`
  - [ ] 注入两个 RedisTemplate（delete/restore）
  - [ ] 实现 `tryAcquire` 方法调用 Lua 脚本
  - [ ] 实现 `acquireWithBackoff` 方法（失败后休眠重试）

- [ ] **单元测试**
  - [ ] 测试正常流量下的通过率
  - [ ] 测试超限时是否正确拒绝
  - [ ] 测试窗口滑动逻辑

---

## 🗑️ 第四阶段：异步删除功能实现

### 4.1 数据模型设计
- [ ] **创建 DTO 类**
  - [ ] `DeleteProcessInfo.java` - 删除进程信息
    ```java
    private String processId;
    private String nodeName;
    private Long nodeId;
    private Integer nodeType; // 0=folder, 1=file
    private String sessionId;
    private LocalDateTime startTime;
    private String status; // running/completed/failed
    private DeleteProgress progress;
    ```
  - [ ] `DeleteProgress.java` - 删除进度
    ```java
    private int totalNodes;
    private int deletedNodes;
    private int totalFiles;
    private int deletedFiles;
    private int totalFolders;
    private int deletedFolders;
    ```

- [ ] **创建响应类**
  - [ ] `DeleteNodeResponse.java`（已存在，需扩展字段）
    - 新增: `processId`, `sessionId`

### 4.2 Mapper 层改造
- [ ] **FolderNodeMapper.java**
  - [ ] 添加批量软删除方法（带乐观锁）
    ```java
    @Update("UPDATE folder_nodes SET directory_status = 'in_recycle_bin', " +
            "is_deleted = 1, deleted_at = NOW(), " +
            "delete_expires_at = DATE_ADD(NOW(), INTERVAL 30 DAY), " +
            "original_parent_id = parent_id, original_path = path, " +
            "version = version + 1 " +
            "WHERE id IN (<foreach collection='ids' item='id'>#{id}</foreach>) " +
            "AND version = #{version}")
    int batchSoftDeleteFolders(@Param("ids") List<Long> ids, @Param("version") Long version);
    ```

  - [ ] 添加查询回收站子节点方法
    ```java
    List<FolderNode> findChildrenInRecycleBin(@Param("parentId") Long parentId);
    ```

- [ ] **FileNodeMapper.java**
  - [ ] 添加批量软删除方法（带乐观锁）
  - [ ] 添加查询回收站子文件方法

### 4.3 Service 层实现
- [ ] **创建 DeleteProcessService.java**
  - [ ] 方法: `startDeleteProcess(Long nodeId, Integer nodeType, Long userId)`
    - [ ] 验证权限
    - [ ] 生成 processId 和 sessionId
    - [ ] 存入 Redis DB 0
    - [ ] 启动异步任务
    - [ ] 立即返回进程信息

  - [ ] 方法: `getDeleteProcesses(Long userId)`
    - [ ] 从 Redis DB 0 查询用户的所有删除进程
    - [ ] 过滤过期进程（>24小时）
    - [ ] 返回进程列表

  - [ ] 方法: `cleanupExpiredProcesses()`
    - [ ] 定时任务（每小时执行）
    - [ ] 清理所有用户的过期进程

- [ ] **创建 AsyncDeleteWorker.java**
  - [ ] 使用 `@Async` 注解
  - [ ] 方法: `executeDeleteAsync(String processId, Long nodeId, Integer nodeType)`
    - [ ] 获取节点信息
    - [ ] 标记顶层节点为"删除中"状态
    - [ ] 递归收集所有子节点 ID
    - [ ] 分批处理（每批 1000-5000 条）
    - [ ] 每批次前调用限流器 `acquireWithBackoff`
    - [ ] 执行批量软删除（带乐观锁）
    - [ ] 更新进度到 Redis
    - [ ] 完成后移除进程记录

### 4.4 Controller 层接口
- [ ] **修改 FileController.java**
  - [ ] 修改现有删除接口
    ```java
    @DeleteMapping("/files/delete")
    public Result<DeleteNodeResponse> deleteNode(
        @RequestParam Long nodeId,
        @RequestParam Integer nodeType) {
        // 改为异步模式，立即返回 processId
    }
    ```

  - [ ] 新增查询删除进程接口
    ```java
    @GetMapping("/files/delete/processes")
    public Result<List<DeleteProcessInfo>> getDeleteProcesses() {
        // 返回当前用户的所有删除进程
    }
    ```

---

## ♻️ 第五阶段：异步恢复功能实现

### 5.1 数据模型设计
- [ ] **创建 DTO 类**
  - [ ] `RestoreProcessInfo.java` - 恢复进程信息
    ```java
    private String processId;
    private String nodeName;
    private Long nodeId;
    private Integer nodeType;
    private String sessionId;
    private LocalDateTime startTime;
    private String status; // running/completed/failed
    private RestoreProgress progress;
    ```
  - [ ] `RestoreProgress.java` - 恢复进度
    ```java
    private int totalNodes;
    private int restoredNodes;
    private int totalFiles;
    private int restoredFiles;
    private int totalFolders;
    private int restoredFolders;
    ```

- [ ] **扩展现有响应类**
  - [ ] `RestoreNodeResponse.java`（如不存在则创建）
    - 包含: `processId`, `sessionId`, `nodeName`

### 5.2 Mapper 层改造
- [ ] **FolderNodeMapper.java**
  - [ ] 添加批量恢复方法（带乐观锁）
    ```java
    @Update("UPDATE folder_nodes SET directory_status = 'active', " +
            "is_deleted = 0, deleted_at = NULL, delete_expires_at = NULL, " +
            "parent_id = CASE WHEN #{originalParentExists} THEN #{originalParentId} ELSE #{userRootId} END, " +
            "path = CASE WHEN #{originalParentExists} THEN #{originalPath} ELSE #{newPath} END, " +
            "original_parent_id = NULL, original_path = NULL, " +
            "version = version + 1 " +
            "WHERE id IN (<foreach collection='ids' item='id'>#{id}</foreach>) " +
            "AND version = #{version}")
    int batchRestoreFolders(...);
    ```

  - [ ] 添加智能恢复路径判断方法
    ```java
    Boolean checkParentExists(@Param("parentId") Long parentId);
    ```

- [ ] **FileNodeMapper.java**
  - [ ] 添加批量恢复方法（带乐观锁）
  - [ ] 添加原始父文件夹存在性检查

### 5.3 Service 层实现
- [ ] **创建 RestoreProcessService.java**
  - [ ] 方法: `startRestoreProcess(Long nodeId, Integer nodeType, Long userId)`
    - [ ] 验证权限
    - [ ] 检查节点是否在回收站
    - [ ] 生成 processId 和 sessionId
    - [ ] 存入 Redis DB 1
    - [ ] 启动异步任务
    - [ ] 立即返回进程信息

  - [ ] 方法: `getRestoreProcesses(Long userId)`
    - [ ] 从 Redis DB 1 查询用户的所有恢复进程
    - [ ] 过滤过期进程
    - [ ] 返回进程列表

  - [ ] 方法: `cleanupExpiredProcesses()`
    - [ ] 定时任务（每小时执行）

- [ ] **创建 AsyncRestoreWorker.java**
  - [ ] 使用 `@Async` 注解
  - [ ] 方法: `executeRestoreAsync(String processId, Long nodeId, Integer nodeType)`
    - [ ] 获取节点信息（包括 original_parent_id, original_path）
    - [ ] 判断原始父文件夹是否存在
    - [ ] 确定恢复目标路径
    - [ ] 递归收集所有子节点 ID
    - [ ] 分批处理（每批 1000-5000 条）
    - [ ] 每批次前调用限流器
    - [ ] 执行批量恢复（带乐观锁）
    - [ ] 更新进度到 Redis
    - [ ] 重建全文索引（异步）
    - [ ] 完成后移除进程记录

### 5.4 Controller 层接口
- [ ] **修改 FileController.java**
  - [ ] 修改现有恢复接口
    ```java
    @PostMapping("/files/recycle/restore")
    public Result<RestoreNodeResponse> restoreNode(
        @RequestParam Long nodeId,
        @RequestParam Integer nodeType) {
        // 改为异步模式，立即返回 processId
    }
    ```

  - [ ] 新增查询恢复进程接口
    ```java
    @GetMapping("/files/recycle/restore/processes")
    public Result<List<RestoreProcessInfo>> getRestoreProcesses() {
        // 返回当前用户的所有恢复进程
    }
    ```

---

## 🔐 第六阶段：并发控制与状态校验

### 6.1 状态前置校验
- [ ] **创建枚举类**: `NodeStatus.java`
  ```java
  public enum NodeStatus {
      ACTIVE,           // 活跃
      DELETING,         // 删除中
      IN_RECYCLE_BIN,   // 回收站中
      RESTORING,        // 恢复中
      PERMANENTLY_DELETED // 已彻底删除
  }
  ```

- [ ] **创建校验工具类**: `NodeStatusValidator.java`
  - [ ] 方法: `validateBeforeDelete(nodeId, nodeType)`
    - [ ] 检查节点状态是否为 ACTIVE
    - [ ] 检查父节点状态
    - [ ] 如状态异常，抛出明确错误

  - [ ] 方法: `validateBeforeRestore(nodeId, nodeType)`
    - [ ] 检查节点是否在回收站
    - [ ] 检查是否正在被其他进程操作

  - [ ] 方法: `validateBeforeMove(sourceId, targetId, nodeId)`
    - [ ] 三向校验：源父目录、目标父目录、被操作实体
    - [ ] 任一状态异常则快速失败

### 6.2 乐观锁冲突处理
- [ ] **创建异常类**: `OptimisticLockException.java`
  ```java
  public class OptimisticLockException extends RuntimeException {
      public OptimisticLockException(String message) {
          super(message);
      }
  }
  ```

- [ ] **全局异常处理器**
  - [ ] 在 `GlobalExceptionHandler.java` 中添加
    ```java
    @ExceptionHandler(OptimisticLockException.class)
    public Result<?> handleOptimisticLock(OptimisticLockException e) {
        return Result.error(40901, "文件正在被系统处理，请稍后重试");
    }
    ```

- [ ] **Service 层捕获乐观锁冲突**
  - [ ] 检测到影响行数为 0 时抛出 `OptimisticLockException`
  - [ ] 记录日志便于追踪

---

## 🧹 第七阶段：补偿机制与孤儿数据清理

### 7.1 孤儿数据巡检任务
- [ ] **创建定时任务类**: `OrphanDataCleanupJob.java`
  - [ ] 每天凌晨 2:00 执行
  - [ ] 扫描数据库中 `directory_status = 'in_recycle_bin'` 但不在 Redis 队列中的节点
  - [ ] 重新投入删除/恢复队列

- [ ] **实现逻辑**
  ```java
  @Scheduled(cron = "0 0 2 * * ?")
  public void cleanupOrphanData() {
      // 1. 查询回收站中超过 1 小时的节点
      // 2. 检查 Redis 中是否有对应的进程
      // 3. 如无进程，重新创建任务
  }
  ```

### 7.2 非核心元数据延迟重建
- [ ] **全文索引重建**
  - [ ] 监听 binlog 或消息队列
  - [ ] 异步更新 Elasticsearch 或 MySQL FULLTEXT 索引

- [ ] **缩略图缓存清理**
  - [ ] 删除操作后异步清理缩略图缓存
  - [ ] 恢复操作后异步生成新缩略图

---

## 📊 第八阶段：监控与日志

### 8.1 关键指标监控
- [ ] **Prometheus 指标暴露**
  - [ ] 活跃删除进程数
  - [ ] 活跃恢复进程数
  - [ ] 平均删除耗时
  - [ ] 平均恢复耗时
  - [ ] 限流触发次数
  - [ ] 乐观锁冲突次数

- [ ] **Grafana 看板配置**
  - [ ] 创建 Dashboard
  - [ ] 配置告警规则（如限流频繁触发）

### 8.2 日志增强
- [ ] **结构化日志**
  - [ ] 所有删除/恢复操作记录 sessionId
  - [ ] 记录每批次的处理数量和耗时
  - [ ] 记录限流等待时间

- [ ] **日志级别配置**
  - [ ] INFO: 进程启动/完成
  - [ ] DEBUG: 批次处理详情
  - [ ] ERROR: 失败情况及堆栈

---

## 🧪 第九阶段：测试与验证

### 9.1 单元测试
- [ ] **限流器测试**
  - [ ] 测试正常流量通过率
  - [ ] 测试超限拒绝逻辑
  - [ ] 测试窗口滑动正确性

- [ ] **Service 层测试**
  - [ ] 测试删除进程创建
  - [ ] 测试恢复进程创建
  - [ ] 测试进程查询
  - [ ] 测试乐观锁冲突处理

### 9.2 集成测试
- [ ] **端到端删除流程测试**
  - [ ] 创建包含 10000 个文件的文件夹
  - [ ] 执行删除操作
  - [ ] 验证立即返回
  - [ ] 轮询查询进度
  - [ ] 验证最终一致性

- [ ] **端到端恢复流程测试**
  - [ ] 从回收站恢复大型文件夹
  - [ ] 验证智能恢复逻辑（原位置 vs 根目录）
  - [ ] 验证数据完整性

- [ ] **并发冲突测试**
  - [ ] 同时删除和恢复同一节点
  - [ ] 验证乐观锁生效
  - [ ] 验证错误提示清晰

### 9.3 性能测试
- [ ] **压力测试**
  - [ ] 模拟 100 用户同时删除大型文件夹
  - [ ] 监控数据库 IOPS
  - [ ] 验证限流效果（平滑曲线）

- [ ] **基准测试**
  - [ ] 记录单文件删除/恢复耗时
  - [ ] 记录批量操作吞吐量

---

## 📝 第十阶段：文档与部署

### 10.1 技术文档
- [ ] **API 文档更新**
  - [ ] 更新 Swagger/OpenAPI 文档
  - [ ] 标注异步行为变更
  - [ ] 提供前端集成示例

- [ ] **运维文档**
  - [ ] Redis 6381 实例维护指南
  - [ ] 监控指标说明
  - [ ] 故障排查手册

### 10.2 部署清单
- [ ] **预发布环境验证**
  - [ ] 部署 Redis 6381
  - [ ] 执行数据库迁移脚本
  - [ ] 运行完整测试套件

- [ ] **生产环境部署**
  - [ ] 灰度发布（先开放给内部用户）
  - [ ] 监控关键指标 24 小时
  - [ ] 全量发布

- [ ] **回滚预案**
  - [ ] 准备回滚脚本
  - [ ] 保留旧版代码分支
  - [ ] 制定紧急回滚流程

---

## ✅ 验收标准

### 功能验收
- [ ] 删除操作响应时间 < 100ms（无论文件大小）
- [ ] 恢复操作响应时间 < 100ms
- [ ] 前端可实时查看删除/恢复进度
- [ ] 智能恢复逻辑正确（原位置优先）

### 性能验收
- [ ] 数据库 IOPS 稳定在 1000 以下（平滑曲线）
- [ ] 无长事务（单事务 < 1 秒）
- [ ] 无死锁发生
- [ ] 支持 100 并发用户同时操作

### 稳定性验收
- [ ] 7x24 小时运行无内存泄漏
- [ ] Redis 宕机后有补偿机制
- [ ] 乐观锁冲突率 < 1%
- [ ] 孤儿数据自动清理成功率 100%

---

## 📅 预计工期

| 阶段 | 工作内容 | 预计工时 |
|------|---------|---------|
| 第一阶段 | Redis 部署与配置 | 0.5 天 |
| 第二阶段 | 数据库 Schema 升级 | 1 天 |
| 第三阶段 | 滑动窗口限流器 | 1.5 天 |
| 第四阶段 | 异步删除功能 | 3 天 |
| 第五阶段 | 异步恢复功能 | 3 天 |
| 第六阶段 | 并发控制 | 1.5 天 |
| 第七阶段 | 补偿机制 | 1 天 |
| 第八阶段 | 监控与日志 | 1 天 |
| 第九阶段 | 测试与验证 | 3 天 |
| 第十阶段 | 文档与部署 | 1.5 天 |
| **合计** | | **17 天** |

---

## 🎯 风险提示

1. **Redis 单点故障**
   - 缓解措施：启用 RDB+AOF 持久化，配置哨兵模式（可选）

2. **数据库迁移风险**
   - 缓解措施：充分测试迁移脚本，准备回滚方案

3. **乐观锁冲突激增**
   - 缓解措施：监控冲突率，必要时调整批次大小

4. **限流阈值不合理**
   - 缓解措施：根据实际负载动态调整，预留 20% 余量

---

**文档版本**: v1.0  
**创建日期**: 2026-06-03  
**负责人**: 后端开发团队
