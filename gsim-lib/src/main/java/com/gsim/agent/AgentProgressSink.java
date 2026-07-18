package com.gsim.agent;

/**
 * AgentProgressSink -- Agent 进度输出侧通道。
 *
 * <p>只能作为 side-channel 输出到 CLI / 日志，
 * 绝不能写入 BranchMessageStore，也不能进入 LLM messages。
 * 函数式接口，可由 Lambda 实现。
 */
@FunctionalInterface
public interface AgentProgressSink {
    void onProgress(AgentProgressEvent event);

    /** 空实现，测试和非 CLI 使用。 */
    AgentProgressSink NOOP = event -> {};
}
