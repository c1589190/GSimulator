package com.gsim.event;

import com.gsim.api.SseWriter;

import java.io.IOException;
import java.util.Objects;

/**
 * 带过滤的 SSE 事件 sink。
 *
 * <p>只接受匹配 sessionId 和/或 taskId 的事件。
 * 用于按需订阅特定 task 的 SSE 流。
 */
public class FilteredEventSink implements EventSink {

    private final SseWriter sse;
    private final String sessionId;
    private final String taskId;
    private volatile boolean closed = false;

    /**
     * @param sse       SSE 写入器
     * @param sessionId 过滤的 sessionId（null 表示不过滤）
     * @param taskId    过滤的 taskId（null 表示不过滤）
     */
    public FilteredEventSink(SseWriter sse, String sessionId, String taskId) {
        this.sse = Objects.requireNonNull(sse, "sse");
        this.sessionId = sessionId;
        this.taskId = taskId;
    }

    @Override
    public boolean accepts(GSimEvent event) {
        if (closed) return false;

        // 按 sessionId 过滤
        if (sessionId != null && !sessionId.equals(event.sessionId())) {
            return false;
        }

        // 按 taskId 过滤
        if (taskId != null && !taskId.equals(event.taskId())) {
            return false;
        }

        return true;
    }

    @Override
    public void accept(GSimEvent event) {
        if (closed) return;

        try {
            sse.writeEvent(event.type(), SseEventSink.toSseJson(event));
        } catch (IOException e) {
            closed = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        // SseWriter 由调用者管理关闭
    }

    public boolean isClosed() {
        return closed;
    }
}
