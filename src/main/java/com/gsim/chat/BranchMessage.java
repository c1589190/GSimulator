package com.gsim.chat;

import java.time.Instant;

/**
 * Branch 消息块 — 嵌入在 branch 文件中的单条对话/工具记录。
 */
public record BranchMessage(
        String id,        // m0001, m0002 ...
        String role,      // system, user, assistant, tool
        String type,      // chat_user, chat_response, sim_user, sim_response, tool_call, tool_result, state_update, rule_update, skill_delta, system_note, error
        String toolName,  // 可选，工具名
        Instant createdAt,
        String content
) {
    public static BranchMessage create(String id, String role, String type, String content) {
        return new BranchMessage(id, role, type, null, Instant.now(), content);
    }

    public static BranchMessage tool(String id, String type, String toolName, String content) {
        return new BranchMessage(id, "tool", type, toolName, Instant.now(), content);
    }

    /** 序列化为 message block 文本。 */
    public String toBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- message:start id=").append(id)
                .append(" role=").append(role)
                .append(" type=").append(type);
        if (toolName != null && !toolName.isBlank()) sb.append(" tool=").append(toolName);
        sb.append(" created=").append(createdAt.toString()).append(" -->\n");
        sb.append(content).append("\n");
        sb.append("<!-- message:end -->\n");
        return sb.toString();
    }
}
