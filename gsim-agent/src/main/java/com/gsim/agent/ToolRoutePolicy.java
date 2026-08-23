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

    /**
     * 基于给定的允许工具集创建路由决策。
     *
     * @param allowedTools 当前允许使用的工具名称集合
     * @param routeName    路由名称标识
     * @param reason       路由决策的原因说明
     * @return 路由决策结果
     */
    public ToolRouteDecision createDecision(Set<String> allowedTools, String routeName, String reason) {
        return new ToolRouteDecision(
                Collections.unmodifiableSet(new java.util.LinkedHashSet<>(allowedTools)), routeName, reason);
    }
}
