package com.gsim.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具权限配置 — 默认启用的 READ_ONLY 工具集。
 *
 * <p>TODO: 改为从 config/tool-permissions.yml 加载。
 * 当前为硬编码默认值，覆盖最常用的只读工具。
 */
public class ToolPermissionConfig {

    /** 默认启用的只读工具集合 */
    static final Set<String> DEFAULT_ENABLED = Set.of(
            "finish_action",
            "player_action_list",
            "player_action_get",
            "player_profile_list",
            "player_profile_get",
            "simulation_content_list",
            "simulation_content_get",
            "knowledge_search",
            "knowledge_get_chunk",
            "knowledge_get_document",
            "branch_node_get",
            "branch_path",
            "branch_list",
            "keyword_search",
            "import_document_list",
            "import_document_read",
            "import_document_search",
            "root_status",
            "root_world_get",
            "root_entities_get",
            "root_rules_get",
            "root_initial_info_get",
            "root_players_get",
            "branch_analysis",
            "branch_pin_get",
            "branch_log_filter",
            "turn_settlement_get"
    );

    /** 是否默认允许 MUTATING 工具 */
    private boolean defaultAllowMutating = false;

    /** 是否默认允许 DESTRUCTIVE 工具 */
    private boolean defaultAllowDestructive = false;

    /** 返回默认启用的工具集合（不可变副本）。 */
    public Set<String> defaultEnabledTools() {
        return DEFAULT_ENABLED;
    }

    /** 当前是否允许 MUTATING 工具（需确认后设置）。 */
    public boolean isDefaultAllowMutating() {
        return defaultAllowMutating;
    }

    /** 设置默认允许 MUTATING（如"一直允许本轮"）。 */
    public void setDefaultAllowMutating(boolean v) {
        this.defaultAllowMutating = v;
    }

    public boolean isDefaultAllowDestructive() {
        return defaultAllowDestructive;
    }

    public void setDefaultAllowDestructive(boolean v) {
        this.defaultAllowDestructive = v;
    }
}
