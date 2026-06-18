package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * API handler 公共方法。
 */
public final class BaseApiHandler {

    private BaseApiHandler() {}

    /**
     * 发送 JSON 响应。
     */
    public static void sendJson(HttpExchange exchange, int statusCode, ApiResponse response) throws IOException {
        byte[] body = response.toJson().getBytes(StandardCharsets.UTF_8);
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
}
