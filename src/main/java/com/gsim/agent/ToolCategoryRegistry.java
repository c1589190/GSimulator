package com.gsim.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具分类注册表 — 维护所有已注册工具的名称 → 分类映射。
 *
 * <p>分类策略见 {@link ToolCategory}。
 */
public class ToolCategoryRegistry {

    private static final Map<String, ToolCategory> CATEGORIES = new HashMap<>();

    static {
        // === READ_ONLY ===
        CATEGORIES.put("wiki_search", ToolCategory.READ_ONLY);
        CATEGORIES.put("mediawiki_search", ToolCategory.READ_ONLY);
        CATEGORIES.put("import_document_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("import_document_read", ToolCategory.READ_ONLY);
        CATEGORIES.put("import_document_search", ToolCategory.READ_ONLY);
        // WorldInfo: query
        CATEGORIES.put("query_node", ToolCategory.READ_ONLY);
        CATEGORIES.put("query_checkpoint", ToolCategory.READ_ONLY);
        CATEGORIES.put("query_keyword", ToolCategory.READ_ONLY);
        CATEGORIES.put("query_element", ToolCategory.READ_ONLY);
        // Node: read-only
        CATEGORIES.put("node_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("node_status", ToolCategory.READ_ONLY);
        // SubAgent cache
        CATEGORIES.put("list_sub_agent_caches", ToolCategory.READ_ONLY);
        CATEGORIES.put("view_sub_agent_cache", ToolCategory.READ_ONLY);
        CATEGORIES.put("view_sub_agent_output", ToolCategory.READ_ONLY);
        // LLM providers
        CATEGORIES.put("list_llm_providers", ToolCategory.READ_ONLY);
        // World: read-only
        CATEGORIES.put("world_list", ToolCategory.READ_ONLY);
        // Skill: read-only
        CATEGORIES.put("skill_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("skill_read", ToolCategory.READ_ONLY);
        CATEGORIES.put("skill_search", ToolCategory.READ_ONLY);

        // === MUTATING ===
        // WorldInfo: write
        CATEGORIES.put("write_element", ToolCategory.MUTATING);
        CATEGORIES.put("create_checkpoint", ToolCategory.MUTATING);
        // Node: mutating
        CATEGORIES.put("node_create", ToolCategory.MUTATING);
        CATEGORIES.put("node_switch", ToolCategory.MUTATING);
        CATEGORIES.put("node_goto_parent", ToolCategory.MUTATING);
        // Dynamic agent config
        CATEGORIES.put("create_sub_agent_config", ToolCategory.MUTATING);
        CATEGORIES.put("update_sub_agent_config", ToolCategory.MUTATING);
        // Skill: mutating
        // Cache: mutating
        CATEGORIES.put("compact_cache", ToolCategory.MUTATING);
        // World: mutating
        CATEGORIES.put("world_create", ToolCategory.MUTATING);
        CATEGORIES.put("world_switch", ToolCategory.MUTATING);
        // Skill: mutating
        CATEGORIES.put("skill_create", ToolCategory.MUTATING);
        CATEGORIES.put("skill_write", ToolCategory.MUTATING);
        CATEGORIES.put("skill_index", ToolCategory.MUTATING);

        // === CONTROL ===
        CATEGORIES.put("finish_action", ToolCategory.CONTROL);
        CATEGORIES.put("activate_tool_groups", ToolCategory.CONTROL);
        CATEGORIES.put("dispatch_sub_agent", ToolCategory.CONTROL);
        CATEGORIES.put("collect_sub_agent_results", ToolCategory.CONTROL);
    }

    /** 已知的 CONTROL 工具名集合 */
    private static final Set<String> CONTROL_TOOLS = Set.of(
            "finish_action", "activate_tool_groups",
            "dispatch_sub_agent", "collect_sub_agent_results");

    /**
     * 返回工具的分类。未知工具默认返回 MUTATING（保守策略：不假执行未知写入工具）。
     */
    public static ToolCategory categoryOf(String toolName) {
        return CATEGORIES.getOrDefault(toolName, ToolCategory.MUTATING);
    }

    /** 是否为 CONTROL 工具（finish_action）。 */
    public static boolean isControl(String toolName) {
        return CONTROL_TOOLS.contains(toolName);
    }

    /** 是否为只读工具。 */
    public static boolean isReadOnly(String toolName) {
        return CATEGORIES.get(toolName) == ToolCategory.READ_ONLY;
    }

    /** 返回已知工具名集合。 */
    public static Set<String> knownTools() {
        return CATEGORIES.keySet();
    }
}
