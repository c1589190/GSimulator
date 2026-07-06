package com.gsim.api.handlers;

import com.gsim.api.OperationLog;
import com.gsim.doc.DocCacheManager;
import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.manager.WorldManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * World API v2 — 层级化 World/Node/Checkpoint/Element 统一读写。
 *
 * <p>读（GET）：
 * <pre>
 * GET /api/world/{worldId}                                  → 世界概览 + 节点链
 * GET /api/world/{worldId}/nodes/{nodeId}                   → 节点详情 + 检查点列表
 * GET /api/world/{worldId}/nodes/{nodeId}/{checkpointId}    → 元素列表（截断）
 * GET /api/world/{worldId}/nodes/{nodeId}/{checkpointId}/{key} → 单个元素全文
 * </pre>
 *
 * <p>写（POST）：
 * <pre>
 * POST /api/world/{worldId}                                             → 创建 world
 * POST /api/world/{worldId}/nodes                                       → 创建 node
 * POST /api/world/{worldId}/nodes/{nodeId}/{checkpointId}               → 创建 checkpoint
 * POST /api/world/{worldId}/nodes/{nodeId}/{checkpointId}/{key}         → 写入 element
 * </pre>
 *
 * <p>删（DELETE）：DELETE /api/world/{worldId}
 */
public class WorldApiV2Handler implements HttpHandler {

    private static final String PREFIX = "/api/world";

    private final WorldManager wm;
    private final DocCacheManager cacheManager;

