package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolGroupManager 单元测试。
 */
@DisplayName("工具组管理器")
class ToolGroupManagerTest {

    private ToolGroupManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new ToolGroupManager();
    }

    @Test
    @DisplayName("初始状态无激活组")
    void initialNoActiveGroups() {
        assertFalse(mgr.hasActiveGroups());
        assertTrue(mgr.activeGroupKeys().isEmpty());
    }

    @Test
    @DisplayName("computeAllowedTools 初始只包含默认工具")
    void computeAllowedToolsInitial() {
        Set<String> tools = mgr.computeAllowedTools();
        assertEquals(ToolGroup.DEFAULT_TOOLS, tools);
        assertTrue(tools.contains("finish_action"));
        assertTrue(tools.contains("activate_tool_groups"));
        assertTrue(tools.contains("dispatch_sub_agent"));
        assertTrue(tools.contains("collect_sub_agent_results"));
    }

    @Test
    @DisplayName("activate 单个组后 computeAllowedTools 包含默认工具 + 该组成员")
    void activateSingleGroup() {
        mgr.activate("world_info");
        assertTrue(mgr.hasActiveGroups());
        assertEquals(Set.of("world_info"), mgr.activeGroupKeys());

        Set<String> tools = mgr.computeAllowedTools();
        assertTrue(tools.containsAll(ToolGroup.DEFAULT_TOOLS));
        assertTrue(tools.containsAll(ToolGroup.WORLD_INFO.memberTools()));
        assertTrue(tools.contains("query_element"));
        assertTrue(tools.contains("query_node"));
        assertTrue(tools.contains("write_element"));
    }

    @Test
    @DisplayName("activate 多个组后 computeAllowedTools 包含所有成员")
    void activateMultipleGroups() {
        mgr.activate("world_info");
        mgr.activate("import_doc");

        Set<String> tools = mgr.computeAllowedTools();
        assertTrue(tools.containsAll(ToolGroup.DEFAULT_TOOLS));
        assertTrue(tools.containsAll(ToolGroup.WORLD_INFO.memberTools()));
        assertTrue(tools.containsAll(ToolGroup.IMPORT_DOC.memberTools()));
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
        assertEquals(ToolGroup.DEFAULT_TOOLS, mgr.computeAllowedTools());
    }

    @Test
    @DisplayName("createWithAllGroupsActivated 预激活全部 4 组")
    void createWithAllGroupsActivated() {
        ToolGroupManager allMgr = ToolGroupManager.createWithAllGroupsActivated();
        assertTrue(allMgr.hasActiveGroups());
        assertEquals(4, allMgr.activeGroupKeys().size());

        Set<String> tools = allMgr.computeAllowedTools();
        assertTrue(tools.containsAll(ToolGroup.DEFAULT_TOOLS));
        for (ToolGroup g : ToolGroup.ALL_GROUPS) {
            assertTrue(tools.containsAll(g.memberTools()),
                    g.key() + " 组的成员工具应全部包含");
        }
    }

    @Test
    @DisplayName("generateGroupCatalogPrompt 包含所有组和默认工具")
    void generateGroupCatalogPromptContainsAllGroups() {
        String prompt = mgr.generateGroupCatalogPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("activate_tool_groups"));
        assertTrue(prompt.contains("工具组目录"));
        for (ToolGroup g : ToolGroup.ALL_GROUPS) {
            assertTrue(prompt.contains(g.key()),
                    "应包含组 key: " + g.key());
            assertTrue(prompt.contains(g.displayName()),
                    "应包含组名: " + g.displayName());
        }
        for (String defaultTool : ToolGroup.DEFAULT_TOOLS) {
            assertTrue(prompt.contains(defaultTool),
                    "应包含默认工具: " + defaultTool);
        }
    }

    @Test
    @DisplayName("activeGroupKeys 返回不可变快照")
    void activeGroupKeysReturnsSnapshot() {
        mgr.activate("world_info");
        Set<String> keys = mgr.activeGroupKeys();
        assertThrows(UnsupportedOperationException.class, () -> keys.add("import_doc"));
    }

    @Test
    @DisplayName("computeAllowedTools 返回的集合包含 DEFAULT_TOOLS 中所有工具名")
    void defaultToolsAreAlwaysPresent() {
        mgr.activate("import_doc"); // 只激活 import_doc 组
        Set<String> tools = mgr.computeAllowedTools();
        for (String dt : ToolGroup.DEFAULT_TOOLS) {
            assertTrue(tools.contains(dt), "默认工具 " + dt + " 应始终包含");
        }
        assertTrue(tools.contains("import_document_search"));
        assertTrue(tools.contains("import_document_list"));
    }
}
