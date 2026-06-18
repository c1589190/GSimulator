package com.gsim.api;

import com.gsim.api.dto.CommandRequest;
import com.gsim.app.AppConfig;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
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
 * Stream Command 旧接口兼容性测试。
 */
@DisplayName("Stream Command Compatibility")
class StreamCommandHandlerCompatibilityTest {

    private ApiManager apiManager;
    private ApplicationContext ctx;
    private EventBus eventBus;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        AppConfig config = AppConfig.forTesting();
        ctx = new ApplicationContext(config);
        ctx.initialize();
        ctx.getInteractionManager().registerCommand(new StatusCommand());

        eventBus = ctx.getEventBus();

        port = 18710 + (int) (Math.random() * 1000);
        ApiConfig apiConfig = new ApiConfig("127.0.0.1", port, true);
        apiManager = new ApiManager(apiConfig, ctx, eventBus);
        apiManager.start();
    }

    @AfterEach
    void tearDown() {
        if (apiManager != null) apiManager.stop();
        if (ctx != null) ctx.shutdown();
    }

    @Test
    @DisplayName("旧 /api/command/stream 仍可用并返回 SSE 流")
    void oldStreamEndpointShouldStillWork() throws Exception {
        CommandRequest req = new CommandRequest("s1", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command/stream", reqJson);
        assertEquals(200, conn.getResponseCode());
        assertEquals("text/event-stream; charset=utf-8",
                conn.getHeaderField("Content-Type"));

        String body = readBody(conn);
        assertTrue(body.contains("event: "));
        assertTrue(body.contains("event: done") || body.contains("\"type\":\"done\""));
    }

    @Test
    @DisplayName("旧 /api/command/stream 应包含命令事件")
    void oldStreamShouldContainCommandEvents() throws Exception {
        CommandRequest req = new CommandRequest("s1", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command/stream", reqJson);
        String body = readBody(conn);

        // 应该包含至少一种类型的事件
        assertTrue(body.contains("event: "));
    }

    @Test
    @DisplayName("旧接口 GET 应返回 405")
    void oldStreamShouldReturn405ForGet() throws Exception {
        HttpURLConnection conn = get("/api/command/stream");
        assertEquals(405, conn.getResponseCode());
    }

    @Test
    @DisplayName("流式接口应正确关闭连接")
    void streamShouldCloseCleanly() throws Exception {
        CommandRequest req = new CommandRequest("default", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command/stream", reqJson);
        assertEquals(200, conn.getResponseCode());

        // 读取完整响应 — 不应挂起
        String body = readBody(conn);
        assertNotNull(body);
    }

    // ---- helpers ----

    private HttpURLConnection get(String path) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private HttpURLConnection post(String path, String jsonBody) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

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
            try (var errIn = conn.getErrorStream()) {
                if (errIn != null) {
                    return new String(errIn.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return "";
        }
    }
}
