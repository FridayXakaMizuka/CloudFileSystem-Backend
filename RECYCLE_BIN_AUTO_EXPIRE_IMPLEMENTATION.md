# 回收站自动过期彻底删除实施指南

## 📋 概述

本文档基于更新后的 `RECYCLE_BIN_REDIS_STORAGE_DESIGN.md` v2.0，提供 Redis Keyspace Notification 自动触发彻底删除的完整实施方案。

**核心变更：**
1. ✅ 同一 batchId 的所有节点共享过期时间（30天）
2. ✅ Redis Key 过期时自动触发彻底删除逻辑
3. ✅ 先标记 MySQL 为待分配/永久删除，再清理 Redis 缓存
4. ✅ 异步执行，不阻塞 Redis 事件线程

---

## 🔧 实施步骤

### Step 1: 配置 Redis Keyspace Notification

**redis.conf 配置：**
```conf
# 启用 Keyspace Notification
notify-keyspace-events Ex
```

**说明：**
- `E`: Key 事件通知
- `x`: 过期事件
- `Ex`: 订阅所有 Key 的过期事件

**重启 Redis 使配置生效：**
```bash
systemctl restart redis
# 或
docker restart redis
```

**验证配置：**
```bash
redis-cli CONFIG GET notify-keyspace-events
# 应该返回: "Ex"
```

---

### Step 2: 创建 Mapper 接口

#### FolderNodeMapper.java

```java
package com.mizuka.cloudfilesystem.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FolderNodeMapper {
    
    /**
     * 标记文件夹为待分配（进入待分配池）
     * 
     * @param nodeId 文件夹ID
     * @return 影响行数
     */
    @Update("UPDATE folder_nodes SET " +
            "directory_status = 'unassigned', " +
            "last_del_uuid = NULL, " +
            "deleted_at = NULL, " +
            "delete_expires_at = NULL, " +
            "version = version + 1 " +
            "WHERE id = #{nodeId}")
    int markAsUnassigned(@Param("nodeId") Long nodeId);
}
```

#### FileNodeMapper.java

```java
package com.mizuka.cloudfilesystem.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileNodeMapper {
    
    /**
     * 标记文件为永久删除
     * 
     * @param nodeId 文件ID
     * @return 影响行数
     */
    @Update("UPDATE file_nodes SET " +
            "directory_status = 'permanently_deleted', " +
            "is_deleted = 1, " +
            "last_del_uuid = NULL, " +
            "version = version + 1 " +
            "WHERE id = #{nodeId}")
    int markAsPermanentlyDeleted(@Param("nodeId") Long nodeId);
}
```

---

### Step 3: 创建 RecycleBinExpireListener 监听器

