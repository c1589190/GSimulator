package com.gsim.agent;

import java.util.Collections;
import java.util.Set;

/**
 * 工具路由策略 — 薄壳委托。
 *
 * <p>v2 重构后，路由决策由 {@link ToolGroupManager} 承担。
 * 此策略仅保留基本类型和数据传递，不包含业务逻辑。
 * 所有预设允许集、意图路由、expandToolFamily 等均已删除。
 */
public class ToolRoutePolicy {

    /** 创建路由决策（基于给定允许集）。 */
    public ToolRouteDecision createDecision(Set<String> allowedTools, String routeName, String reason) {
        return new ToolRouteDecision(
                Collections.unmodifiableSet(new java.util.LinkedHashSet<>(allowedTools)),
                routeName, reason);
    }
}
