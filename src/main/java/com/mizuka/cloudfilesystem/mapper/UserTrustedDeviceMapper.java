package com.mizuka.cloudfilesystem.mapper;

import com.mizuka.cloudfilesystem.entity.UserTrustedDevice;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户信任设备Mapper接口
 */
@Mapper
public interface UserTrustedDeviceMapper {
    
    /**
     * 插入信任设备
     */
    @Insert("""
        INSERT INTO user_trusted_devices (
            user_id, device_uuid, device_fingerprint, hardware_id,
            client_type, client_identifier, platform,
            device_name, device_model,
            is_trusted, trust_level,
            last_login_time, last_login_ip, login_count, first_seen_at
        ) VALUES (
            #{userId}, #{deviceUuid}, #{deviceFingerprint}, #{hardwareId},
            #{clientType}, #{clientIdentifier}, #{platform},
            #{deviceName}, #{deviceModel},
            #{isTrusted}, #{trustLevel},
            #{lastLoginTime}, #{lastLoginIp}, #{loginCount}, #{firstSeenAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserTrustedDevice device);
    
    /**
     * 根据用户ID查询所有信任设备
     */
    @Select("""
        SELECT * FROM user_trusted_devices 
        WHERE user_id = #{userId}
        ORDER BY last_login_time DESC
        """)
    List<UserTrustedDevice> findByUserId(Long userId);
    
    /**
     * 根据设备ID查询
     */
    @Select("SELECT * FROM user_trusted_devices WHERE id = #{id}")
    UserTrustedDevice findById(Long id);
    
    /**
     * 根据设备UUID查询
     */
    @Select("SELECT * FROM user_trusted_devices WHERE device_uuid = #{deviceUuid}")
    UserTrustedDevice findByDeviceUuid(String deviceUuid);
    
    /**
     * 根据用户ID和设备指纹查询
     */
    @Select("""
        SELECT * FROM user_trusted_devices 
        WHERE user_id = #{userId} AND device_fingerprint = #{fingerprint}
        LIMIT 1
        """)
    UserTrustedDevice findByUserIdAndFingerprint(@Param("userId") Long userId, 
                                                  @Param("fingerprint") String fingerprint);
    
    /**
     * 根据用户ID和硬件ID查询（仅客户端）
     */
    @Select("""
        SELECT * FROM user_trusted_devices 
        WHERE user_id = #{userId} AND hardware_id = #{hardwareId}
        LIMIT 1
        """)
    UserTrustedDevice findByUserIdAndHardwareId(@Param("userId") Long userId, 
                                                @Param("hardwareId") String hardwareId);
    
    /**
     * 更新登录信息
     */
    @Update("""
        UPDATE user_trusted_devices SET
            last_login_time = #{lastLoginTime},
            last_login_ip = #{lastLoginIp},
            login_count = login_count + 1,
            updated_at = NOW()
        WHERE device_uuid = #{deviceUuid}
        """)
    int updateLoginInfo(UserTrustedDevice device);
    
    /**
     * 更新设备信息（通用）
     */
    @Update("""
        UPDATE user_trusted_devices SET
            is_trusted = #{isTrusted},
            trust_level = #{trustLevel},
            updated_at = NOW()
        WHERE id = #{id}
        """)
    int update(UserTrustedDevice device);
    
    /**
     * 更新信任等级
     */
    @Update("""
        UPDATE user_trusted_devices SET
            trust_level = #{trustLevel},
            updated_at = NOW()
        WHERE device_uuid = #{deviceUuid} AND user_id = #{userId}
        """)
    int updateTrustLevel(@Param("deviceUuid") String deviceUuid,
                        @Param("userId") Long userId,
                        @Param("trustLevel") Integer trustLevel);
    
    /**
     * 更新设备名称
     */
    @Update("""
        UPDATE user_trusted_devices SET
            device_name = #{deviceName},
            updated_at = NOW()
        WHERE device_uuid = #{deviceUuid} AND user_id = #{userId}
        """)
    int updateDeviceName(@Param("deviceUuid") String deviceUuid,
                        @Param("userId") Long userId,
                        @Param("deviceName") String deviceName);
    
    /**
     * 删除设备
     */
    @Delete("DELETE FROM user_trusted_devices WHERE device_uuid = #{deviceUuid} AND user_id = #{userId}")
    int deleteByDeviceUuid(@Param("deviceUuid") String deviceUuid,
                          @Param("userId") Long userId);
    
    /**
     * 根据ID删除设备
     */
    @Delete("DELETE FROM user_trusted_devices WHERE id = #{id}")
    int deleteById(Long id);
    
    /**
     * 统计用户的信任设备数量
     */
    @Select("SELECT COUNT(*) FROM user_trusted_devices WHERE user_id = #{userId}")
    int countByUserId(Long userId);
}
