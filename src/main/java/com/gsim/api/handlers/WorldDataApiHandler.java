package com.gsim.api.handlers;

import com.gsim.api.JsonBodyParser;
import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.Element;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.KeywordIndex.SearchHit;
import com.gsim.worldinfo.KeywordIndex.SearchResult;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;
import com.gsim.worldinfo.loader.WorldInfoBuilder;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * World 数据 API — Checkpoint + Element 查询与写入。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/world-manager-data/{worldId}/checkpoints — 列出 checkpoint ID</li>
 *   <li>GET /api/world-manager-data/{worldId}/checkpoints/{checkpointId} — 查询 checkpoint 历史</li>
 *   <li>POST /api/world-manager-data/{worldId}/checkpoints — 创建 checkpoint</li>
 *   <li>GET /api/world-manager-data/{worldId}/elements?ref=... — 按 ref 查询 element</li>
 *   <li>POST /api/world-manager-data/{worldId}/elements — 写入/更新 element</li>
 *   <li>GET /api/world-manager-data/{worldId}/elements/by-tag/{tag} — 按 tag 查询</li>
 *   <li>GET /api/world-manager-data/{worldId}/elements/search?keywords=... — 关键词搜索</li>
 * </ul>
 */
public class WorldDataApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/world-manager-data";

    private final Path worldsDir;

    public WorldDataApiHandler(Path worldsDir) {
        this.worldsDir = worldsDir;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length < 2) {
                BaseApiHandler.sendNotFound(exchange, "Usage: /api/world-manager-data/{worldId}/{resource}");
                return;
            }

            String worldId = segs[0];
            if (!Files.exists(WorldIndexManager.worldFile(worldsDir, worldId))) {
                BaseApiHandler.sendError(exchange, 404, "World not found: " + worldId);
                return;
            }

            String resource = segs[1];
            if ("checkpoints".equals(resource)) {
                handleCheckpointRoutes(exchange, method, worldId, segs);
            } else if ("elements".equals(resource)) {
                handleElementRoutes(exchange, method, worldId, segs);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown resource: " + resource);
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── Checkpoint routes ──

    private void handleCheckpointRoutes(HttpExchange exchange, String method, String worldId, String[] segs)
            throws IOException {
        // GET /{worldId}/checkpoints — list all
        if (segs.length == 2 && "GET".equals(method)) {
            handleListCheckpoints(exchange, worldId);
            return;
        }
        // POST /{worldId}/checkpoints — create
        if (segs.length == 2 && "POST".equals(method)) {
            handleCreateCheckpoint(exchange, worldId);
            return;
        }
        // GET /{worldId}/checkpoints/{checkpointId} — query history
        if (segs.length == 3 && "GET".equals(method)) {
            handleQueryCheckpoint(exchange, worldId, segs[2]);
            return;
        }
        BaseApiHandler.sendNotFound(exchange, "Unknown checkpoint endpoint");
    }

    private void handleListCheckpoints(HttpExchange exchange, String worldId) throws IOException {
        WorldInformation wi = loadWorldInfo(worldId, exchange);
        if (wi == null) return;

        List<String> ids = wi.allCheckpointIds();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", worldId);
        data.put("count", ids.size());
        data.put("checkpoints", ids);
        BaseApiHandler.sendOk(exchange, "Checkpoints listed", data);
    }

    private void handleQueryCheckpoint(HttpExchange exchange, String worldId, String checkpointId)
            throws IOException {
        WorldInformation wi = loadWorldInfo(worldId, exchange);
        if (wi == null) return;

        String turnFromStr = parseQueryParam(exchange, "turnFrom");
        String turnToStr = parseQueryParam(exchange, "turnTo");

        List<ElementRef> results;
        boolean isWildcard = checkpointId.endsWith("*");

        if (turnFromStr != null || turnToStr != null) {
            int turnFrom = turnFromStr != null ? Integer.parseInt(turnFromStr) : 0;
            int turnTo = turnToStr != null ? Integer.parseInt(turnToStr) : Integer.MAX_VALUE;
            results = wi.checkpointHistory(checkpointId, turnFrom, turnTo);
        } else if (isWildcard) {
            results = wi.checkpointHistoryByPrefix(checkpointId);
        } else {
            results = wi.checkpointHistory(checkpointId);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (ElementRef ref : results) {
            items.add(refToMap(ref));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", worldId);
        data.put("checkpointId", checkpointId);
        data.put("count", items.size());
        data.put("results", items);
        BaseApiHandler.sendOk(exchange, "Checkpoint query: " + checkpointId, data);
    }

    private void handleCreateCheckpoint(HttpExchange exchange, String worldId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String checkpointId = req != null ? (String) req.get("checkpointId") : null;
        if (checkpointId == null || checkpointId.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "checkpointId is required");
            return;
        }

        ActiveStateManager.ActiveState activeState = ActiveStateManager.load(worldsDir, worldId);
        if (activeState == null) {
            BaseApiHandler.sendError(exchange, 400, "World has no active state: " + worldId);
            return;
        }

        String nodeId = req != null ? (String) req.getOrDefault("nodeId", activeState.nodeId()) : activeState.nodeId();
        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) {
            BaseApiHandler.sendError(exchange, 404, "Node not found: " + nodeId);
            return;
        }

        String label = req != null ? (String) req.getOrDefault("label", checkpointId) : checkpointId;
        String type = req != null ? (String) req.getOrDefault("type", "misc") : "misc";

        NodeSnapshot node = NodeLoader.load(nodeFile);
        if (node.checkpoints().containsKey(checkpointId)) {
            BaseApiHandler.sendError(exchange, 409, "Checkpoint already exists: " + checkpointId);
            return;
        }

        node.checkpoints().put(checkpointId, new Checkpoint(label, type, new ArrayList<>()));
        NodeLoader.save(nodeFile, node);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", nodeId);
        data.put("checkpointId", checkpointId);
        data.put("label", label);
        data.put("type", type);
        BaseApiHandler.sendOk(exchange, "Checkpoint created: " + checkpointId, data);
    }

    // ── Element routes ──

    private void handleElementRoutes(HttpExchange exchange, String method, String worldId, String[] segs)
            throws IOException {
        // GET /{worldId}/elements/search?keywords=...
        if (segs.length == 3 && "search".equals(segs[2]) && "GET".equals(method)) {
            handleSearchElements(exchange, worldId);
            return;
        }
        // GET /{worldId}/elements/by-tag/{tag}
        if (segs.length == 4 && "by-tag".equals(segs[2]) && "GET".equals(method)) {
            handleElementsByTag(exchange, worldId, segs[3]);
            return;
        }
        // GET /{worldId}/elements?ref=...
        if (segs.length == 2 && "GET".equals(method)) {
            handleQueryElement(exchange, worldId);
            return;
        }
        // POST /{worldId}/elements
        if (segs.length == 2 && "POST".equals(method)) {
            handleWriteElement(exchange, worldId);
            return;
        }
        BaseApiHandler.sendNotFound(exchange, "Unknown element endpoint");
    }

    private void handleQueryElement(HttpExchange exchange, String worldId) throws IOException {
        String ref = parseQueryParam(exchange, "ref");
        if (ref == null || ref.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Query parameter 'ref' is required (format: nodeId:checkpointId:key)");
            return;
        }

        WorldInformation wi = loadWorldInfo(worldId, exchange);
        if (wi == null) return;

        String[] parts = ref.split(":", 3);
        String nodeId, checkpointId, key;
        if (parts.length == 2) {
            nodeId = wi.activeNodeId();
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            BaseApiHandler.sendError(exchange, 400,
                    "Invalid ref format. Expected nodeId:checkpointId:key or checkpointId:key");
            return;
        }

        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            BaseApiHandler.sendError(exchange, 404, "Node not found: " + nodeId);
            return;
        }

        Checkpoint cp = node.checkpoint(checkpointId);
        if (cp == null) {
            BaseApiHandler.sendError(exchange, 404, "Checkpoint not found: " + checkpointId);
            return;
        }

        Element found = null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) {
                found = el;
                break;
            }
        }

        if (found == null) {
            BaseApiHandler.sendError(exchange, 404, "Element not found: " + ref);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ref", nodeId + ":" + checkpointId + ":" + key);
        data.put("nodeId", nodeId);
        data.put("turn", node.turn());
        data.put("worldTime", node.worldTime());
        data.put("checkpointId", checkpointId);
        data.put("element", Map.of(
                "key", found.key(),
                "type", found.type(),
                "value", found.value(),
                "tags", found.tags(),
                "links", found.links()));
        BaseApiHandler.sendOk(exchange, "Element found: " + key, data);
    }

    private void handleWriteElement(HttpExchange exchange, String worldId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String ref = req != null ? (String) req.get("ref") : null;
        if (ref == null || ref.isBlank()) {
            BaseApiHandler.sendError(exchange, 400,
                    "ref is required (format: nodeId:checkpointId:key or checkpointId:key)");
            return;
        }
        String value = req != null ? (String) req.get("value") : null;
        if (value == null || value.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "value is required");
            return;
        }

        WorldInformation wi = loadWorldInfo(worldId, exchange);
        if (wi == null) return;

        String[] parts = ref.split(":", 3);
        String nodeId, checkpointId, key;
        if (parts.length == 2) {
            nodeId = wi.activeNodeId();
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            BaseApiHandler.sendError(exchange, 400,
                    "Invalid ref format. Expected nodeId:checkpointId:key or checkpointId:key");
            return;
        }

        String type = req != null ? (String) req.getOrDefault("type", "text") : "text";
        String tagsStr = req != null ? (String) req.getOrDefault("tags", null) : null;
        String linksStr = req != null ? (String) req.getOrDefault("links", null) : null;
        String mode = req != null ? (String) req.getOrDefault("mode", "replace") : "replace";

        List<String> tags = tagsStr != null && !tagsStr.isBlank()
                ? Arrays.asList(tagsStr.split(","))
                : List.of();
        List<String> links = linksStr != null && !linksStr.isBlank()
                ? Arrays.asList(linksStr.split(","))
                : List.of();

        Element element = new Element(key, type, value, tags, links);

        boolean replaced;
        if ("append".equalsIgnoreCase(mode)) {
            wi.appendElement(nodeId, checkpointId, element);
            replaced = false;
        } else {
            replaced = wi.upsertElement(nodeId, checkpointId, element);
        }

        // Persist
        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        String unifiedId = nodeId + ":" + checkpointId + ":" + key;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ref", unifiedId);
        data.put("action", replaced ? "replaced" : "appended");
        data.put("key", key);
        BaseApiHandler.sendOk(exchange, "Element " + (replaced ? "replaced" : "appended") + ": " + key, data);
    }

    private void handleElementsByTag(HttpExchange exchange, String worldId, String tag) throws IOException {
        WorldInformation wi = loadWorldInfo(worldId, exchange);
        if (wi == null) return;

        List<ElementRef> refs = wi.byTag(tag);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ElementRef ref : refs) {
            items.add(refToMap(ref));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", worldId);
        data.put("tag", tag);
        data.put("count", items.size());
        data.put("results", items);
        BaseApiHandler.sendOk(exchange, "Elements by tag: " + tag, data);
    }

    private void handleSearchElements(HttpExchange exchange, String worldId) throws IOException {
        String keywords = parseQueryParam(exchange, "keywords");
        if (keywords == null || keywords.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Query parameter 'keywords' is required");
            return;
        }
        String limitStr = parseQueryParam(exchange, "limit");
        String offsetStr = parseQueryParam(exchange, "offset");
        String checkpointId = parseQueryParam(exchange, "checkpointId");

        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;
        int offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;

        WorldInformation wi = loadWorldInfo(worldId, exchange);
        if (wi == null) return;

        SearchResult result = wi.keywordIndex().search(keywords, limit, offset, checkpointId);

        List<Map<String, Object>> items = new ArrayList<>();
        for (SearchHit hit : result.items()) {
            Map<String, Object> item = refToMap(hit.elementRef());
            item.put("snippet", hit.snippet());
            item.put("score", hit.score());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", worldId);
        data.put("keywords", keywords);
        data.put("totalHits", result.totalHits());
        data.put("offset", result.offset());
        data.put("limit", limit);
        if (checkpointId != null) data.put("checkpointId", checkpointId);
        data.put("count", items.size());
        data.put("results", items);
        BaseApiHandler.sendOk(exchange, "Search results", data);
    }

    // ── Helpers ──

    private WorldInformation loadWorldInfo(String worldId, HttpExchange exchange) throws IOException {
        ActiveStateManager.ActiveState activeState = ActiveStateManager.load(worldsDir, worldId);
        if (activeState == null) {
            BaseApiHandler.sendError(exchange, 400, "World has no active state: " + worldId);
            return null;
        }
        WorldInformation wi = WorldInfoBuilder.build(worldsDir, worldId, activeState.nodeId());
        if (wi == null) {
            BaseApiHandler.sendError(exchange, 404, "Cannot load world information for: " + worldId);
            return null;
        }
        return wi;
    }

    private Map<String, Object> refToMap(ElementRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", ref.nodeId());
        m.put("turn", ref.turn());
        m.put("worldTime", ref.worldTime());
        m.put("checkpointId", ref.checkpointId());
        m.put("key", ref.element().key());
        m.put("type", ref.element().type());
        m.put("value", ref.element().value());
        m.put("tags", ref.element().tags());
        m.put("links", ref.element().links());
        return m;
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
}
