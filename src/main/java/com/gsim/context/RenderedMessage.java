package com.gsim.context;

/**
 * 渲染后的单条上下文消息。
 */
public record RenderedMessage(
        String role,      // system, user, assistant, tool
        String type,      // system_prompt, branch_input, branch_user, branch_assistant, tool_call, tool_result, effective_data, current_input
        String branchId,  // 来源 branch id，system_prompt 时为空
        String content
) {}
