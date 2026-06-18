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
 * CommandApiHandler Session 隔离测试。
 */
@DisplayName("Command API Session")
class CommandApiHandlerSessionTest {

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
    @DisplayName("旧 /api/command 仍可用")
    void oldCommandEndpointShouldStillWork() throws Exception {
        CommandRequest req = new CommandRequest("s1", "/status");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command", reqJson);
        assertEquals(200, conn.getResponseCode());

        String body = readBody(conn);
        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(true, result.get("success"));
    }

    @Test
    @DisplayName("两个 session 使用不同 sessionId 不共享 InteractionContext")
    void twoSessionsShouldHaveSeparateContexts() throws Exception {
        CommandRequest req1 = new CommandRequest("session-a", "/status");
        CommandRequest req2 = new CommandRequest("session-b", "/status");

        HttpURLConnection conn1 = post("/api/command", JsonUtils.toJson(req1));
        HttpURLConnection conn2 = post("/api/command", JsonUtils.toJson(req2));

        assertEquals(200, conn1.getResponseCode());
        assertEquals(200, conn2.getResponseCode());

        // 验证两个 session 的 context 不同
        SessionManager sm = apiManager.getSessionManager();
        var s1 = sm.getSession("session-a");
        var s2 = sm.getSession("session-b");
        assertNotNull(s1);
        assertNotNull(s2);
        assertNotSame(s1.getContext(), s2.getContext());
    }

    @Test
    @DisplayName("sessionId default 应自动创建")
    void defaultSessionShouldBeAutoCreated() throws Exception {
        CommandRequest req = new CommandRequest("default", "/status");
        HttpURLConnection conn = post("/api/command", JsonUtils.toJson(req));

        assertEquals(200, conn.getResponseCode());

        SessionManager sm = apiManager.getSessionManager();
        assertNotNull(sm.getSession("default"));
    }

    @Test
    @DisplayName("无效命令应返回错误")
    void invalidCommandShouldReturnError() throws Exception {
        CommandRequest req = new CommandRequest("s1", "/invalid_cmd_xyz");
        String reqJson = JsonUtils.toJson(req);

        HttpURLConnection conn = post("/api/command", reqJson);
        String body = readBody(conn);

        Map<?, ?> result = JsonUtils.fromJson(body, Map.class);
        assertEquals(false, result.get("success"));
        assertNotNull(result.get("error"));
    }

    // ---- helpers ----

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
