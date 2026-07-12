package com.gsim.event;

import com.gsim.util.JsonUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SSE 事件输出器 — 把 GSimEvent 转为 Server-Sent Events 格式写入 OutputStream。
 *
 * <p>格式：
 * <pre>
 * event: {type}
 * data: {json}
 *
 * </pre>
 */
public class SseEventSink implements EventSink {

    private final OutputStream out;
    private volatile boolean closed = false;

    public SseEventSink(OutputStream out) {
        this.out = out;
    }

    @Override
    public void accept(GSimEvent event) {
        if (closed) return;

        try {
            StringBuilder sb = new StringBuilder();
            sb.append("event: ").append(event.type()).append("\n");
            sb.append("data: ").append(toSseJson(event)).append("\n");
            sb.append("\n");

            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            // 客户端可能已断开，标记关闭
            closed = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            synchronized (out) {
                out.flush();
                out.close();
            }
        } catch (IOException ignored) {
            // 客户端可能已断开
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * 将事件转为 SSE data 行的 JSON。
     * 包含除 time 外的所有字段。
     */
    public static String toSseJson(GSimEvent event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sessionId", event.sessionId());
        if (event.taskId() != null) {
            payload.put("taskId", event.taskId());
        }
        payload.put("type", event.type());
        payload.putAll(event.data());
        return JsonUtils.toJsonCompact(payload);
    }
}
