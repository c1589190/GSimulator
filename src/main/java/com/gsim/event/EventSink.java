package com.gsim.event;

/**
 * 事件消费者 (sink)。CLI ConsoleEventSink 和 HTTP SseEventSink 都实现此接口。
 */
public interface EventSink {

    /**
     * 接收一个事件。
     *
     * @param event 要处理的事件
     */
    void accept(GSimEvent event);

    /**
     * 关闭 sink，释放资源。
     */
    default void close() {
        // no-op by default
    }
}
