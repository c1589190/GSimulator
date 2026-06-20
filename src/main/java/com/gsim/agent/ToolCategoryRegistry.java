package com.gsim.agent;

import java.util.Map;
import java.util.Set;

/**
 * 工具分类注册表 — 维护所有已注册工具的名称 → 分类映射。
 *
 * <p>分类策略见 {@link ToolCategory}。
 */
public class ToolCategoryRegistry {

    private static final Map<String, ToolCategory> CATEGORIES = Map.ofEntries(
            // === READ_ONLY ===
            Map.entry("wiki_search", ToolCategory.READ_ONLY),
            Map.entry("keyword_search", ToolCategory.READ_ONLY),
            Map.entry("knowledge_search", ToolCategory.READ_ONLY),
            Map.entry("knowledge_get_chunk", ToolCategory.READ_ONLY),
            Map.entry("knowledge_get_document", ToolCategory.READ_ONLY),
            Map.entry("player_profile_list", ToolCategory.READ_ONLY),
            Map.entry("player_profile_get", ToolCategory.READ_ONLY),
            Map.entry("import_document_list", ToolCategory.READ_ONLY),
            Map.entry("import_document_read", ToolCategory.READ_ONLY),
            Map.entry("import_document_search", ToolCategory.READ_ONLY),
            Map.entry("branch_analysis", ToolCategory.READ_ONLY),
            Map.entry("branch_path", ToolCategory.READ_ONLY),
            Map.entry("branch_node_get", ToolCategory.READ_ONLY),
            Map.entry("branch_node_search", ToolCategory.READ_ONLY),
            Map.entry("branch_log_filter", ToolCategory.READ_ONLY),
            Map.entry("branch_pin_get", ToolCategory.READ_ONLY),
            Map.entry("root_status", ToolCategory.READ_ONLY),
            Map.entry("root_world_get", ToolCategory.READ_ONLY),
            Map.entry("root_entities_get", ToolCategory.READ_ONLY),
            Map.entry("root_rules_get", ToolCategory.READ_ONLY),
            Map.entry("root_initial_info_get", ToolCategory.READ_ONLY),
            Map.entry("root_players_get", ToolCategory.READ_ONLY),
            Map.entry("simulation_content_list", ToolCategory.READ_ONLY),
            Map.entry("simulation_content_get", ToolCategory.READ_ONLY),
            Map.entry("turn_settlement_get", ToolCategory.READ_ONLY),
            Map.entry("branch_list", ToolCategory.READ_ONLY),
            Map.entry("player_action_list", ToolCategory.READ_ONLY),
            Map.entry("player_action_get", ToolCategory.READ_ONLY),

            // === MUTATING ===
            Map.entry("knowledge_upsert", ToolCategory.MUTATING),
            Map.entry("knowledge_update", ToolCategory.MUTATING),
            Map.entry("knowledge_embed_missing", ToolCategory.MUTATING),
            Map.entry("player_input", ToolCategory.MUTATING),
            Map.entry("player_profile_update", ToolCategory.MUTATING),
            Map.entry("player_profile_note", ToolCategory.MUTATING),
            Map.entry("branch_pin_add", ToolCategory.MUTATING),
            Map.entry("root_create", ToolCategory.MUTATING),
            Map.entry("root_world_update", ToolCategory.MUTATING),
            Map.entry("root_entities_update", ToolCategory.MUTATING),
            Map.entry("root_rules_update", ToolCategory.MUTATING),
            Map.entry("root_initial_info_update", ToolCategory.MUTATING),
            Map.entry("root_players_update", ToolCategory.MUTATING),
            Map.entry("simulation_content_append", ToolCategory.MUTATING),
            Map.entry("simulation_content_update", ToolCategory.MUTATING),
            Map.entry("turn_settlement_save", ToolCategory.MUTATING),
            Map.entry("turn_settlement_save_last_response", ToolCategory.MUTATING),
            Map.entry("branch_create_child", ToolCategory.MUTATING),
            Map.entry("branch_switch", ToolCategory.MUTATING),
            Map.entry("branch_goto_parent", ToolCategory.MUTATING),
            Map.entry("branch_next_turn", ToolCategory.MUTATING),
            Map.entry("player_action_append", ToolCategory.MUTATING),
            Map.entry("player_action_update", ToolCategory.MUTATING),

            // === DESTRUCTIVE ===
            Map.entry("knowledge_delete", ToolCategory.DESTRUCTIVE),

            // === CONTROL ===
            Map.entry("finish_action", ToolCategory.CONTROL),
            Map.entry("activate_tool_groups", ToolCategory.CONTROL)
    );

    /** 已知的 CONTROL 工具名集合 */
    private static final Set<String> CONTROL_TOOLS = Set.of("finish_action", "activate_tool_groups");

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