```java
package com.mizuka.cloudfilesystem.listener;

import com.mizuka.cloudfilesystem.dto.RecycleBinTask;
import com.mizuka.cloudfilesystem.mapper.FileNodeMapper;
import com.mizuka.cloudfilesystem.mapper.FolderNodeMapper;
import com.mizuka.cloudfilesystem.mapper.RecycleBinTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Redis Key 过期监听器
 * 监听 recycle:batch:{batchId}:nodes 的过期事件，自动触发彻底删除
 */
@Component
public class RecycleBinExpireListener implements MessageListener {
    
    private static final Logger log = LoggerFactory.getLogger(RecycleBinExpireListener.class);
    
    @Autowired
    private RecycleBinTaskMapper recycleBinTaskMapper;
    
    @Autowired
    private FolderNodeMapper folderNodeMapper;
    
    @Autowired
    private FileNodeMapper fileNodeMapper;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    /**
     * 处理 Redis Key 过期事件
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        
        log.debug("[Redis过期] 检测到 Key 过期 - Key: {}", expiredKey);
        
        // 只处理 recycle:batch:*:nodes 模式的 Key
        if (expiredKey != null && expiredKey.matches("recycle:batch:.+:nodes")) {
            // 提取 batchId
            String batchId = extractBatchId(expiredKey);
            
            if (batchId != null) {
                log.info("[Redis过期] 检测到 batch 过期 - BatchId: {}", batchId);
                
                // 异步执行彻底删除（避免阻塞 Redis 事件线程）
                CompletableFuture.runAsync(() -> {
                    executePermanentDeleteOnExpire(batchId);
                });
            }
        }
    }
    
    /**
     * 从 Key 中提取 batchId
     * 例：recycle:batch:550e8400-e29b-41d4-a716-446655440000:nodes → 550e8400-e29b-41d4-a716-446655440000
     */
    private String extractBatchId(String key) {
        // recycle:batch:{batchId}:nodes
        String[] parts = key.split(":");
        if (parts.length >= 3) {
            return parts[2];
        }
        return null;
    }
    
    /**
     * 执行过期后的彻底删除逻辑
     * 
     * 步骤：
     * 1. 查询 MySQL 获取根节点信息
     * 2. 遍历 ZSET 中的所有节点（如果还在）
     * 3. 先将所有节点标记为待分配（MySQL）
     * 4. 确认全部标记完成后，清理 Redis 缓存
     * 5. 更新任务状态
     */
    @Transactional
    public void executePermanentDeleteOnExpire(String batchId) {
        try {
            // 1. 从 MySQL 查询 batch 信息
            RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
            if (task == null) {
                log.warn("[彻底删除] 未找到任务记录 - BatchId: {}", batchId);
                return;
            }
            
            // 如果任务已经完成或已终止，跳过
            if (task.getStatus() == 1 || task.getStatus() == 3) {
                log.info("[彻底删除] 任务已完成或已终止，跳过 - BatchId: {}, Status: {}", 
                    batchId, task.getStatus());
                return;
            }
            
            Long rootNodeId = task.getRootNodeId();
            Integer nodeType = task.getNodeType();
            Long userId = task.getUserId();
            
            log.info("[彻底删除] 开始执行 - BatchId: {}, RootNodeId: {}, NodeType: {}", 
                batchId, rootNodeId, nodeType);
            
            // 2. 尝试从 Redis ZSET 获取所有节点（可能已经被恢复操作清空）
            String nodesKey = "recycle:batch:" + batchId + ":nodes";
            Set<Object> members = redisTemplate.opsForZSet().range(nodesKey, 0, -1);
            
            int markedCount = 0;
            
            if (members != null && !members.isEmpty()) {
                // 3. 遍历所有节点，标记为待分配
                for (Object member : members) {
                    String memberStr = member.toString();
                    String[] parts = memberStr.split(":");
                    
                    if (parts.length != 2) {
                        log.warn("[彻底删除] 无效的 member 格式 - Member: {}", memberStr);
                        continue;
                    }
                    
                    Integer currentNodeType;
                    Long nodeId;
                    
                    try {
                        currentNodeType = Integer.parseInt(parts[0]);
                        nodeId = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        log.error("[彻底删除] 解析 member 失败 - Member: {}", memberStr, e);
                        continue;
                    }
                    
                    try {
                        if (currentNodeType == 0) {
                            // 文件夹：标记为 unassigned
                            folderNodeMapper.markAsUnassigned(nodeId);
                        } else if (currentNodeType == 1) {
                            // 文件：标记为 permanently_deleted
                            fileNodeMapper.markAsPermanentlyDeleted(nodeId);
                        } else {
                            log.warn("[彻底删除] 未知的节点类型 - Type: {}", currentNodeType);
                            continue;
                        }
                        
                        markedCount++;
                        
                    } catch (Exception e) {
                        log.error("[彻底删除] 标记节点失败 - NodeId: {}, Type: {}", 
                            nodeId, currentNodeType, e);
                        // 继续处理其他节点，不因单个节点失败而中断
                    }
                }
                
                log.info("[彻底删除] 标记完成 - BatchId: {}, MarkedCount: {}", batchId, markedCount);
                
            } else {
                // ZSET 已经为空（可能已被恢复操作清空），只需处理根节点
                log.info("[彻底删除] ZSET 已空，仅处理根节点 - BatchId: {}", batchId);
                
                try {
                    if (nodeType == 0) {
                        folderNodeMapper.markAsUnassigned(rootNodeId);
                    } else {
                        fileNodeMapper.markAsPermanentlyDeleted(rootNodeId);
                    }
                    markedCount = 1;
                } catch (Exception e) {
                    log.error("[彻底删除] 标记根节点失败 - RootNodeId: {}", rootNodeId, e);
                    throw e; // 根节点失败则抛出异常
                }
            }
            
            // 4. 清理 Redis 缓存（所有相关 Key）
            cleanupBatchCache(batchId, userId);
            
            // 5. 更新任务状态为已完成
            recycleBinTaskMapper.updateTaskStatus(
                batchId, 
                1,  // status = 1 (已完成)
                LocalDateTime.now(), 
                null, 
                markedCount, 
                markedCount
            );
            
            log.info("[彻底删除] 完成 - BatchId: {}, TotalMarked: {}", batchId, markedCount);
            
        } catch (Exception e) {
            log.error("[彻底删除] 执行失败 - BatchId: {}", batchId, e);
            
            // 更新任务状态为失败
            try {
                recycleBinTaskMapper.updateTaskStatus(
                    batchId, 
                    2,  // status = 2 (失败)
                    LocalDateTime.now(), 
                    e.getMessage(), 
                    0, 
                    0
                );
            } catch (Exception ex) {
                log.error("[彻底删除] 更新任务状态失败 - BatchId: {}", batchId, ex);
            }
        }
    }
    
    /**
     * 清理 batch 相关的所有 Redis 缓存
     */
    private void cleanupBatchCache(String batchId, Long userId) {
        try {
            String nodesKey = "recycle:batch:" + batchId + ":nodes";
            String infoKey = "recycle:batch:" + batchId + ":info";
            String rootKey = "recycle:batch:" + batchId + ":root";
            String cursorKey = "recycle:batch:" + batchId + ":cursor";
            String userBatchesKey = "recycle:user:" + userId + ":batches";
            
            // 删除 batch 相关的所有 Key
            redisTemplate.delete(nodesKey, infoKey, rootKey, cursorKey);
            
            // 从用户列表中移除 batchId
            redisTemplate.opsForZSet().remove(userBatchesKey, batchId);
            
            log.info("[Redis清理] 清理完成 - BatchId: {}, UserId: {}", batchId, userId);
            
        } catch (Exception e) {
            log.error("[Redis清理] 清理失败 - BatchId: {}", batchId, e);
        }
    }
}
```

