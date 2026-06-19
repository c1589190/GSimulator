package com.gsim.agent;

import java.util.Set;

/**
 * 执行前工具门禁 — 在 ToolLoop 执行任何工具前验证路由和权限。
 *
 * <p>规则优先级：
 * <ol>
 *   <li>工具不在 allowedTools → REJECT</li>
 *   <li>expectedNextStep=FINISH_ACTION 且 tool≠finish_action → REJECT</li>
 *   <li>finish_action 与其他工具同轮 → REJECT（在 OrchestratorAgent 中检查）</li>
 *   <li>READ_ONLY → ALLOW</li>
 *   <li>MUTATING → NEED_CONFIRMATION（unless allowAllMutations）</li>
 *   <li>DESTRUCTIVE → NEED_CONFIRMATION（永不免除）</li>
 *   <li>CONTROL（finish_action）→ ALLOW</li>
 * </ol>
 */
public class ToolExecutionPolicy {

    private final ToolCategoryRegistry categoryRegistry;

    public ToolExecutionPolicy() {
        this.categoryRegistry = new ToolCategoryRegistry(); // static methods, instance not used
    }

    /**
     * 对给定的工具调用进行执行前验证。
     *
     * @param toolName           工具名
     * @param args               工具参数
     * @param routeDecision      本轮路由决策（含 allowedTools）
     * @param expectedNextStep   当前阶段
     * @param allowAllMutations  是否已设置"一直允许本轮"
     * @return 执行决策
     */
    public ToolExecutionDecision validateBeforeExecute(
            String toolName,
            java.util.Map<String, String> args,
            ToolRouteDecision routeDecision,
            ExpectedNextStep expectedNextStep,
            boolean allowAllMutations) {

        ToolCategory category = ToolCategoryRegistry.categoryOf(toolName);

        // Rule 1: 工具不在 allowedTools（通配路由跳过此检查）
        boolean inAllowed = routeDecision.allToolsAllowed()
                || routeDecision.allowedTools().contains(toolName);
        if (!inAllowed) {
            return ToolExecutionDecision.reject(
                    "工具调用被系统拒绝：" + toolName
                            + "。原因：当前路由 " + routeDecision.routeName()
                            + " 不允许此工具。允许的工具：" + routeDecision.allowedTools()
                            + "。请改用允许的工具或调用 finish_action 结束。",
                    category, false);
        }

        // Rule 2: FINISH_ACTION 阶段只能调用 finish_action
        if (expectedNextStep == ExpectedNextStep.FINISH_ACTION
                && !"finish_action".equals(toolName)) {
            return ToolExecutionDecision.reject(
                    "工具调用被系统拒绝：" + toolName
                            + "。原因：当前阶段要求 FINISH_ACTION，只能调用 finish_action。"
                            + "不得继续调用业务工具。请调用 finish_action 总结。",
                    category, true);
        }

        // Rule 4: READ_ONLY → ALLOW
        if (category == ToolCategory.READ_ONLY) {
            return ToolExecutionDecision.allow(
                    "只读工具，允许直接执行。", category, true);
        }

        // Rule 6: DESTRUCTIVE → 永远需要确认
        if (category == ToolCategory.DESTRUCTIVE) {
            return ToolExecutionDecision.needConfirmation(
                    "⚠ 破坏性操作：" + toolName + " 可能删除/覆盖数据，需要用户确认。",
                    category, true);
        }

        // Rule 5: MUTATING → 需确认（除非 allowAllMutations）
        if (category == ToolCategory.MUTATING) {
            if (allowAllMutations) {
                return ToolExecutionDecision.allow(
                        "写入工具，本轮已授权全部写入。", category, true);
            }
            return ToolExecutionDecision.needConfirmation(
                    "写入工具：" + toolName + " 将修改数据，需要用户确认。",
                    category, true);
        }

        // Rule 7: CONTROL → ALLOW
        return ToolExecutionDecision.allow("控制流工具。", category, true);
    }

    /** 构建被拒工具的回灌消息（让 LLM 重写）。 */
    public String buildRejectionReprompt(
            String toolName,
            ToolExecutionDecision decision,
            ToolRouteDecision route) {
        return "[系统] 工具调用被系统拒绝：" + toolName + "。\n"
                + "原因：" + decision.reason() + "\n"
                + "允许的工具：" + route.allowedTools() + "\n"
                + "请改用允许的工具，或调用 finish_action 结束本轮。";
    }

    /** 构建拒绝后直接结束的消息（用户选择了 DENY）。 */
    public String buildDenyStopMessage(String toolName) {
        return "已拒绝执行工具 " + toolName + "，本轮已停止。请给出下一步指令。";
    }
}
