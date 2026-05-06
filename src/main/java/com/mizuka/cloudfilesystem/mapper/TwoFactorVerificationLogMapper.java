package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.TwoFactorVerificationLog;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 二次验证日志Mapper接口
 */
@Mapper
public interface TwoFactorVerificationLogMapper {
    
    /**
     * 插入验证日志
     */
    @Insert("""
        INSERT INTO two_factor_verification_logs (
            user_id, device_uuid, device_fingerprint,
            verify_method, verify_result, failure_reason,
            client_type, client_platform, ip_address, user_agent,
            created_at
        ) VALUES (
            #{userId}, #{deviceUuid}, #{deviceFingerprint},
            #{verifyMethod}, #{verifyResult}, #{failureReason},
            #{clientType}, #{clientPlatform}, #{ipAddress}, #{userAgent},
            #{createdAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TwoFactorVerificationLog log);
    
    /**
     * 查询用户的验证日志
     */
    @Select("""
        SELECT * FROM two_factor_verification_logs
        WHERE user_id = #{userId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<TwoFactorVerificationLog> findByUserId(@Param("userId") Long userId,
                                               @Param("limit") int limit);
    
    /**
     * 统计验证成功率
     */
    @Select("""
        SELECT 
            verify_method,
            COUNT(*) as total_count,
            SUM(CASE WHEN verify_result = 'success' THEN 1 ELSE 0 END) as success_count,
            SUM(CASE WHEN verify_result = 'failed' THEN 1 ELSE 0 END) as failed_count
        FROM two_factor_verification_logs
        WHERE user_id = #{userId}
          AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
        GROUP BY verify_method
        """)
    List<Map<String, Object>> getVerifyStats(@Param("userId") Long userId,
                                            @Param("days") int days);
}
