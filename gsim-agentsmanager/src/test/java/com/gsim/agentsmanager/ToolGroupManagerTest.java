package com.gsim.agentsmanager;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ToolGroupManager 单元测试 — 验证工具组激活和可见性计算。
 * 使用自声明（alwaysAvailable / toolGroups）+ 旧静态映射兼容。
 */
@DisplayName("工具组管理器")
class ToolGroupManagerTest {

    private ToolRegistry registry;
    private ToolGroupManager mgr;

    /** A tool that declares itself as always available. */
    static class AlwaysAvailableTool implements AgentTool {
        private final String name;

        AlwaysAvailableTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name, List.of());
        }

        @Override
        public boolean alwaysAvailable() {
            return true;
        }
    }

    /** A tool that belongs to a specific group. */
    static class GroupedTool implements AgentTool {
        private final String name;
        private final Set<String> groups;

        GroupedTool(String name, String group) {
            this.name = name;
            this.groups = Set.of(group);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name, List.of());
        }

        @Override
        public Set<String> toolGroups() {
            return groups;
        }
    }

    /** A tool with no group declaration (legacy fallback). */
    static class PlainTool implements AgentTool {
        private final String name;

        PlainTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name, List.of());
        }
    }

    @BeforeEach
    void setUp() {
        mgr = new ToolGroupManager();
        registry = new ToolRegistry();
        // Register self-declared always-available tools
        registry.register(new AlwaysAvailableTool("finish_action"));
        registry.register(new AlwaysAvailableTool("activate_tool_groups"));
        // Register group-declared tools
        registry.register(new GroupedTool("query_node", "world_info"));
        registry.register(new GroupedTool("query_element", "world_info"));
        registry.register(new GroupedTool("write_element", "world_info"));
        registry.register(new GroupedTool("import_document_list", "import_doc"));
        registry.register(new GroupedTool("import_document_search", "import_doc"));
        // Register a plain tool (legacy/compat)
        registry.register(new PlainTool("custom_test_tool"));
    }

    @Test
    @DisplayName("初始状态无激活组")
    void initialNoActiveGroups() {
        assertFalse(mgr.hasActiveGroups());
        assertTrue(mgr.activeGroupKeys().isEmpty());
    }

    @Test
    @DisplayName("初始状态包含 alwaysAvailable 和 legacy 工具")
    void computeAllowedToolsInitial() {
        Set<String> tools = mgr.computeAllowedTools(registry);
        assertTrue(tools.contains("finish_action"));
        assertTrue(tools.contains("activate_tool_groups"));
        // custom_test_tool is unknown → always allowed
        assertTrue(tools.contains("custom_test_tool"));
        // grouped tools should NOT be present initially
        assertFalse(tools.contains("query_node"));
    }

    @Test
    @DisplayName("activate 单个组后包含 alwaysAvailable + 该组成员")
    void activateSingleGroup() {
        mgr.activate("world_info");
        assertTrue(mgr.hasActiveGroups());
        assertEquals(Set.of("world_info"), mgr.activeGroupKeys());

        Set<String> tools = mgr.computeAllowedTools(registry);
        assertTrue(tools.contains("finish_action"));
        assertTrue(tools.contains("query_node"));
        assertTrue(tools.contains("query_element"));
        assertTrue(tools.contains("write_element"));
        // import_doc tools should NOT be present
        assertFalse(tools.contains("import_document_list"));
    }

    @Test
    @DisplayName("activate 多个组后包含所有成员")
    void activateMultipleGroups() {
        mgr.activate("world_info");
        mgr.activate("import_doc");

        Set<String> tools = mgr.computeAllowedTools(registry);
        assertTrue(tools.contains("query_element"));
        assertTrue(tools.contains("import_document_list"));
        assertTrue(tools.contains("import_document_search"));
    }

    @Test
    @DisplayName("activate 同一组多次是幂等的")
    void activateSameGroupIdempotent() {
        mgr.activate("world_info");
        mgr.activate("world_info");
        mgr.activate("world_info");
        assertEquals(1, mgr.activeGroupKeys().size());
    }

    @Test
    @DisplayName("activate 不存在的组 key 静默忽略")
    void activateUnknownGroupIgnored() {
        mgr.activate("nonexistent_group");
        assertFalse(mgr.hasActiveGroups());
        assertTrue(mgr.activeGroupKeys().isEmpty());
    }

    @Test
    @DisplayName("reset 清除所有激活组")
    void resetClearsAll() {
        mgr.activate("world_info");
        mgr.activate("import_doc");
        assertTrue(mgr.hasActiveGroups());
        mgr.reset();
        assertFalse(mgr.hasActiveGroups());
        assertTrue(mgr.activeGroupKeys().isEmpty());
    }

    @Test
    @DisplayName("createWithAllGroupsActivated 预激活全部 5 组")
    void createWithAllGroupsActivated() {
        ToolGroupManager allMgr = ToolGroupManager.createWithAllGroupsActivated();
        assertTrue(allMgr.hasActiveGroups());
        assertEquals(5, allMgr.activeGroupKeys().size());
        Set<String> tools = allMgr.computeAllowedTools(registry);
        assertTrue(tools.contains("query_node"));
    }

    @Test
    @DisplayName("generateGroupCatalogPrompt 包含所有组")
    void generateGroupCatalogPromptContainsAllGroups() {
        String prompt = mgr.generateGroupCatalogPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("activate_tool_groups"));
        assertTrue(prompt.contains("工具组目录"));
        for (ToolGroup g : ToolGroup.ALL_GROUPS) {
            assertTrue(prompt.contains(g.key()), "应包含组 key: " + g.key());
        }
    }

    @Test
    @DisplayName("activeGroupKeys 返回不可变快照")
    void activeGroupKeysReturnsSnapshot() {
        mgr.activate("world_info");
        Set<String> keys = mgr.activeGroupKeys();
        assertThrows(UnsupportedOperationException.class, () -> keys.add("import_doc"));
    }
}
