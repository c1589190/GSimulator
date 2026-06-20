package com.gsim.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具组定义 — 所有工具按功能分组，LLM 通过 activate_tool_groups 按需激活。
 *
 * <p>10 个工具组，按 key 索引。默认工具（无需激活）见 {@link #DEFAULT_TOOLS}。
 */
public record ToolGroup(
        String key,
        String displayName,
        String description,
        Set<String> memberTools
) {

    // ===== 10 个工具组 =====

    public static final ToolGroup PLAYER_ACTION = new ToolGroup(
            "player_action", "玩家行动",
            "查看/记录/修订当前分支(branch)节点内的玩家行动记录。"
                    + "player_action_list 查看列表，player_action_get 读取详情，"
                    + "player_action_append 记录新行动，player_action_update 修订已有行动。"
                    + "包含 player_input 处理玩家输入。",
            Set.of("player_action_list", "player_action_get",
                    "player_action_append", "player_action_update", "player_input")
    );

    public static final ToolGroup SIMULATION = new ToolGroup(
            "simulation", "推演内容",
            "读写当前 branch 节点内的推演正文/场景/事件/对话。"
                    + "simulation_content_append 保存推演内容，"
                    + "simulation_content_list/get 查看，simulation_content_update 修订。",
            Set.of("simulation_content_list", "simulation_content_get",
                    "simulation_content_append", "simulation_content_update")
    );

    public static final ToolGroup KNOWLEDGE = new ToolGroup(
            "knowledge", "知识库",
            "语义搜索、关键词检索和 CRUD 操作 embDB/KnowledgeStore。"
                    + "knowledge_search 语义检索，keyword_search 关键词检索，"
                    + "knowledge_get_chunk/get_document 读取全文，"
                    + "knowledge_upsert 写入/补充知识，knowledge_update 更新非分支知识，"
                    + "knowledge_delete 删除知识，knowledge_embed_missing 补嵌入。",
            Set.of("knowledge_search", "keyword_search",
                    "knowledge_get_chunk", "knowledge_get_document",
                    "knowledge_upsert", "knowledge_update", "knowledge_delete",
                    "knowledge_embed_missing")
    );

    public static final ToolGroup BRANCH_MUTATION = new ToolGroup(
            "branch_mutation", "分支变更",
            "创建/切换/返回/推进 branch 节点。"
                    + "branch_create_child 创建子节点，branch_switch 切换节点，"
                    + "branch_goto_parent 返回父节点，branch_next_turn 创建并进入下一回合。",
            Set.of("branch_create_child", "branch_switch",
                    "branch_goto_parent", "branch_next_turn")
    );

    public static final ToolGroup SETTLEMENT = new ToolGroup(
            "settlement", "回合结算",
            "保存和查询回合结算。"
                    + "turn_settlement_save 保存结算（含额外字段），"
                    + "turn_settlement_save_last_response 自动使用上一条自然语言回复作为结算正文，"
                    + "turn_settlement_get 查询已有结算。",
            Set.of("turn_settlement_save", "turn_settlement_save_last_response",
                    "turn_settlement_get")
    );

    public static final ToolGroup IMPORT_DOC = new ToolGroup(
            "import_doc", "导入文档",
            "浏览和读取 import 目录下的本地/Wiki 文档（只读，不入库）。"
                    + "import_document_list 列出文档，import_document_read 读取内容，"
                    + "import_document_search 搜索文档。",
            Set.of("import_document_list", "import_document_read", "import_document_search")
    );

    public static final ToolGroup PLAYER_PROFILE = new ToolGroup(
            "player_profile", "玩家档案",
            "查看和维护玩家长期资料/人物卡/背景设定。"
                    + "player_profile_list 列出，player_profile_get 读取，"
                    + "player_profile_update 更新，player_profile_note 追加备注。",
            Set.of("player_profile_list", "player_profile_get",
                    "player_profile_update", "player_profile_note")
    );

    public static final ToolGroup ROOT_MGMT = new ToolGroup(
            "root_mgmt", "根节点管理",
            "读取和修改 root 级世界设定文件。"
                    + "root_world_get/entities_get/rules_get/initial_info_get/players_get 读取，"
                    + "root_world_update/entities_update/rules_update/initial_info_update/players_update 修改，"
                    + "root_create 创建新 root。"
                    + "注意：不在根节点(branch.b0000-start)时，只能读取，不能修改 root 文件。",
            Set.of("root_world_get", "root_entities_get", "root_rules_get",
                    "root_initial_info_get", "root_players_get",
                    "root_world_update", "root_entities_update", "root_rules_update",
                    "root_initial_info_update", "root_players_update", "root_create")
    );

    public static final ToolGroup BRANCH_MEMORY = new ToolGroup(
            "branch_memory", "分支记忆",
            "搜索和翻阅历史 branch 节点的内容和日志。"
                    + "branch_node_search 搜索节点，branch_log_filter 过滤日志，"
                    + "branch_pin_get 查看固定内容，branch_pin_add 固定内容，"
                    + "branch_analysis 分析节点。",
            Set.of("branch_node_search", "branch_log_filter",
                    "branch_pin_get", "branch_pin_add", "branch_analysis")
    );

    public static final ToolGroup SEARCH = new ToolGroup(
            "search", "文件搜索",
            "全文搜索导入的 Wiki 文本文件。"
                    + "wiki_search 搜索 Wiki 文本。",
            Set.of("wiki_search")
    );

    // ===== 所有工具组列表 =====

    public static final List<ToolGroup> ALL_GROUPS = List.of(
            PLAYER_ACTION, SIMULATION, KNOWLEDGE, BRANCH_MUTATION, SETTLEMENT,
            IMPORT_DOC, PLAYER_PROFILE, ROOT_MGMT, BRANCH_MEMORY, SEARCH
    );

    // ===== 默认工具（无需激活，始终可用） =====

    public static final Set<String> DEFAULT_TOOLS = Set.of(
            "finish_action",
            "activate_tool_groups",
            "branch_path",
            "branch_node_get",
            "branch_list",
            "root_status"
    );

    // ===== 工具名 → 所属组 key 查找表 =====

    public static final Map<String, String> TOOL_TO_GROUP;

    static {
        var map = new java.util.LinkedHashMap<String, String>();
        for (var g : ALL_GROUPS) {
            for (var tool : g.memberTools()) {
                map.put(tool, g.key());
            }
        }
        TOOL_TO_GROUP = java.util.Collections.unmodifiableMap(map);
    }

    /** 按 key 查找工具组。未找到返回 null。 */
    public static ToolGroup findByKey(String key) {
        for (var g : ALL_GROUPS) {
            if (g.key().equals(key)) return g;
        }
        return null;
    }

    /** 工具是否在任何工具组或默认工具集中（已知工具）。未知工具（如测试自定义工具）始终可用。 */
    public static boolean isKnownTool(String toolName) {
        return TOOL_TO_GROUP.containsKey(toolName) || DEFAULT_TOOLS.contains(toolName);
    }
}
