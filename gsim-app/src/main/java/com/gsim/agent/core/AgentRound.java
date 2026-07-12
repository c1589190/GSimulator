package com.gsim.agent.core;

import com.gsim.agent.OrchestratorAgent.ToolCallRecord;
import com.gsim.llm.LlmMessage;

import java.util.List;

/**
 * Agent 一轮对话 — 包含该轮完整消息、工具调用、推理文本。
 */
public record AgentRound(
        int round,
        List<LlmMessage> messages,
        List<ToolCallRecord> toolCalls,
        String finishMessage,
        String thinking
) {
    public static AgentRound of(int round, List<LlmMessage> messages) {
        return new AgentRound(round, messages, List.of(), null, "");
    }
}