---

### Step 4: 配置 Redis 消息监听容器

创建 `RedisListenerConfig.java`：

```java
package com.mizuka.cloudfilesystem.config;

import com.mizuka.cloudfilesystem.listener.RecycleBinExpireListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisListenerConfig {
    
    /**
     * 配置 Redis 消息监听容器
     * 监听 recycle:batch:*:nodes 的过期事件
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RecycleBinExpireListener expireListener) {
        
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        
        // 订阅 Key 过期事件
        // 模式：__keyevent@0__:expired （数据库0的过期事件）
        container.addMessageListener(
            expireListener, 
            new PatternTopic("__keyevent@0__:expired")
        );
        
        return container;
    }
}
```

---

### Step 5: 确保初始化时设置正确的 TTL

修改 `RecycleBinRedisService.initializeBatch()` 方法：

```java
public void initializeBatch(String batchId, Long rootNodeId, Integer nodeType, Long userId) {
    try {
        String nodesKey = BATCH_NODES_PREFIX + batchId + ":nodes";
        String rootKey = BATCH_NODES_PREFIX + batchId + BATCH_ROOT_SUFFIX;
        String infoKey = BATCH_NODES_PREFIX + batchId + ":info";
        String userBatchesKey = "recycle:user:" + userId + ":batches";
        
        // 1. 创建空的ZSET（用于存储所有删除的节点）
        long timestamp = System.currentTimeMillis();
        deleteRedisCommands.zadd(nodesKey, timestamp, String.valueOf(rootNodeId));
        
        // 2. 存储根目录信息到Hash
        Map<String, String> rootInfo = new HashMap<>();
        rootInfo.put("rootNodeId", String.valueOf(rootNodeId));
        rootInfo.put("nodeType", String.valueOf(nodeType));
        rootInfo.put("userId", String.valueOf(userId));
        rootInfo.put("createdAt", String.valueOf(timestamp));
        
        deleteRedisCommands.hset(rootKey, rootInfo);
        
        // 3. 【关键】所有 Key 设置相同的过期时间（30天）
        // 确保同一 batchId 的所有节点同步过期
        deleteRedisCommands.expire(nodesKey, EXPIRE_SECONDS);
        deleteRedisCommands.expire(rootKey, EXPIRE_SECONDS);
        deleteRedisCommands.expire(infoKey, EXPIRE_SECONDS);
        deleteRedisCommands.expire(userBatchesKey, EXPIRE_SECONDS);
        
        log.info("[Redis] 初始化batch成功 - BatchId: {}, RootNodeId: {}, NodeType: {}, TTL: {}s", 
            batchId, rootNodeId, nodeType, EXPIRE_SECONDS);
        
    } catch (Exception e) {
        log.error("[Redis] 初始化batch失败 - BatchId: {}", batchId, e);
    }
}
```

---

## ⚠️ 注意事项

