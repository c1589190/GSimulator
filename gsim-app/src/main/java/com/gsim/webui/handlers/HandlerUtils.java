package com.gsim.webui.handlers;

import com.gsim.core.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared HTTP handler utilities.
 */
public final class HandlerUtils {

    private static final Logger log = LoggerFactory.getLogger(HandlerUtils.class);

    private HandlerUtils() {}

    /**
     * 解析 URL 编码的表单请求体为键值对映射。
     *
     * @param body URL 编码的表单字符串
     * @return 解析后的键值对映射，不会返回 null
     */
    public static Map<String, String> parseFormEncoded(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return params;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = eq > 0 ? URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8) : "";
                String value =
                        eq + 1 < pair.length() ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
                if (!key.isBlank()) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    /**
     * 发送 JSON 响应。
     *
     * @param exchange HTTP 交换对象
     * @param status   HTTP 状态码
     * @param data     要序列化为 JSON 的响应数据对象
     * @throws IOException 如果写入响应失败
     */
    public static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = JsonUtils.toJsonCompact(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 发送 HTML 响应。
     *
     * @param exchange HTTP 交换对象
     * @param status   HTTP 状态码
     * @param html     HTML 内容字符串
     * @throws IOException 如果写入响应失败
     */
    public static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 发送 HTML 样式的错误响应片段（所有 handler 统一格式 — HTMX 可交换 HTML）。
     *
     * @param exchange HTTP 交换对象
     * @param status   HTTP 状态码
     * @param msg      错误消息文本
     * @throws IOException 如果写入响应失败
     */
    public static void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        String html = "<div class=\"text-red-400 text-xs p-2\">" + escapeHtml(msg) + "</div>";
        sendHtml(exchange, status, html);
    }

    /**
     * 从查询字符串中提取指定键的值。
     *
     * @param query 查询字符串（不含前导 "?"），可能为 null
     * @param key   要提取的参数键名
     * @return 对应的参数值，如果未找到则返回 null
     */
    public static String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /**
     * 记录 HTTP 请求处理错误日志。
     *
     * @param handler handler 名称
     * @param method  HTTP 请求方法
     * @param path    请求路径
     * @param e       异常对象
     */
    public static void logError(String handler, String method, String path, Exception e) {
        log.error("[{}] Error handling {} {}: {}", handler, method, path, e.getMessage(), e);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
