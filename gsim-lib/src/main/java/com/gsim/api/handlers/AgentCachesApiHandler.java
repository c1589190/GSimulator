package com.gsim.api.handlers;

import com.gsim.agent.management.AgentCacheStore;
import com.gsim.api.ApiResponse;
import com.gsim.cache.CacheInfo;
import com.gsim.cache.CacheSession;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 对话缓存 HTTP API。
 *
 * <pre>
 * GET    /api/agent-caches                 列出缓存（?worldId=&agentType=）
 * GET    /api/agent-caches/{cacheId}       读取缓存（?summary=true 仅摘要）
 * POST   /api/agent-caches                 创建新缓存（自动注入 system prompt）
 * DELETE /api/agent-caches/{cacheId}       删除缓存
 * </pre>
 */
public class AgentCachesApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/agent-caches";

    private final AgentCacheStore cacheStore;

    public AgentCachesApiHandler(AgentCacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            route(exchange, method, segs);
        } catch (IllegalArgumentException e) {
            BaseApiHandler.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void route(HttpExchange exchange, String method, String[] segs) throws IOException {
        if (segs.length == 0) {
            switch (method) {
                case "GET" -> handleList(exchange);
                case "POST" -> handleCreate(exchange);
                default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET or POST.");
            }
            return;
        }

        if (segs.length == 1) {
            if ("GET".equals(method)) {
                handleGet(exchange, segs[0]);
            } else if ("DELETE".equals(method)) {
                handleDelete(exchange, segs[0]);
            } else {
                BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET or DELETE.");
            }
            return;
        }

        BaseApiHandler.sendNotFound(exchange, "Unknown route");
    }

    // ── GET /api/agent-caches ──

    private void handleList(HttpExchange exchange) throws IOException {
        String worldId = parseQueryParam(exchange, "worldId");
        String agentType = parseQueryParam(exchange, "agentType");

        List<CacheInfo> caches = cacheStore.list(worldId, agentType);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "caches",
                caches.stream().map(AgentCachesApiHandler::cacheInfoToMap).toList());
        data.put("count", caches.size());

        BaseApiHandler.sendOk(exchange, "Agent caches listed", data);
    }

    // ── GET /api/agent-caches/{cacheId} ──

    private void handleGet(HttpExchange exchange, String cacheId) throws IOException {
        boolean summaryOnly = "true".equalsIgnoreCase(parseQueryParam(exchange, "summary"));

        if (summaryOnly) {
            CacheInfo info = cacheStore.getSummary(cacheId);
            if (info == null) {
                BaseApiHandler.sendError(exchange, 404, "Cache not found: " + cacheId);
                return;
            }
            BaseApiHandler.sendOk(exchange, "Cache summary: " + cacheId, cacheInfoToMap(info));
            return;
        }

        CacheSession session = cacheStore.get(cacheId);
        if (session == null) {
            BaseApiHandler.sendError(exchange, 404, "Cache not found: " + cacheId);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cacheId", session.sessionId());
        data.put("agentName", session.agentName());
        data.put("worldId", session.worldId());
        data.put("nodeId", session.nodeId());
        data.put("createdAt", session.createdAt());
        data.put("previousSessionId", session.previousSessionId());
        data.put("messageCount", session.messageCount());

        // 支持分页
        String offsetStr = parseQueryParam(exchange, "offset");
        String limitStr = parseQueryParam(exchange, "limit");
        int offset = offsetStr != null ? Math.max(0, Integer.parseInt(offsetStr)) : 0;
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 50;

        List<Map<String, Object>> msgs = session.messages();
        int totalMsgs = msgs.size();
        int start = Math.min(offset, totalMsgs);
        int end = Math.min(start + limit, totalMsgs);

        data.put("totalMessages", totalMsgs);
        data.put("offset", start);
        data.put("limit", limit);
        data.put("returnedMessages", end - start);
        data.put("hasMore", end < totalMsgs);
        data.put("nextOffset", end < totalMsgs ? end : null);
        data.put("messages", msgs.subList(start, end));

        BaseApiHandler.sendOk(exchange, "Cache read: " + cacheId, data);
    }

    // ── POST /api/agent-caches ──

    private void handleCreate(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);

        String worldId = str(req, "worldId", "default");
        String configId = str(req, "configId", null);
        if (configId == null) {
            BaseApiHandler.sendError(exchange, 400, "configId is required");
            return;
        }
        String nodeId = str(req, "nodeId", "n0000");

        CacheSession session = cacheStore.create(worldId, configId, nodeId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cacheId", session.sessionId());
        data.put("configId", configId);
        data.put("worldId", worldId);
        data.put("nodeId", nodeId);
        data.put("messageCount", session.messageCount());

        BaseApiHandler.sendJson(exchange, 201, ApiResponse.ok("Cache created: " + session.sessionId(), data));
    }

    // ── DELETE /api/agent-caches/{cacheId} ──

    private void handleDelete(HttpExchange exchange, String cacheId) throws IOException {
        boolean deleted = cacheStore.delete(cacheId);
        if (deleted) {
            BaseApiHandler.sendOk(
                    exchange, "Cache deleted: " + cacheId, Map.of("cacheId", cacheId, "action", "deleted"));
        } else {
            BaseApiHandler.sendError(exchange, 404, "Cache not found: " + cacheId);
        }
    }

    // ── helpers ──

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : def;
    }

    private static String parseQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static Map<String, Object> cacheInfoToMap(CacheInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", info.sessionId());
        m.put("agentName", info.agentName());
        m.put("agentType", info.agentType());
        m.put("worldId", info.worldId());
        m.put("nodeId", info.nodeId());
        m.put("createdAt", info.createdAt());
        m.put("messageCount", info.messageCount());
        m.put("previousSessionId", info.previousSessionId());
        if (info.firstUserMsg() != null) {
            m.put("firstUserMsg", info.firstUserMsg());
        }
        return m;
    }
}
