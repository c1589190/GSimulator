package com.gsim.agentlib.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * MCP JSON-RPC 2.0 协议层单元测试。
 *
 * <p>通过直接调用 {@link AbstractMcpServer} 的模板方法
 * 验证 JSON-RPC 2.0 协议的 initialize、tools/list、tools/call 行为，
 * 以及错误处理和 ID 回显。
 */
@DisplayName("MCP JSON-RPC 2.0 协议测试")
class McpProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 测试用工具注册表。 */
    static class TestRegistry implements McpToolRegistry {
        private final Map<String, ToolDef> tools = new LinkedHashMap<>();

        TestRegistry() {
            tools.put(
                    "test_echo",
                    new ToolDef(
                            "test_echo",
                            "Echo back the input",
                            """
                {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""));
            tools.put(
                    "test_add",
                    new ToolDef(
                            "test_add",
                            "Add two numbers",
                            """
                {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}"""));
            tools.put(
                    "test_no_args",
                    new ToolDef(
                            "test_no_args",
                            "Return a static greeting",
                            """
                {"type":"object","properties":{}}"""));
        }

        @Override
        public List<ToolDef> all() {
            return List.copyOf(tools.values());
        }

        @Override
        public String execute(String name, JsonNode args) throws Exception {
            return switch (name) {
                case "test_echo" -> {
                    String text = args.has("text") ? args.get("text").asText() : "";
                    yield "{\"echo\":\"" + text + "\"}";
                }
                case "test_add" -> {
                    int a = args.get("a").asInt();
                    int b = args.get("b").asInt();
                    yield "{\"sum\":" + (a + b) + "}";
                }
                case "test_no_args" -> "{\"greeting\":\"Hello, MCP!\"}";
                default -> throw new UnknownToolException(name);
            };
        }
    }

    /** 测试用的 AbstractMcpServer 子类。 */
    static class TestServer extends AbstractMcpServer {
        private final TestRegistry registry;

        TestServer(TestRegistry registry) {
            super();
            this.registry = registry;
        }

        @Override
        protected String getServerName() {
            return "TestServer";
        }

        @Override
        protected String getServerVersion() {
            return "1.0.0-test";
        }

        @Override
        protected List<ToolDef> getAllTools() {
            return registry.all();
        }

        @Override
        public String executeTool(String name, JsonNode args) throws Exception {
            return registry.execute(name, args);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Nested 测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("initialize 方法")
    class InitializeTests {

        @Test
        @DisplayName("返回正确的 protocolVersion 和 capabilities")
        void returnsProtocolVersionAndCapabilities() {
            TestServer s = new TestServer(new TestRegistry());
            JsonNode result = s.handleInitialize(MAPPER.createObjectNode());

            assertEquals("2024-11-05", result.get("protocolVersion").asText());
            assertTrue(result.has("capabilities"));
            assertTrue(result.get("capabilities").has("tools"));
            assertTrue(result.has("serverInfo"));
            assertEquals("TestServer", result.get("serverInfo").get("name").asText());
            assertEquals("1.0.0-test", result.get("serverInfo").get("version").asText());
        }
    }

    @Nested
    @DisplayName("tools/list 方法")
    class ToolsListTests {

        @Test
        @DisplayName("返回所有注册的工具及 schema")
        void returnsAllRegisteredTools() {
            TestServer s = new TestServer(new TestRegistry());
            JsonNode result = s.handleToolsList();

            assertTrue(result.has("tools"));
            assertEquals(3, result.get("tools").size());

            for (JsonNode tool : result.get("tools")) {
                assertTrue(tool.has("name"));
                assertTrue(tool.has("description"));
                assertTrue(tool.has("inputSchema"));
            }
        }

        @Test
        @DisplayName("返回空列表当未注册工具时")
        void returnsEmptyListWhenNoTools() {
            TestServer s = new TestServer(new TestRegistry() {
                @Override
                public List<ToolDef> all() {
                    return List.of();
                }
            });
            JsonNode result = s.handleToolsList();
            assertEquals(0, result.get("tools").size());
        }
    }

    @Nested
    @DisplayName("tools/call 方法")
    class ToolsCallTests {

        @Test
        @DisplayName("执行 test_echo 返回正确结果")
        void executesEchoTool() throws Exception {
            TestServer s = new TestServer(new TestRegistry());
            var params = MAPPER.createObjectNode();
            params.put("name", "test_echo");
            var args = MAPPER.createObjectNode();
            args.put("text", "hello world");
            params.set("arguments", args);

            var req = MAPPER.createObjectNode();
            req.set("params", params);

            JsonNode result = s.handleToolCall(req);

            assertTrue(result.has("content"));
            assertEquals(1, result.get("content").size());
            assertEquals("text", result.get("content").get(0).get("type").asText());
            assertEquals(
                    "{\"echo\":\"hello world\"}",
                    result.get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("执行 test_add 返回正确结果")
        void executesAddTool() throws Exception {
            TestServer s = new TestServer(new TestRegistry());
            var params = MAPPER.createObjectNode();
            params.put("name", "test_add");
            var args = MAPPER.createObjectNode();
            args.put("a", 3);
            args.put("b", 4);
            params.set("arguments", args);

            var req = MAPPER.createObjectNode();
            req.set("params", params);

            JsonNode result = s.handleToolCall(req);
            assertEquals("{\"sum\":7}", result.get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("执行 test_no_args 返回静态问候信息")
        void executesNoArgsTool() throws Exception {
            TestServer s = new TestServer(new TestRegistry());
            var params = MAPPER.createObjectNode();
            params.put("name", "test_no_args");
            params.set("arguments", MAPPER.createObjectNode());

            var req = MAPPER.createObjectNode();
            req.set("params", params);

            JsonNode result = s.handleToolCall(req);
            assertEquals(
                    "{\"greeting\":\"Hello, MCP!\"}",
                    result.get("content").get(0).get("text").asText());
        }

        @Test
        @DisplayName("参数缺失时抛出异常")
        void throwsWhenMissingParams() {
            TestServer s = new TestServer(new TestRegistry());
            var req = MAPPER.createObjectNode();
            assertThrows(IllegalArgumentException.class, () -> s.handleToolCall(req));
        }
    }

    @Nested
    @DisplayName("JSON-RPC 序列化")
    class JsonRpcSerializationTests {

        @Test
        @DisplayName("成功响应包含 jsonrpc, id, result 字段")
        void successResponseHasCorrectFields() throws Exception {
            var result = MAPPER.createObjectNode();
            result.put("ok", true);

            String response = AbstractMcpServer.jsonRpc("\"abc-123\"", result);
            JsonNode node = MAPPER.readTree(response);

            assertEquals("2.0", node.get("jsonrpc").asText());
            assertEquals("abc-123", node.get("id").asText());
            assertTrue(node.get("result").get("ok").asBoolean());
        }

        @Test
        @DisplayName("数字 ID 被正确回显")
        void numericIdRoundTrips() throws Exception {
            var result = MAPPER.createObjectNode();
            result.put("x", 1);

            String response = AbstractMcpServer.jsonRpc("42", result);
            JsonNode node = MAPPER.readTree(response);

            assertEquals(42, node.get("id").asInt());
        }

        @Test
        @DisplayName("字符串 ID 被正确回显（不再只用 Long.parseLong）")
        void stringIdRoundTrips() throws Exception {
            var result = MAPPER.createObjectNode();
            result.put("x", 1);

            String response = AbstractMcpServer.jsonRpc("\"req-abc-123\"", result);
            JsonNode node = MAPPER.readTree(response);

            assertEquals("req-abc-123", node.get("id").asText());
        }

        @Test
        @DisplayName("null ID 被正确保留")
        void nullIdPreserved() throws Exception {
            var result = MAPPER.createObjectNode();

            String response = AbstractMcpServer.jsonRpc(null, result);
            JsonNode node = MAPPER.readTree(response);

            assertTrue(node.get("id").isNull());
        }

        @Test
        @DisplayName("错误响应包含 code 和 message")
        void errorResponseHasCodeAndMessage() throws Exception {
            String response = AbstractMcpServer.jsonRpcError("\"1\"", -32601, "Method not found: bad");
            JsonNode node = MAPPER.readTree(response);

            assertEquals("2.0", node.get("jsonrpc").asText());
            assertEquals(-32601, node.get("error").get("code").asInt());
            assertEquals(
                    "Method not found: bad", node.get("error").get("message").asText());
        }

        @Test
        @DisplayName("未知方法应返回 -32601 错误码")
        void unknownMethodReturnsCorrectCode() throws Exception {
            String response = AbstractMcpServer.jsonRpcError("1", -32601, "Method not found: foo");
            JsonNode node = MAPPER.readTree(response);

            assertEquals(-32601, node.get("error").get("code").asInt());
        }
    }

    @Nested
    @DisplayName("ToolDef 顶层记录")
    class ToolDefTests {

        @Test
        @DisplayName("ToolDef 可被创建并访问字段")
        void toolDefCreation() {
            ToolDef td = new ToolDef("my_tool", "My description", "{\"type\":\"object\"}");

            assertEquals("my_tool", td.name());
            assertEquals("My description", td.description());
            assertEquals("{\"type\":\"object\"}", td.schema());
        }
    }
}
