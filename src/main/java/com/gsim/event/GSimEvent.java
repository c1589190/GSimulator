package com.gsim.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 统一事件类型，CLI 和 HTTP SSE 共用。
 *
 * <p>事件类型 (type) 至少包含：
 * <ul>
 *   <li>command_started / command_done / command_error</li>
 *   <li>log</li>
 *   <li>run_stage</li>
 *   <li>import_progress / search_progress</li>
 *   <li>tool_started / tool_done / tool_error</li>
 *   <li>llm_started / llm_delta / llm_reasoning_delta / llm_done</li>
 *   <li>result</li>
 *   <li>done</li>
 * </ul>
 *
 * @param sessionId 会话 ID
 * @param taskId    任务 ID（可为 null）
 * @param type      事件类型
 * @param time      事件时间
 * @param data      事件数据
 */
public record GSimEvent(
        String sessionId,
        String taskId,
        String type,
        Instant time,
        Map<String, Object> data) {

    public GSimEvent {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(time, "time");
        // defensive copy
        data = data != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(data))
                : Collections.emptyMap();
    }

    // ---- 工厂方法 ----

    public static GSimEvent of(String sessionId, String type) {
        return new GSimEvent(sessionId, null, type, Instant.now(), Map.of());
    }

    public static GSimEvent of(String sessionId, String type, Map<String, Object> data) {
        return new GSimEvent(sessionId, null, type, Instant.now(), data);
    }

    public static GSimEvent of(String sessionId, String taskId, String type, Map<String, Object> data) {
        return new GSimEvent(sessionId, taskId, type, Instant.now(), data);
    }

    /**
     * 便捷 — 获取 data 中的字符串字段。
     */
    public String getString(String key) {
        Object val = data.get(key);
        return val != null ? val.toString() : null;
    }

    @Override
    public String toString() {
        return "GSimEvent[" + type + " | session=" + sessionId
                + (taskId != null ? " | task=" + taskId : "")
                + " | data=" + data + "]";
    }
}
