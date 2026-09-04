package com.gsim.agentsmanager.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AgentTool#mcpExposed()} filtering in {@link ToolRegistryMcpAdapter}.
 *
 * <p>Verifies that tools with {@code mcpExposed() == false} are:
 * <ul>
 *   <li>Not visible in MCP {@code tools/list}</li>
 *   <li>Not callable via MCP {@code tools/call} (even by constructing the name directly)</li>
 *   <li>Default {@code mcpExposed() == true} tools remain visible and callable</li>
 * </ul>
 */
@DisplayName("mcpExposed 过滤测试")
class McpExposedFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A tool that is MCP-exposed (default). */
    static class ExposedTool implements AgentTool {
        @Override
        public String name() {
            return "exposed_tool";
        }

        @Override
        public String description() {
            return "An MCP-exposed tool";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok("exposed_tool", List.of(new ToolResult.Item("result", "exposed_tool", "ok", 1.0)));
        }
    }

    /** A tool that is NOT MCP-exposed. */
    static class HiddenTool implements AgentTool {
        @Override
        public String name() {
            return "hidden_tool";
        }

        @Override
        public String description() {
            return "A hidden internal tool";
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok("hidden_tool", List.of(new ToolResult.Item("result", "hidden_tool", "ok", 1.0)));
        }

        @Override
        public boolean mcpExposed() {
            return false;
        }
    }

    private ToolRegistry registry;
    private ToolRegistryMcpAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new ExposedTool());
        registry.register(new HiddenTool());
        adapter = new ToolRegistryMcpAdapter(registry);
    }

    @Nested
    @DisplayName("tools/list 过滤")
    class ToolsListFiltering {

        @Test
        @DisplayName("mcpExposed=true 的工具出现在列表中")
        void exposedToolAppearsInList() {
            List<ToolDef> tools = adapter.all();

            boolean found = tools.stream().anyMatch(t -> t.name().contains("exposed_tool"));
            assertTrue(found, "exposed_tool should appear in MCP tools/list");
        }

        @Test
        @DisplayName("mcpExposed=false 的工具不出现在列表中")
        void hiddenToolNotInList() {
            List<ToolDef> tools = adapter.all();

            boolean found = tools.stream().anyMatch(t -> t.name().contains("hidden_tool"));
            assertFalse(found, "hidden_tool must NOT appear in MCP tools/list");
        }

        @Test
        @DisplayName("过滤后列表只包含 exposed 工具")
        void listOnlyContainsExposedTools() {
            List<ToolDef> tools = adapter.all();

            // Should have only the exposed tool, not the hidden one
            assertEquals(1, tools.size(), "Only exposed tools should be listed");
        }
    }

    @Nested
    @DisplayName("tools/call 守卫")
    class ToolsCallGuard {

        @Test
        @DisplayName("mcpExposed=true 的工具可正常执行")
        void exposedToolCanExecute() throws Exception {
            ObjectNode args = MAPPER.createObjectNode();
            String result = adapter.execute("gsim_exposed_tool", args);

            assertTrue(result.contains("\"success\":true"), "exposed_tool should execute successfully");
        }

        @Test
        @DisplayName("mcpExposed=false 的工具抛出 UnknownToolException")
        void hiddenToolThrowsUnknownTool() {
            ObjectNode args = MAPPER.createObjectNode();

            assertThrows(
                    UnknownToolException.class,
                    () -> {
                        adapter.execute("gsim_hidden_tool", args);
                    },
                    "Calling hidden tool should throw UnknownToolException");
        }

        @Test
        @DisplayName("mcpExposed=false 的工具即使用 registry 原始名称调用也不可访问")
        void hiddenToolWithRegistryNameThrowsUnknownTool() {
            ObjectNode args = MAPPER.createObjectNode();

            assertThrows(
                    UnknownToolException.class,
                    () -> {
                        adapter.execute("hidden_tool", args);
                    },
                    "Calling hidden tool with raw registry name should also fail");
        }

        @Test
        @DisplayName("不存在的工具抛出 UnknownToolException（与 hidden 工具行为一致）")
        void unknownToolThrowsSameAsHidden() {
            ObjectNode args = MAPPER.createObjectNode();

            assertThrows(
                    UnknownToolException.class,
                    () -> {
                        adapter.execute("gsim_nonexistent_tool", args);
                    },
                    "Unknown tool should throw UnknownToolException");
        }
    }

    @Nested
    @DisplayName("默认 mcpExposed 兼容性")
    class DefaultMcpExposedCompatibility {

        @Test
        @DisplayName("不覆盖 mcpExposed 的工具默认为 true（暴露）")
        void defaultIsExposed() {
            AgentTool tool = new ExposedTool();
            assertTrue(tool.mcpExposed(), "Default mcpExposed() should return true for backward compatibility");
        }

        @Test
        @DisplayName("显式设置为 false 的工具不暴露")
        void explicitFalseIsHidden() {
            AgentTool tool = new HiddenTool();
            assertFalse(tool.mcpExposed(), "Explicitly returning false should hide the tool");
        }
    }
}
