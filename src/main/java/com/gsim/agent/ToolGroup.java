package com.gsim.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具组定义 — 所有工具按功能分组，LLM 通过 activate_tool_groups 按需激活。
 *
 * <p>4 个工具组，按 key 索引。默认工具（无需激活）见 {@link #DEFAULT_TOOLS}。
 */
public record ToolGroup(
        String key,
        String displayName,
        String description,
        Set<String> memberTools
) {

    // ===== 4 个工具组 =====

    public static final ToolGroup WORLD_INFO = new ToolGroup(
            "world_info", "WorldInfo 元素读写",
            "查询和写入 WorldInfo 结构化元素。"
                    + "query_node 查看节点内全部元素，"
                    + "query_checkpoint 查看检查点历史（支持 player.* 前缀通配），"
                    + "query_keyword 全文关键词搜索（支持分页），"
                    + "query_element 按 nodeId:checkpointId:key 精确查询单个元素，"
                    + "write_element 写入/更新元素（ref=nodeId:checkpointId:key，默认 upsert，mode=append 追加；"
                    + "写入不存在的 checkpoint 会自动创建），"
                    + "create_checkpoint 显式创建检查点（带 label/type 元数据）。",
            Set.of("query_node", "query_checkpoint", "query_keyword",
                    "query_element", "write_element", "create_checkpoint")
    );

    public static final ToolGroup NODE_MGMT = new ToolGroup(
            "node_mgmt", "节点管理",
            "查看、创建、切换、返回分支节点。"
                    + "node_list 列出当前链所有节点，"
                    + "node_status 查看当前节点状态，"
                    + "node_create 创建子节点（下一回合）并切换，"
                    + "node_switch 切换到链内已有节点，"
                    + "node_goto_parent 返回父节点。",
            Set.of("node_list", "node_status", "node_create",
                    "node_switch", "node_goto_parent")
    );

    public static final ToolGroup IMPORT_DOC = new ToolGroup(
            "import_doc", "导入文档",
            "浏览和读取 import 目录下的本地/Wiki 文档（只读，不入库）。"
                    + "import_document_list 列出文档，import_document_read 读取内容，"
                    + "import_document_search 搜索文档。",
            Set.of("import_document_list", "import_document_read", "import_document_search")
    );

    public static final ToolGroup SEARCH = new ToolGroup(
            "search", "文件搜索",
            "全文搜索导入的 Wiki 文本文件。"
                    + "wiki_search 搜索 Wiki 文本。",
            Set.of("wiki_search")
    );

    // ===== 所有工具组列表 =====

    public static final List<ToolGroup> ALL_GROUPS = List.of(
            WORLD_INFO, NODE_MGMT, IMPORT_DOC, SEARCH
    );

    // ===== 默认工具（无需激活，始终可用） =====

    public static final Set<String> DEFAULT_TOOLS = Set.of(
            "finish_action",
            "activate_tool_groups",
            "dispatch_sub_agent",
            "collect_sub_agent_results"
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
