package com.mizuka.cloudfilesystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mizuka.cloudfilesystem.dto.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 集成测试
 * 测试认证相关的 API 接口
 */
@SpringBootTest
class AuthControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    private String testSessionId;

    @BeforeEach
    void setUp() {
        // 初始化 MockMvc
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        
        // 初始化 ObjectMapper
        objectMapper = new ObjectMapper();

        // 生成测试用的 sessionId
        testSessionId = "test-session-" + System.currentTimeMillis();
    }

    @Test
    @DisplayName("健康检查接口 - 应该返回成功")
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/auth/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("Backend is running"));
    }

    @Test
    @DisplayName("获取RSA公钥 - 应该返回200和公钥")
    void testGetRSAPublicKey() throws Exception {
        // 准备请求数据
        java.util.Map<String, String> request = new java.util.HashMap<>();
        request.put("sessionId", testSessionId);

        // 执行请求
        mockMvc.perform(post("/auth/rsa-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.publicKey").exists());
    }

    @Test
    @DisplayName("获取RSA公钥 - sessionId为空应该返回400")
    void testGetRSAPublicKeyWithEmptySessionId() throws Exception {
        java.util.Map<String, String> request = new java.util.HashMap<>();
        request.put("sessionId", "");

        mockMvc.perform(post("/auth/rsa-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("用户登录 - 缺少参数应该返回错误")
    void testLoginWithMissingParameters() throws Exception {
        LoginRequest request = new LoginRequest();
        // 不设置任何参数

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("获取安全问题列表 - 应该返回问题列表")
    void testGetSecurityQuestions() throws Exception {
        mockMvc.perform(get("/auth/security-questions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.questions").isArray());
    }
}
