package com.mizuka.cloudfilesystem.service;

import com.mizuka.cloudfilesystem.dto.LoginRequest;
import com.mizuka.cloudfilesystem.dto.LoginResponse;
import com.mizuka.cloudfilesystem.dto.NicknameChangeRequest;
import com.mizuka.cloudfilesystem.dto.NicknameChangeResponse;
import com.mizuka.cloudfilesystem.dto.PasswordChangeRequest;
import com.mizuka.cloudfilesystem.dto.PasswordChangeResponse;
import com.mizuka.cloudfilesystem.dto.PasswordVerificationRequest;
import com.mizuka.cloudfilesystem.dto.PasswordVerificationResponse;
import com.mizuka.cloudfilesystem.dto.RegisterRequest;
import com.mizuka.cloudfilesystem.dto.RegisterResponse;
import com.mizuka.cloudfilesystem.dto.RSAKeyPairDTO;
import com.mizuka.cloudfilesystem.dto.SecurityQuestionChangeRequest;
import com.mizuka.cloudfilesystem.dto.SecurityQuestionChangeResponse;
import com.mizuka.cloudfilesystem.dto.UserProfileResponse;
import com.mizuka.cloudfilesystem.dto.EmailChangeRequest;
import com.mizuka.cloudfilesystem.dto.EmailChangeResponse;
import com.mizuka.cloudfilesystem.dto.PhoneChangeRequest;
import com.mizuka.cloudfilesystem.dto.PhoneChangeResponse;
import com.mizuka.cloudfilesystem.dto.FindUserRequest;
import com.mizuka.cloudfilesystem.dto.FindUserResponse;
import com.mizuka.cloudfilesystem.dto.ResetPasswordEmailVerifyRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordPhoneVerifyRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordSecurityVerifyRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordVerifyResponse;
import com.mizuka.cloudfilesystem.dto.ResetPasswordRequest;
import com.mizuka.cloudfilesystem.dto.ResetPasswordResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mizuka.cloudfilesystem.entity.Administrator;
import com.mizuka.cloudfilesystem.entity.SecurityQuestion;
import com.mizuka.cloudfilesystem.entity.User;
import com.mizuka.cloudfilesystem.mapper.AdministratorMapper;
import com.mizuka.cloudfilesystem.mapper.SecurityQuestionMapper;
import com.mizuka.cloudfilesystem.mapper.UserMapper;
import com.mizuka.cloudfilesystem.util.JwtUtil;
import com.mizuka.cloudfilesystem.util.RSAKeyManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private AdministratorMapper administratorMapper;
    
    @Autowired
    private SecurityQuestionMapper securityQuestionMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    @Qualifier("profileRedisTemplate")
    private RedisTemplate<String, String> profileRedisTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private SmsVerificationService smsVerificationService;

    @Autowired
    private com.mizuka.cloudfilesystem.service.SecurityQuestionService securityQuestionService;

    // Redis中存储密钥对的key前缀
    private static final String RSA_KEY_PREFIX = "rsa:key:";

    // 密钥对在Redis中的过期时间（单位：分钟）
    private static final long KEY_EXPIRE_MINUTES = 5;

    // BCrypt密码加密器
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 根据用户ID获取用户信息
     * @param userId 用户ID
     * @return 用户对象，如果不存在返回null
     */
    public User getUserById(Long userId) {
        if (userId == null) {
            return null;
        }
        return userMapper.findById(userId);
    }

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

            // 3. 检查昵称、邮箱是否已存在
            if (userMapper.findByNickname(data.getNickname()) != null) {
                return new RegisterResponse(400, false, "昵称已被使用", null);
            }
            if (userMapper.findByEmail(data.getEmail()) != null) {
                return new RegisterResponse(400, false, "邮箱已被注册", null);
            }
            
            // 4. 如果提供了手机号，检查是否已注册
            if (data.getPhone() != null && !data.getPhone().trim().isEmpty()) {
                if (userMapper.findByPhone(data.getPhone()) != null) {
                    return new RegisterResponse(400, false, "手机号已被注册", null);
                }
            }

            // 5. 从 Redis 获取 RSA 密钥对
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.trim().isEmpty()) {
                return new RegisterResponse(400, false, "会话ID不能为空", null);
            }
            
            String redisKey = RSA_KEY_PREFIX + sessionId;
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                return new RegisterResponse(400, false, "会话已过期或无效，请重新获取公钥", null);
            }

            // 6. 验证邮箱验证码
            if (data.getEmailVfCode() == null || data.getEmailVfCode().trim().isEmpty()) {
                return new RegisterResponse(400, false, "邮箱验证码不能为空", null);
            }
            
            boolean emailVerified = emailVerificationService.verifyCode(sessionId, data.getEmail(), data.getEmailVfCode());
            if (!emailVerified) {
                return new RegisterResponse(400, false, "邮箱验证码错误或已过期", null);
            }

            // 7. 如果提供了手机号，验证手机验证码
            if (data.getPhone() != null && !data.getPhone().trim().isEmpty()) {
                if (data.getPhoneVfCode() == null || data.getPhoneVfCode().trim().isEmpty()) {
                    return new RegisterResponse(400, false, "手机验证码不能为空", null);
                }
                
                boolean phoneVerified = smsVerificationService.verifyCode(sessionId, data.getPhone(), data.getPhoneVfCode());
                if (!phoneVerified) {
                    return new RegisterResponse(400, false, "手机验证码错误或已过期", null);
                }
            }

            // 8. 使用私钥解密密码
            String decryptedPassword = RSAKeyManager.decryptWithPrivateKey(
                    data.getEncryptedPassword(),
                    keyPairDTO.getPrivateKey()
            );

            // 9. 使用 BCrypt 加密密码
            String bcryptPassword = passwordEncoder.encode(decryptedPassword);

            // 10. 创建用户对象
            User user = new User();
            user.setNickname(data.getNickname());
            user.setPassword(bcryptPassword);
            user.setEmail(data.getEmail());
            user.setPhone(data.getPhone());
            user.setStorageQuota(10737418240L);  // 默认10GB
            user.setStorageUsed(0L);
            user.setStatus(1);  // 正常状态
            user.setSecurityQuestionId(data.getSecurityQuestion());
            // 使用 BCrypt 加密安全答案
            String bcryptSecurityAnswer = passwordEncoder.encode(data.getSecurityAnswer());
            user.setSecurityAnswer(bcryptSecurityAnswer);

            // 11. 插入数据库
            int result = userMapper.insertUser(user);

            if (result > 0) {
                // 12. 注册成功后，删除 Redis 中的密钥对和验证码（一次性使用）
                redisTemplate.delete(redisKey);

                // 13. 构建响应数据
                RegisterResponse.UserInfo userInfo = new RegisterResponse.UserInfo(
                        user.getId(),
                        user.getNickname()
                );

                logger.info("[用户注册] 成功 - UserId: {}, Nickname: {}, Email: {}", 
                    user.getId(), user.getNickname(), user.getEmail());

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
            logger.error("[用户注册] 异常 - {}", e.getMessage(), e);
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
            String userIdOrEmail = loginRequest.getUserIdOrEmail();
            String encryptedPassword = loginRequest.getEncryptedPassword();
            Long tokenExpiration = loginRequest.getTokenExpiration();

            if (sessionId == null || sessionId.trim().isEmpty()) {
                return new LoginResponse(400, false, "会话ID不能为空");
            }

            if (userIdOrEmail == null || userIdOrEmail.trim().isEmpty()) {
                return new LoginResponse(400, false, "用户ID或邮箱不能为空");
            }

            if (encryptedPassword == null || encryptedPassword.trim().isEmpty()) {
                return new LoginResponse(400, false, "密码不能为空");
            }

            String redisKey = RSA_KEY_PREFIX + sessionId;
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                logger.error("[用户登录] RSA密钥对不存在 - SessionId: {}, RedisKey: {}", sessionId, redisKey);
                return new LoginResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }

            logger.info("[用户登录] 找到RSA密钥对 - SessionId: {}, PublicKey预览: {}", 
                sessionId, 
                keyPairDTO.getPublicKey().substring(0, Math.min(50, keyPairDTO.getPublicKey().length())) + "...");

            // 重置RSA密钥对的过期时间（延长5分钟）
            redisTemplate.expire(redisKey, 5, TimeUnit.MINUTES);
            logger.debug("[用户登录] 已重置RSA密钥对有效期 - SessionId: {}", sessionId);

            logger.info("[用户登录] 开始解密密码 - SessionId: {}, EncryptedPassword长度: {}", 
                sessionId, encryptedPassword != null ? encryptedPassword.length() : 0);
            
            String decryptedPassword = RSAKeyManager.decryptWithPrivateKey(
                    encryptedPassword,
                    keyPairDTO.getPrivateKey()
            );
            
            logger.info("[用户登录] 密码解密成功 - SessionId: {}", sessionId);

            // 判断是邮箱还是用户ID
            boolean isEmail = userIdOrEmail.contains("@");
            
            long userIdNum = 0;  // 初始化为0
            String userType = "";
            Object userObj = null;
            String storedPassword = null;
            String nickname = null;
            String email = null;
            String phone = null;
            LocalDateTime registeredAt = null;

            if (isEmail) {
                // 邮箱登录：只支持普通用户
                userType = "user";
                User user = userMapper.findByEmail(userIdOrEmail);
                if (user != null) {
                    userObj = user;
                    userIdNum = user.getId();
                    storedPassword = user.getPassword();
                    nickname = user.getNickname();
                    email = user.getEmail();
                    phone = user.getPhone();
                    registeredAt = user.getRegisteredAt();
                }
            } else {
                // 用户ID登录
                try {
                    userIdNum = Long.parseLong(userIdOrEmail);
                } catch (NumberFormatException e) {
                    return new LoginResponse(400, false, "用户ID格式错误");
                }

                if (userIdNum >= 1 && userIdNum <= 9999) {
                    // 管理员
                    userType = "administrator";
                    Administrator admin = administratorMapper.findById((int) userIdNum);
                    if (admin != null) {
                        userObj = admin;
                        storedPassword = admin.getPassword();
                        nickname = admin.getNickname();
                        email = admin.getEmail();
                        phone = admin.getPhone();
                        registeredAt = admin.getRegisteredAt();
                    }
                } else {
                    // 普通用户
                    userType = "user";
                    User user = userMapper.findById(userIdNum);
                    if (user != null) {
                        userObj = user;
                        storedPassword = user.getPassword();
                        nickname = user.getNickname();
                        email = user.getEmail();
                        phone = user.getPhone();
                        registeredAt = user.getRegisteredAt();
                    }
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

            // ⚠️ 注意：不在这里删除Redis中的密钥对
            // 因为可能需要用于二次验证（解密密保答案等）
            // 密钥对将在二次验证完成后或删除

            String token = jwtUtil.generateToken(
                    userIdNum,
                    nickname,
                    userType,
                    registeredAt,
                    tokenExpiration
            );

            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(
                    (tokenExpiration != null && tokenExpiration > 0) ? tokenExpiration : 604800
            );

            LoginResponse response = new LoginResponse();
            response.setCode(200);
            response.setSuccess(true);
            response.setMessage("登录成功");
            response.setUserId(String.valueOf(userIdNum));
            response.setToken(token);
            // 不再返回nickname字段
            response.setEmail(email);
            response.setPhone(phone);
            response.setUserType(userType);
            response.setHomeDirectory("user".equals(userType) ? "/users/" + userIdNum : "/admin/");
            response.setExpiresAt(expiresAt);

            logger.info("[用户登录] 成功 - UserId: {}, UserType: {}, LoginMethod: {}", 
                userIdNum, userType, isEmail ? "邮箱" : "用户ID");

            return response;

        } catch (Exception e) {
            logger.error("[用户登录] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new LoginResponse(500, false, "登录失败：" + e.getMessage());
        }
    }

    /**
     * 验证用户原密码是否正确
     * @param token JWT令牌，用于获取用户ID
     * @param request 密码验证请求对象
     * @return 密码验证响应对象
     */
    public PasswordVerificationResponse verifyInitialPassword(String token, PasswordVerificationRequest request) {
        try {
            // 1. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                return new PasswordVerificationResponse(401, false, "无效的用户令牌");
            }

            // 2. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                return new PasswordVerificationResponse(400, false, "会话ID不能为空");
            }

            if (request.getEncryptedPassword() == null || request.getEncryptedPassword().trim().isEmpty()) {
                return new PasswordVerificationResponse(400, false, "密码不能为空");
            }

            // 3. 从Redis获取RSA密钥对
            String redisKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                return new PasswordVerificationResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }

            // 重置RSA密钥对的过期时间（延长5分钟）
            redisTemplate.expire(redisKey, 5, TimeUnit.MINUTES);

            // 4. 使用私钥解密密码
            String decryptedPassword = RSAKeyManager.decryptWithPrivateKey(
                    request.getEncryptedPassword(),
                    keyPairDTO.getPrivateKey()
            );

            // 5. 根据用户ID范围判断是管理员还是普通用户
            String storedPassword = null;
            if (userId >= 1 && userId <= 9999) {
                // 管理员
                Administrator admin = administratorMapper.findById(userId.intValue());
                if (admin != null) {
                    storedPassword = admin.getPassword();
                }
            } else {
                // 普通用户
                User user = userMapper.findById(userId);
                if (user != null) {
                    storedPassword = user.getPassword();
                }
            }

            if (storedPassword == null) {
                return new PasswordVerificationResponse(404, false, "用户不存在");
            }

            // 6. 验证密码
            if (passwordEncoder.matches(decryptedPassword, storedPassword)) {
                return new PasswordVerificationResponse(200, true, "密码正确");
            } else {
                return new PasswordVerificationResponse(401, false, "密码错误");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return new PasswordVerificationResponse(500, false, "验证失败：" + e.getMessage());
        }
    }

    /**
     * 获取用户所有个人信息
     * @param token JWT令牌，用于获取用户ID
     * @return 用户个人信息响应对象
     */
    public UserProfileResponse getAllProfile(String token) {
        try {
            // 1. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                return new UserProfileResponse(401, false, "无效的用户令牌", null);
            }

            // 2. 构建Redis缓存key
            String cacheKey = "profile:" + userId;

            // 3. 尝试从Redis缓存获取（端口6380）
            String cachedProfile = profileRedisTemplate.opsForValue().get(cacheKey);
            if (cachedProfile != null) {
                logger.info("[获取个人资料] 缓存命中 - UserId: {}", userId);
                
                // 更新缓存有效期（与JWT令牌剩余有效期一致）
                long remainingSeconds = getRemainingTokenExpiration(token);
                if (remainingSeconds > 0) {
                    profileRedisTemplate.expire(cacheKey, remainingSeconds, TimeUnit.SECONDS);
                    logger.debug("[获取个人资料] 更新缓存过期时间 - UserId: {}, 剩余时间: {}秒", userId, remainingSeconds);
                }
                
                // 解析缓存的JSON数据
                ObjectMapper mapper = new ObjectMapper();
                UserProfileResponse.UserData userData = mapper.readValue(cachedProfile, UserProfileResponse.UserData.class);
                
                // 确保 securityQuestion 不为 null
                if (userData.getSecurityQuestion() == null) {
                    userData.setSecurityQuestion("");
                    logger.debug("[获取个人资料] 缓存中 securityQuestion 为 null，已设置为空字符串 - UserId: {}", userId);
                }
                
                // 脱敏处理
                userData.setEmail(maskEmail(userData.getEmail()));
                userData.setPhone(maskPhone(userData.getPhone()));
                
                return new UserProfileResponse(200, true, "获取成功（来自缓存）", userData);
            }

            // 4. 缓存未命中，从数据库获取
            logger.info("[获取个人资料] 缓存未命中，查询数据库 - UserId: {}", userId);

            String avatar = null;
            String nickname = null;
            String email = null;
            String phone = null;
            String securityQuestion = null;
            Long storageUsed = 0L;
            Long storageQuota = 0L;
            Integer securityQuestionId = null;

            // 判断是管理员还是普通用户
            if (userId >= 1 && userId <= 9999) {
                // 管理员
                Administrator admin = administratorMapper.findById(userId.intValue());
                if (admin != null) {
                    // 处理头像：null 或空字符串都转为 null
                    avatar = (admin.getAvatar() != null && !admin.getAvatar().isEmpty()) ? admin.getAvatar() : null;
                    nickname = admin.getNickname();
                    email = admin.getEmail();
                    phone = admin.getPhone();
                    // 管理员没有存储空间概念，设为0
                    storageUsed = 0L;
                    storageQuota = 0L;
                }
            } else {
                // 普通用户
                User user = userMapper.findById(userId);
                if (user != null) {
                    // 处理头像：null 或空字符串都转为 null
                    avatar = (user.getAvatar() != null && !user.getAvatar().isEmpty()) ? user.getAvatar() : null;
                    nickname = user.getNickname();
                    email = user.getEmail();
                    phone = user.getPhone();
                    storageUsed = user.getStorageUsed();
                    storageQuota = user.getStorageQuota();
                    securityQuestionId = user.getSecurityQuestionId();
                }
            }

            if (nickname == null) {
                return new UserProfileResponse(404, false, "用户不存在", null);
            }
            
            // 5. 获取安全问题内容（仅对普通用户）
            if (userId >= 10001) {
                if (securityQuestionId != null) {
                    logger.info("[获取个人资料] 开始获取安全问题 - UserId: {}, QuestionId: {}", userId, securityQuestionId);
                    securityQuestion = getSecurityQuestionContent(securityQuestionId);
                    logger.info("[获取个人资料] 安全问题获取结果 - UserId: {}, QuestionId: {}, Result: {}", 
                        userId, securityQuestionId, securityQuestion != null ? "成功" : "null");
                } else {
                    logger.info("[获取个人资料] 用户未设置密保问题 - UserId: {}", userId);
                    securityQuestion = ""; // 未设置时返回空字符串
                }
            } else {
                logger.info("[获取个人资料] 管理员用户，无安全问题 - UserId: {}", userId);
                securityQuestion = "";
            }

            // 6. 构建未脱敏的数据对象（用于缓存）
            // 注意：avatar 为 null 时转为空字符串，保持与前端约定一致
            UserProfileResponse.UserData rawData = new UserProfileResponse.UserData(
                avatar != null ? avatar : "",
                email != null ? email : "",
                nickname,
                phone != null ? phone : "",
                securityQuestion != null ? securityQuestion : "",
                storageQuota,
                storageUsed
            );

            // 7. 将未脱敏的数据存入Redis缓存（JSON格式，过期时间与JWT令牌一致）
            try {
                ObjectMapper mapper = new ObjectMapper();
                String jsonProfile = mapper.writeValueAsString(rawData);
                
                // 获取JWT令牌剩余有效期
                long expirationSeconds = getRemainingTokenExpiration(token);
                if (expirationSeconds > 0) {
                    profileRedisTemplate.opsForValue().set(cacheKey, jsonProfile, expirationSeconds, TimeUnit.SECONDS);
                    logger.info("[获取个人资料] 缓存已设置 - UserId: {}, 过期时间: {}秒", userId, expirationSeconds);
                } else {
                    // 如果无法获取剩余时间，使用默认7天
                    profileRedisTemplate.opsForValue().set(cacheKey, jsonProfile, 7, TimeUnit.DAYS);
                    logger.info("[获取个人资料] 缓存已设置（默认7天） - UserId: {}", userId);
                }
            } catch (Exception e) {
                logger.warn("[获取个人资料] 缓存设置失败 - {}", e.getMessage());
            }

            // 8. 脱敏处理（返回给前端）
            rawData.setEmail(maskEmail(rawData.getEmail()));
            rawData.setPhone(maskPhone(rawData.getPhone()));

            return new UserProfileResponse(200, true, "获取成功（来自数据库）", rawData);

        } catch (Exception e) {
            e.printStackTrace();
            return new UserProfileResponse(500, false, "获取失败：" + e.getMessage(), null);
        }
    }

    /**
     * 获取安全问题内容（直接从数据库查询）
     * @param questionId 安全问题ID
     * @return 安全问题内容，如果不存在返回null
     */
    private String getSecurityQuestionContent(Integer questionId) {
        if (questionId == null) {
            return null;
        }
        
        try {
            // 从数据库查询安全问题内容
            logger.debug("[获取安全问题] 查询数据库 - QuestionId: {}", questionId);
            SecurityQuestion question = securityQuestionMapper.selectById(questionId);
            
            if (question != null && question.getQuestionText() != null) {
                String questionText = question.getQuestionText();
                logger.debug("[获取安全问题] 查询成功 - QuestionId: {}, 问题: {}", questionId, questionText);
                return questionText;
            }
            
            logger.warn("[获取安全问题] 问题不存在 - QuestionId: {}", questionId);
            return null;
            
        } catch (Exception e) {
            logger.error("[获取安全问题] 异常 - QuestionId: {}, 错误: {}", questionId, e.getMessage());
            return null;
        }
    }

    /**
     * 验证安全答案（兼容明文和BCrypt加密）
     * @param plainAnswer 明文答案
     * @param storedAnswer 数据库中存储的答案（可能是明文或BCrypt）
     * @return 是否匹配
     */
    private boolean verifySecurityAnswer(String plainAnswer, String storedAnswer) {
        if (plainAnswer == null || storedAnswer == null) {
            return false;
        }
        
        // 判断是否为 BCrypt 加密（BCrypt 哈希以 $2a$, $2b$, 或 $2y$ 开头）
        if (storedAnswer.startsWith("$2a$") || storedAnswer.startsWith("$2b$") || storedAnswer.startsWith("$2y$")) {
            // BCrypt 加密，使用 matches 比较
            return passwordEncoder.matches(plainAnswer, storedAnswer);
        } else {
            // 明文存储，直接比较
            return plainAnswer.equals(storedAnswer);
        }
    }

    /**
     * 检查并迁移安全答案为 BCrypt 格式
     * @param userId 用户ID
     * @param storedAnswer 数据库中存储的答案
     * @return 如果已迁移返回 true，否则返回 false
     */
    private boolean migrateSecurityAnswerToBCrypt(Long userId, String storedAnswer) {
        if (storedAnswer == null || storedAnswer.isEmpty()) {
            return false;
        }
        
        // 如果已经是 BCrypt 格式，不需要迁移
        if (storedAnswer.startsWith("$2a$") || storedAnswer.startsWith("$2b$") || storedAnswer.startsWith("$2y$")) {
            return false; // 已经迁移
        }
        
        // 明文存储，需要迁移为 BCrypt
        try {
            String bcryptAnswer = passwordEncoder.encode(storedAnswer);
            int result = userMapper.updateSecurityQuestion(userId, null, bcryptAnswer); // 只更新答案，不改变问题ID
            
            if (result > 0) {
                logger.info("[安全答案迁移] 成功 - UserId: {}, 从明文迁移到BCrypt", userId);
                return true;
            } else {
                logger.error("[安全答案迁移] 失败 - UserId: {}", userId);
                return false;
            }
        } catch (Exception e) {
            logger.error("[安全答案迁移] 异常 - UserId: {}, 错误: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 邮箱脱敏处理
     * 例如：test@example.com -> t***t@example.com
     */
    private String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "";
        }
        
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return email;
        }
        
        String localPart = email.substring(0, atIndex);
        String domainPart = email.substring(atIndex);
        
        if (localPart.length() <= 2) {
            return localPart.charAt(0) + "*" + domainPart;
        }
        
        return localPart.charAt(0) + "***" + localPart.charAt(localPart.length() - 1) + domainPart;
    }

    /**
     * 手机号脱敏处理
     * 例如：13812345678 -> 138****5678
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "";
        }
        
        if (phone.length() < 7) {
            return phone;
        }
        
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 获取JWT令牌的剩余有效期（秒）
     *
     * @param token JWT令牌
     * @return 剩余秒数
     */
    private long getRemainingTokenExpiration(String token) {
        try {
            io.jsonwebtoken.Claims claims = jwtUtil.parseToken(token);
            long expirationTime = claims.getExpiration().getTime();
            long currentTime = System.currentTimeMillis();
            long remainingMillis = expirationTime - currentTime;

            if (remainingMillis > 0) {
                return remainingMillis / 1000; // 转换为秒
            }
            return 0;
        } catch (Exception e) {
            logger.warn("[获取令牌有效期] 失败 - {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 修改用户密码
     * @param token JWT令牌，用于获取用户ID
     * @param request 修改密码请求对象
     * @return 修改密码响应对象
     */
    public PasswordChangeResponse changePassword(String token, PasswordChangeRequest request) {
        try {
            // 1. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                logger.warn("[修改密码] 失败 - 无效的用户令牌");
                return new PasswordChangeResponse(401, false, "无效的用户令牌");
            }
            logger.info("[修改密码] 开始处理 - UserId: {}", userId);

            // 2. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[修改密码] 失败 - UserId: {}, 会话ID不能为空", userId);
                return new PasswordChangeResponse(400, false, "会话ID不能为空");
            }

            if (request.getOldPassword() == null || request.getOldPassword().trim().isEmpty()) {
                logger.warn("[修改密码] 失败 - UserId: {}, 旧密码不能为空", userId);
                return new PasswordChangeResponse(400, false, "旧密码不能为空");
            }

            if (request.getNewPassword() == null || request.getNewPassword().trim().isEmpty()) {
                logger.warn("[修改密码] 失败 - UserId: {}, 新密码不能为空", userId);
                return new PasswordChangeResponse(400, false, "新密码不能为空");
            }

            // 3. 从Redis获取RSA密钥对
            String redisKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                logger.warn("[修改密码] 失败 - UserId: {}, 会话已过期或无效", userId);
                return new PasswordChangeResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }
            logger.debug("[修改密码] RSA密钥对获取成功 - UserId: {}", userId);

            // 重置RSA密钥对的过期时间（延长5分钟）
            redisTemplate.expire(redisKey, 5, TimeUnit.MINUTES);

            // 4. 使用私钥解密旧密码和新密码
            logger.debug("[修改密码] 开始解密密码 - UserId: {}", userId);
            String decryptedOldPassword = RSAKeyManager.decryptWithPrivateKey(
                    request.getOldPassword(),
                    keyPairDTO.getPrivateKey()
            );

            String decryptedNewPassword = RSAKeyManager.decryptWithPrivateKey(
                    request.getNewPassword(),
                    keyPairDTO.getPrivateKey()
            );
            logger.debug("[修改密码] 密码解密完成 - UserId: {}", userId);

            // 5. 根据用户ID范围判断是管理员还是普通用户
            String storedPassword = null;
            boolean isUser = false;

            if (userId >= 1 && userId <= 9999) {
                // 管理员
                Administrator admin = administratorMapper.findById(userId.intValue());
                if (admin != null) {
                    storedPassword = admin.getPassword();
                    logger.debug("[修改密码] 识别为管理员 - UserId: {}", userId);
                }
            } else {
                // 普通用户
                User user = userMapper.findById(userId);
                if (user != null) {
                    storedPassword = user.getPassword();
                    isUser = true;
                    logger.debug("[修改密码] 识别为普通用户 - UserId: {}", userId);
                }
            }

            if (storedPassword == null) {
                logger.warn("[修改密码] 失败 - UserId: {}, 用户不存在", userId);
                return new PasswordChangeResponse(404, false, "用户不存在");
            }

            // 6. 验证旧密码是否正确
            if (!passwordEncoder.matches(decryptedOldPassword, storedPassword)) {
                logger.warn("[修改密码] 失败 - UserId: {}, 旧密码错误", userId);
                return new PasswordChangeResponse(401, false, "旧密码错误");
            }
            logger.debug("[修改密码] 旧密码验证通过 - UserId: {}", userId);

            // 7. 检查新密码是否与旧密码相同
            if (passwordEncoder.matches(decryptedNewPassword, storedPassword)) {
                logger.warn("[修改密码] 失败 - UserId: {}, 新密码与旧密码相同", userId);
                return new PasswordChangeResponse(400, false, "新密码不能与旧密码相同");
            }
            logger.debug("[修改密码] 新密码验证通过 - UserId: {}", userId);

            // 8. 使用BCrypt加密新密码
            logger.debug("[修改密码] 开始加密新密码 - UserId: {}", userId);
            String bcryptNewPassword = passwordEncoder.encode(decryptedNewPassword);

            // 9. 更新数据库中的密码
            int affectedRows;
            if (isUser) {
                affectedRows = userMapper.updatePassword(userId, bcryptNewPassword);
                logger.debug("[修改密码] 更新users表 - UserId: {}", userId);
            } else {
                affectedRows = administratorMapper.updatePassword(userId.intValue(), bcryptNewPassword);
                logger.debug("[修改密码] 更新administrators表 - UserId: {}", userId);
            }

            if (affectedRows == 0) {
                logger.error("[修改密码] 失败 - UserId: {}, 数据库更新失败", userId);
                return new PasswordChangeResponse(500, false, "密码修改失败");
            }

            // 10. 修改成功后，删除Redis中的密钥对（一次性使用）
            redisTemplate.delete(redisKey);
            logger.debug("[修改密码] RSA密钥对已删除 - UserId: {}", userId);

            // 11. 同步更新Redis缓存（密码不在缓存中，但需要清除sessionId相关的验证码缓存）
            String profileCacheKey = "profile:" + userId;
            String cachedProfile = profileRedisTemplate.opsForValue().get(profileCacheKey);
            
            if (cachedProfile != null) {
                try {
                    // 解析缓存的JSON数据
                    ObjectMapper mapper = new ObjectMapper();
                    UserProfileResponse.UserData userData = mapper.readValue(cachedProfile, UserProfileResponse.UserData.class);
                    
                    // 将更新后的数据重新存入Redis（保持原有的过期时间）
                    Long remainingTtl = profileRedisTemplate.getExpire(profileCacheKey, TimeUnit.SECONDS);
                    if (remainingTtl != null && remainingTtl > 0) {
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, remainingTtl, TimeUnit.SECONDS);
                        logger.info("[修改密码] Redis缓存已刷新 - UserId: {}, 剩余TTL: {}秒", userId, remainingTtl);
                    } else {
                        // 如果无法获取剩余时间，使用默认7天
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, 7, TimeUnit.DAYS);
                        logger.info("[修改密码] Redis缓存已刷新（默认7天） - UserId: {}", userId);
                    }
                } catch (Exception e) {
                    logger.warn("[修改密码] Redis缓存刷新失败，将删除缓存 - {}", e.getMessage());
                    // 如果更新失败，则删除缓存
                    profileRedisTemplate.delete(profileCacheKey);
                }
            } else {
                logger.debug("[修改密码] Redis缓存不存在，无需更新 - UserId: {}", userId);
            }
            
            // 12. 清除sessionId相关的验证码缓存（如果存在）
            String emailCodeKey = "email:code:" + request.getSessionId();
            String smsCodeKey = "sms:code:" + request.getSessionId();
            
            // 删除所有以该sessionId开头的邮箱验证码键
            java.util.Set<String> emailKeys = redisTemplate.keys(emailCodeKey + "*");
            if (emailKeys != null && !emailKeys.isEmpty()) {
                redisTemplate.delete(emailKeys);
                logger.debug("[修改密码] 已清除邮箱验证码缓存 - UserId: {}, 清除数量: {}", userId, emailKeys.size());
            }
            
            // 删除所有以该sessionId开头的短信验证码键
            java.util.Set<String> smsKeys = redisTemplate.keys(smsCodeKey + "*");
            if (smsKeys != null && !smsKeys.isEmpty()) {
                redisTemplate.delete(smsKeys);
                logger.debug("[修改密码] 已清除短信验证码缓存 - UserId: {}, 清除数量: {}", userId, smsKeys.size());
            }
            
            logger.info("[修改密码] 成功 - UserId: {}, 所有缓存已清除", userId);

            return new PasswordChangeResponse(200, true, "密码修改成功");

        } catch (Exception e) {
            logger.error("[修改密码] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new PasswordChangeResponse(500, false, "修改失败：" + e.getMessage());
        }
    }

    /**
     * 修改用户昵称
     * @param token JWT令牌，用于获取用户ID
     * @param request 修改昵称请求对象
     * @return 修改昵称响应对象
     */
    public NicknameChangeResponse changeNickname(String token, NicknameChangeRequest request) {
        try {
            // 1. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                logger.warn("[修改昵称] 失败 - 无效的用户令牌");
                return new NicknameChangeResponse(401, false, "无效的用户令牌", null);
            }
            logger.info("[修改昵称] 开始处理 - UserId: {}", userId);

            // 2. 验证请求参数
            if (request.getNickname() == null || request.getNickname().trim().isEmpty()) {
                logger.warn("[修改昵称] 失败 - UserId: {}, 昵称不能为空", userId);
                return new NicknameChangeResponse(400, false, "昵称不能为空", null);
            }

            String newNickname = request.getNickname().trim();

            // 3. 验证昵称长度（2-20个字符）
            if (newNickname.length() < 2 || newNickname.length() > 20) {
                logger.warn("[修改昵称] 失败 - UserId: {}, 昵称长度不符合要求: {}", userId, newNickname.length());
                return new NicknameChangeResponse(400, false, "昵称长度必须在2-20个字符之间", null);
            }

            // 4. 验证昵称格式（只允许中文、英文、数字、下划线）
            if (!newNickname.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9_]+$")) {
                logger.warn("[修改昵称] 失败 - UserId: {}, 昵称包含非法字符", userId);
                return new NicknameChangeResponse(400, false, "昵称只能包含中文、英文、数字和下划线", null);
            }

            // 5. 检查昵称是否已被其他用户使用
            User existingUser = userMapper.findByNickname(newNickname);
            if (existingUser != null && !existingUser.getId().equals(userId)) {
                logger.warn("[修改昵称] 失败 - UserId: {}, 昵称已被使用: {}", userId, newNickname);
                return new NicknameChangeResponse(400, false, "昵称已被使用", null);
            }

            Administrator existingAdmin = administratorMapper.findById(userId.intValue());
            // 管理员ID范围是1-9999，如果userId在这个范围内，需要检查管理员表
            if (userId >= 1 && userId <= 9999) {
                if (existingAdmin != null && existingAdmin.getNickname().equals(newNickname)) {
                    // 如果是同一个管理员，允许修改
                    logger.debug("[修改昵称] 管理员昵称未变化 - UserId: {}", userId);
                }
            }

            // 6. 根据用户ID范围判断是管理员还是普通用户
            int affectedRows;
            if (userId >= 1 && userId <= 9999) {
                // 管理员
                affectedRows = administratorMapper.updateNickname(userId.intValue(), newNickname);
                logger.debug("[修改昵称] 更新administrators表 - UserId: {}, 新昵称: {}", userId, newNickname);
            } else {
                // 普通用户
                affectedRows = userMapper.updateNickname(userId, newNickname);
                logger.debug("[修改昵称] 更新users表 - UserId: {}, 新昵称: {}", userId, newNickname);
            }

            if (affectedRows == 0) {
                logger.error("[修改昵称] 失败 - UserId: {}, 数据库更新失败", userId);
                return new NicknameChangeResponse(500, false, "昵称修改失败", null);
            }

            // 7. 同步更新Redis缓存中的昵称
            String profileCacheKey = "profile:" + userId;
            String cachedProfile = profileRedisTemplate.opsForValue().get(profileCacheKey);
            
            if (cachedProfile != null) {
                try {
                    // 解析缓存的JSON数据
                    ObjectMapper mapper = new ObjectMapper();
                    UserProfileResponse.UserData userData = mapper.readValue(cachedProfile, UserProfileResponse.UserData.class);
                    
                    // 更新昵称
                    userData.setNickname(newNickname);
                    
                    // 将更新后的数据重新存入Redis（保持原有的过期时间）
                    Long remainingTtl = profileRedisTemplate.getExpire(profileCacheKey, TimeUnit.SECONDS);
                    if (remainingTtl != null && remainingTtl > 0) {
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, remainingTtl, TimeUnit.SECONDS);
                        logger.info("[修改昵称] Redis缓存已同步更新 - UserId: {}, 新昵称: {}, 剩余TTL: {}秒", userId, newNickname, remainingTtl);
                    } else {
                        // 如果无法获取剩余时间，使用默认7天
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, 7, TimeUnit.DAYS);
                        logger.info("[修改昵称] Redis缓存已同步更新（默认7天） - UserId: {}, 新昵称: {}", userId, newNickname);
                    }
                } catch (Exception e) {
                    logger.warn("[修改昵称] Redis缓存同步更新失败，将删除缓存 - {}", e.getMessage());
                    // 如果更新失败，则删除缓存
                    profileRedisTemplate.delete(profileCacheKey);
                }
            } else {
                logger.debug("[修改昵称] Redis缓存不存在，无需更新 - UserId: {}", userId);
            }
            
            logger.info("[修改昵称] 成功 - UserId: {}, 新昵称: {}", userId, newNickname);

            return new NicknameChangeResponse(200, true, "昵称修改成功", null);

        } catch (Exception e) {
            logger.error("[修改昵称] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new NicknameChangeResponse(500, false, "修改失败：" + e.getMessage(), null);
        }
    }

    /**
     * 修改用户邮箱
     * @param token JWT令牌，用于获取用户ID
     * @param request 修改邮箱请求对象
     * @return 修改邮箱响应对象
     */
    public EmailChangeResponse changeEmail(String token, EmailChangeRequest request) {
        try {
            // 1. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                logger.warn("[修改邮箱] 失败 - 无效的用户令牌");
                return new EmailChangeResponse(401, false, "无效的用户令牌");
            }
            logger.info("[修改邮箱] 开始处理 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 2. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, 会话ID不能为空", userId);
                return new EmailChangeResponse(400, false, "会话ID不能为空");
            }

            if (request.getEncryptedEmail() == null || request.getEncryptedEmail().trim().isEmpty()) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, 邮箱不能为空", userId);
                return new EmailChangeResponse(400, false, "邮箱不能为空");
            }

            if (request.getVerificationCode() == null || request.getVerificationCode().trim().isEmpty()) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, 验证码不能为空", userId);
                return new EmailChangeResponse(400, false, "验证码不能为空");
            }

            // 3. 从Redis获取RSA密钥对
            String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

            if (keyPairDTO == null) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, SessionId: {}, 会话已过期或无效", userId, request.getSessionId());
                return new EmailChangeResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }
            logger.debug("[修改邮箱] RSA密钥对获取成功 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 重置RSA密钥对的过期时间（延长5分钟）
            redisTemplate.expire(rsaKey, 5, TimeUnit.MINUTES);

            // 4. 使用私钥解密邮箱
            logger.debug("[修改邮箱] 开始解密邮箱 - UserId: {}, SessionId: {}", userId, request.getSessionId());
            String newEmail = RSAKeyManager.decryptWithPrivateKey(
                    request.getEncryptedEmail(),
                    keyPairDTO.getPrivateKey()
            );
            newEmail = newEmail.trim();
            logger.debug("[修改邮箱] 邮箱解密完成 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 5. 验证邮箱格式
            String emailRegex = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
            if (!newEmail.matches(emailRegex)) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, 邮箱格式不正确: {}", userId, newEmail);
                return new EmailChangeResponse(400, false, "邮箱格式不正确");
            }

            // 6. 验证邮箱验证码
            boolean emailVerified = emailVerificationService.verifyCode(
                request.getSessionId(), 
                newEmail, 
                request.getVerificationCode()
            );
            if (!emailVerified) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, 验证码错误或已过期", userId);
                return new EmailChangeResponse(400, false, "验证码错误或已过期");
            }
            logger.debug("[修改邮箱] 验证码验证通过 - UserId: {}", userId);

            // 7. 检查邮箱是否已被其他用户使用
            User existingUser = userMapper.findByEmail(newEmail);
            if (existingUser != null && !existingUser.getId().equals(userId)) {
                logger.warn("[修改邮箱] 失败 - UserId: {}, 邮箱已被使用: {}", userId, newEmail);
                return new EmailChangeResponse(400, false, "该邮箱已被其他用户使用");
            }

            // 8. 更新数据库中的邮箱
            int affectedRows = userMapper.updateEmail(userId, newEmail);
            if (affectedRows == 0) {
                logger.error("[修改邮箱] 失败 - UserId: {}, 数据库更新失败", userId);
                return new EmailChangeResponse(500, false, "邮箱修改失败");
            }
            logger.debug("[修改邮箱] 数据库更新成功 - UserId: {}, 新邮箱: {}", userId, newEmail);

            // 9. 同步更新Redis缓存中的邮箱
            String profileCacheKey = "profile:" + userId;
            String cachedProfile = profileRedisTemplate.opsForValue().get(profileCacheKey);
            
            if (cachedProfile != null) {
                try {
                    // 解析缓存的JSON数据
                    ObjectMapper mapper = new ObjectMapper();
                    UserProfileResponse.UserData userData = mapper.readValue(cachedProfile, UserProfileResponse.UserData.class);
                    
                    // 更新邮箱
                    userData.setEmail(newEmail);
                    
                    // 将更新后的数据重新存入Redis（保持原有的过期时间）
                    Long remainingTtl = profileRedisTemplate.getExpire(profileCacheKey, TimeUnit.SECONDS);
                    if (remainingTtl != null && remainingTtl > 0) {
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, remainingTtl, TimeUnit.SECONDS);
                        logger.info("[修改邮箱] Redis缓存已同步更新 - UserId: {}, 新邮箱: {}, 剩余TTL: {}秒", userId, newEmail, remainingTtl);
                    } else {
                        // 如果无法获取剩余时间，使用默认7天
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, 7, TimeUnit.DAYS);
                        logger.info("[修改邮箱] Redis缓存已同步更新（默认7天） - UserId: {}, 新邮箱: {}", userId, newEmail);
                    }
                } catch (Exception e) {
                    logger.warn("[修改邮箱] Redis缓存同步更新失败，将删除缓存 - {}", e.getMessage());
                    // 如果更新失败，则删除缓存
                    profileRedisTemplate.delete(profileCacheKey);
                }
            } else {
                logger.debug("[修改邮箱] Redis缓存不存在，无需更新 - UserId: {}", userId);
            }
            
            logger.info("[修改邮箱] 成功 - UserId: {}, SessionId: {}, 新邮箱: {}", userId, request.getSessionId(), newEmail);

            // 10. 清除sessionId相关的验证码缓存
            String emailCodeKey = "email:code:" + request.getSessionId() + ":" + newEmail;
            redisTemplate.delete(emailCodeKey);
            logger.debug("[修改邮箱] 已清除邮箱验证码缓存 - UserId: {}, Key: {}", userId, emailCodeKey);
            
            // 11. 清除RSA密钥对（一次性使用）
            redisTemplate.delete(rsaKey);
            logger.debug("[修改邮箱] 已清除RSA密钥对 - UserId: {}", userId);

            return new EmailChangeResponse(200, true, "邮箱修改成功");

        } catch (Exception e) {
            logger.error("[修改邮箱] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new EmailChangeResponse(500, false, "修改失败：" + e.getMessage());
        }
    }

    /**
     * 修改用户手机号
     * @param token JWT令牌，用于获取用户ID
     * @param request 修改手机号请求对象
     * @return 修改手机号响应对象
     */
    public PhoneChangeResponse changePhone(String token, PhoneChangeRequest request) {
        try {
            // 1. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                logger.warn("[修改手机号] 失败 - 无效的用户令牌");
                return new PhoneChangeResponse(401, false, "无效的用户令牌");
            }
            logger.info("[修改手机号] 开始处理 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 2. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[修改手机号] 失败 - UserId: {}, 会话ID不能为空", userId);
                return new PhoneChangeResponse(400, false, "会话ID不能为空");
            }

            if (request.getEncryptedPhone() == null || request.getEncryptedPhone().trim().isEmpty()) {
                logger.warn("[修改手机号] 失败 - UserId: {}, 手机号不能为空", userId);
                return new PhoneChangeResponse(400, false, "手机号不能为空");
            }

            if (request.getVerificationCode() == null || request.getVerificationCode().trim().isEmpty()) {
                logger.warn("[修改手机号] 失败 - UserId: {}, 验证码不能为空", userId);
                return new PhoneChangeResponse(400, false, "验证码不能为空");
            }

            // 3. 从Redis获取RSA密钥对
            String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

            if (keyPairDTO == null) {
                logger.warn("[修改手机号] 失败 - UserId: {}, SessionId: {}, 会话已过期或无效", userId, request.getSessionId());
                return new PhoneChangeResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }
            logger.debug("[修改手机号] RSA密钥对获取成功 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 重置RSA密钥对的过期时间（延长5分钟）
            redisTemplate.expire(rsaKey, 5, TimeUnit.MINUTES);

            // 4. 使用私钥解密手机号
            logger.debug("[修改手机号] 开始解密手机号 - UserId: {}, SessionId: {}", userId, request.getSessionId());
            String newPhone = RSAKeyManager.decryptWithPrivateKey(
                    request.getEncryptedPhone(),
                    keyPairDTO.getPrivateKey()
            );
            newPhone = newPhone.trim();
            logger.debug("[修改手机号] 手机号解密完成 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 5. 验证手机号格式（中国大陆手机号）
            String phoneRegex = "^1[3-9]\\d{9}$";
            if (!newPhone.matches(phoneRegex)) {
                logger.warn("[修改手机号] 失败 - UserId: {}, 手机号格式不正确: {}", userId, newPhone);
                return new PhoneChangeResponse(400, false, "手机号格式不正确");
            }

            // 6. 验证手机验证码
            boolean phoneVerified = smsVerificationService.verifyCode(
                request.getSessionId(), 
                newPhone, 
                request.getVerificationCode()
            );
            if (!phoneVerified) {
                logger.warn("[修改手机号] 失败 - UserId: {}, SessionId: {}, 验证码错误或已过期", userId, request.getSessionId());
                return new PhoneChangeResponse(400, false, "验证码错误或已过期");
            }
            logger.debug("[修改手机号] 验证码验证通过 - UserId: {}, SessionId: {}", userId, request.getSessionId());

            // 7. 检查手机号是否已被其他用户使用
            User existingUser = userMapper.findByPhone(newPhone);
            if (existingUser != null && !existingUser.getId().equals(userId)) {
                logger.warn("[修改手机号] 失败 - UserId: {}, 手机号已被使用: {}", userId, newPhone);
                return new PhoneChangeResponse(400, false, "该手机号已被其他用户使用");
            }

            // 8. 更新数据库中的手机号
            int affectedRows = userMapper.updatePhone(userId, newPhone);
            if (affectedRows == 0) {
                logger.error("[修改手机号] 失败 - UserId: {}, 数据库更新失败", userId);
                return new PhoneChangeResponse(500, false, "手机号修改失败");
            }
            logger.debug("[修改手机号] 数据库更新成功 - UserId: {}, 新手机号: {}", userId, newPhone);

            // 9. 同步更新Redis缓存中的手机号
            String profileCacheKey = "profile:" + userId;
            String cachedProfile = profileRedisTemplate.opsForValue().get(profileCacheKey);
            
            if (cachedProfile != null) {
                try {
                    // 解析缓存的JSON数据
                    ObjectMapper mapper = new ObjectMapper();
                    UserProfileResponse.UserData userData = mapper.readValue(cachedProfile, UserProfileResponse.UserData.class);
                    
                    // 更新手机号
                    userData.setPhone(newPhone);
                    
                    // 将更新后的数据重新存入Redis（保持原有的过期时间）
                    Long remainingTtl = profileRedisTemplate.getExpire(profileCacheKey, TimeUnit.SECONDS);
                    if (remainingTtl != null && remainingTtl > 0) {
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, remainingTtl, TimeUnit.SECONDS);
                        logger.info("[修改手机号] Redis缓存已同步更新 - UserId: {}, 新手机号: {}, 剩余TTL: {}秒", userId, newPhone, remainingTtl);
                    } else {
                        // 如果无法获取剩余时间，使用默认7天
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, 7, TimeUnit.DAYS);
                        logger.info("[修改手机号] Redis缓存已同步更新（默认7天） - UserId: {}, 新手机号: {}", userId, newPhone);
                    }
                } catch (Exception e) {
                    logger.warn("[修改手机号] Redis缓存同步更新失败，将删除缓存 - {}", e.getMessage());
                    // 如果更新失败，则删除缓存
                    profileRedisTemplate.delete(profileCacheKey);
                }
            } else {
                logger.debug("[修改手机号] Redis缓存不存在，无需更新 - UserId: {}", userId);
            }
            
            logger.info("[修改手机号] 成功 - UserId: {}, SessionId: {}, 新手机号: {}", userId, request.getSessionId(), newPhone);

            // 10. 清除sessionId相关的验证码缓存
            String smsCodeKey = "sms:code:" + request.getSessionId() + ":" + newPhone;
            redisTemplate.delete(smsCodeKey);
            logger.debug("[修改手机号] 已清除短信验证码缓存 - UserId: {}, Key: {}", userId, smsCodeKey);
            
            // 11. 清除手机号发送时间记录
            String smsSendTimeKey = "sms:send_time:" + newPhone;
            redisTemplate.delete(smsSendTimeKey);
            logger.debug("[修改手机号] 已清除短信发送时间记录 - UserId: {}, Key: {}", userId, smsSendTimeKey);
            
            // 12. 清除RSA密钥对（一次性使用）
            redisTemplate.delete(rsaKey);
            logger.debug("[修改手机号] 已清除RSA密钥对 - UserId: {}", userId);

            return new PhoneChangeResponse(200, true, "手机号修改成功");

        } catch (Exception e) {
            logger.error("[修改手机号] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new PhoneChangeResponse(500, false, "修改失败：" + e.getMessage());
        }
    }

    /**
     * 查找用户信息（重置密码第一步）
     * @param request 查找用户请求对象
     * @return 查找用户响应对象
     */
    public FindUserResponse findUser(FindUserRequest request) {
        try {
            // 1. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[查找用户] 失败 - 会话ID不能为空");
                return new FindUserResponse(400, false, "会话ID不能为空", null, null, null, null, null);
            }

            if (request.getEncryptedUserIdOrEmail() == null || request.getEncryptedUserIdOrEmail().trim().isEmpty()) {
                logger.warn("[查找用户] 失败 - 加密数据不能为空");
                return new FindUserResponse(400, false, "加密数据不能为空", null, null, null, null, null);
            }

            logger.info("[查找用户] 开始处理 - SessionId: {}", request.getSessionId());

            // 2. 从 Redis 获取 RSA 密钥对
            String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

            if (keyPairDTO == null) {
                logger.warn("[查找用户] 失败 - SessionId: {}, 会话已过期或无效", request.getSessionId());
                return new FindUserResponse(400, false, "会话已过期或无效，请重新获取公钥", null, null, null, null, null);
            }
            logger.debug("[查找用户] RSA密钥对获取成功 - SessionId: {}", request.getSessionId());

            // 3. 使用私钥解密用户ID或邮箱
            logger.debug("[查找用户] 开始解密 - SessionId: {}", request.getSessionId());
            String userIdOrEmail = RSAKeyManager.decryptWithPrivateKey(
                    request.getEncryptedUserIdOrEmail(),
                    keyPairDTO.getPrivateKey()
            );
            userIdOrEmail = userIdOrEmail.trim();
            logger.debug("[查找用户] 解密完成 - SessionId: {}, 数据类型: {}", 
                request.getSessionId(), 
                userIdOrEmail.contains("@") ? "邮箱" : "用户ID");

            // 4. 查询用户
            User user = null;
            if (userIdOrEmail.contains("@")) {
                // 是邮箱地址
                logger.debug("[查找用户] 按邮箱查询 - Email: {}", userIdOrEmail);
                user = userMapper.findByEmail(userIdOrEmail);
            } else {
                // 是用户ID
                try {
                    Long userId = Long.parseLong(userIdOrEmail);
                    logger.debug("[查找用户] 按用户ID查询 - UserId: {}", userId);
                    user = userMapper.findById(userId);
                } catch (NumberFormatException e) {
                    logger.warn("[查找用户] 失败 - 输入既不是有效的邮箱也不是有效的用户ID: {}", userIdOrEmail);
                    return new FindUserResponse(400, false, "输入格式不正确", null, null, null, null, null);
                }
            }

            // 5. 检查是否找到用户
            if (user == null) {
                logger.warn("[查找用户] 失败 - 未找到用户, 查询条件: {}", userIdOrEmail);
                return new FindUserResponse(404, false, "未找到该用户，请检查输入", null, null, null, null, null);
            }

            logger.info("[查找用户] 成功 - UserId: {}, Nickname: {}", user.getId(), user.getNickname());

            // 6. 准备返回数据
            String email = user.getEmail() != null ? user.getEmail() : "";
            String phone = user.getPhone() != null ? user.getPhone() : "";
            Integer securityQuestion = user.getSecurityQuestionId();
            
            // 获取密保问题文本
            String securityQuestionText = "";
            if (securityQuestion != null) {
                securityQuestionText = securityQuestionService.getQuestionTextById(securityQuestion);
                if (securityQuestionText == null) {
                    securityQuestionText = "";
                }
            }

            logger.debug("[查找用户] 返回验证方式 - Email: {}, Phone: {}, SecurityQuestion: {}", 
                email.isEmpty() ? "未设置" : "已设置",
                phone.isEmpty() ? "未设置" : "已设置",
                securityQuestion != null ? "已设置" : "未设置");

            return new FindUserResponse(
                200,
                true,
                "找到用户",
                user.getId(),
                email,
                phone,
                securityQuestion,
                securityQuestionText
            );

        } catch (IllegalArgumentException e) {
            // RSA解密失败（密钥不匹配）
            logger.warn("[查找用户] 失败 - RSA解密失败: {}", e.getMessage());
            return new FindUserResponse(400, false, "解密失败，请确认使用的公钥正确", null, null, null, null, null);
        } catch (Exception e) {
            logger.error("[查找用户] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new FindUserResponse(500, false, "服务器内部错误：" + e.getMessage(), null, null, null, null, null);
        }
    }

    /**
     * 邮箱验证码验证（重置密码第二步）
     * @param request 验证请求对象
     * @param deviceFingerprint 设备指纹
     * @return 验证响应对象
     */
    public ResetPasswordVerifyResponse verifyEmailForReset(ResetPasswordEmailVerifyRequest request, String deviceFingerprint) {
        try {
            // 1. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[重置密码-邮箱验证] 失败 - 会话ID不能为空");
                return new ResetPasswordVerifyResponse(400, false, "会话ID不能为空", null);
            }

            if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                logger.warn("[重置密码-邮箱验证] 失败 - 邮箱不能为空");
                return new ResetPasswordVerifyResponse(400, false, "邮箱不能为空", null);
            }

            if (request.getVerificationCode() == null || request.getVerificationCode().trim().isEmpty()) {
                logger.warn("[重置密码-邮箱验证] 失败 - 验证码不能为空");
                return new ResetPasswordVerifyResponse(400, false, "验证码不能为空", null);
            }

            logger.info("[重置密码-邮箱验证] 开始处理 - SessionId: {}, Email: {}", 
                request.getSessionId(), request.getEmail());

            // 2. 验证邮箱验证码
            boolean verified = emailVerificationService.verifyCode(
                request.getSessionId(), 
                request.getEmail(), 
                request.getVerificationCode()
            );

            if (!verified) {
                logger.warn("[重置密码-邮箱验证] 失败 - 验证码错误或已过期 - SessionId: {}, Email: {}", 
                    request.getSessionId(), request.getEmail());
                return new ResetPasswordVerifyResponse(400, false, "验证码错误或已过期", null);
            }

            // 3. 查找用户
            User user = userMapper.findByEmail(request.getEmail());
            if (user == null) {
                logger.warn("[重置密码-邮箱验证] 失败 - 用户不存在 - Email: {}", request.getEmail());
                return new ResetPasswordVerifyResponse(404, false, "用户不存在", null);
            }

            logger.info("[重置密码-邮箱验证] 成功 - UserId: {}, Email: {}", user.getId(), user.getEmail());

            // 4. 生成resetToken（有效期10分钟）
            String resetToken = jwtUtil.generateResetToken(
                user.getId(),
                "email",
                user.getEmail(),
                null,
                deviceFingerprint,
                600L  // 600秒 = 10分钟
            );

            logger.debug("[重置密码-邮箱验证] 已生成resetToken - UserId: {}", user.getId());

            return new ResetPasswordVerifyResponse(200, true, "验证成功", resetToken);

        } catch (Exception e) {
            logger.error("[重置密码-邮箱验证] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new ResetPasswordVerifyResponse(500, false, "服务器内部错误：" + e.getMessage(), null);
        }
    }

    /**
     * 手机验证码验证（重置密码第二步）
     * @param request 验证请求对象
     * @param deviceFingerprint 设备指纹
     * @return 验证响应对象
     */
    public ResetPasswordVerifyResponse verifyPhoneForReset(ResetPasswordPhoneVerifyRequest request, String deviceFingerprint) {
        try {
            // 1. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[重置密码-手机验证] 失败 - 会话ID不能为空");
                return new ResetPasswordVerifyResponse(400, false, "会话ID不能为空", null);
            }

            if (request.getPhone() == null || request.getPhone().trim().isEmpty()) {
                logger.warn("[重置密码-手机验证] 失败 - 手机号不能为空");
                return new ResetPasswordVerifyResponse(400, false, "手机号不能为空", null);
            }

            if (request.getVerificationCode() == null || request.getVerificationCode().trim().isEmpty()) {
                logger.warn("[重置密码-手机验证] 失败 - 验证码不能为空");
                return new ResetPasswordVerifyResponse(400, false, "验证码不能为空", null);
            }

            logger.info("[重置密码-手机验证] 开始处理 - SessionId: {}, Phone: {}", 
                request.getSessionId(), request.getPhone());

            // 2. 验证手机验证码
            boolean verified = smsVerificationService.verifyCode(
                request.getSessionId(), 
                request.getPhone(), 
                request.getVerificationCode()
            );

            if (!verified) {
                logger.warn("[重置密码-手机验证] 失败 - 验证码错误或已过期 - SessionId: {}, Phone: {}", 
                    request.getSessionId(), request.getPhone());
                return new ResetPasswordVerifyResponse(400, false, "验证码错误或已过期", null);
            }

            // 3. 查找用户
            User user = userMapper.findByPhone(request.getPhone());
            if (user == null) {
                logger.warn("[重置密码-手机验证] 失败 - 用户不存在 - Phone: {}", request.getPhone());
                return new ResetPasswordVerifyResponse(404, false, "用户不存在", null);
            }

            logger.info("[重置密码-手机验证] 成功 - UserId: {}, Phone: {}", user.getId(), user.getPhone());

            // 4. 生成resetToken（有效期10分钟）
            String resetToken = jwtUtil.generateResetToken(
                user.getId(),
                "phone",
                null,
                user.getPhone(),
                deviceFingerprint,
                600L  // 600秒 = 10分钟
            );

            logger.debug("[重置密码-手机验证] 已生成resetToken - UserId: {}", user.getId());

            return new ResetPasswordVerifyResponse(200, true, "验证成功", resetToken);

        } catch (Exception e) {
            logger.error("[重置密码-手机验证] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new ResetPasswordVerifyResponse(500, false, "服务器内部错误：" + e.getMessage(), null);
        }
    }

    /**
     * 密保问题答案验证（重置密码第二步）
     * @param request 验证请求对象
     * @param deviceFingerprint 设备指纹
     * @return 验证响应对象
     */
    public ResetPasswordVerifyResponse verifySecurityAnswerForReset(ResetPasswordSecurityVerifyRequest request, String deviceFingerprint) {
        try {
            // 1. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[重置密码-密保验证] 失败 - 会话ID不能为空");
                return new ResetPasswordVerifyResponse(400, false, "会话ID不能为空", null);
            }

            if (request.getUserId() == null) {
                logger.warn("[重置密码-密保验证] 失败 - 用户ID不能为空");
                return new ResetPasswordVerifyResponse(400, false, "用户ID不能为空", null);
            }

            if (request.getEncryptedSecurityAnswer() == null || request.getEncryptedSecurityAnswer().trim().isEmpty()) {
                logger.warn("[重置密码-密保验证] 失败 - 加密答案不能为空");
                return new ResetPasswordVerifyResponse(400, false, "加密答案不能为空", null);
            }

            logger.info("[重置密码-密保验证] 开始处理 - SessionId: {}, UserId: {}", 
                request.getSessionId(), request.getUserId());

            // 2. 从 Redis 获取 RSA 密钥对
            String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

            if (keyPairDTO == null) {
                logger.warn("[重置密码-密保验证] 失败 - SessionId: {}, 会话已过期或无效", request.getSessionId());
                return new ResetPasswordVerifyResponse(400, false, "会话已过期或无效，请重新获取公钥", null);
            }
            logger.debug("[重置密码-密保验证] RSA密钥对获取成功 - SessionId: {}", request.getSessionId());

            // 3. 使用私钥解密答案
            logger.debug("[重置密码-密保验证] 开始解密答案 - SessionId: {}", request.getSessionId());
            String securityAnswer = RSAKeyManager.decryptWithPrivateKey(
                request.getEncryptedSecurityAnswer(),
                keyPairDTO.getPrivateKey()
            );
            securityAnswer = securityAnswer.trim();
            logger.debug("[重置密码-密保验证] 答案解密完成 - SessionId: {}", request.getSessionId());

            // 4. 查找用户
            User user = userMapper.findById(request.getUserId());
            if (user == null) {
                logger.warn("[重置密码-密保验证] 失败 - 用户不存在 - UserId: {}", request.getUserId());
                return new ResetPasswordVerifyResponse(404, false, "用户不存在", null);
            }

            // 5. 检查用户是否设置了密保问题
            if (user.getSecurityQuestionId() == null || user.getSecurityAnswer() == null) {
                logger.warn("[重置密码-密保验证] 失败 - 用户未设置密保问题 - UserId: {}", request.getUserId());
                return new ResetPasswordVerifyResponse(400, false, "用户未设置密保问题", null);
            }

            // 6. 验证答案（兼容明文和BCrypt）
            boolean isMatch = verifySecurityAnswer(securityAnswer, user.getSecurityAnswer());
            if (!isMatch) {
                logger.warn("[重置密码-密保验证] 失败 - 答案错误 - UserId: {}", request.getUserId());
                return new ResetPasswordVerifyResponse(400, false, "密保答案错误", null);
            }
            
            // 7. 如果是明文存储，自动迁移为BCrypt
            migrateSecurityAnswerToBCrypt(user.getId(), user.getSecurityAnswer());

            logger.info("[重置密码-密保验证] 成功 - UserId: {}", user.getId());

            // 7. 生成resetToken（有效期10分钟）
            String resetToken = jwtUtil.generateResetToken(
                user.getId(),
                "security",
                null,
                null,
                deviceFingerprint,
                600L  // 600秒 = 10分钟
            );

            logger.debug("[重置密码-密保验证] 已生成resetToken - UserId: {}", user.getId());

            // 8. 清除RSA密钥对（一次性使用）
            redisTemplate.delete(rsaKey);
            logger.debug("[重置密码-密保验证] 已清除RSA密钥对 - SessionId: {}", request.getSessionId());

            return new ResetPasswordVerifyResponse(200, true, "验证成功", resetToken);

        } catch (IllegalArgumentException e) {
            // RSA解密失败（密钥不匹配）
            logger.warn("[重置密码-密保验证] 失败 - RSA解密失败: {}", e.getMessage());
            return new ResetPasswordVerifyResponse(400, false, "解密失败，请确认使用的公钥正确", null);
        } catch (Exception e) {
            logger.error("[重置密码-密保验证] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new ResetPasswordVerifyResponse(500, false, "服务器内部错误：" + e.getMessage(), null);
        }
    }

    /**
     * 重置密码（最后一步）
     * @param token JWT令牌（从Authorization头获取）
     * @param request 重置密码请求对象
     * @param deviceFingerprint 当前请求的设备指纹
     * @return 重置密码响应对象
     */
    public ResetPasswordResponse resetPassword(String token, ResetPasswordRequest request, String deviceFingerprint) {
        try {
            // 1. 验证JWT令牌
            if (token == null || token.trim().isEmpty()) {
                logger.warn("[重置密码] 失败 - 未提供令牌");
                return new ResetPasswordResponse(401, false, "未提供验证令牌");
            }

            logger.info("[重置密码] 开始处理 - Token预览: {}", 
                token.length() > 20 ? token.substring(0, 20) + "..." : token);

            // 2. 验证并解析resetToken
            io.jsonwebtoken.Claims claims;
            try {
                claims = jwtUtil.validateResetToken(token);
            } catch (IllegalArgumentException e) {
                logger.warn("[重置密码] 失败 - 令牌验证失败: {}", e.getMessage());
                // 判断是过期还是无效
                if (e.getMessage().contains("过期")) {
                    return new ResetPasswordResponse(401, false, "验证令牌已过期");
                } else {
                    return new ResetPasswordResponse(401, false, "验证令牌无效");
                }
            }

            // 3. 检查令牌是否已被使用（使用Redis存储已使用的令牌）
            String usedTokenKey = "used_reset_token:" + token;
            Boolean isUsed = (Boolean) redisTemplate.opsForValue().get(usedTokenKey);
            if (isUsed != null && isUsed) {
                logger.warn("[重置密码] 失败 - 令牌已被使用");
                return new ResetPasswordResponse(410, false, "验证令牌已被使用，请重新验证");
            }

            // 4. 从令牌中获取用户ID
            Long userId = claims.get("userId", Long.class);
            if (userId == null) {
                logger.warn("[重置密码] 失败 - 令牌中缺少用户ID");
                return new ResetPasswordResponse(401, false, "验证令牌无效");
            }

            // 5. 校验设备指纹是否一致
            String tokenDeviceFingerprint = jwtUtil.getDeviceFingerprintFromResetToken(token);
            if (tokenDeviceFingerprint != null && deviceFingerprint != null) {
                if (!tokenDeviceFingerprint.equals(deviceFingerprint)) {
                    logger.warn("[重置密码] 失败 - 设备指纹不一致 - UserId: {}, Token设备: {}, 请求设备: {}",
                        userId, tokenDeviceFingerprint, deviceFingerprint);
                    return new ResetPasswordResponse(403, false, "设备不匹配，请重新验证");
                }
                logger.debug("[重置密码] 设备指纹验证通过 - UserId: {}", userId);
            } else {
                logger.warn("[重置密码] 警告 - 设备指纹缺失 - Token设备: {}, 请求设备: {}",
                    tokenDeviceFingerprint, deviceFingerprint);
            }

            logger.info("[重置密码] 令牌验证成功 - UserId: {}", userId);

            // 6. 验证请求参数
            if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
                logger.warn("[重置密码] 失败 - 会话ID不能为空");
                return new ResetPasswordResponse(400, false, "会话ID不能为空");
            }

            if (request.getEncryptedNewPassword() == null || request.getEncryptedNewPassword().trim().isEmpty()) {
                logger.warn("[重置密码] 失败 - 加密密码不能为空");
                return new ResetPasswordResponse(400, false, "加密密码不能为空");
            }

            // 7. 从 Redis 获取 RSA 密钥对
            String rsaKey = RSA_KEY_PREFIX + request.getSessionId();
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(rsaKey);

            if (keyPairDTO == null) {
                logger.warn("[重置密码] 失败 - SessionId: {}, 会话已过期或无效", request.getSessionId());
                return new ResetPasswordResponse(400, false, "会话已过期或无效，请重新获取公钥");
            }
            logger.debug("[重置密码] RSA密钥对获取成功 - SessionId: {}", request.getSessionId());

            // 8. 使用私钥解密密码
            logger.debug("[重置密码] 开始解密密码 - SessionId: {}", request.getSessionId());
            String newPassword = RSAKeyManager.decryptWithPrivateKey(
                request.getEncryptedNewPassword(),
                keyPairDTO.getPrivateKey()
            );
            logger.debug("[重置密码] 密码解密完成 - SessionId: {}", request.getSessionId());

            // 9. 验证密码格式（长度6-14位）
            if (newPassword.length() < 6 || newPassword.length() > 14) {
                logger.warn("[重置密码] 失败 - 密码长度不符合要求 - Length: {}", newPassword.length());
                return new ResetPasswordResponse(400, false, "密码长度必须为6-14位");
            }

            // 10. 查找用户
            User user = userMapper.findById(userId);
            if (user == null) {
                logger.warn("[重置密码] 失败 - 用户不存在 - UserId: {}", userId);
                return new ResetPasswordResponse(404, false, "用户不存在");
            }

            logger.info("[重置密码] 找到用户 - UserId: {}, Nickname: {}", user.getId(), user.getNickname());

            // 11. 使用 BCrypt 哈希密码
            String hashedPassword = passwordEncoder.encode(newPassword);
            logger.debug("[重置密码] 密码已哈希 - UserId: {}", userId);

            // 12. 更新数据库中的密码
            int result = userMapper.updatePassword(userId, hashedPassword);
            if (result <= 0) {
                logger.error("[重置密码] 失败 - 密码更新失败 - UserId: {}", userId);
                return new ResetPasswordResponse(500, false, "密码更新失败");
            }

            logger.info("[重置密码] 密码更新成功 - UserId: {}", userId);

            // 13. 标记令牌已使用（存储到Redis，24小时过期）
            redisTemplate.opsForValue().set(usedTokenKey, true, 24, java.util.concurrent.TimeUnit.HOURS);
            logger.debug("[重置密码] 令牌已标记为已使用 - Token预览: {}", 
                token.length() > 20 ? token.substring(0, 20) + "..." : token);

            // 14. 清除RSA密钥对（一次性使用）
            redisTemplate.delete(rsaKey);
            logger.debug("[重置密码] 已清除RSA密钥对 - SessionId: {}", request.getSessionId());

            logger.info("[重置密码] 成功 - UserId: {}", userId);

            return new ResetPasswordResponse(200, true, "密码重置成功");

        } catch (IllegalArgumentException e) {
            // RSA解密失败（密钥不匹配）
            logger.warn("[重置密码] 失败 - RSA解密失败: {}", e.getMessage());
            return new ResetPasswordResponse(400, false, "密码解密失败，请确认使用的公钥正确");
        } catch (Exception e) {
            logger.error("[重置密码] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new ResetPasswordResponse(500, false, "服务器内部错误：" + e.getMessage());
        }
    }

    /**
     * 修改用户密保问题
     * @param token JWT令牌，用于获取用户ID
     * @param request 修改密保问题请求对象
     * @return 修改密保问题响应对象
     */
    public SecurityQuestionChangeResponse changeSecurityQuestion(String token, SecurityQuestionChangeRequest request) {
        try {
            // 1. 验证参数
            if (request == null) {
                logger.warn("[修改密保问题] 失败 - 请求对象为空");
                return new SecurityQuestionChangeResponse(false, "缺少必要参数");
            }

            String sessionId = request.getSessionId();
            String encryptedOldAnswer = request.getEncryptedOldAnswer();
            Integer newSecurityQuestionId = request.getNewSecurityQuestionId();
            String encryptedNewAnswer = request.getEncryptedNewAnswer();

            if (sessionId == null || sessionId.trim().isEmpty() ||
                encryptedOldAnswer == null || encryptedOldAnswer.trim().isEmpty() ||
                newSecurityQuestionId == null ||
                encryptedNewAnswer == null || encryptedNewAnswer.trim().isEmpty()) {
                logger.warn("[修改密保问题] 失败 - 参数缺失");
                return new SecurityQuestionChangeResponse(false, "缺少必要参数");
            }

            // 2. 从JWT令牌中获取用户ID
            Long userId = jwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                logger.warn("[修改密保问题] 失败 - 无效的用户令牌");
                return new SecurityQuestionChangeResponse(false, "无效的用户令牌");
            }

            // 3. 验证是否为普通用户（管理员没有密保问题）
            if (userId < 10001) {
                logger.warn("[修改密保问题] 失败 - 管理员用户不能修改密保问题 - UserId: {}", userId);
                return new SecurityQuestionChangeResponse(false, "管理员用户不能修改密保问题");
            }

            // 4. 从 Redis 获取 RSA 密钥对
            String redisKey = RSA_KEY_PREFIX + sessionId;
            RSAKeyPairDTO keyPairDTO = (RSAKeyPairDTO) redisTemplate.opsForValue().get(redisKey);

            if (keyPairDTO == null) {
                logger.warn("[修改密保问题] 失败 - 会话已过期 - SessionId: {}", sessionId);
                return new SecurityQuestionChangeResponse(false, "会话已过期，请重新操作");
            }

            // 5. 查询用户当前信息
            User user = userMapper.findById(userId);
            if (user == null) {
                logger.warn("[修改密保问题] 失败 - 用户不存在 - UserId: {}", userId);
                return new SecurityQuestionChangeResponse(false, "用户不存在");
            }

            // 6. 验证用户是否设置了密保问题
            if (user.getSecurityQuestionId() == null || user.getSecurityAnswer() == null) {
                logger.warn("[修改密保问题] 失败 - 用户未设置密保问题 - UserId: {}", userId);
                return new SecurityQuestionChangeResponse(false, "您还未设置密保问题");
            }

            // 7. 使用私钥解密旧答案
            String decryptedOldAnswer;
            try {
                decryptedOldAnswer = RSAKeyManager.decryptWithPrivateKey(
                    encryptedOldAnswer,
                    keyPairDTO.getPrivateKey()
                );
            } catch (Exception e) {
                logger.warn("[修改密保问题] 失败 - 旧答案解密失败: {}", e.getMessage());
                return new SecurityQuestionChangeResponse(false, "旧答案解密失败");
            }

            // 8. 验证旧答案是否正确（兼容明文和BCrypt）
            boolean isOldAnswerMatch = verifySecurityAnswer(decryptedOldAnswer, user.getSecurityAnswer());
            if (!isOldAnswerMatch) {
                logger.warn("[修改密保问题] 失败 - 旧答案错误 - UserId: {}", userId);
                return new SecurityQuestionChangeResponse(false, "旧密保答案错误");
            }
            
            // 8.5. 如果是明文存储，自动迁移为BCrypt
            migrateSecurityAnswerToBCrypt(userId, user.getSecurityAnswer());

            // 9. 验证新的安全问题是否存在
            SecurityQuestion newQuestion = securityQuestionMapper.selectById(newSecurityQuestionId);
            if (newQuestion == null) {
                logger.warn("[修改密保问题] 失败 - 新的安全问题不存在 - QuestionId: {}", newSecurityQuestionId);
                return new SecurityQuestionChangeResponse(false, "新的安全问题不存在");
            }

            // 10. 使用私钥解密新答案
            String decryptedNewAnswer;
            try {
                decryptedNewAnswer = RSAKeyManager.decryptWithPrivateKey(
                    encryptedNewAnswer,
                    keyPairDTO.getPrivateKey()
                );
            } catch (Exception e) {
                logger.warn("[修改密保问题] 失败 - 新答案解密失败: {}", e.getMessage());
                return new SecurityQuestionChangeResponse(false, "新答案解密失败");
            }

            // 11. 验证新答案不为空
            if (decryptedNewAnswer == null || decryptedNewAnswer.trim().isEmpty()) {
                logger.warn("[修改密保问题] 失败 - 新答案为空");
                return new SecurityQuestionChangeResponse(false, "新答案不能为空");
            }

            // 12. 使用 BCrypt 加密新答案
            String bcryptNewAnswer = passwordEncoder.encode(decryptedNewAnswer);

            // 13. 更新数据库
            int updateResult = userMapper.updateSecurityQuestion(userId, newSecurityQuestionId, bcryptNewAnswer);

            if (updateResult <= 0) {
                logger.error("[修改密保问题] 失败 - 数据库更新失败 - UserId: {}", userId);
                return new SecurityQuestionChangeResponse(false, "修改失败，请稍后重试");
            }

            logger.info("[修改密保问题] 成功 - UserId: {}, 新问题ID: {}", userId, newSecurityQuestionId);

            // 14. 同步更新Redis缓存中的安全问题内容
            String profileCacheKey = "profile:" + userId;
            String cachedProfile = profileRedisTemplate.opsForValue().get(profileCacheKey);
            
            if (cachedProfile != null) {
                try {
                    // 解析缓存的JSON数据
                    ObjectMapper mapper = new ObjectMapper();
                    UserProfileResponse.UserData userData = mapper.readValue(cachedProfile, UserProfileResponse.UserData.class);
                    
                    // 获取新安全问题的文本内容
                    String newQuestionText = getSecurityQuestionContent(newSecurityQuestionId);
                    
                    // 更新安全问题字段
                    userData.setSecurityQuestion(newQuestionText != null ? newQuestionText : "");
                    
                    // 将更新后的数据重新存入Redis（保持原有的过期时间）
                    Long remainingTtl = profileRedisTemplate.getExpire(profileCacheKey, TimeUnit.SECONDS);
                    if (remainingTtl != null && remainingTtl > 0) {
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, remainingTtl, TimeUnit.SECONDS);
                        logger.info("[修改密保问题] Redis缓存已同步更新 - UserId: {}, 新问题: {}, 剩余TTL: {}秒", userId, newQuestionText, remainingTtl);
                    } else {
                        // 如果无法获取剩余时间，使用默认7天
                        String updatedJson = mapper.writeValueAsString(userData);
                        profileRedisTemplate.opsForValue().set(profileCacheKey, updatedJson, 7, TimeUnit.DAYS);
                        logger.info("[修改密保问题] Redis缓存已同步更新（默认7天） - UserId: {}, 新问题: {}", userId, newQuestionText);
                    }
                } catch (Exception e) {
                    logger.warn("[修改密保问题] Redis缓存同步更新失败，将删除缓存 - {}", e.getMessage());
                    // 如果更新失败，则删除缓存
                    profileRedisTemplate.delete(profileCacheKey);
                }
            } else {
                logger.debug("[修改密保问题] Redis缓存不存在，无需更新 - UserId: {}", userId);
            }

            // 15. 清除RSA密钥对（一次性使用）
            redisTemplate.delete(redisKey);
            logger.debug("[修改密保问题] 已清除RSA密钥对 - SessionId: {}", sessionId);

            return new SecurityQuestionChangeResponse(true, "密保问题修改成功");

        } catch (Exception e) {
            logger.error("[修改密保问题] 异常 - {}", e.getMessage(), e);
            e.printStackTrace();
            return new SecurityQuestionChangeResponse(false, "服务器内部错误：" + e.getMessage());
        }
    }
}
