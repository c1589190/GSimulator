package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API handler 公共方法。
 */
public final class BaseApiHandler {

    private BaseApiHandler() {}

    // ---- CORS ----

    /**
     * 为响应添加 CORS 头，允许浏览器跨域访问。
     */
    public static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods",
                "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Authorization, X-GSim-Session-Id");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    /**
     * 处理 CORS 预检请求（OPTIONS），返回 204 No Content。
     */
    public static void handlePreflight(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);  // -1 = no body
        exchange.close();
    }

    /**
     * 发送 JSON 响应。
     */
    public static void sendJson(HttpExchange exchange, int statusCode, ApiResponse response) throws IOException {
        byte[] body = response.toJson().getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    /**
     * 发送 OK 响应。
     */
    public static void sendOk(HttpExchange exchange, String message) throws IOException {
        sendJson(exchange, 200, ApiResponse.ok(message));
    }

    /**
     * 发送 OK 响应带数据。
     */
    public static void sendOk(HttpExchange exchange, String message, java.util.Map<String, Object> data) throws IOException {
        sendJson(exchange, 200, ApiResponse.ok(message, data));
    }

    /**
     * 发送错误响应。
     */
    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendJson(exchange, statusCode, ApiResponse.fail(message));
    }

    /**
     * 发送 404。
     */
    public static void sendNotFound(HttpExchange exchange, String message) throws IOException {
        sendJson(exchange, 404, ApiResponse.fail(message));
    }

    /**
     * 发送 not implemented。
     */
    public static void sendNotImplemented(HttpExchange exchange) throws IOException {
        sendJson(exchange, 501, ApiResponse.notImplemented());
    }

    /**
     * 读取请求体为字符串。
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 解析路径段。
     * e.g. "/api/campaigns/c001/turns/t001/actions" → ["c001", "t001"]
     * 根据给定的前缀，提取后面的路径段。
     */
    public static String[] pathSegments(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix)) return new String[0];
        String sub = path.substring(prefix.length());
        if (sub.startsWith("/")) sub = sub.substring(1);
        if (sub.isEmpty()) return new String[0];
        return sub.split("/");
    }

    // ---- Session ID extraction ----

    /**
     * 从请求中提取 sessionId，优先级：query param → header → null。
     * 返回 null 表示调用者应使用 body 中的 sessionId 或默认值 "default"。
     */
    public static String extractSessionId(HttpExchange exchange) {
        // 1. query param: ?sessionId=xxx
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    if ("sessionId".equals(key)) {
                        String val = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                        if (!val.isBlank()) return val.trim();
                    }
                }
            }
        }
        // 2. header: X-GSim-Session-Id
        String headerVal = exchange.getRequestHeaders().getFirst("X-GSim-Session-Id");
        if (headerVal != null && !headerVal.isBlank()) return headerVal.trim();
        // 3. not found
        return null;
    }

    /**
     * 提取 sessionId 并回退到 "default"。
     * 供不需要从 body 解析 sessionId 的 handler 使用。
     */
    public static String resolveSessionId(HttpExchange exchange) {
        String sid = extractSessionId(exchange);
        return sid != null ? sid : "default";
    }
}
