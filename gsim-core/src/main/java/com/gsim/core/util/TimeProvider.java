package com.gsim.core.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间提供者，便于测试时注入固定时间。
 */
public class TimeProvider {

    public static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    /**
     * 返回当前 UTC 时间。
     *
     * @return 当前 UTC 时间的 Instant 对象
     */
    public Instant now() {
        return Instant.now();
    }

    /**
     * 返回当前 UTC 时间字符串（ISO 8601 格式）。
     *
     * @return ISO 8601 格式的当前时间字符串
     */
    public String nowIso() {
        return ISO_FORMATTER.format(now());
    }

    /**
     * 格式化 Instant 为 ISO 8601 字符串。
     *
     * @param instant 要格式化的时间点
     * @return ISO 8601 格式的时间字符串
     */
    public String format(Instant instant) {
        return ISO_FORMATTER.format(instant);
    }
}
