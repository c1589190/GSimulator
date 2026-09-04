package com.gsim.agent;

import com.gsim.agentsmanager.tool.AgentTool;
import com.gsim.agentsmanager.tool.ToolRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 工具组管理器 — 维护当前激活的工具组，计算本轮允许的工具集。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>每次对话开始前（chatWithContextSession / runWithContextSession）调用 {@link #reset()}</li>
 *   <li>LLM 调用 activate_tool_groups 时调用 {@link #activate(String)}</li>
 *   <li>ToolLoop 每轮调用 {@link #computeAllowedTools(ToolRegistry)} 获取当前允许集</li>
 * </ul>
 *
 * <h3>激活语义</h3>
 * <p>activate_tool_groups 在同一轮执行后，立即更新允许集。
 * 后续工具可以来自新激活的组。激活状态不跨对话保留。
 *
 * <h3>工具可见性计算</h3>
 * <p>不再依赖 {@link ToolGroup} 中的静态 {@code DEFAULT_TOOLS} 和
 * {@code TOOL_TO_GROUP} 映射。改为扫描 {@link ToolRegistry} 中每个工具的
 * {@link AgentTool#alwaysAvailable()} 和 {@link AgentTool#toolGroups()} 声明。
 */
public class ToolGroupManager {

    /** 当前激活的工具组 key 集合。线程安全：仅在单线程 ToolLoop 内操作。 */
    private final Set<String> activeGroupKeys = new LinkedHashSet<>();

    /** 创建新的 ToolGroupManager，初始状态无激活组。 */
    public ToolGroupManager() {}

    /**
     * 创建预激活所有工具组的 ToolGroupManager。
     * 用于测试和向后兼容场景（不需要显式 activate_tool_groups）。
     */
    public static ToolGroupManager createWithAllGroupsActivated() {
        ToolGroupManager mgr = new ToolGroupManager();
        for (ToolGroup g : ToolGroup.ALL_GROUPS) {
            mgr.activeGroupKeys.add(g.key());
        }
        return mgr;
    }

    /**
     * 重置所有激活状态。每次用户发起新对话时调用。
     */
    public void reset() {
        activeGroupKeys.clear();
    }

    /**
     * 激活指定 key 的工具组。同一轮内重复激活同一组是幂等的。
     * 如果 key 对应的组不存在，静默忽略。
     */
    public void activate(String groupKey) {
        if (ToolGroup.findByKey(groupKey) != null) {
            activeGroupKeys.add(groupKey);
        }
    }

    // ── 静态 fallback 映射（存量工具尚未迁移到自声明之前的过渡方案） ──

    /** 旧 DEFAULT_TOOLS 中尚未通过 alwaysAvailable() 自声明的工具名。逐步迁移后可删除。 */
    private static final Set<String> LEGACY_DEFAULT_TOOLS = Set.of(
            "finish_action",
            "activate_tool_groups",
            "dispatch_sub_agent",
            "collect_sub_agent_results",
            "list_sub_agent_caches",
            "view_sub_agent_cache",
            "view_sub_agent_output",
            "world_list",
            "world_create",
            "compact_cache",
            "doc_list",
            "doc_read",
            "doc_create",
            "doc_write",
            "doc_search",
            "doc_index");

    /** 旧 TOOL_TO_GROUP 中尚未通过 toolGroups() 自声明的工具名→组 key。逐步迁移后可删除。 */
    private static final Map<String, String> LEGACY_TOOL_TO_GROUP;

    static {
        var map = new LinkedHashMap<String, String>();
        for (var g : ToolGroup.ALL_GROUPS) {
            for (var tool : g.memberTools()) {
                map.put(tool, g.key());
            }
        }
        LEGACY_TOOL_TO_GROUP = Collections.unmodifiableMap(map);
    }

    // ── 可见性计算 ─────────────────────────────────────────────

    /**
     * 根据当前激活组计算允许的工具集。
     *
     * <p>扫描注册表中所有工具，按优先级判断：
     * <ol>
     *   <li>工具声明了 {@link AgentTool#alwaysAvailable()} == true → 允许</li>
     *   <li>工具声明了 {@link AgentTool#toolGroups()} 非空 → 检查与激活组交集</li>
     *   <li>工具名在 LEGACY_DEFAULT_TOOLS 中 → 允许（存量兼容）</li>
     *   <li>工具名在 LEGACY_TOOL_TO_GROUP 中 → 检查对应组是否激活</li>
     *   <li>以上都不满足 → 未知工具，始终允许（测试/自定义工具）</li>
     * </ol>
     *
     * @param registry 工具注册表（用于扫描工具声明）
     * @return 不可变集合，包含本轮允许的所有工具名
     */
    public Set<String> computeAllowedTools(ToolRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        for (var entry : registry.all().entrySet()) {
            String name = entry.getKey();
            AgentTool tool = entry.getValue();

            // Rule 1: self-declared always available
            if (tool.alwaysAvailable()) {
                result.add(name);
                continue;
            }

            // Rule 2: self-declared tool groups
            Set<String> declared = tool.toolGroups();
            if (!declared.isEmpty()) {
                for (String g : declared) {
                    if (activeGroupKeys.contains(g)) {
                        result.add(name);
                        break;
                    }
                }
                continue;
            }

            // Rule 3: legacy default tools
            if (LEGACY_DEFAULT_TOOLS.contains(name)) {
                result.add(name);
                continue;
            }

            // Rule 4: legacy tool-to-group mapping
            String legacyGroup = LEGACY_TOOL_TO_GROUP.get(name);
            if (legacyGroup != null) {
                if (activeGroupKeys.contains(legacyGroup)) {
                    result.add(name);
                }
                continue;
            }

            // Rule 5: unknown tool — always allow
            result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 返回旧静态映射中的所有工具名（过渡期使用，存量工具逐步迁移到自声明后可删除）。
     * @return 所有在 LEGACY_DEFAULT_TOOLS 或 LEGACY_TOOL_TO_GROUP 中的工具名
     */
    public static Set<String> legacyKnownToolNames() {
        Set<String> all = new LinkedHashSet<>(LEGACY_DEFAULT_TOOLS);
        all.addAll(LEGACY_TOOL_TO_GROUP.keySet());
        return Collections.unmodifiableSet(all);
    }

    /** 当前激活的组 key 集合快照（不可变）。 */
    public Set<String> activeGroupKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(activeGroupKeys));
    }

    /** 是否有任何组被激活。 */
    public boolean hasActiveGroups() {
        return !activeGroupKeys.isEmpty();
    }

    /**
     * 生成工具组目录提示文本，嵌入 orchestrator 系统 prompt。
     * 列出所有工具组及其 key、描述和成员工具。
     */
    public String generateGroupCatalogPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 工具组目录 (Tool Groups)\n\n");
        sb.append("你需要通过 activate_tool_groups 激活所需的工具组，才能使用组内的工具。\n");
        sb.append("请在首轮根据用户任务一次性激活所有需要的工具组，尽量避免后续再激活其他组。\n\n");

        sb.append("| 激活名 | 工具组 | 说明 | 成员工具 |\n");
        sb.append("|--------|--------|------|----------|\n");
        for (ToolGroup g : ToolGroup.ALL_GROUPS) {
            sb.append("| `").append(g.key()).append("` | ").append(g.displayName());
            sb.append(" | ").append(g.description());
            sb.append(" | ").append(String.join(", ", g.memberTools()));
            sb.append(" |\n");
        }

        sb.append("\n**使用方法：** `activate_tool_groups` 的 groups 参数传入需要的组 key 列表。\n");
        sb.append("示例：激活玩家行动和知识库组：");
        sb.append("`{\"tool\":\"activate_tool_groups\",\"args\":{\"groups\":\"[\\\"player_action\\\","
                + "\\\"knowledge\\\"]\"}}`\n");
        sb.append("可以在一轮中先调用 activate_tool_groups，再调用其他工具（同一轮内生效）。\n");

        return sb.toString();
    }
}
