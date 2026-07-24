package com.gsim.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ToolExecutionGuard 统一门禁测试")
class ToolExecutionGuardTest {

    static class AlwaysAvailTool implements AgentTool {
        @Override public String name() { return "always_tool"; }
        @Override public String description() { return "always"; }
        @Override public ToolResult execute(ToolCall c) { return ToolResult.ok("always_tool", List.of()); }
        @Override public boolean alwaysAvailable() { return true; }
    }

    static class GroupedTool implements AgentTool {
        private final String name;
        private final Set<String> groups;
        GroupedTool(String name, Set<String> groups) { this.name = name; this.groups = groups; }
        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public ToolResult execute(ToolCall c) { return ToolResult.ok(name, List.of()); }
        @Override public Set<String> toolGroups() { return groups; }
    }

    static class HiddenTool implements AgentTool {
        @Override public String name() { return "hidden_tool"; }
        @Override public String description() { return "hidden"; }
        @Override public ToolResult execute(ToolCall c) { return ToolResult.ok("hidden_tool", List.of()); }
        @Override public boolean mcpExposed() { return false; }
    }

    static class PlainTool implements AgentTool {
        private final String name;
        PlainTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return name; }
        @Override public ToolResult execute(ToolCall c) { return ToolResult.ok(name, List.of()); }
    }

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new AlwaysAvailTool());
        registry.register(new GroupedTool("world_tool", Set.of("world_info")));
        registry.register(new GroupedTool("dual_tool", Set.of("world_info", "search")));
        registry.register(new HiddenTool());
        registry.register(new PlainTool("plain_tool"));
    }

    @Nested
    @DisplayName("MCP surface")
    class McpSurface {

        @Test
        @DisplayName("mcpExposed=true 工具允许")
        void exposedToolAllowed() {
            var r = ToolExecutionGuard.checkMcp(registry, new ToolCall("always_tool", java.util.Map.of()));
            assertTrue(r.allowed());
        }

        @Test
        @DisplayName("mcpExposed=false 工具拒绝（MCP_TOOL_NOT_EXPOSED）")
        void hiddenToolDenied() {
            var r = ToolExecutionGuard.checkMcp(registry, new ToolCall("hidden_tool", java.util.Map.of()));
            assertFalse(r.allowed());
            assertEquals("MCP_TOOL_NOT_EXPOSED", r.errorCode());
        }

        @Test
        @DisplayName("不存在工具返回 UNKNOWN_TOOL")
        void unknownToolDenied() {
            var r = ToolExecutionGuard.checkMcp(registry, new ToolCall("nonexistent", java.util.Map.of()));
            assertFalse(r.allowed());
            assertEquals("UNKNOWN_TOOL", r.errorCode());
        }
    }

    @Nested
    @DisplayName("Agent surface")
    class AgentSurface {

        @Test
        @DisplayName("alwaysAvailable 工具跳过组检查直接允许")
        void alwaysAvailableAllowed() {
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("always_tool", java.util.Map.of()), Set.of(), null);
            assertTrue(r.allowed());
        }

        @Test
        @DisplayName("组已激活的工具允许")
        void activatedGroupAllowed() {
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("world_tool", java.util.Map.of()), Set.of("world_info"), null);
            assertTrue(r.allowed());
        }

        @Test
        @DisplayName("组未激活的工具拒绝（TOOL_GROUP_NOT_ACTIVE）")
        void notActivatedGroupDenied() {
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("world_tool", java.util.Map.of()), Set.of("import_doc"), null);
            assertFalse(r.allowed());
            assertEquals("TOOL_GROUP_NOT_ACTIVE", r.errorCode());
        }

        @Test
        @DisplayName("多组工具只要有一个组激活即允许")
        void multiGroupOneActivatedAllowed() {
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("dual_tool", java.util.Map.of()), Set.of("search"), null);
            assertTrue(r.allowed());
        }

        @Test
        @DisplayName("allowedGroups 限制生效 — 激活了组但不在 AgentConfig 授权范围")
        void allowedGroupsRestriction() {
            // 激活了 world_info，但 AgentConfig 只允许 import_doc
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("world_tool", java.util.Map.of()),
                    Set.of("world_info"), Set.of("import_doc"));
            assertFalse(r.allowed());
            assertEquals("TOOL_GROUP_NOT_ALLOWED", r.errorCode());
        }

        @Test
        @DisplayName("plain 工具（无组声明）总是允许")
        void plainToolAllowed() {
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("plain_tool", java.util.Map.of()), Set.of(), null);
            assertTrue(r.allowed());
        }

        @Test
        @DisplayName("不存在工具返回 UNKNOWN_TOOL")
        void unknownToolDenied() {
            var r = ToolExecutionGuard.checkAgent(
                    registry, new ToolCall("nonexistent", java.util.Map.of()), Set.of(), null);
            assertFalse(r.allowed());
            assertEquals("UNKNOWN_TOOL", r.errorCode());
        }
    }
}
