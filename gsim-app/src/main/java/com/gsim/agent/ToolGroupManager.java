package com.gsim.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具组管理器 — 维护当前激活的工具组，计算本轮允许的工具集。
 *
 * <h3>生命周期</h3>
 * <ul>
 *   <li>每次对话开始前（chatWithContextSession / runWithContextSession）调用 {@link #reset()}</li>
 *   <li>LLM 调用 activate_tool_groups 时调用 {@link #activate(String)}</li>
 *   <li>ToolLoop 每轮调用 {@link #computeAllowedTools()} 获取当前允许集</li>
 * </ul>
 *
 * <h3>激活语义</h3>
 * <p>activate_tool_groups 在同一轮执行后，立即更新允许集。
 * 后续工具可以来自新激活的组。激活状态不跨对话保留。
 */
public class ToolGroupManager {

    /** 当前激活的工具组 key 集合。线程安全：仅在单线程 ToolLoop 内操作。 */
    private final Set<String> activeGroupKeys = new LinkedHashSet<>();

    /** 创建新的 ToolGroupManager，初始状态无激活组。 */
    public ToolGroupManager() {
    }

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

    /**
     * 根据当前激活组计算允许的工具集。
     * 始终包含 DEFAULT_TOOLS + 所有激活组的成员工具。
     *
     * @return 不可变集合，包含本轮允许的所有工具名
     */
    public Set<String> computeAllowedTools() {
        Set<String> result = new LinkedHashSet<>(ToolGroup.DEFAULT_TOOLS);
        for (String key : activeGroupKeys) {
            ToolGroup g = ToolGroup.findByKey(key);
            if (g != null) {
                result.addAll(g.memberTools());
            }
        }
        return Collections.unmodifiableSet(result);
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
        sb.append("**默认可用工具（无需激活）：** ");
        sb.append(String.join(", ", ToolGroup.DEFAULT_TOOLS));
        sb.append("\n\n");

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
