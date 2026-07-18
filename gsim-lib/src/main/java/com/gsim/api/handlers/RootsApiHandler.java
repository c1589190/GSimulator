package com.gsim.api.handlers;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry;
import com.gsim.worldinfo.manager.WorldManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 根节点/World 工作区管理 API — 直接调 WorldManager。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/roots — 列出所有 World</li>
 *   <li>POST /api/roots — 创建 World</li>
 *   <li>DELETE /api/roots/{worldId} — 删除 World</li>
 * </ul>
 */
public class RootsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/roots";

    private final WorldManager wm;
    private final Path worldsDir;
    private final Supplier<String> activeWorldId;

    public RootsApiHandler(WorldManager wm, Path worldsDir, Supplier<String> activeWorldId) {
        this.wm = wm;
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0 && "GET".equals(method)) {
                handleList(exchange);
            } else if (segs.length == 0 && "POST".equals(method)) {
                handleCreate(exchange);
            } else if (segs.length == 1 && "DELETE".equals(method)) {
                handleDelete(exchange, segs[0]);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown roots endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        List<WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
        String active = activeWorldId.get();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (var e : entries) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", e.id());
            r.put("name", e.name());
            r.put("createdAt", e.createdAt());
            r.put("isActive", e.id().equals(active));
            roots.add(r);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", roots.size());
        data.put("roots", roots);
        data.put("activeRoot", active);
        BaseApiHandler.sendOk(exchange, "Roots listed", data);
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String id = str(req, "id");
        String name = str(req, "name");
        if (id.isEmpty()) {
            BaseApiHandler.sendError(exchange, 400, "id is required");
            return;
        }
        if (!id.matches("[a-zA-Z0-9_\\-]+")) {
            BaseApiHandler.sendError(exchange, 400, "id must contain only alphanumeric, dash, or underscore");
            return;
        }
        if (Files.exists(worldsDir.resolve(id))) {
            BaseApiHandler.sendError(exchange, 409, "World already exists: " + id);
            return;
        }
        try {
            var data = wm.createWorld(id, name.isEmpty() ? id : name);
            BaseApiHandler.sendOk(exchange, "World created: " + id, data);
        } catch (IllegalArgumentException e) {
            BaseApiHandler.sendError(exchange, 400, e.getMessage());
        }
    }

    private void handleDelete(HttpExchange exchange, String worldId) throws IOException {
        try {
            wm.deleteWorld(worldId);
            BaseApiHandler.sendOk(exchange, "World deleted: " + worldId, Map.of("worldId", worldId, "deleted", true));
        } catch (IllegalArgumentException e) {
            BaseApiHandler.sendError(exchange, 404, e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : "";
    }
}
