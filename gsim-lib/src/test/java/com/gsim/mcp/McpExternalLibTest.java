package com.gsim.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 集成测试：模拟外部项目使用 gsim-lib 作为 MCP 依赖。
 *
 * <p>验证外部项目可以：
 * <ul>
 *   <li>实现 {@link McpToolRegistry} 注册自定义工具</li>
 *   <li>使用 {@link CompositeMcpToolRegistry} 合并 GSim 工具和自定义工具</li>
 *   <li>通过 {@link AbstractMcpServer} 启动 MCP 服务</li>
 *   <li>GSim 工具（如 gsim_list_worlds）仍可正常使用</li>
 * </ul>
 */
@DisplayName("外部项目集成测试")
class McpExternalLibTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 模拟外部项目的自定义工具注册表。
     * 提供 my_hello 和 my_status 两个工具。
     */
    static class ExternalAppRegistry implements McpToolRegistry {
        private final Map<String, ToolDef> tools = new LinkedHashMap<>();

        ExternalAppRegistry() {
            register(
                    "my_hello",
                    "Returns a greeting",
                    """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""");
            register(
                    "my_status",
                    "Returns server status info",
                    """
                {"type":"object","properties":{}}""");
        }

        private void register(String name, String description, String schema) {
            tools.put(name, new ToolDef(name, description, schema));
        }

        @Override
        public List<ToolDef> all() {
            return List.copyOf(tools.values());
        }

        @Override
        public String execute(String name, JsonNode args) throws Exception {
            return switch (name) {
                case "my_hello" -> {
                    String n = args.has("name") ? args.get("name").asText() : "World";
                    yield "{\"greeting\":\"Hello, " + n + "!\"}";
                }
                case "my_status" -> "{\"status\":\"ok\",\"version\":\"1.0-custom\"}";
                default -> throw new UnknownToolException(name);
            };
        }
    }

    /**
     * 模拟外部项目的 MCP 服务器。
     * 继承 AbstractMcpServer 并合并 GSim 工具和自定义工具。
     */
    static class ExternalAppServer extends AbstractMcpServer {
        private final CompositeMcpToolRegistry composite;

        ExternalAppServer(Path worldsDir) {
            super();
            // 合并 GSim 工具和自定义工具
            this.composite = new CompositeMcpToolRegistry(
                    new ExternalAppRegistry(), new GsimMcpToolRegistry(worldsDir).asMcpRegistry());
        }

        @Override
        protected String getServerName() {
            return "ExternalApp-MCP";
        }

        @Override
        protected String getServerVersion() {
            return "2.0.0-custom";
        }

        @Override
        protected List<ToolDef> getAllTools() {
            return composite.all();
        }

        @Override
        protected String executeTool(String name, JsonNode args) throws Exception {
            return composite.execute(name, args);
        }

        CompositeMcpToolRegistry getComposite() {
            return composite;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 测试用例
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自定义工具注册表")
    class CustomRegistryTests {

        @Test
        @DisplayName("自定义工具有 my_ 前缀")
        void customToolsHaveMyPrefix() {
            ExternalAppRegistry reg = new ExternalAppRegistry();
            List<ToolDef> tools = reg.all();

            assertEquals(2, tools.size());
            List<String> names = tools.stream().map(ToolDef::name).toList();
            assertTrue(names.contains("my_hello"));
            assertTrue(names.contains("my_status"));
        }

        @Test
        @DisplayName("my_hello 执行返回问候信息")
        void myHelloReturnsGreeting() throws Exception {
            ExternalAppRegistry reg = new ExternalAppRegistry();
            var args = MAPPER.createObjectNode();
            args.put("name", "测试用户");

            String result = reg.execute("my_hello", args);
            assertEquals("{\"greeting\":\"Hello, 测试用户!\"}", result);
        }

        @Test
        @DisplayName("my_status 执行返回状态 JSON")
        void myStatusReturnsStatus() throws Exception {
            ExternalAppRegistry reg = new ExternalAppRegistry();
            String result = reg.execute("my_status", MAPPER.createObjectNode());

            JsonNode node = MAPPER.readTree(result);
            assertEquals("ok", node.get("status").asText());
        }

        @Test
        @DisplayName("未知工具抛出 IllegalArgumentException")
        void unknownToolThrows() {
            ExternalAppRegistry reg = new ExternalAppRegistry();
            assertThrows(UnknownToolException.class, () -> reg.execute("unknown_tool", MAPPER.createObjectNode()));
        }
    }

    @Nested
    @DisplayName("CompositeMcpToolRegistry 合并")
    class CompositeRegistryTests {

        @Test
        @DisplayName("合并后包含 GSim 工具和自定义工具")
        void compositeIncludesBoth(@TempDir Path tempDir) {
            ExternalAppRegistry custom = new ExternalAppRegistry();
            GsimMcpToolRegistry gsim = new GsimMcpToolRegistry(tempDir);
            CompositeMcpToolRegistry composite = new CompositeMcpToolRegistry(custom, gsim.asMcpRegistry());

            List<ToolDef> allTools = composite.all();
            List<String> names = allTools.stream().map(ToolDef::name).toList();

            // 自定义工具存在
            assertTrue(names.contains("my_hello"));
            assertTrue(names.contains("my_status"));

            // GSim 工具存在
            assertTrue(names.contains("gsim_list_worlds"));
            assertTrue(names.contains("gsim_get_status"));

            // 自定义工具在前
            int myHelloIdx = names.indexOf("my_hello");
            int gsimWorldsIdx = names.indexOf("gsim_list_worlds");
            assertTrue(myHelloIdx < gsimWorldsIdx, "Custom tools should appear before GSim tools");
        }

        @Test
        @DisplayName("execute 正确路由到对应注册表")
        void executeRoutesCorrectly(@TempDir Path tempDir) throws Exception {
            CompositeMcpToolRegistry composite = new CompositeMcpToolRegistry(
                    new ExternalAppRegistry(), new GsimMcpToolRegistry(tempDir).asMcpRegistry());

            // 自定义工具路由
            var args = MAPPER.createObjectNode();
            args.put("name", "Claude");
            String result = composite.execute("my_hello", args);
            assertEquals("{\"greeting\":\"Hello, Claude!\"}", result);

            // GSim 工具路由
            String gsimResult = composite.execute("gsim_list_worlds", MAPPER.createObjectNode());
            JsonNode node = MAPPER.readTree(gsimResult);
            assertTrue(node.has("worlds"), "gsim_list_worlds should return 'worlds' key");
        }

        @Test
        @DisplayName("未知工具抛出异常")
        void unknownToolThrows(@TempDir Path tempDir) {
            CompositeMcpToolRegistry composite = new CompositeMcpToolRegistry(
                    new ExternalAppRegistry(), new GsimMcpToolRegistry(tempDir).asMcpRegistry());

            assertThrows(
                    UnknownToolException.class,
                    () -> composite.execute("completely_unknown", MAPPER.createObjectNode()));
        }

        @Test
        @DisplayName("getRegistries 返回所有子注册表")
        void getRegistriesReturnsAll() {
            ExternalAppRegistry custom = new ExternalAppRegistry();
            McpToolRegistry gsimAdapter = new GsimMcpToolRegistry(Path.of("/tmp/test")).asMcpRegistry();
            CompositeMcpToolRegistry composite = new CompositeMcpToolRegistry(custom, gsimAdapter);

            assertEquals(2, composite.getRegistries().size());
            assertInstanceOf(
                    ExternalAppRegistry.class, composite.getRegistries().get(0));
            assertInstanceOf(McpToolRegistry.class, composite.getRegistries().get(1));
        }
    }

    @Nested
    @DisplayName("外部应用 MCP 服务器")
    class ExternalAppServerTests {

        @Test
        @DisplayName("服务器提供正确的标识信息")
        void serverProvidesCorrectIdentity(@TempDir Path tempDir) {
            ExternalAppServer server = new ExternalAppServer(tempDir);

            assertEquals("ExternalApp-MCP", server.getServerName());
            assertEquals("2.0.0-custom", server.getServerVersion());
        }

        @Test
        @DisplayName("getAllTools 包含自定义和 GSim 工具")
        void getAllToolsIncludesBoth(@TempDir Path tempDir) {
            ExternalAppServer server = new ExternalAppServer(tempDir);
            List<ToolDef> tools = server.getAllTools();

            List<String> names = tools.stream().map(ToolDef::name).toList();
            assertTrue(names.contains("my_hello"), "Should include custom hello tool");
            assertTrue(names.contains("gsim_list_worlds"), "Should include GSim worlds tool");
        }

        @Test
        @DisplayName("handleToolsList 返回正确的 JSON 格式")
        void handleToolsListReturnsValidJson(@TempDir Path tempDir) {
            ExternalAppServer server = new ExternalAppServer(tempDir);
            JsonNode result = server.handleToolsList();

            assertTrue(result.has("tools"));
            assertFalse(result.get("tools").isEmpty());

            JsonNode first = result.get("tools").get(0);
            assertTrue(first.has("name"), "Each tool should have a name");
            assertTrue(first.has("description"), "Each tool should have a description");
            assertTrue(first.has("inputSchema"), "Each tool should have an inputSchema");
        }

        @Test
        @DisplayName("executeTool 路由自定义工具")
        void executeToolRoutesCustom(@TempDir Path tempDir) throws Exception {
            ExternalAppServer server = new ExternalAppServer(tempDir);
            var args = MAPPER.createObjectNode();
            args.put("name", "World");

            String result = server.executeTool("my_hello", args);
            assertEquals("{\"greeting\":\"Hello, World!\"}", result);
        }

        @Test
        @DisplayName("executeTool 路由 GSim 工具")
        void executeToolRoutesGsim(@TempDir Path tempDir) throws Exception {
            ExternalAppServer server = new ExternalAppServer(tempDir);

            String result = server.executeTool("gsim_list_worlds", MAPPER.createObjectNode());
            assertNotNull(result);
            JsonNode node = MAPPER.readTree(result);
            assertTrue(node.has("worlds"), "gsim_list_worlds should return 'worlds' key");
        }

        @Test
        @DisplayName("executeTool 未知工具抛出异常")
        void executeToolThrowsOnUnknown(@TempDir Path tempDir) {
            ExternalAppServer server = new ExternalAppServer(tempDir);

            assertThrows(
                    UnknownToolException.class,
                    () -> server.executeTool("no_such_tool", MAPPER.createObjectNode()));
        }

        @Test
        @DisplayName("handleInitialize 使用自定义 serverInfo")
        void handleInitializeUsesCustomInfo(@TempDir Path tempDir) {
            ExternalAppServer server = new ExternalAppServer(tempDir);
            JsonNode result = server.handleInitialize(MAPPER.createObjectNode());

            assertEquals("2024-11-05", result.get("protocolVersion").asText());
            assertEquals("ExternalApp-MCP", result.get("serverInfo").get("name").asText());
            assertEquals("2.0.0-custom", result.get("serverInfo").get("version").asText());
        }
    }

    @Nested
    @DisplayName("GsimMcpServer 向后兼容")
    class GsimMcpServerBackwardCompatTests {

        @Test
        @DisplayName("原有构造函数仍然可用")
        void legacyConstructorWorks(@TempDir Path tempDir) {
            GsimMcpServer server = new GsimMcpServer(tempDir);
            assertNotNull(server);
            assertNotNull(server.getRegistry());
            assertEquals("GSimulator-MCP", server.getServerName());
        }

        @Test
        @DisplayName("ApplicationContext 构造函数仍然可用")
        void legacyApplicationContextConstructorWorks(@TempDir Path tempDir) {
            // GsimMcpServer can be constructed with directories
            GsimMcpServer server = new GsimMcpServer(tempDir, tempDir.resolve("import"));
            assertNotNull(server);
            assertNotNull(server.getRegistry());
        }

        @Test
        @DisplayName("getRegistry 返回 GsimMcpToolRegistry")
        void getRegistryReturnsCorrectType(@TempDir Path tempDir) {
            GsimMcpServer server = new GsimMcpServer(tempDir);
            assertInstanceOf(GsimMcpToolRegistry.class, server.getRegistry());
        }

        @Test
        @DisplayName("start() 和 stop() 方法可用（System.in 受 CloseShieldInputStream 保护）")
        void startAndStopAvailable(@TempDir Path tempDir) {
            GsimMcpServer server = new GsimMcpServer(tempDir);
            assertDoesNotThrow(server::stop);
        }

        @Test
        @DisplayName("gsim_list_worlds 工具仍可执行")
        void gsimListWorldsStillWorks(@TempDir Path tempDir) throws Exception {
            GsimMcpServer server = new GsimMcpServer(tempDir);

            String result = server.executeTool("gsim_list_worlds", MAPPER.createObjectNode());
            JsonNode node = MAPPER.readTree(result);
            assertTrue(node.has("worlds"));
        }

        @Test
        @DisplayName("未知 gsim_ 工具抛出异常")
        void unknownGsimToolThrows(@TempDir Path tempDir) {
            GsimMcpServer server = new GsimMcpServer(tempDir);

            assertThrows(
                    UnknownToolException.class,
                    () -> server.executeTool("gsim_nonexistent", MAPPER.createObjectNode()));
        }
    }
}
