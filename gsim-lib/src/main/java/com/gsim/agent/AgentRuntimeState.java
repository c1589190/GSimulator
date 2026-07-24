package com.gsim.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个 Agent 实例的运行时可变状态。
 *
 * <p>由 AgentCache 中的 {@link ToolGroupEvent} 重放恢复。
 * 不存储在全局单例或进程级状态中 — 每个 Agent 拥有独立实例。
 *
 * @param activeToolGroups 当前激活的工具组 key 集合
 * @param boundWorldId     Agent 当前绑定的 world（可为 null，SubAgent 沿用父 Agent 上下文）
 * @param boundNodeId      Agent 当前绑定的 node（可为 null）
 */
public record AgentRuntimeState(Set<String> activeToolGroups, String boundWorldId, String boundNodeId) {

    public static AgentRuntimeState empty() {
        return new AgentRuntimeState(Set.of(), null, null);
    }

    /**
     * 从 ToolGroupEvent 列表重放，构建初始状态。
     * 按时间戳顺序应用 activated/deactivated 事件。
     *
     * @param events 按时间排序的工具组变更事件列表
     * @return 重放后的运行时状态
     */
    public static AgentRuntimeState replay(List<ToolGroupEvent> events) {
        if (events == null || events.isEmpty()) {
            return empty();
        }
        Set<String> active = new LinkedHashSet<>();
        for (ToolGroupEvent e : events) {
            switch (e.type()) {
                case "activated" -> active.addAll(e.groups());
                case "deactivated" -> active.removeAll(e.groups());
                default -> {
                    /* unknown event type, skip */
                }
            }
        }
        return new AgentRuntimeState(Set.copyOf(active), null, null);
    }

    /** 返回激活了指定组后的新状态（不可变更新）。 */
    public AgentRuntimeState withActivatedGroups(Set<String> groups) {
        var merged = new LinkedHashSet<>(activeToolGroups);
        merged.addAll(groups);
        return new AgentRuntimeState(Set.copyOf(merged), boundWorldId, boundNodeId);
    }

    /** 返回停用指定组后的新状态（不可变更新）。 */
    public AgentRuntimeState withDeactivatedGroups(Set<String> groups) {
        var remaining = new LinkedHashSet<>(activeToolGroups);
        remaining.removeAll(groups);
        return new AgentRuntimeState(Set.copyOf(remaining), boundWorldId, boundNodeId);
    }
}
