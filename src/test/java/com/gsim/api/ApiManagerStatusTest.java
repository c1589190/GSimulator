package com.gsim.api;

import com.gsim.api.dto.CommandRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.app.AppConfig;
import com.gsim.event.EventBus;
import com.gsim.event.ConsoleEventSink;
import com.gsim.interaction.commands.StatusCommand;
import com.gsim.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API 集成测试 — 启动 HTTP 服务器并验证端点。
 */
@DisplayName("API Manager")
class ApiManagerStatusTest {

    private ApiManager apiManager;
    private ApplicationContext ctx;
    private EventBus eventBus;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        ctx = new ApplicationContext(config);
        ctx.initialize();

        // 注册必要的命令
        ctx.getInteractionManager().registerCommand(new StatusCommand());

        eventBus = ctx.getEventBus();

        // 使用随机端口
        port = 18710 + (int) (Math.random() * 1000);
        ApiConfig apiConfig = new ApiConfig("127.0.0.1", port, true);
        apiManager = new ApiManager(apiConfig, ctx, eventBus);
        apiManager.start();
    }

    @AfterEach
    void tearDown() {
        if (apiManager != null) {
            apiManager.stop();
        }
        if (ctx != null) {
            ctx.shutdown();
        }
    }

    @Test
    @DisplayName("GET /api/status 应返回 JSON")
    void shouldReturnStatusJson() throws Exception {
        HttpURLConnection conn = get("/api/status");
        assertEquals(200, conn.getResponseCode());
        assertEquals("application/json; charset=utf-8",
                conn.getHeaderField("Content-Type"));

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
        assertNotNull(result.get("data"));
    }

    @Test
    @DisplayName("GET /api/status 应包含 API 版本")
    void shouldContainApiVersion() throws Exception {
        HttpURLConnection conn = get("/api/status");
        String body = readBody(conn);
        assertTrue(body.contains("apiVersion"));
        assertTrue(body.contains("0.1"));
    }

    @Test
    @DisplayName("POST /api/command 应返回结果")
    void shouldExecuteCommand() throws Exception {
        CommandRequest req = new CommandRequest("default", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command", reqJson);
        assertEquals(200, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
    }

    @Test
    @DisplayName("POST /api/command 无效命令应返回错误")
    void shouldReturnErrorForInvalidCommand() throws Exception {
        CommandRequest req = new CommandRequest("default", "/invalid_cmd_xyz");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command", reqJson);
        // 未知命令可能返回 400
        int code = conn.getResponseCode();
        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        // 验证有错误信息
        assertNotNull(result.get("message"));
    }

    @Test
    @DisplayName("POST /api/command 缺少 command 字段应返回 400")
    void shouldReturn400ForMissingCommand() throws Exception {
        HttpURLConnection conn = post("/api/command", "{\"sessionId\":\"default\"}");
        assertEquals(400, conn.getResponseCode());
    }

    @Test
    @DisplayName("POST /api/command/stream 应返回 SSE 流")
    void shouldReturnSseStream() throws Exception {
        CommandRequest req = new CommandRequest("default", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command/stream", reqJson);
        assertEquals(200, conn.getResponseCode());
        assertEquals("text/event-stream; charset=utf-8",
                conn.getHeaderField("Content-Type"));

        String body = readBody(conn);
        assertTrue(body.contains("event: command_started"));
        assertTrue(body.contains("event: result"));
        assertTrue(body.contains("event: done"));
    }

    @Test
    @DisplayName("未实现端点应返回 not implemented")
    void shouldReturnNotImplemented() throws Exception {
        // Branches 端点目前返回 not implemented
        HttpURLConnection conn = get("/api/branches");
        assertEquals(200, conn.getResponseCode());  // 仍返回 200，但 success=false
        String body = readBody(conn);
        assertTrue(body.contains("not yet implemented") || body.contains("not_implemented"));
    }

    @Test
    @DisplayName("未知路径应返回 404")
    void shouldReturn404ForUnknownPath() throws Exception {
        HttpURLConnection conn = get("/api/nonexistent");
        // HttpServer 不匹配任何 context 时也返回 404
        assertTrue(conn.getResponseCode() >= 400);
    }

    @Test
    @DisplayName("GET 访问 POST 端点应返回 405")
    void shouldReturn405ForWrongMethod() throws Exception {
        HttpURLConnection conn = get("/api/command");
        assertEquals(405, conn.getResponseCode());
    }

    // ---- helpers ----

    private HttpURLConnection get(String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private HttpURLConnection post(String path, String jsonBody) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        try (var in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 尝试从错误流读取
            try (var errIn = conn.getErrorStream()) {
                if (errIn != null) {
                    return new String(errIn.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return "";
        }
    }
}
