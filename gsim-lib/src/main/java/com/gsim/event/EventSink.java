package com.gsim.event;

/**
 * 事件消费者 (sink)。CLI ConsoleEventSink 和 HTTP SseEventSink 都实现此接口。
 *
 * <p>通过 {@link #accepts(GSimEvent)} 实现事件过滤，避免全局广播。
 */
public interface EventSink {

    /**
     * 判断是否接受该事件。
     * 默认接受所有事件。子类可覆写以实现按 sessionId / taskId 过滤。
     *
     * @param event 待判断的事件
     * @return true 如果该 sink 应接收此事件
     */
    default boolean accepts(GSimEvent event) {
        return true;
    }

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
