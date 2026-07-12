package com.gsim.util;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;

/**
 * 时间提供者，便于测试时注入固定时间。
 */
public class TimeProvider {

    public static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    /**
     * 返回当前 UTC 时间。
     */
    public Instant now() {
        return Instant.now();
    }

    /**
     * 返回当前 UTC 时间字符串 (ISO 8601)。
     */
    public String nowIso() {
        return ISO_FORMATTER.format(now());
    }

    /**
     * 格式化 Instant 为 ISO 字符串。
     */
    public String format(Instant instant) {
        return ISO_FORMATTER.format(instant);
    }
}
