package com.gsim.agent;

import java.util.Collections;
import java.util.Set;

/**
 * 工具路由策略 — 根据用户意图、当前阶段、上一轮工具结果，
 * 计算本轮允许调用的工具集合。
 *
 * <p>7 条路由规则，覆盖 PLAYER_ACTION_QUERY / SHORT_POST_REWRITE /
 * FINISH_ACTION / KNOWLEDGE_SEARCH / KNOWLEDGE_WRITE / NEXT_TURN_SETTLE / GENERAL。
 */
public class ToolRoutePolicy {

    /**
     * 预设：玩家行动查询只允许的读工具
     */
    static final Set<String> PLAYER_ACTION_QUERY_ALLOW = Set.of(
            "player_action_list",
            "player_action_get",
            "finish_action"
    );

    /**
     * 预设：短推复写允许的工具
     */
    static final Set<String> SHORT_POST_REWRITE_ALLOW = Set.of(
            "player_action_list",
            "player_action_get",
            "simulation_content_list",
            "simulation_content_get",
            "simulation_content_append",
            "simulation_content_update",
            "finish_action"
    );

    /**
     * 预设：知识搜索允许
     */
    static final Set<String> KNOWLEDGE_SEARCH_ALLOW = Set.of(
            "knowledge_search",
            "knowledge_get_chunk",
            "knowledge_get_document",
            "keyword_search",
            "finish_action"
    );

    /**
     * 预设：知识写入允许
     */
    static final Set<String> KNOWLEDGE_WRITE_ALLOW = Set.of(
            "knowledge_upsert",
            "knowledge_update",
            "knowledge_search",
            "knowledge_get_chunk",
            "knowledge_get_document",
            "keyword_search",
            "finish_action"
    );

    /**
     * 预设：结算/下一回合允许
     */
    static final Set<String> NEXT_TURN_SETTLE_ALLOW = Set.of(
            "turn_settlement_save",
            "turn_settlement_save_last_response",
            "branch_next_turn",
            "branch_create_child",
            "player_action_list",
            "simulation_content_list",
            "finish_action"
    );

    /**
     * 预设：GENERAL（未识别意图）只允许
     */
    static final Set<String> GENERAL_ALLOW = Set.of(
            "finish_action"
    );

    /**
     * 计算本轮允许的工具集合。
     *
     * @param intent               用户意图
     * @param expectedNextStep     当前阶段：CALL_TOOL 或 FINISH_ACTION
     * @param defaultEnabledTools  配置中默认启用的工具
     * @return 路由决策（含允许集、路由名、原因）
     */
    public ToolRouteDecision decide(
            UserIntent intent,
            ExpectedNextStep expectedNextStep,
            Set<String> defaultEnabledTools) {

        // 阶段 1: FINISH_ACTION — 只允许 finish_action
        if (expectedNextStep == ExpectedNextStep.FINISH_ACTION) {
            return new ToolRouteDecision(
                    Set.of("finish_action"),
                    "FINISH_ACTION_STEP",
                    "当前阶段已获得足够工具结果，必须调用 finish_action 总结，"
                            + "不得继续调用业务工具。");
        }

        // 阶段 2: CALL_TOOL — 根据意图决定
        // 未识别意图（GENERAL 等）→ 通配路由，不限工具，确认机制兜底
        boolean wildcard = switch (intent) {
            case WORLD_SIM, STATUS_CHECK, GENERAL -> true;
            default -> false;
        };

        Set<String> allowed = switch (intent) {
            case PLAYER_ACTION_QUERY -> mergeWithDefaults(
                    defaultEnabledTools, PLAYER_ACTION_QUERY_ALLOW);
            case SHORT_POST_REWRITE -> mergeWithDefaults(
                    defaultEnabledTools, SHORT_POST_REWRITE_ALLOW);
            case KNOWLEDGE_SEARCH -> mergeWithDefaults(
                    defaultEnabledTools, KNOWLEDGE_SEARCH_ALLOW);
            case KNOWLEDGE_WRITE -> mergeWithDefaults(
                    defaultEnabledTools, KNOWLEDGE_WRITE_ALLOW);
            case NEXT_TURN_SETTLE -> mergeWithDefaults(
                    defaultEnabledTools, NEXT_TURN_SETTLE_ALLOW);
            case WORLD_SIM, STATUS_CHECK, GENERAL -> mergeWithDefaults(
                    defaultEnabledTools, GENERAL_ALLOW);
        };

        if (wildcard) {
            return ToolRouteDecision.wildcard(
                    Collections.unmodifiableSet(allowed), intent.name(),
                    "未识别明确意图 → 通配路由（不限工具），确认机制兜底安全。"
                            + "推荐使用: " + allowed);
        }

        return new ToolRouteDecision(
                Collections.unmodifiableSet(allowed),
                intent.name(),
                "路由规则: " + intent.name() + " (CALL_TOOL 阶段)");
    }

    /** 合并默认启用的只读工具到给定集合。 */
    private static Set<String> mergeWithDefaults(Set<String> defaults, Set<String> base) {
        java.util.Set<String> merged = new java.util.HashSet<>(base);
        for (String t : defaults) {
            if (ToolCategoryRegistry.isReadOnly(t)) {
                merged.add(t);
            }
        }
        return merged;
    }

    /**
     * 展开工具所属的所有路由组的工具集合。
     * 当 LLM 成功使用某工具后，激活该工具所属的全部工具组，下一轮可见。
     */
    public static java.util.Set<String> expandToolFamily(String toolName) {
        java.util.Set<String> expanded = new java.util.HashSet<>();
        // 检查每个预设组，如果工具在其中，则加入该组全部工具
        if (KNOWLEDGE_SEARCH_ALLOW.contains(toolName)) expanded.addAll(KNOWLEDGE_SEARCH_ALLOW);
        if (KNOWLEDGE_WRITE_ALLOW.contains(toolName)) expanded.addAll(KNOWLEDGE_WRITE_ALLOW);
        if (PLAYER_ACTION_QUERY_ALLOW.contains(toolName)) expanded.addAll(PLAYER_ACTION_QUERY_ALLOW);
        if (SHORT_POST_REWRITE_ALLOW.contains(toolName)) expanded.addAll(SHORT_POST_REWRITE_ALLOW);
        if (NEXT_TURN_SETTLE_ALLOW.contains(toolName)) expanded.addAll(NEXT_TURN_SETTLE_ALLOW);
        return expanded;
    }
}
