package com.gsim.agent;

import com.gsim.tool.AgentTool;

/**
 * 工具执行前门禁 — 基于 AgentTool.permission() 进行路由和权限验证。
 *
 * <p>规则优先级：
 * <ol>
 *   <li>工具不在 allowedTools → REJECT</li>
 *   <li>Permission.READ → ALLOW</li>
 *   <li>Permission.WRITE → NEED_CONFIRMATION（unless allowAllMutations）</li>
 *   <li>Permission.SYSTEM → NEED_CONFIRMATION（永不免除，除非当前上下文中为 MCP 入口）</li>
 * </ol>
 *
 * <p>与旧版 {@link ToolCategoryRegistry} 不同，此版本从工具自身的 {@code permission()}
 * 方法获取权限等级，避免了集中式静态映射的维护成本。
 */
public class ToolExecutionPolicy {

    // SYSTEM_CONTROL_TOOLS removed — flow control tools now use Permission.SELF instead

    /** 是否为 MCP 模式 — MCP 入口拥有 SYSTEM 权限，免确认。 */
    private final boolean mcpMode;

    public ToolExecutionPolicy() {
        this(false);
    }

    public ToolExecutionPolicy(boolean mcpMode) {
        this.mcpMode = mcpMode;
    }

    /**
     * 对给定的工具调用进行执行前验证。
     *
     * @param toolName           工具名
     * @param args               工具参数（用于错误消息生成）
     * @param routeDecision      本轮路由决策（含 allowedTools）
     * @param allowAllMutations  是否已设置"一直允许本轮"
     * @return 执行决策
     */
    public ToolExecutionDecision validateBeforeExecute(
            String toolName,
            java.util.Map<String, String> args,
            ToolRouteDecision routeDecision,
            boolean allowAllMutations,
            AgentTool.Permission permission,
            java.util.Set<String> knownTools) {

        // Rule 1: 工具不在 allowedTools（通配路由跳过此检查）
        // 不在任何工具组中的未知工具始终允许
        boolean inAllowed = routeDecision.allToolsAllowed()
                || routeDecision.allowedTools().contains(toolName)
                || !knownTools.contains(toolName);
        if (!inAllowed) {
            ToolCategory category = mapToCategory(permission);
            return ToolExecutionDecision.reject(
                    "工具调用被系统拒绝：" + toolName
                            + "。原因：当前路由 " + routeDecision.routeName()
                            + " 不允许此工具。允许的工具：" + routeDecision.allowedTools()
                            + "。请改用允许的工具，或调用 activate_tool_groups 激活所需工具组，"
                            + "或调用 finish_action 结束。",
                    category,
                    false);
        }

        // Rule 2: SELF → ALWAYS ALLOW（Agent 自身流程控制，最低权限，任意时刻可用）
        if (permission == AgentTool.Permission.SELF) {
            return ToolExecutionDecision.allow("Agent 自控工具，始终允许。", ToolCategory.CONTROL, true);
        }

        // Rule 3: READ → ALLOW
        if (permission == AgentTool.Permission.READ) {
            return ToolExecutionDecision.allow("只读工具，允许直接执行。", ToolCategory.READ_ONLY, true);
        }

        // Rule 4: SYSTEM — 破坏性操作需确认（MCP 模式免确认）
        if (permission == AgentTool.Permission.SYSTEM) {
            if (mcpMode) {
                return ToolExecutionDecision.allow("MCP 入口 — 系统级工具免确认。", ToolCategory.CONTROL, true);
            }
            return ToolExecutionDecision.needConfirmation(
                    "⚠ 系统级操作：" + toolName + " 可能删除/覆盖数据，需要用户确认。", ToolCategory.MUTATING, true);
        }

        // Rule 5: WRITE → 需确认（unless allowAllMutations）
        if (permission == AgentTool.Permission.WRITE) {
            if (allowAllMutations) {
                return ToolExecutionDecision.allow("写入工具，本轮已授权全部写入。", ToolCategory.MUTATING, true);
            }
            return ToolExecutionDecision.needConfirmation(
                    "写入工具：" + toolName + " 将修改数据，需要用户确认。", ToolCategory.MUTATING, true);
        }

        // Fallback: conservative
        return ToolExecutionDecision.needConfirmation("未知权限等级的工具：" + toolName, ToolCategory.MUTATING, true);
    }

    /** 兼容旧版调用（无 Permission 参数，从 ToolCategoryRegistry 查找）。 */
    public ToolExecutionDecision validateBeforeExecute(
            String toolName,
            java.util.Map<String, String> args,
            ToolRouteDecision routeDecision,
            boolean allowAllMutations,
            java.util.Set<String> knownTools) {
        AgentTool.Permission permission = resolvePermission(toolName);
        return validateBeforeExecute(toolName, args, routeDecision, allowAllMutations, permission, knownTools);
    }

    /**
     * 从 ToolCategoryRegistry 解析 Permission — 过渡期桥接。
     * 逐步迁移到直接传入 Permission 的新调用路径。
     */
    private static AgentTool.Permission resolvePermission(String toolName) {
        ToolCategory cat = ToolCategoryRegistry.categoryOf(toolName);
        return categoryToPermission(cat);
    }

    /** Permission → ToolCategory 映射（用于旧版 ToolExecutionDecision API）。 */
    static ToolCategory mapToCategory(AgentTool.Permission p) {
        return switch (p) {
            case SELF -> ToolCategory.CONTROL;
            case READ -> ToolCategory.READ_ONLY;
            case WRITE -> ToolCategory.MUTATING;
            case SYSTEM -> ToolCategory.MUTATING; // SYSTEM maps closest to MUTATING in old category system
        };
    }

    /** ToolCategory → Permission 映射。 */
    static AgentTool.Permission categoryToPermission(ToolCategory cat) {
        return switch (cat) {
            case READ_ONLY -> AgentTool.Permission.READ;
            case MUTATING -> AgentTool.Permission.WRITE;
            case CONTROL -> AgentTool.Permission.SELF;
            case DESTRUCTIVE -> AgentTool.Permission.SYSTEM;
        };
    }

    /**
     * 构建被拒工具的回灌消息。
     */
    public String buildRejectionReprompt(String toolName, ToolExecutionDecision decision, ToolRouteDecision route) {
        return "[系统] 工具调用被系统拒绝：" + toolName + "。\n"
                + "原因：" + decision.reason() + "\n"
                + "允许的工具：" + route.allowedTools() + "\n"
                + "请改用允许的工具，或调用 finish_action 结束本轮。";
    }

    /**
     * 构建用户拒绝确认后的结束消息。
     */
    public String buildDenyStopMessage(String toolName) {
        return "已拒绝执行工具 " + toolName + "，本轮已停止。请给出下一步指令。";
    }
}
