package com.gsim.agent;

import java.util.List;

/**
 * 组合多个 AgentProgressSink，将事件广播给所有 delegate。
 */
public class CompositeAgentProgressSink implements AgentProgressSink {

    private final List<AgentProgressSink> delegates;

    public CompositeAgentProgressSink(AgentProgressSink... delegates) {
        this.delegates = List.of(delegates);
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        for (AgentProgressSink sink : delegates) {
            try {
                sink.onProgress(event);
            } catch (Exception ignored) {
                // 单个 sink 失败不影响其他
            }
        }
    }
}
