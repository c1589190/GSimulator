package com.gsim.agentlib.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.agentlib.tool.ToolResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link McpHttpServer}.
 * Starts a real HTTP server on a random port and verifies endpoints.
 */
@DisplayName("McpHttpServer HTTP MCP 测试")
class McpHttpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static class EchoTool implements AgentTool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echo tool";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            String msg = call.param("message", "hello");
            return ToolResult.ok("echo", List.of(new ToolResult.Item("echo", "echo", msg, 1.0)));
        }
    }

    static class HiddenTool implements AgentTool {
        @Override
        public String name() {
            return "internal";
        }

        @Override
        public String description() {
            return "Internal tool";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok("internal", List.of());
        }

        @Override
        public boolean mcpExposed() {
            return false;
        }
    }

    private McpHttpServer server;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new HiddenTool());

        // Use port 0 for OS-assigned port
        server = new McpHttpServer(registry, 0);
        server.start();
        port = server.getPort();

        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ── Health ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /health 返回 UP 状态")
    void healthReturnsUp() throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .GET()
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"UP\""));
        assertTrue(resp.body().contains("\"version\""));
        assertTrue(resp.body().contains("\"tools\""));
    }

    @Test
    @DisplayName("POST /health 返回 405")
    void healthPostReturns405() throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    // ── Initialize ──────────────────────────────────────────────

    @Test
    @DisplayName("initialize 返回 protocolVersion 和 capabilities")
    void initializeReturnsProtocolInfo() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":"1","method":"initialize","params":{}}""";
        var resp = postMcp(body);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"protocolVersion\""));
        assertTrue(resp.body().contains("\"2024-11-05\""));
        assertTrue(resp.body().contains("\"capabilities\""));
        assertTrue(resp.body().contains("\"serverInfo\""));
    }

    // ── tools/list ──────────────────────────────────────────────

    @Test
    @DisplayName("tools/list 返回 exposed 工具，过滤 hidden 工具")
    void toolsListFiltersHidden() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":"2","method":"tools/list","params":{}}""";
        var resp = postMcp(body);
        assertEquals(200, resp.statusCode());
        // Echo tool (exposed) should appear
        assertTrue(resp.body().contains("gsim_echo"));
        // Hidden tool should NOT appear
        assertFalse(resp.body().contains("internal"));
    }

    // ── tools/call ──────────────────────────────────────────────

    @Test
    @DisplayName("tools/call 执行 exposed 工具成功")
    void toolsCallExposedTool() throws Exception {
        String body =
                """
                {"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"gsim_echo","arguments":{"message":"hello-world"}}}""";
        var resp = postMcp(body);
        assertEquals(200, resp.statusCode());
        // Should contain the echo result
        assertTrue(resp.body().contains("hello-world"));
    }

    @Test
    @DisplayName("tools/call hidden 工具返回错误")
    void toolsCallHiddenToolFails() throws Exception {
        String body =
                """
                {"jsonrpc":"2.0","id":"4","method":"tools/call","params":{"name":"gsim_internal","arguments":{}}}""";
        var resp = postMcp(body);
        assertEquals(200, resp.statusCode());
        // Should contain error about unknown tool
        assertTrue(resp.body().contains("\"error\""));
    }

    @Test
    @DisplayName("tools/call 未知方法返回 METHOD_NOT_FOUND")
    void unknownMethodReturnsError() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":"5","method":"bad/method","params":{}}""";
        var resp = postMcp(body);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("Method not found"));
    }

    // ── JSON-RPC 格式 ───────────────────────────────────────────

    @Test
    @DisplayName("响应包含 jsonrpc 2.0 版本标识")
    void responseContainsJsonRpcVersion() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":"6","method":"initialize","params":{}}""";
        var resp = postMcp(body);
        assertTrue(resp.body().contains("\"jsonrpc\":\"2.0\""));
    }

    @Test
    @DisplayName("ID 被正确回显")
    void idIsRoundTripped() throws Exception {
        String body =
                """
                {"jsonrpc":"2.0","id":"my-custom-id-123","method":"initialize","params":{}}""";
        var resp = postMcp(body);
        assertTrue(resp.body().contains("\"my-custom-id-123\""));
    }

    // ── Helper ──────────────────────────────────────────────────

    private HttpResponse<String> postMcp(String body) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
