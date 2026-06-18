package com.gsim.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线 — 线程安全的事件发布/订阅。
 *
 * <p>CLI 和 HTTP SSE 都通过 EventBus 消费事件。
 * 每个 ApplicationContext 持有一个 EventBus 实例。
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();

    /**
     * 订阅事件。
     */
    public void subscribe(EventSink sink) {
        sinks.add(sink);
        log.debug("EventSink subscribed: {}", sink.getClass().getSimpleName());
    }

    /**
     * 取消订阅。
     */
    public void unsubscribe(EventSink sink) {
        sinks.remove(sink);
        log.debug("EventSink unsubscribed: {}", sink.getClass().getSimpleName());
    }

    /**
     * 发布事件到所有订阅者。
     * 每个 sink 的异常被隔离，不会影响其他 sink。
     */
    public void publish(GSimEvent event) {
        for (EventSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (Exception e) {
                log.warn("EventSink {} failed to handle event {}: {}",
                        sink.getClass().getSimpleName(), event.type(), e.getMessage());
            }
        }
    }

    /**
     * 获取当前订阅者数量。
     */
    public int sinkCount() {
        return sinks.size();
    }

    /**
     * 关闭所有 sink 并清空。
     */
    public void shutdown() {
        for (EventSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                log.warn("Error closing EventSink: {}", e.getMessage());
            }
        }
        sinks.clear();
    }
}
