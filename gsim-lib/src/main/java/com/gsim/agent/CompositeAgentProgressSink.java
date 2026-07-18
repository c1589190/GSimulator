package com.gsim.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组合多个 AgentProgressSink，将事件广播给所有 delegate。
 * 支持运行时动态 addSink / removeSink（线程安全）。
 */
public class CompositeAgentProgressSink implements AgentProgressSink {

    private final List<AgentProgressSink> delegates;

    /**
     * 创建组合 sink，将传入的所有 delegate 加入广播列表。
     *
     * @param delegates 要组合的 AgentProgressSink 实例列表
     */
    public CompositeAgentProgressSink(AgentProgressSink... delegates) {
        this.delegates = new CopyOnWriteArrayList<>();
        for (var d : delegates) {
            if (d != null) this.delegates.add(d);
        }
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        for (AgentProgressSink sink : delegates) {
            try {
                sink.onProgress(event);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 动态添加一个进度输出目标。
     *
     * @param sink 要添加的 AgentProgressSink
     */
    public void addSink(AgentProgressSink sink) {
        if (sink != null) delegates.add(sink);
    }

    /**
     * 动态移除一个进度输出目标。
     *
     * @param sink 要移除的 AgentProgressSink
     */
    public void removeSink(AgentProgressSink sink) {
        delegates.remove(sink);
    }
}
