package com.mizuka.cloudfilesystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mizuka.cloudfilesystem.dto.NewFolderRequest;
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
 * FileController 集成测试
 * 测试文件目录浏览和创建相关的 API 接口
 */
@SpringBootTest
class FileControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 初始化 MockMvc
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        
        // 初始化 ObjectMapper
        objectMapper = new ObjectMapper();
    }

    // ==================== 浏览目录测试 ====================

    @Test
    @DisplayName("浏览目录 - 缺少 currentNodeId 应该返回 400")
    void testBrowseWithoutCurrentNodeId() throws Exception {
        mockMvc.perform(get("/files/browse")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("浏览目录 - 第一页默认参数")
    void testBrowseFirstPageWithDefaultParams() throws Exception {
        Long testNodeId = 1L;  // 根据实际情况修改
        
        mockMvc.perform(get("/files/browse")
                        .param("currentNodeId", String.valueOf(testNodeId))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.currentNode").exists())
                .andExpect(jsonPath("$.data.children").isArray())
                .andExpect(jsonPath("$.data.pagination").exists());
    }

    @Test
    @DisplayName("浏览目录 - 带排除列表")
    void testBrowseWithExcludeLists() throws Exception {
        Long testNodeId = 1L;  // 根据实际情况修改
        
        mockMvc.perform(get("/files/browse")
                        .param("currentNodeId", String.valueOf(testNodeId))
                        .param("excludeNewFileIds", "100,101")
                        .param("excludeNewFolderIds", "200,201")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("浏览目录 - 按名称升序排序")
    void testBrowseSortedByNameAsc() throws Exception {
        Long testNodeId = 1L;
        
        mockMvc.perform(get("/files/browse")
                        .param("currentNodeId", String.valueOf(testNodeId))
                        .param("sortedBy", "1")
                        .param("order", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("浏览目录 - 游标分页第二页")
    void testBrowseSecondPageWithCursor() throws Exception {
        Long testNodeId = 1L;
        Long lastChildrenNode = 10L;
        String lastChildrenType = "folder";
        
        mockMvc.perform(get("/files/browse")
                        .param("currentNodeId", String.valueOf(testNodeId))
                        .param("lastChildrenNode", String.valueOf(lastChildrenNode))
                        .param("lastChildrenType", lastChildrenType)
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pagination.lastChildrenNode").exists())
                .andExpect(jsonPath("$.data.pagination.isEnd").exists());
    }

    // ==================== 搜索功能测试 ====================

    @Test
    @DisplayName("搜索文件/文件夹 - 基本搜索")
    void testSearchBasic() throws Exception {
        mockMvc.perform(get("/files/search")
                        .param("keyword", "work")
                        .param("type", "all")
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.pagination").exists());
    }

    @Test
    @DisplayName("搜索文件/文件夹 - 只搜索文件")
    void testSearchFilesOnly() throws Exception {
        mockMvc.perform(get("/files/search")
                        .param("keyword", "pdf")
                        .param("type", "file")
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("搜索文件/文件夹 - 只搜索文件夹")
    void testSearchFoldersOnly() throws Exception {
        mockMvc.perform(get("/files/search")
                        .param("keyword", "documents")
                        .param("type", "folder")
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("搜索文件/文件夹 - 游标分页第二页")
    void testSearchWithCursor() throws Exception {
        mockMvc.perform(get("/files/search")
                        .param("keyword", "work")
                        .param("type", "all")
                        .param("lastFoldersNode", "100")
                        .param("lastFilesNode", "200")
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pagination.lastFolderNode").exists())
                .andExpect(jsonPath("$.data.pagination.lastFileNode").exists());
    }

    @Test
    @DisplayName("搜索回收站 - 基本搜索")
    void testSearchRecycleBinBasic() throws Exception {
        mockMvc.perform(get("/files/recycle/search")
                        .param("keyword", "deleted")
                        .param("type", "all")
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.pagination").exists());
    }

    @Test
    @DisplayName("搜索回收站 - 游标分页")
    void testSearchRecycleBinWithCursor() throws Exception {
        mockMvc.perform(get("/files/recycle/search")
                        .param("keyword", "deleted")
                        .param("type", "all")
                        .param("lastFoldersNode", "100")
                        .param("lastFilesNode", "200")
                        .param("maxPageSize", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ==================== 创建文件夹测试 ====================

    @Test
    @DisplayName("创建文件夹 - 成功创建")
    void testCreateFolderSuccess() throws Exception {
        NewFolderRequest request = new NewFolderRequest();
        request.setParentId(1L);  // 根据实际情况修改
        request.setFolderName("test_folder_" + System.currentTimeMillis());
        
        mockMvc.perform(post("/files/folder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value(request.getFolderName()))
                .andExpect(jsonPath("$.data.path").exists())
                .andExpect(jsonPath("$.data.reusedFromPool").exists());
    }

    @Test
    @DisplayName("创建文件夹 - 缺少 parentId 应该返回错误")
    void testCreateFolderWithoutParentId() throws Exception {
        NewFolderRequest request = new NewFolderRequest();
        request.setFolderName("test_folder");
        // 不设置 parentId
        
        mockMvc.perform(post("/files/folder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("创建文件夹 - 文件夹名称为空应该返回错误")
    void testCreateFolderWithEmptyName() throws Exception {
        NewFolderRequest request = new NewFolderRequest();
        request.setParentId(1L);
        request.setFolderName("");
        
        mockMvc.perform(post("/files/folder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("创建文件夹 - 父目录不存在应该返回错误")
    void testCreateFolderWithInvalidParentId() throws Exception {
        NewFolderRequest request = new NewFolderRequest();
        request.setParentId(999999999L);  // 不存在的ID
        request.setFolderName("test_folder");
        
        mockMvc.perform(post("/files/folder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("创建文件夹 - 验证响应结构")
    void testCreateFolderResponseStructure() throws Exception {
        NewFolderRequest request = new NewFolderRequest();
        request.setParentId(1L);
        request.setFolderName("test_folder_structure");
        
        mockMvc.perform(post("/files/folder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.success").exists())
                .andExpect(jsonPath("$.message").value("文件夹创建成功"))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").exists())
                .andExpect(jsonPath("$.data.path").exists())
                .andExpect(jsonPath("$.data.reusedFromPool").exists())
                .andExpect(jsonPath("$.data.reusedFromPool").isBoolean());
    }
}
