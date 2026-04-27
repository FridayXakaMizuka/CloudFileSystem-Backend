package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.LoginRequest;
import com.mizuka.cloudfilesystem.dto.LoginResponse;
import com.mizuka.cloudfilesystem.dto.RegisterRequest;
import com.mizuka.cloudfilesystem.dto.RegisterResponse;
import com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO;
import com.mizuka.cloudfilesystem.entity.Administrator;
import com.mizuka.cloudfilesystem.entity.User;
import com.mizuka.cloudfilesystem.mapper.AdministratorMapper;
import com.mizuka.cloudfilesystem.mapper.UserMapper;
import com.mizuka.cloudfilesystem.util.JwtUtil;
import com.mizuka.cloudfilesystem.util.RSAKeyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务类
 * 处理用户注册、登录等业务逻辑
 */
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AdministratorMapper administratorMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    // Redis中存储密钥对的key前缀
    private static final String RSA_KEY_PREFIX = "rsa:key:";

    // 密钥对在Redis中的过期时间（单位：分钟）
    private static final long KEY_EXPIRE_MINUTES = 5;

    // BCrypt密码加密器
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户注册
     * @param request 注册请求对象
     * @return 注册响应对象
     */
    public RegisterResponse register(RegisterRequest request) {
        try {
            // 1. 验证请求数据
            if (request.getData() == null || request.getData().length == 0) {
                return new RegisterResponse(400, false, "注册数据不能为空", null);
            }

            RegisterRequest.RegisterData data = request.getData()[0];

            // 2. 验证必填字段
            if (data.getNickname() == null || data.getNickname().trim().isEmpty()) {
                return new RegisterResponse(400, false, "昵称不能为空", null);
            }
            if (data.getEmail() == null || data.getEmail().trim().isEmpty()) {
                return new RegisterResponse(400, false, "邮箱不能为空", null);
            }
            if (data.getEncryptedPassword() == null || data.getEncryptedPassword().trim().isEmpty()) {
                return new RegisterResponse(400, false, "密码不能为空", null);
            }
            if (data.getSecurityQuestion() == null) {
                return new RegisterResponse(400, false, "安全问题不能为空", null);
            }
            if (data.getSecurityAnswer() == null || data.getSecurityAnswer().trim().isEmpty()) {
                return new RegisterResponse(400, false, "安全问题答案不能为空", null);
            }

            // 3. 检查昵称、邮箱、手机号是否已存在
            if (userMapper.findByNickname(data.getNickname()) != null) {
                return new RegisterResponse(400, false, "昵称已被使用", null);
            }
            if (userMapper.findByEmail(data.getEmail()) != null) {
                return new RegisterResponse(400, false, "邮箱已被注册", null);
            }
            if (data.getPhone() != null && !data.getPhone().trim().isEmpty()) {
                if (userMapper.findByPhone(data.getPhone()) != null) {
                    return new RegisterResponse(400, false, "手机号已被注册", null);
                }
            }

            // 4. 从Redis获取RSA密钥对
            String sessionId = request.getSessionId();
            String redisKey = RSA_KEY_PREFIX + sessionId;
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                return new RegisterResponse(400, false, "会话已过期或无效，请重新获取公钥", null);
            }

            // 5. 使用私钥解密密码
            String decryptedPassword = RSAKeyManager.decryptWithPrivateKey(
                    data.getEncryptedPassword(),
                    keyPairDTO.getPrivateKey()
            );

            // 6. 使用BCrypt加密密码
            String bcryptPassword = passwordEncoder.encode(decryptedPassword);

            // 7. 创建用户对象
            User user = new User();
            user.setNickname(data.getNickname());
            user.setPassword(bcryptPassword);
            user.setEmail(data.getEmail());
            user.setPhone(data.getPhone());
            user.setStorageQuota(10737418240L);  // 默认10GB
            user.setStorageUsed(0L);
            user.setStatus(1);  // 正常状态
            user.setSecurityQuestionId(data.getSecurityQuestion());
            user.setSecurityAnswer(data.getSecurityAnswer());

            // 8. 插入数据库
            int result = userMapper.insertUser(user);

            if (result > 0) {
                // 9. 注册成功后，删除Redis中的密钥对（一次性使用）
                redisTemplate.delete(redisKey);

                // 10. 构建响应数据
                RegisterResponse.UserInfo userInfo = new RegisterResponse.UserInfo(
                        user.getId(),
                        user.getNickname()
                );

                return new RegisterResponse(
                        200,
                        true,
                        "注册成功",
                        Collections.singletonList(userInfo)
                );
            } else {
                return new RegisterResponse(500, false, "注册失败，请稍后重试", null);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return new RegisterResponse(500, false, "注册失败：" + e.getMessage(), null);
        }
    }
    /**
     * 用户登录
     * @param loginRequest 登录请求对象
     * @return 登录响应对象
     */
    public LoginResponse login(LoginRequest loginRequest) {
        try {
            String sessionId = loginRequest.getSessionId();
            String userId = loginRequest.getUserId();
            String encryptedPassword = loginRequest.getEncryptedPassword();
            Long tokenExpiration = loginRequest.getTokenExpiration();

            if (sessionId == null || sessionId.trim().isEmpty()) {
                return new LoginResponse(400, false, "会话ID不能为空");
            }

            if (userId == null || userId.trim().isEmpty()) {
                return new LoginResponse(400, false, "用户ID不能为空");
            }

            if (encryptedPassword == null || encryptedPassword.trim().isEmpty()) {
                return new LoginResponse(400, false, "密码不能为空");
            }

            String redisKey = RSA_KEY_PREFIX + sessionId;
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                return new LoginResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }

            // 重置RSA密钥对的过期时间（延长5分钟）
            redisTemplate.expire(redisKey, 5, TimeUnit.MINUTES);

            String decryptedPassword = RSAKeyManager.decryptWithPrivateKey(
                    encryptedPassword,
                    keyPairDTO.getPrivateKey()
            );

            long userIdNum;
            try {
                userIdNum = Long.parseLong(userId);
            } catch (NumberFormatException e) {
                return new LoginResponse(400, false, "用户ID格式错误");
            }

            String userType;
            Object userObj = null;
            String storedPassword = null;
            String nickname = null;
            byte[] avatar = null;
            LocalDateTime registeredAt = null;

            if (userIdNum >= 1 && userIdNum <= 9999) {
                userType = "administrator";
                Administrator admin = administratorMapper.findById((int) userIdNum);
                if (admin != null) {
                    userObj = admin;
                    storedPassword = admin.getPassword();
                    nickname = admin.getNickname();
                }
            } else {
                userType = "user";
                User user = userMapper.findById(userIdNum);
                if (user != null) {
                    userObj = user;
                    storedPassword = user.getPassword();
                    nickname = user.getNickname();
                }
            }

            if (userObj == null) {
                return new LoginResponse(401, false, "用户不存在");
            }

            if (!passwordEncoder.matches(decryptedPassword, storedPassword)) {
                return new LoginResponse(401, false, "密码错误");
            }

            if (userObj instanceof User) {
                User user = (User) userObj;
                if (user.getStatus() == 0) {
                    return new LoginResponse(403, false, "账号已被禁用");
                }
                if (user.getStatus() == 2) {
                    return new LoginResponse(403, false, "账号已被锁定");
                }
                userMapper.updateLastLogin(user.getId());
            } else if (userObj instanceof Administrator) {
                Administrator admin = (Administrator) userObj;
                if (admin.getStatus() == 0) {
                    return new LoginResponse(403, false, "账号已被禁用");
                }
                if (admin.getStatus() == 2) {
                    return new LoginResponse(403, false, "账号已被锁定");
                }
                administratorMapper.updateLastLogin(admin.getId());
            }

            // 登录成功后，删除Redis中的密钥对（一次性使用）
            redisTemplate.delete(redisKey);

            String token = jwtUtil.generateToken(
                    userIdNum,
                    nickname,
                    userType,
                    tokenExpiration
            );

            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(
                    (tokenExpiration != null && tokenExpiration > 0) ? tokenExpiration : 604800
            );

            LoginResponse response = new LoginResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("登录成功");
            response.setUserId(userId);
            response.setToken(token);
            response.setNickname(nickname);
            response.setUserType(userType);
            response.setHomeDirectory("user".equals(userType) ? "/users/" + userIdNum : "/admin/");
            response.setExpiresAt(expiresAt);

            return response;

        } catch (Exception e) {
            e.printStackTrace();
            return new LoginResponse(500, false, "登录失败：" + e.getMessage());
        }
    }
}
