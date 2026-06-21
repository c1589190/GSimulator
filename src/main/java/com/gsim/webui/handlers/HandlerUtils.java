package com.gsim.webui.handlers;

import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared HTTP handler utilities.
 */
public final class HandlerUtils {

    private static final Logger log = LoggerFactory.getLogger(HandlerUtils.class);

    private HandlerUtils() {}

    public static Map<String, String> parseFormEncoded(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return params;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = eq > 0 ? URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8) : "";
                String value = eq + 1 < pair.length() ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
                if (!key.isBlank()) {
                    params.put(key, value);
                }
            }
        }
        return params;
    }

    public static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = JsonUtils.toJsonCompact(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Send an HTML-styled error snippet (consistent across all handlers — HTMX swaps HTML). */
    public static void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        String html = "<div class=\"text-red-400 text-xs p-2\">" + escapeHtml(msg) + "</div>";
        sendHtml(exchange, status, html);
    }

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

    public static void logError(String handler, String method, String path, Exception e) {
        log.error("[{}] Error handling {} {}: {}", handler, method, path, e.getMessage(), e);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
