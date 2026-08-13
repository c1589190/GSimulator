package com.gsim.agent.core;

import com.gsim.agent.OrchestratorAgent.ToolCallRecord;
import com.gsim.llm.LlmMessage;
import java.util.List;

/**
 * AgentRound -- Agent 一轮对话记录。
 *
 * <p>包含该轮完整消息列表、工具调用记录、结束信息和推理文本，
 * 用于审计、回放和调试。
 *
 * @param round         轮次序号（从 1 开始）
 * @param messages      该轮完整消息列表
 * @param toolCalls     该轮执行的工具调用记录
 * @param finishMessage 若通过 finish_action 结束，则为最终输出
 * @param thinking      该轮 LLM 推理文本
 */
public record AgentRound(
        int round, List<LlmMessage> messages, List<ToolCallRecord> toolCalls, String finishMessage, String thinking) {
    /**
     * 创建一轮简单记录（不含工具调用和结束信息）。
     *
     * @param round    轮次序号
     * @param messages 该轮完整消息列表
     * @return 新创建的 AgentRound 实例
     */
    public static AgentRound of(int round, List<LlmMessage> messages) {
        return new AgentRound(round, messages, List.of(), null, "");
    }
}
