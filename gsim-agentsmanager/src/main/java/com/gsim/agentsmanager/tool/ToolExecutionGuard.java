package com.gsim.agentsmanager.tool;

import java.util.Set;

/**
 * 工具执行统一门禁 — MCP 和内部 Agent ToolLoop 的最终可信边界。
 *
 * <h3>检查项（按顺序）</h3>
 * <ol>
 *   <li>工具是否存在（Registry 中查找）</li>
 *   <li>MCP surface：工具是否 {@code mcpExposed()}（未暴露 → 视为不存在）</li>
 *   <li>{@code alwaysAvailable()} → 跳过组检查，继续 Permission 检查</li>
 *   <li>工具所属组 ∩ 当前激活组 ≠ ∅</li>
 *   <li>{@link AgentConfig#allowedToolGroups()} 是否包含所需组（防权限提升）</li>
 *   <li>Permission 校验（READ/WRITE/SYSTEM）</li>
 * </ol>
 *
 * <p>模型可能记住旧工具名、手工构造 ToolCall 或通过其他路径调用，
 * 因此执行入口必须是最终可信边界 — 不能认为"没有注入给模型"就等于"无法执行"。
 */
public final class ToolExecutionGuard {

    /** 调用 surface 类型。 */
    public enum Surface {
        /** 内部 Agent ToolLoop — 需要组激活和 AgentConfig 检查。 */
        AGENT,
        /** MCP 外部调用 — 只需 mcpExposed 检查，不检查组激活。 */
        MCP
    }

    private ToolExecutionGuard() {}

    // ── 结果类型 ───────────────────────────────────────────────

    /**
     * 门禁检查结果。
     *
     * @param allowed      是否允许执行
     * @param errorCode    错误码（不允许时填充），如 "TOOL_GROUP_NOT_ACTIVE"
     * @param errorMessage 人类可读的错误消息
     */
    public record GuardResult(boolean allowed, String errorCode, String errorMessage) {
        public static GuardResult allow() {
            return new GuardResult(true, null, null);
        }

        public static GuardResult deny(String code, String message) {
            return new GuardResult(false, code, message);
        }
    }

    // ── 门禁入口 ────────────────────────────────────────────────

    /**
     * MCP surface 门禁。
     * 只检查工具存在性和 mcpExposed，不检查工具组。
     */
    public static GuardResult checkMcp(ToolRegistry registry, ToolCall call) {
        AgentTool tool = registry.get(call.toolName());
        if (tool == null) {
            return GuardResult.deny("UNKNOWN_TOOL", "Tool not found: " + call.toolName());
        }
        if (!tool.mcpExposed()) {
            return GuardResult.deny("MCP_TOOL_NOT_EXPOSED", "Tool '" + call.toolName() + "' is not available via MCP");
        }
        return GuardResult.allow();
    }

    /**
     * Agent surface 门禁。
     * 检查工具存在性、alwaysAvailable、组激活状态、AgentConfig 授权。
     *
     * @param registry      工具注册表
     * @param call          工具调用请求
     * @param activeGroups  当前 Agent 已激活的工具组 key 集合
     * @param allowedGroups AgentConfig 允许的工具组 key 集合（null = 不限制）
     */
    public static GuardResult checkAgent(
            ToolRegistry registry, ToolCall call, Set<String> activeGroups, Set<String> allowedGroups) {
        AgentTool tool = registry.get(call.toolName());
        if (tool == null) {
            return GuardResult.deny("UNKNOWN_TOOL", "Tool not found: " + call.toolName());
        }

        // alwaysAvailable → skip group checks
        if (tool.alwaysAvailable()) {
            return GuardResult.allow();
        }

        // Check group activation
        Set<String> toolGroups = tool.toolGroups();
        if (!toolGroups.isEmpty()) {
            boolean anyGroupActive = false;
            String requiredGroup = null;
            for (String g : toolGroups) {
                if (activeGroups.contains(g)) {
                    anyGroupActive = true;
                    break;
                }
                requiredGroup = g; // remember for error message
            }
            if (!anyGroupActive) {
                return GuardResult.deny(
                        "TOOL_GROUP_NOT_ACTIVE",
                        "Tool '" + call.toolName() + "' requires group '"
                                + (requiredGroup != null ? requiredGroup : "unknown")
                                + "' to be activated. Use activate_tool_groups first.");
            }

            // AgentConfig authorization check (prevent privilege escalation via cache)
            if (allowedGroups != null && !allowedGroups.isEmpty()) {
                boolean authorized = false;
                for (String g : toolGroups) {
                    if (allowedGroups.contains(g)) {
                        authorized = true;
                        break;
                    }
                }
                if (!authorized) {
                    return GuardResult.deny(
                            "TOOL_GROUP_NOT_ALLOWED",
                            "Tool '" + call.toolName() + "' requires group(s) "
                                    + toolGroups + " which are not in AgentConfig.allowedToolGroups: "
                                    + allowedGroups);
                }
            }
        }

        return GuardResult.allow();
    }
}
