package com.gsim.event;

import com.gsim.api.SseWriter;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 带过滤的 SSE 事件 sink。
 *
 * <p>支持两种构造方式：
 * <ul>
 *   <li>{@link #FilteredEventSink(SseWriter, String, String)} — 按 sessionId/taskId 过滤，写入 SseWriter</li>
 *   <li>{@link #FilteredEventSink(Predicate, Consumer)} — 自定义过滤和消费逻辑</li>
 * </ul>
 */
public class FilteredEventSink implements EventSink {

    private final SseWriter sse;
    private final String sessionId;
    private final String taskId;
    private final Predicate<GSimEvent> filter;
    private final Consumer<GSimEvent> consumer;
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
        this.filter = null;
        this.consumer = null;
    }

    /**
     * 使用自定义过滤器和消费者构造。
     *
     * @param filter   事件过滤器
     * @param consumer 事件消费者
     */
    public FilteredEventSink(Predicate<GSimEvent> filter, Consumer<GSimEvent> consumer) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.sse = null;
        this.sessionId = null;
        this.taskId = null;
    }

    @Override
    public boolean accepts(GSimEvent event) {
        if (closed) return false;

        // 自定义过滤器优先
        if (filter != null) {
            return filter.test(event);
        }

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

        // 自定义消费者优先
        if (consumer != null) {
            consumer.accept(event);
            return;
        }

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
