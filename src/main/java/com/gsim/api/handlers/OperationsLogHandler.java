package com.gsim.api.handlers;

import com.gsim.api.OperationLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志查询 API — 查看最近的写操作记录。
 *
 * <p>端点：GET /api/logs/operations?limit=50&worldId=...&since=...
 */
public class OperationsLogHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        String limitStr = parseQueryParam(exchange, "limit");
        String worldId = parseQueryParam(exchange, "worldId");
        String since = parseQueryParam(exchange, "since");

        int limit = 50;
        if (limitStr != null && !limitStr.isBlank()) {
            try { limit = Integer.parseInt(limitStr); } catch (NumberFormatException ignored) {}
        }

        List<Map<String, Object>> entries = OperationLog.get().query(worldId, since, limit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", entries.size());
        data.put("totalStored", OperationLog.get().size());
        data.put("maxEntries", 2000);
        if (worldId != null) data.put("worldId", worldId);
        data.put("entries", entries);
        BaseApiHandler.sendOk(exchange, "Operation log entries", data);
    }

    private static String parseQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
