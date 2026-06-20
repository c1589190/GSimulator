package com.gsim.agent;

import java.time.Instant;

/**
 * LLM 流式输出的不可变快照。
 * 供 CLI 灰框 / WebUI 等渲染层读取，不暴露内部可变状态。
 */
public record LlmStreamSnapshot(
        String streamId,
        String reasoning,
        String content,
        int reasoningDeltaCount,
        int contentDeltaCount,
        int toolCallDeltaCount,
        boolean active,
        boolean completed,
        String error
) {
    /** 空快照（stream 尚未开始或已被移除）。 */
    public static final LlmStreamSnapshot EMPTY = new LlmStreamSnapshot(
            "", "", "", 0, 0, 0, false, false, null);

    /** 是否有任何可见内容（reasoning / content / tool call）。 */
    public boolean hasAnyContent() {
        return (reasoning != null && !reasoning.isEmpty())
                || (content != null && !content.isEmpty())
                || toolCallDeltaCount > 0;
    }
}
