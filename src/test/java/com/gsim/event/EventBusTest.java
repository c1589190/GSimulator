package com.gsim.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventBus 测试 — 验证事件发布/订阅/隔离。
 */
@DisplayName("EventBus")
class EventBusTest {

    @Test
    @DisplayName("应能广播事件到所有订阅者")
    void shouldBroadcastEvents() {
        EventBus bus = new EventBus();
        List<GSimEvent> received = new ArrayList<>();

        EventSink sink = received::add;
        bus.subscribe(sink);

        GSimEvent event = GSimEvent.of("test", "test_type", Map.of("key", "value"));
        bus.publish(event);

        assertEquals(1, received.size());
        assertEquals("test_type", received.get(0).type());
        assertEquals("test", received.get(0).sessionId());
        assertEquals("value", received.get(0).getString("key"));
    }

    @Test
    @DisplayName("应能广播到多个订阅者")
    void shouldBroadcastToMultipleSinks() {
        EventBus bus = new EventBus();
        List<GSimEvent> sink1 = new ArrayList<>();
        List<GSimEvent> sink2 = new ArrayList<>();

        bus.subscribe(sink1::add);
        bus.subscribe(sink2::add);

        bus.publish(GSimEvent.of("s", "type_a"));

        assertEquals(1, sink1.size());
        assertEquals(1, sink2.size());
    }

    @Test
    @DisplayName("取消订阅后不应收到事件")
    void shouldNotReceiveAfterUnsubscribe() {
        EventBus bus = new EventBus();
        List<GSimEvent> received = new ArrayList<>();
        EventSink sink = received::add;

        bus.subscribe(sink);
        bus.unsubscribe(sink);
        bus.publish(GSimEvent.of("s", "type_b"));

        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("单个 sink 异常不应影响其他 sink")
    void shouldIsolateSinkErrors() {
        EventBus bus = new EventBus();
        List<GSimEvent> received = new ArrayList<>();

        EventSink failingSink = e -> { throw new RuntimeException("boom"); };
        EventSink normalSink = received::add;

        bus.subscribe(failingSink);
        bus.subscribe(normalSink);

        // 不应抛出异常
        assertDoesNotThrow(() -> bus.publish(GSimEvent.of("s", "type_c")));
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("应正确报告 sink 数量")
    void shouldReportSinkCount() {
        EventBus bus = new EventBus();
        assertEquals(0, bus.sinkCount());

        EventSink s1 = e -> {};
        bus.subscribe(s1);
        assertEquals(1, bus.sinkCount());

        bus.subscribe(e -> {});
        assertEquals(2, bus.sinkCount());

        bus.unsubscribe(s1);
        assertEquals(1, bus.sinkCount());
    }

    @Test
    @DisplayName("shutdown 应关闭所有 sink")
    void shouldCloseAllSinksOnShutdown() {
        EventBus bus = new EventBus();
        List<String> closed = new ArrayList<>();
        bus.subscribe(new EventSink() {
            @Override
            public void accept(GSimEvent event) {}
            @Override
            public void close() { closed.add("sink1"); }
        });
        bus.subscribe(new EventSink() {
            @Override
            public void accept(GSimEvent event) {}
            @Override
            public void close() { closed.add("sink2"); }
        });

        bus.shutdown();
        assertEquals(2, closed.size());
        assertEquals(0, bus.sinkCount());
    }
}