    public WorldApiV2Handler(WorldManager wm, Path worldsDir) {
        this.wm = wm;
        this.cacheManager = new DocCacheManager(
                worldsDir.resolveSibling("docs").resolve(".cache"));
        try { this.cacheManager.init(); } catch (IOException ignored) {}
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
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
        int n = segs.length;

        // DELETE /api/world/{worldId}
        if (n == 1 && "DELETE".equals(method)) {
            wm.deleteWorld(segs[0]);
            OperationLog.get().record(segs[0], "world.delete", "DELETE",
                    "/api/world/" + segs[0], "deleted", null, true);
            BaseApiHandler.sendOk(exchange, "World deleted: " + segs[0],
                    Map.of("worldId", segs[0], "action", "deleted"));
            return;
        }

        // GET /api/world/{worldId}
        if (n == 1 && "GET".equals(method)) {
            var data = wm.getWorld(segs[0]);
            BaseApiHandler.sendOk(exchange, "World: " + segs[0], data);
            return;
        }

        // POST /api/world/{worldId} — create world
        if (n == 1 && "POST".equals(method)) {
            String body = BaseApiHandler.readBody(exchange);
            @SuppressWarnings("unchecked")
            Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
            String name = req != null ? (String) req.getOrDefault("name", segs[0]) : segs[0];
            var data = wm.createWorld(segs[0], name);
            OperationLog.get().record(segs[0], "world.create", "POST",
                    "/api/world/" + segs[0], "created: " + segs[0],
                    Map.of("name", name), true);
            BaseApiHandler.sendOk(exchange, "World created: " + segs[0], data);
            return;
        }

        // POST /api/world/{id}/nodes — create node (n=2: [worldId, nodes])
        if (n == 2 && "nodes".equals(segs[1]) && "POST".equals(method)) {
            String body = BaseApiHandler.readBody(exchange);
            @SuppressWarnings("unchecked")
            Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
            String parentId = str(req, "parentId");
            String worldTime = str(req, "worldTime");
            String title = str(req, "title");
            var data = wm.createNode(segs[0], parentId, worldTime, title);
            OperationLog.get().record(segs[0], "node.create", "POST",
                    "/api/world/" + segs[0] + "/nodes",
                    "created node " + data.get("nodeId") + " (parent=" + parentId + ")",
                    Map.of("nodeId", data.get("nodeId"), "parentId", parentId), true);
            BaseApiHandler.sendOk(exchange, "Node created", data);
            return;
        }

        // Need node segment for everything below
        if (n < 3 || !"nodes".equals(segs[1])) {
            BaseApiHandler.sendNotFound(exchange, "Expected /api/world/{id}/nodes/...");
            return;
        }

        String worldId = segs[0];
        String nodeId = segs[2];

        // GET /api/world/{id}/nodes/{nodeId}
        if (n == 3 && "GET".equals(method)) {
            var data = wm.getNode(worldId, nodeId);
            BaseApiHandler.sendOk(exchange, "Node: " + nodeId, data);
            return;
        }

        // Need checkpoint segment
        if (n < 4) {
            BaseApiHandler.sendNotFound(exchange, "Expected .../nodes/{nodeId}/{checkpointId}");
            return;
        }

        String cpId = segs[3];

        // POST /api/world/{id}/nodes/{nid}/{cpId} — create checkpoint
        if (n == 4 && "POST".equals(method)) {
            String body = BaseApiHandler.readBody(exchange);
            @SuppressWarnings("unchecked")
            Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
            String label = str(req, "label");
            String type = str(req, "type");
            var data = wm.createCheckpoint(worldId, nodeId, cpId, label, type);
            OperationLog.get().record(worldId, "checkpoint.create", "POST",
                    "/api/world/" + worldId + "/nodes/" + nodeId + "/" + cpId,
                    "created checkpoint: " + cpId, Map.of("nodeId", nodeId), true);
            BaseApiHandler.sendOk(exchange, "Checkpoint created: " + cpId, data);
            return;
        }

        // GET /api/world/{id}/nodes/{nid}/{cpId}
        if (n == 4 && "GET".equals(method)) {
            String truncateStr = parseQueryParam(exchange, "truncate");
            int truncate = 200;
            if (truncateStr != null) {
                try { truncate = Integer.parseInt(truncateStr); } catch (NumberFormatException ignored) {}
            }
            var data = wm.getCheckpoint(worldId, nodeId, cpId, truncate);
            BaseApiHandler.sendOk(exchange, "Checkpoint: " + cpId, data);
            return;
        }

        // Need element key
        if (n < 5) {
            BaseApiHandler.sendNotFound(exchange, "Expected .../nodes/{nid}/{cpId}/{key}");
            return;
        }

        String key = segs[4];

        // POST /api/world/{id}/nodes/{nid}/{cpId}/{key} — write element
        if (n == 5 && "POST".equals(method)) {
            String body = BaseApiHandler.readBody(exchange);
            @SuppressWarnings("unchecked")
            Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
            String value = str(req, "value");
            String type = str(req, "type");
            String tagsStr = str(req, "tags");
            String linksStr = str(req, "links");
            boolean autoDoc = "true".equalsIgnoreCase(
                    req.get("autoDoc") instanceof String s ? s : "");

            if (value == null || value.isBlank()) {
                BaseApiHandler.sendError(exchange, 400, "value is required");
                return;
            }

            // 解析 @cache: 引用
            value = cacheManager.resolve(value);

            List<String> tags = tagsStr != null ? Arrays.asList(tagsStr.split(",")) : List.of();
            List<String> links = linksStr != null ? Arrays.asList(linksStr.split(",")) : List.of();

            var data = wm.writeElement(worldId, nodeId, cpId, key, value, type, tags, links, autoDoc);
            OperationLog.get().record(worldId, "element.write", "POST",
                    "/api/world/" + worldId + "/nodes/" + nodeId + "/" + cpId + "/" + key,
                    "wrote " + data.get("ref") + " (" + data.get("action") + ")",
                    Map.of("ref", data.get("ref"), "action", data.get("action")), true);
            BaseApiHandler.sendOk(exchange, "Element " + data.get("action") + ": " + key, data);
            return;
        }

        // GET /api/world/{id}/nodes/{nid}/{cpId}/{key} — full element
        if (n == 5 && "GET".equals(method)) {
            var data = wm.getElement(worldId, nodeId, cpId, key);
            if (BaseApiHandler.isTextFormat(exchange)) {
                // ?format=text → 返回 raw value（优先 renderedContent）
                String raw = (String) data.getOrDefault("renderedContent",
                        data.getOrDefault("value", ""));
                BaseApiHandler.sendTextOk(exchange, raw);
                return;
            }
            BaseApiHandler.sendOk(exchange, "Element: " + key, data);
            return;
        }

        BaseApiHandler.sendNotFound(exchange, "Unknown route");
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
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
