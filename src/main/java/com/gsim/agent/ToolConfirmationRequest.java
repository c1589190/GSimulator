package com.gsim.agent;

import java.util.Map;

/**
 * 工具确认请求 — 当 MUTATING/DESTRUCTIVE 工具需要用户确认时发送。
 */
public record ToolConfirmationRequest(
        String toolName,
        ToolCategory category,
        String reason,
        Map<String, String> params,
        String branchId) {
}
