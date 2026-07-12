package com.gsim.api;

import com.gsim.event.EventBus;
import com.gsim.event.EventSink;
import com.gsim.event.GSimEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventBus 过滤测试 — 验证 accepts() 方法和 FilteredEventSink。
 */
@DisplayName("EventBus Filtering")
class EventBusFilteringTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
    }

    @Test
    @DisplayName("默认 accepts 应接受所有事件")
    void defaultAcceptsShouldAcceptAll() {
        List<GSimEvent> received = new ArrayList<>();
        eventBus.subscribe(new EventSink() {
            @Override
            public void accept(GSimEvent event) {
                received.add(event);
            }
        });

        eventBus.publish(GSimEvent.of("s1", "type1"));
        eventBus.publish(GSimEvent.of("s2", "type2"));

        assertEquals(2, received.size());
    }

    @Test
    @DisplayName("两个 task sink 不应互相收到事件")
    void twoTaskSinksShouldNotCrossReceive() {
        List<String> events1 = new ArrayList<>();
        List<String> events2 = new ArrayList<>();

        EventSink sink1 = new EventSink() {
            @Override
            public boolean accepts(GSimEvent event) {
                return "task-1".equals(event.taskId());
            }

            @Override
            public void accept(GSimEvent event) {
                events1.add(event.type());
            }
        };

        EventSink sink2 = new EventSink() {
            @Override
            public boolean accepts(GSimEvent event) {
                return "task-2".equals(event.taskId());
            }

            @Override
            public void accept(GSimEvent event) {
                events2.add(event.type());
            }
        };

        eventBus.subscribe(sink1);
        eventBus.subscribe(sink2);

        eventBus.publish(GSimEvent.of("s1", "task-1", "command_started", Map.of()));
        eventBus.publish(GSimEvent.of("s1", "task-2", "command_started", Map.of()));
        eventBus.publish(GSimEvent.of("s1", "task-1", "done", Map.of()));
        eventBus.publish(GSimEvent.of("s1", "task-2", "done", Map.of()));

        // sink1 只应收到 task-1 的事件
        assertEquals(2, events1.size());
        assertTrue(events1.contains("command_started"));
        assertTrue(events1.contains("done"));

        // sink2 只应收到 task-2 的事件
        assertEquals(2, events2.size());
        assertTrue(events2.contains("command_started"));
        assertTrue(events2.contains("done"));
    }

    @Test
    @DisplayName("sessionId 过滤应正确")
    void shouldFilterBySessionId() {
        List<String> events = new ArrayList<>();

        EventSink sink = new EventSink() {
            @Override
            public boolean accepts(GSimEvent event) {
                return "s1".equals(event.sessionId());
            }

            @Override
            public void accept(GSimEvent event) {
                events.add(event.type());
            }
        };

        eventBus.subscribe(sink);

        eventBus.publish(GSimEvent.of("s1", "t1", "type1", Map.of()));
        eventBus.publish(GSimEvent.of("s2", "t2", "type2", Map.of()));
        eventBus.publish(GSimEvent.of("s1", "t3", "type3", Map.of()));

        assertEquals(2, events.size());
        assertTrue(events.contains("type1"));
        assertFalse(events.contains("type2"));
        assertTrue(events.contains("type3"));
    }

    @Test
    @DisplayName("sessionId + taskId 联合过滤")
    void shouldFilterBySessionAndTask() {
        List<String> events = new ArrayList<>();

        EventSink sink = new EventSink() {
            @Override
            public boolean accepts(GSimEvent event) {
                boolean sessionMatch = "s1".equals(event.sessionId());
                boolean taskMatch = event.taskId() == null || "task-1".equals(event.taskId());
                return sessionMatch && taskMatch;
            }

            @Override
            public void accept(GSimEvent event) {
                events.add(event.type());
            }
        };

        eventBus.subscribe(sink);

        // 完全匹配
        eventBus.publish(GSimEvent.of("s1", "task-1", "match", Map.of()));
        // taskId 不匹配
        eventBus.publish(GSimEvent.of("s1", "task-2", "mismatch_task", Map.of()));
        // sessionId 不匹配
        eventBus.publish(GSimEvent.of("s2", "task-1", "mismatch_session", Map.of()));
        // 无 taskId 但 sessionId 匹配 — 应接受
        eventBus.publish(GSimEvent.of("s1", "no_task_event", Map.of()));

        assertEquals(2, events.size());
        assertTrue(events.contains("match"));
        assertTrue(events.contains("no_task_event"));
        assertFalse(events.contains("mismatch_task"));
        assertFalse(events.contains("mismatch_session"));
    }

    @Test
    @DisplayName("sink 取消订阅后不应收到事件")
    void unsubscribedSinkShouldNotReceiveEvents() {
        List<String> received = new ArrayList<>();
        EventSink sink = new EventSink() {
            @Override
            public void accept(GSimEvent event) {
                received.add(event.type());
            }
        };

        eventBus.subscribe(sink);
        eventBus.publish(GSimEvent.of("s1", "e1"));
        assertEquals(1, received.size());

        eventBus.unsubscribe(sink);
        eventBus.publish(GSimEvent.of("s1", "e2"));
        assertEquals(1, received.size());
    }
}