### 1. Redis 配置必须正确
- ✅ 必须在 `redis.conf` 中启用 `notify-keyspace-events Ex`
- ✅ 重启 Redis 后验证配置是否生效
- ❌ 如果未启用，监听器不会收到任何事件

### 2. 异步执行避免阻塞
- ✅ 使用 `CompletableFuture.runAsync()` 异步执行
- ✅ 避免在监听器中执行耗时操作
- ❌ 不要直接在 `onMessage()` 中执行数据库操作

### 3. 数据一致性保证
- ✅ 先标记 MySQL，再清理 Redis
- ✅ 使用 `@Transactional` 保证原子性
- ✅ 失败时更新任务状态为失败
- ❌ 不要先清理 Redis 再标记 MySQL

### 4. 容错处理
- ✅ ZSET 已空时仍处理根节点
- ✅ 单个节点失败不影响其他节点
- ✅ 根节点失败则抛出异常
- ❌ 不要因为部分节点失败而中断整个流程

### 5. 监控与告警
建议添加以下监控指标：
- Redis Key 过期事件触发次数
- 彻底删除成功率/失败率
- 平均处理时间
- 待分配池中的节点数量

---

## 🧪 测试方案

### 测试 1: 验证 Keyspace Notification 配置

```bash
# 终端 1：订阅过期事件
redis-cli --csv psubscribe '__keyevent@0__:expired'

# 终端 2：设置一个临时 Key
redis-cli SET test:key "value" EX 5

# 等待 5 秒后，终端 1 应该收到：
# "pmessage","*__keyevent@0__:expired*","test:key"
```

### 测试 2: 模拟 batch 过期

```java
@Test
public void testBatchExpire() {
    String batchId = UUID.randomUUID().toString();
    Long userId = 10001L;
    
    // 1. 创建 batch
    recycleBinRedisService.initializeBatch(batchId, 12345L, 0, userId);
    
    // 2. 手动设置短 TTL（用于测试）
    redisTemplate.expire("recycle:batch:" + batchId + ":nodes", 5, TimeUnit.SECONDS);
    
    // 3. 等待过期
    Thread.sleep(6000);
    
    // 4. 验证任务状态已更新为已完成
    RecycleBinTask task = recycleBinTaskMapper.findByBatchId(batchId);
    assertEquals(1, task.getStatus()); // 已完成
    
    // 5. 验证 Redis Key 已清理
    assertNull(redisTemplate.opsForZSet().range("recycle:batch:" + batchId + ":nodes", 0, -1));
}
```

### 测试 3: 验证节点标记逻辑

```java
@Test
public void testNodeMarking() {
    // 1. 创建测试数据
    Long folderId = createTestFolder();
    Long fileId = createTestFile();
    
    // 2. 执行标记
    folderNodeMapper.markAsUnassigned(folderId);
    fileNodeMapper.markAsPermanentlyDeleted(fileId);
    
    // 3. 验证文件夹状态
    FolderNode folder = folderNodeMapper.findById(folderId);
    assertEquals("unassigned", folder.getDirectoryStatus());
    assertNull(folder.getLastDelUuid());
    
    // 4. 验证文件状态
    FileNode file = fileNodeMapper.findById(fileId);
    assertEquals("permanently_deleted", file.getDirectoryStatus());
    assertEquals(1, file.getIsDeleted());
}
```

---

## 📊 性能影响评估

| 指标 | 影响 | 说明 |
|------|------|------|
| Redis CPU | +5-10% | Keyspace Notification 有轻微开销 |
| 内存占用 | 无影响 | 事件通知不占用额外内存 |
| 网络带宽 | +1-2% | 事件消息传输 |
| 彻底删除延迟 | 0-1s | 异步执行，几乎无感知 |
| 数据库负载 | 一次性峰值 | 过期时批量更新节点状态 |

---

## 🚀 部署检查清单

- [ ] Redis 配置已启用 `notify-keyspace-events Ex`
- [ ] Redis 已重启并验证配置生效
- [ ] `FolderNodeMapper` 和 `FileNodeMapper` 已创建
- [ ] `RecycleBinExpireListener` 已实现
- [ ] `RedisListenerConfig` 已配置
- [ ] `initializeBatch()` 已更新为统一 TTL
- [ ] 单元测试已通过
- [ ] 集成测试已通过
- [ ] 监控指标已配置
- [ ] 告警规则已设置

---

**文档版本**: v1.0  
**最后更新**: 2026-06-07  
**作者**: CloudFileSystem Team
