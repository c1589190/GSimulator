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
        CATEGORIES.put("keyword_search", ToolCategory.READ_ONLY);
        CATEGORIES.put("knowledge_search", ToolCategory.READ_ONLY);
        CATEGORIES.put("knowledge_get_chunk", ToolCategory.READ_ONLY);
        CATEGORIES.put("knowledge_get_document", ToolCategory.READ_ONLY);
        CATEGORIES.put("player_profile_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("player_profile_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("import_document_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("import_document_read", ToolCategory.READ_ONLY);
        CATEGORIES.put("import_document_search", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_analysis", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_path", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_node_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_node_search", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_log_filter", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_pin_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("root_status", ToolCategory.READ_ONLY);
        CATEGORIES.put("root_world_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("root_entities_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("root_rules_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("root_initial_info_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("root_players_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("simulation_content_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("simulation_content_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("turn_settlement_get", ToolCategory.READ_ONLY);
        CATEGORIES.put("branch_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("player_action_list", ToolCategory.READ_ONLY);
        CATEGORIES.put("player_action_get", ToolCategory.READ_ONLY);

        // === MUTATING ===
        CATEGORIES.put("knowledge_upsert", ToolCategory.MUTATING);
        CATEGORIES.put("knowledge_update", ToolCategory.MUTATING);
        CATEGORIES.put("knowledge_embed_missing", ToolCategory.MUTATING);
        CATEGORIES.put("player_input", ToolCategory.MUTATING);
        CATEGORIES.put("player_profile_update", ToolCategory.MUTATING);
        CATEGORIES.put("player_profile_note", ToolCategory.MUTATING);
        CATEGORIES.put("branch_pin_add", ToolCategory.MUTATING);
        CATEGORIES.put("root_create", ToolCategory.MUTATING);
        CATEGORIES.put("root_world_update", ToolCategory.MUTATING);
        CATEGORIES.put("root_entities_update", ToolCategory.MUTATING);
        CATEGORIES.put("root_rules_update", ToolCategory.MUTATING);
        CATEGORIES.put("root_initial_info_update", ToolCategory.MUTATING);
        CATEGORIES.put("root_players_update", ToolCategory.MUTATING);
        CATEGORIES.put("simulation_content_append", ToolCategory.MUTATING);
        CATEGORIES.put("simulation_content_update", ToolCategory.MUTATING);
        CATEGORIES.put("turn_settlement_save", ToolCategory.MUTATING);
        CATEGORIES.put("turn_settlement_save_last_response", ToolCategory.MUTATING);
        CATEGORIES.put("branch_create_child", ToolCategory.MUTATING);
        CATEGORIES.put("branch_switch", ToolCategory.MUTATING);
        CATEGORIES.put("branch_goto_parent", ToolCategory.MUTATING);
        CATEGORIES.put("branch_next_turn", ToolCategory.MUTATING);
        CATEGORIES.put("player_action_append", ToolCategory.MUTATING);
        CATEGORIES.put("player_action_update", ToolCategory.MUTATING);

        // === DESTRUCTIVE ===
        CATEGORIES.put("knowledge_delete", ToolCategory.DESTRUCTIVE);

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
