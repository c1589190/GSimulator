package com.gsim.api;

import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SSE 响应写入器。
 *
 * <p>设置 SSE 响应头并提供写入 SSE 事件的方法。
 */
public class SseWriter {

    private final HttpExchange exchange;
    private final OutputStream out;
    private boolean headersSent = false;

    public SseWriter(HttpExchange exchange) {
        this.exchange = exchange;
        this.out = exchange.getResponseBody();
    }

    /**
     * 发送 SSE 响应头。
     */
    public void sendHeaders() throws IOException {
        if (headersSent) return;
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);  // 0 = chunked
        headersSent = true;
    }

    /**
     * 写一个 SSE 事件。
     */
    public void writeEvent(String eventType, String jsonData) throws IOException {
        if (!headersSent) sendHeaders();

        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(eventType).append("\n");
        sb.append("data: ").append(jsonData).append("\n");
        sb.append("\n");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
    }

    /**
     * 写一个 SSE 事件（data 为 Map，自动序列化为 JSON）。
     */
    public void writeEvent(String eventType, Map<String, Object> data) throws IOException {
        writeEvent(eventType, JsonUtils.toJsonCompact(data));
    }

    /**
     * 写 SSE 注释行（用于 keep-alive）。
     */
    public void writeComment(String comment) throws IOException {
        if (!headersSent) sendHeaders();
        byte[] bytes = (": " + comment + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
    }

    /**
     * 关闭连接。
     */
    public void close() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
