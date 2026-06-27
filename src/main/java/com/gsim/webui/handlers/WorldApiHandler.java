package com.gsim.webui.handlers;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * /api/worlds REST API handler — 世界 CRUD + 文件读写。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/worlds — 列出所有世界</li>
 *   <li>POST /api/worlds — 创建新世界</li>
 *   <li>POST /api/worlds/{id}/switch — 切换活跃世界</li>
 *   <li>DELETE /api/worlds/{id} — 删除世界</li>
 *   <li>GET /api/worlds/{id}/files/{name} — 读取世界文件</li>
 *   <li>POST /api/worlds/{id}/files/{name} — 保存世界文件</li>
 * </ul>
 */
public class WorldApiHandler implements HttpHandler {

    private final Path worldsDir;
    private final Supplier<String> activeWorldId;

    public WorldApiHandler(Path worldsDir, Supplier<String> activeWorldId) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String prefix = "/api/worlds";

        try {
            if (path.equals(prefix) && "GET".equalsIgnoreCase(method)) {
                handleListWorlds(exchange);
            } else if (path.equals(prefix) && "POST".equalsIgnoreCase(method)) {
                handleCreateWorld(exchange);
            } else if (path.startsWith(prefix + "/") && path.endsWith("/switch")
                    && "POST".equalsIgnoreCase(method)) {
                String id = path.substring((prefix + "/").length(), path.length() - "/switch".length());
                handleSwitchWorld(exchange, id);
            } else if (path.startsWith(prefix + "/") && path.contains("/files/")
                    && "GET".equalsIgnoreCase(method)) {
                String rest = path.substring((prefix + "/").length());
                int idx = rest.indexOf("/files/");
                String id = rest.substring(0, idx);
                String fileName = rest.substring(idx + "/files/".length());
                handleReadFile(exchange, id, fileName);
            } else if (path.startsWith(prefix + "/") && path.contains("/files/")
                    && "POST".equalsIgnoreCase(method)) {
                String rest = path.substring((prefix + "/").length());
                int idx = rest.indexOf("/files/");
                String id = rest.substring(0, idx);
                String fileName = rest.substring(idx + "/files/".length());
                handleSaveFile(exchange, id, fileName);
            } else if (path.startsWith(prefix + "/") && "DELETE".equalsIgnoreCase(method)) {
                String id = path.substring((prefix + "/").length());
                handleDeleteWorld(exchange, id);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (Exception e) {
            HandlerUtils.logError("WorldApi", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleListWorlds(HttpExchange exchange) throws IOException {
        List<WorldIndexManager.WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
        String active = activeWorldId.get();
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (var e : entries) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", e.id());
            w.put("name", e.name());
            w.put("createdAt", e.createdAt());
            w.put("isActive", e.id().equals(active));
            worlds.add(w);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("worlds", worlds);
        resp.put("activeWorld", active);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleCreateWorld(HttpExchange exchange) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rawBody.isBlank() ? Map.of() : JsonUtils.fromJson(rawBody, Map.class);
        String name = body != null ? (String) body.getOrDefault("name", "") : "";
        String id = body != null ? (String) body.getOrDefault("id", "") : "";
        if (name.isBlank()) name = id;
        if (id.isBlank()) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", "id is required"));
            return;
        }
        try {
            WorldIndexManager.createWorld(worldsDir, id, name);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("id", id);
            resp.put("name", name);
            HandlerUtils.sendJson(exchange, 201, resp);
        } catch (IllegalArgumentException e) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private void handleSwitchWorld(HttpExchange exchange, String id) throws IOException {
        if (!Files.exists(WorldIndexManager.worldFile(worldsDir, id))) {
            HandlerUtils.sendJson(exchange, 404, Map.of("error", "World not found: " + id));
            return;
        }
        ActiveStateManager.ActiveState current = ActiveStateManager.load(worldsDir, id);
        if (current == null) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", "World has no active state: " + id));
            return;
        }
        // Switching is handled at application level — just return success
        // The frontend should reload the page to pick up the new active world
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("worldId", id);
        resp.put("message", "Switched to world " + id + ". Please reload the page.");
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleDeleteWorld(HttpExchange exchange, String id) throws IOException {
        Path worldPath = worldsDir.resolve(id);
        if (!Files.exists(worldPath)) {
            HandlerUtils.sendJson(exchange, 404, Map.of("error", "World not found: " + id));
            return;
        }
        try {
            deleteRecursive(worldPath);
            // 重建 _index.json（移除对应条目）
            List<WorldIndexManager.WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
            List<WorldIndexManager.WorldEntry> filtered = new ArrayList<>();
            for (var e : entries) {
                if (!e.id().equals(id)) filtered.add(e);
            }
            Files.writeString(WorldIndexManager.indexFile(worldsDir),
                    JsonUtils.toJson(filtered));
        } catch (IOException e) {
            HandlerUtils.sendJson(exchange, 500, Map.of("error", "Failed to delete: " + e.getMessage()));
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Deleted world: " + id);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleReadFile(HttpExchange exchange, String worldId, String fileName) throws IOException {
        Path filePath = worldsDir.resolve(worldId).resolve(fileName);
        if (!Files.exists(filePath)) {
            HandlerUtils.sendJson(exchange, 200, Map.of("content", ""));
            return;
        }
        String content = Files.readString(filePath);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", content);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleSaveFile(HttpExchange exchange, String worldId, String fileName) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rawBody.isBlank() ? Map.of() : JsonUtils.fromJson(rawBody, Map.class);
        String content = body != null ? (String) body.getOrDefault("content", "") : "";
        Path filePath = worldsDir.resolve(worldId).resolve(fileName);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Saved " + fileName + " for world " + worldId);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    deleteRecursive(child);
                }
            }
        }
        Files.delete(dir);
    }
}
