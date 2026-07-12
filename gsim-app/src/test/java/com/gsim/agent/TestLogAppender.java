package com.gsim.agent;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Log4j2 等效的 ListAppender — 捕获日志事件用于测试断言。
 * 配合 {@code LoggerContext.addAppender} 和 {@code ctx.updateLoggers()} 使用。
 */
public class TestLogAppender extends AbstractAppender {

    private final List<LogEvent> events = new ArrayList<>();

    public TestLogAppender(String name) {
        super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        events.add(event.toImmutable());
    }

    /** 返回所有捕获的日志事件。 */
    public List<LogEvent> events() {
        return events;
    }

    /** 清空已捕获事件。 */
    public void clear() {
        events.clear();
    }
}
