package com.gsim.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装饰器 — 给所有 AgentProgressEvent.meta 注入 agentId、taskId、sessionId。
 *
 * <p>用于 SubAgent 的进度事件路由。SubAgent 运行在独立 Virtual Thread，
 * 没有 ThreadLocal 绑定的 taskId/sessionId，因此必须在构造时从父线程捕获。
 * 前端通过 agentId 区分不同 SubAgent 的流式输出。
 */
public class TaggedAgentProgressSink implements AgentProgressSink {

    private final AgentProgressSink delegate;
    private final String agentId;
    private final String taskId;
    private final String sessionId;

    /**
     * @param delegate   底层 sink（通常是 EventBusAgentProgressSink）
     * @param agentId    SubAgent 标识（如 "sim-1", "search-2"）
     * @param taskId     父任务 ID（从父线程 ThreadLocal 捕获）
     * @param sessionId  会话 ID（从父线程 ThreadLocal 捕获）
     */
    public TaggedAgentProgressSink(AgentProgressSink delegate, String agentId,
                                   String taskId, String sessionId) {
        this.delegate = delegate;
        this.agentId = agentId;
        this.taskId = taskId;
        this.sessionId = sessionId;
    }

    /** 简化构造器（用于 CLI 命令，不需要 taskId）。 */
    public TaggedAgentProgressSink(AgentProgressSink delegate, String agentId) {
        this(delegate, agentId, null, null);
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        Map<String, String> newMeta = new LinkedHashMap<>(event.meta());
        newMeta.put("agentId", agentId);
        if (taskId != null) newMeta.put("taskId", taskId);
        if (sessionId != null) newMeta.put("sessionId", sessionId);
        delegate.onProgress(new AgentProgressEvent(
                event.phase(), event.round(), event.maxRounds(),
                event.detail(), newMeta));
    }
}
