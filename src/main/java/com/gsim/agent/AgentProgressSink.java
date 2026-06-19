package com.gsim.agent;

/**
 * Agent 进度输出侧通道。
 * 只能作为 side-channel 输出到 CLI / 日志，
 * 绝不能写入 BranchMessageStore，也不能进入 LLM messages。
 */
@FunctionalInterface
public interface AgentProgressSink {
    void onProgress(AgentProgressEvent event);

    /** 空实现，测试和非 CLI 使用。 */
    AgentProgressSink NOOP = event -> {};
}
