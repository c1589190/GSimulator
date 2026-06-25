package com.gsim.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组合多个 AgentProgressSink，将事件广播给所有 delegate。
 * 支持运行时动态 addSink / removeSink（线程安全）。
 */
public class CompositeAgentProgressSink implements AgentProgressSink {

    private final List<AgentProgressSink> delegates;

    public CompositeAgentProgressSink(AgentProgressSink... delegates) {
        this.delegates = new CopyOnWriteArrayList<>();
        for (var d : delegates) {
            if (d != null) this.delegates.add(d);
        }
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        for (AgentProgressSink sink : delegates) {
            try { sink.onProgress(event); } catch (Exception ignored) {}
        }
    }

    public void addSink(AgentProgressSink sink) {
        if (sink != null) delegates.add(sink);
    }

    public void removeSink(AgentProgressSink sink) {
        delegates.remove(sink);
    }
}
