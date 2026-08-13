package com.gsim.agent.core;

import java.util.List;

/**
 * AgentResult -- Agent 完整执行结果。
 *
 * <p>包含全部轮次记录、最终文本、工具调用统计以及可选的错误信息和缓存会话 ID。
 * 通过静态工厂方法 {@link #ok} 和 {@link #fail} 创建成功或失败的结果实例。
 *
 * @param agentId        Agent 标识符
 * @param success        是否成功完成
 * @param finalText      最终输出文本
 * @param rounds         完整轮次记录列表
 * @param totalToolCalls 本轮执行的总工具调用次数
 * @param error          失败时的错误信息
 * @param cacheSessionId 对应 Cache 的 sessionId（文件名）
 */
public record AgentResult(
        String agentId,
        boolean success,
        String finalText,
        List<AgentRound> rounds,
        int totalToolCalls,
        String error,
        String cacheSessionId // 该 Agent 对应 Cache 的 sessionId（文件名）
        ) {
    /**
     * 创建成功结果（不含缓存会话 ID）。
     *
     * @param agentId        Agent 标识符
     * @param finalText      最终输出文本
     * @param rounds         完整轮次记录列表
     * @param totalToolCalls 本轮执行的总工具调用次数
     * @return 成功结果实例
     */
    public static AgentResult ok(String agentId, String finalText, List<AgentRound> rounds, int totalToolCalls) {
        return new AgentResult(agentId, true, finalText, rounds, totalToolCalls, null, null);
    }

    /**
     * 创建成功结果（含缓存会话 ID）。
     *
     * @param agentId        Agent 标识符
     * @param finalText      最终输出文本
     * @param rounds         完整轮次记录列表
     * @param totalToolCalls 本轮执行的总工具调用次数
     * @param cacheSessionId 对应 Cache 的 sessionId
     * @return 成功结果实例
     */
    public static AgentResult ok(
            String agentId, String finalText, List<AgentRound> rounds, int totalToolCalls, String cacheSessionId) {
        return new AgentResult(agentId, true, finalText, rounds, totalToolCalls, null, cacheSessionId);
    }

    /**
     * 创建失败结果（不含缓存会话 ID）。
     *
     * @param agentId Agent 标识符
     * @param error   错误描述
     * @return 失败结果实例
     */
    public static AgentResult fail(String agentId, String error) {
        return new AgentResult(agentId, false, null, List.of(), 0, error, null);
    }

    /**
     * 创建失败结果（含缓存会话 ID）。
     *
     * @param agentId        Agent 标识符
     * @param error          错误描述
     * @param cacheSessionId 对应 Cache 的 sessionId
     * @return 失败结果实例
     */
    public static AgentResult fail(String agentId, String error, String cacheSessionId) {
        return new AgentResult(agentId, false, null, List.of(), 0, error, cacheSessionId);
    }
}
