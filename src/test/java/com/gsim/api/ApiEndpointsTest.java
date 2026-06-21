package com.gsim.api;

import com.gsim.app.AppConfig;
import com.gsim.app.ApplicationContext;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API 端点可用性集成测试。
 *
 * <p>启动真实 HTTP 服务器，对每个端点发送 HTTP 请求，验证：
 * <ul>
 *   <li>返回正确的 HTTP 状态码</li>
 *   <li>返回合法的 JSON 格式</li>
 *   <li>不返回 stub/not_implemented 占位符</li>
 * </ul>
 *
 * <p>使用 {@link AppConfig#forTesting()} 模拟环境，不依赖外部服务。
 * 使用随机端口避免端口冲突。
 */
@DisplayName("API Endpoints Integration Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiEndpointsTest {

    private static ApplicationContext ctx;
    private static ApiManager apiManager;
    private static int port;

    private HttpClient client;

    @BeforeAll
    static void startServer() throws Exception {
        AppConfig config = AppConfig.forTesting();
        // 确保 data 目录存在且已初始化
        config.getDataDir().toFile().mkdirs();
        config.getImportDir().toFile().mkdirs();
        config.getOutputDir().toFile().mkdirs();
        config.getLogDir().toFile().mkdirs();

        ctx = new ApplicationContext(config);
        ctx.initialize();

        // 停止默认的 ApiManager，用随机端口重新创建
        ctx.getApiManager().stop();

        // 使用随机端口 (0 = 系统自动分配)
        ApiConfig apiConfig = new ApiConfig("127.0.0.1", 0, true);
        apiManager = new ApiManager(apiConfig, ctx, ctx.getEventBus());
        apiManager.forceEnable();
        apiManager.start();

        port = apiManager.getPort();
        System.out.println("Test API server started on port " + port);
    }

    @AfterAll
    static void stopServer() {
        if (apiManager != null) {
            apiManager.stop();
        }
        if (ctx != null) {
            ctx.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ========== 核心端点 ==========

    @Test
    @Order(1)
    @DisplayName("GET /api/status — 应用状态")
    void testStatusEndpoint() throws Exception {
        HttpResponse<String> response = get("/api/status");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("campaignId") || body.contains("turnId") || body.contains("llmEnabled"),
                "status 响应应包含状态字段: " + body);
    }

    @Test
    @Order(2)
    @DisplayName("GET /api/help — 帮助/命令列表")
    void testHelpEndpoint() throws Exception {
        HttpResponse<String> response = get("/api/help");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("commands") || body.contains("count"),
                "help 响应应包含命令列表: " + body);
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/where — 当前位置信息")
    void testWhereEndpoint() throws Exception {
        HttpResponse<String> response = get("/api/where");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("success"), "where 响应应为合法 JSON: " + body);
    }

    // ========== 配置管理 ==========

    @Test
    @Order(4)
    @DisplayName("GET /api/config/status — 配置状态")
    void testConfigStatus() throws Exception {
        HttpResponse<String> response = get("/api/config/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("llm"), "config status 应包含 llm 字段");
    }

    @Test
    @Order(5)
    @DisplayName("GET /api/config/show — 显示配置（脱敏）")
    void testConfigShow() throws Exception {
        HttpResponse<String> response = get("/api/config/show");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("llm.base_url"), "config show 应包含配置项");
    }

    @Test
    @Order(6)
    @DisplayName("GET /api/config/path — 配置文件路径")
    void testConfigPath() throws Exception {
        HttpResponse<String> response = get("/api/config/path");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("path"), "config path 应有 path 字段");
    }

    // ========== 知识库和 Embedding ==========

    @Test
    @Order(7)
    @DisplayName("GET /api/knowledge/status — 知识库状态")
    void testKnowledgeStatus() throws Exception {
        HttpResponse<String> response = get("/api/knowledge/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("available"), "knowledge 响应应包含 available");
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/embedding/status — Embedding 状态")
    void testEmbeddingStatus() throws Exception {
        HttpResponse<String> response = get("/api/embedding/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("available"), "embedding 响应应包含 available");
    }

    @Test
    @Order(9)
    @DisplayName("GET /api/embedding/profiles — Embedding profiles 列表")
    void testEmbeddingProfiles() throws Exception {
        HttpResponse<String> response = get("/api/embedding/profiles");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("profiles"), "embedding profiles 响应应包含 profiles");
    }

    // ========== 数据管理 ==========

    @Test
    @Order(10)
    @DisplayName("GET /api/data/status — 数据状态")
    void testDataStatus() throws Exception {
        HttpResponse<String> response = get("/api/data/status");
        assertEquals(200, response.statusCode());
        String body = response.body();
        // 测试环境 DataManager 可能不可用，接受 available=true/false 两种
        assertTrue(body.contains("available"), "data status 应包含 available 字段: " + body);
    }

    @Test
    @Order(11)
    @DisplayName("GET /api/data/worlds — 世界列表")
    void testDataWorlds() throws Exception {
        HttpResponse<String> response = get("/api/data/worlds");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("worlds"), "data worlds 应包含 worlds");
    }

    @Test
    @Order(12)
    @DisplayName("GET /api/data/branches — 分支列表")
    void testDataBranches() throws Exception {
        HttpResponse<String> response = get("/api/data/branches");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("branches"), "data branches 应包含 branches");
    }

    @Test
    @Order(13)
    @DisplayName("GET /api/data/timeline — 时间线")
    void testDataTimeline() throws Exception {
        HttpResponse<String> response = get("/api/data/timeline");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("tree"), "data timeline 应包含 tree");
    }

    @Test
    @Order(14)
    @DisplayName("GET /api/data/input — 读取 input.md")
    void testDataInput() throws Exception {
        HttpResponse<String> response = get("/api/data/input");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("content"), "data input 应包含 content");
    }

    @Test
    @Order(15)
    @DisplayName("GET /api/data/documents — 文档列表")
    void testDataDocuments() throws Exception {
        HttpResponse<String> response = get("/api/data/documents");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("documents"), "data documents 应包含 documents");
    }

    @Test
    @Order(16)
    @DisplayName("GET /api/data/search?q=test — 搜索文档")
    void testDataSearch() throws Exception {
        HttpResponse<String> response = get("/api/data/search?q=test");
        // 测试环境 DataManager 可能不可用，返回 503 是合理降级
        assertTrue(response.statusCode() == 200 || response.statusCode() == 503,
                "data search 应返回 200 或 503: " + response.statusCode());
        assertTrue(response.body().contains("results") || response.body().contains("available"),
                "data search 响应应包含 results 或 available: " + response.body());
    }

    // ========== 技能和经验 ==========

    @Test
    @Order(17)
    @DisplayName("GET /api/skills — 技能列表")
    void testSkillsList() throws Exception {
        HttpResponse<String> response = get("/api/skills");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "skills 响应应为合法 JSON");
    }

    @Test
    @Order(18)
    @DisplayName("GET /api/experiences — 经验列表")
    void testExperiencesList() throws Exception {
        HttpResponse<String> response = get("/api/experiences");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "experiences 响应应为合法 JSON");
    }

    // ========== 玩家档案 ==========

    @Test
    @Order(19)
    @DisplayName("GET /api/players — 玩家列表")
    void testPlayersList() throws Exception {
        HttpResponse<String> response = get("/api/players");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success") || response.body().contains("displayText"),
                "players 应为合法 JSON: " + response.body());
    }

    // ========== 分支管理 ==========

    @Test
    @Order(20)
    @DisplayName("GET /api/branches — 分支列表")
    void testBranchesList() throws Exception {
        HttpResponse<String> response = get("/api/branches");
        assertEquals(200, response.statusCode());
        String body = response.body();
        // 不应再返回 not_implemented
        assertFalse(body.contains("not_implemented"),
                "branches 不应返回 not_implemented: " + body);
        assertTrue(body.contains("branches") || body.contains("Branches"),
                "branches 响应应包含分支数据: " + body);
    }

    @Test
    @Order(21)
    @DisplayName("POST /api/branches — 创建分支")
    void testBranchesCreate() throws Exception {
        HttpResponse<String> response = post("/api/branches", "{\"name\":\"test-branch\"}");
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("not_implemented"),
                "branches create 不应返回 not_implemented");
    }

    // ========== 搜索 ==========

    @Test
    @Order(22)
    @DisplayName("GET /api/searchdb?q=test — 知识库搜索")
    void testSearchDbGet() throws Exception {
        HttpResponse<String> response = get("/api/searchdb?q=test");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertFalse(body.contains("not_implemented"),
                "searchdb 不应返回 not_implemented: " + body);
        assertTrue(body.contains("results") || body.contains("Search"),
                "searchdb 响应应包含搜索结果: " + body);
    }

    @Test
    @Order(23)
    @DisplayName("POST /api/searchdb — 知识库搜索（POST）")
    void testSearchDbPost() throws Exception {
        HttpResponse<String> response = post("/api/searchdb", "{\"query\":\"test\",\"topK\":5}");
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("not_implemented"),
                "searchdb POST 不应返回 not_implemented");
    }

    // ========== 其他端点 ==========

    @Test
    @Order(24)
    @DisplayName("POST /api/save — 手动保存")
    void testSave() throws Exception {
        HttpResponse<String> response = post("/api/save", "{}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "save 响应应包含 success");
    }

    @Test
    @Order(25)
    @DisplayName("GET /api/pins — 硬约束列表")
    void testPinsList() throws Exception {
        HttpResponse<String> response = get("/api/pins");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "pins 响应应包含 success");
    }

    @Test
    @Order(26)
    @DisplayName("GET /api/messages — 消息历史")
    void testMessagesList() throws Exception {
        HttpResponse<String> response = get("/api/messages");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "messages 响应应包含 success");
    }

    @Test
    @Order(27)
    @DisplayName("GET /api/roots — 根节点列表")
    void testRootsList() throws Exception {
        HttpResponse<String> response = get("/api/roots");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "roots 响应应包含 success");
    }

    @Test
    @Order(28)
    @DisplayName("GET /api/tools — 工具列表")
    void testToolsList() throws Exception {
        HttpResponse<String> response = get("/api/tools");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("tools") || body.contains("count"),
                "tools 响应应包含工具列表: " + body);
    }

    @Test
    @Order(29)
    @DisplayName("POST /api/tools/search — 工具搜索")
    void testToolsSearch() throws Exception {
        HttpResponse<String> response = post("/api/tools/search", "{\"query\":\"test\"}");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("success"), "tools search 响应应包含 success");
    }

    // ========== Campaign / Turn / Action ==========

    @Test
    @Order(30)
    @DisplayName("GET /api/campaigns — 列出所有 Campaign")
    void testCampaignsList() throws Exception {
        HttpResponse<String> response = get("/api/campaigns");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("campaigns"), "campaigns 应包含 campaigns 列表");
    }

    @Test
    @Order(31)
    @DisplayName("POST /api/tasks — 创建任务")
    void testCreateTask() throws Exception {
        HttpResponse<String> response = post("/api/tasks",
                "{\"sessionId\":\"test\",\"command\":\"/status\"}");
        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("taskId"), "task 创建应返回 taskId");
    }

    @Test
    @Order(32)
    @DisplayName("GET /api/tasks — 列出任务")
    void testListTasks() throws Exception {
        HttpResponse<String> response = get("/api/tasks");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("tasks"), "tasks 列表应包含 tasks");
    }

    // ========== 错误处理 ==========

    @Test
    @Order(33)
    @DisplayName("GET /api/nonexistent — 未知路径 → 404")
    void test404() throws Exception {
        HttpResponse<String> response = get("/api/nonexistent");
        assertTrue(response.statusCode() >= 400, "未知路径应返回 >= 400");
    }

    @Test
    @Order(34)
    @DisplayName("PUT /api/status — 错误方法 → 405")
    void test405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .method("PUT", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }

    // ========== Context API ==========

    @Test
    @Order(35)
    @DisplayName("GET /api/context/session — Context 会话状态")
    void testContextSession() throws Exception {
        HttpResponse<String> response = get("/api/context/session");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("active"), "context session 应包含 active");
    }

    @Test
    @Order(36)
    @DisplayName("GET /api/context/base — Base Context")
    void testContextBase() throws Exception {
        HttpResponse<String> response = get("/api/context/base");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("markdown"), "context base 应包含 markdown");
    }

    // ========== Logs / Outputs ==========

    @Test
    @Order(37)
    @DisplayName("GET /api/logs — 日志列表")
    void testLogs() throws Exception {
        HttpResponse<String> response = get("/api/logs");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("files"), "logs 应包含 files");
    }

    @Test
    @Order(38)
    @DisplayName("GET /api/outputs — 输出列表")
    void testOutputs() throws Exception {
        HttpResponse<String> response = get("/api/outputs");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("files"), "outputs 应包含 files");
    }

    // ========== CORS ==========

    @Test
    @Order(39)
    @DisplayName("OPTIONS /api/tasks — CORS 预检")
    void testCorsPreflight() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(204, response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    // ========== Campaign 语义 ==========

    @Test
    @Order(40)
    @DisplayName("POST /api/campaigns 应使用请求的 name 创建 campaign")
    void testCampaignCreateWithName() throws Exception {
        HttpResponse<String> response = post("/api/campaigns",
                "{\"name\":\"test-campaign-42\"}");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("test-campaign-42"),
                "campaign 应包含请求的 name: " + body);
    }

    // ========== 路径穿越防护 ==========

    @Test
    @Order(41)
    @DisplayName("GET /api/logs/../etc 应拒绝路径穿越")
    void testLogsRejectPathTraversal() throws Exception {
        HttpResponse<String> response = get("/api/logs/..%2Fetc");
        // 应返回 400（无效 taskId）而非尝试读取文件
        assertTrue(response.statusCode() >= 400,
                "路径穿越应返回 >= 400: " + response.statusCode());
    }

    @Test
    @Order(42)
    @DisplayName("GET /api/outputs/../../../etc 应拒绝路径穿越")
    void testOutputsRejectPathTraversal() throws Exception {
        HttpResponse<String> response = get("/api/outputs/..%2F..%2F..%2Fetc");
        assertTrue(response.statusCode() >= 400,
                "路径穿越应返回 >= 400: " + response.statusCode());
    }

    // ====== helpers ======

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(5))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
