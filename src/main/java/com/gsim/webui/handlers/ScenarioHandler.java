package com.gsim.webui.handlers;

import com.gsim.app.ApplicationContext;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 剧本管理器 API handler。
 *
 * <p>GET    /scenario/list           — 列出所有剧本
 * <p>POST   /scenario/create         — 创建新剧本
 * <p>POST   /scenario/switch         — 切换活动剧本
 * <p>DELETE /scenario/{name}          — 删除剧本
 * <p>GET    /scenario/file?name=...  — 读取剧本文件
 * <p>POST   /scenario/file           — 保存剧本文件
 */
public class ScenarioHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final PageHandler pageHandler;

    public ScenarioHandler(ApplicationContext ctx, PageHandler pageHandler) {
        this.ctx = ctx;
        this.pageHandler = pageHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();
        // Strip prefix: /scenario → ...
        String subPath = path.replaceFirst("^/scenario/?", "");

        try {
            if (method.equals("OPTIONS")) {
                HandlerUtils.sendJson(exchange, 204, Map.of());
                return;
            }

            // GET /scenario (exact, no subpath) → delegate to PageHandler for HTML fragment
            if (subPath.isEmpty() && "GET".equals(method)) {
                pageHandler.handle(exchange);
                return;
            }

            switch (subPath) {
                case "list" -> {
                    if ("GET".equals(method)) handleList(exchange);
                    else HandlerUtils.sendError(exchange, 405, "Method not allowed");
                }
                case "create" -> {
                    if ("POST".equals(method)) handleCreate(exchange);
                    else HandlerUtils.sendError(exchange, 405, "Method not allowed");
                }
                case "switch" -> {
                    if ("POST".equals(method)) handleSwitch(exchange);
                    else HandlerUtils.sendError(exchange, 405, "Method not allowed");
                }
                case "file" -> {
                    if ("GET".equals(method)) handleGetFile(exchange);
                    else if ("POST".equals(method)) handleSaveFile(exchange);
                    else HandlerUtils.sendError(exchange, 405, "Method not allowed");
                }
                default -> {
                    // DELETE /scenario/{name}
                    if ("DELETE".equals(method) && !subPath.isBlank()) {
                        handleDelete(exchange, subPath);
                    } else {
                        HandlerUtils.sendError(exchange, 404, "Unknown scenario endpoint: " + subPath);
                    }
                }
            }
        } catch (Exception e) {
            HandlerUtils.logError("ScenarioHandler", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    // ---- list ----

    private void handleList(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendJson(exchange, 200, Map.of("worlds", List.of(), "activeWorld", ""));
            return;
        }

        List<String> worlds = dm.listWorlds();
        String active = dm.getActiveWorld();
        String activeSafe = active != null ? active : "";
        List<Map<String, Object>> list = worlds.stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", w);
            m.put("isActive", w.equals(activeSafe));
            return m;
        }).toList();

        HandlerUtils.sendJson(exchange, 200,
                Map.of("worlds", list, "activeWorld", activeSafe, "count", list.size()));
    }

    // ---- create ----

    private void handleCreate(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        String name = reqMap != null ? (String) reqMap.get("name") : null;
        String worldContent = reqMap != null ? (String) reqMap.get("worldContent") : null;

        if (name == null || name.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "Missing required field: name");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendError(exchange, 500, "DataManager not available");
            return;
        }

        // Check if world already exists
        if (dm.listWorlds().contains(name.trim())) {
            HandlerUtils.sendJson(exchange, 400,
                    Map.of("success", false, "message", "剧本 '" + name + "' 已存在"));
            return;
        }

        try {
            dm.initWorld(name.trim(), worldContent);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("name", name.trim());
            resp.put("message", "剧本 '" + name.trim() + "' 创建成功");
            HandlerUtils.sendJson(exchange, 200, resp);
        } catch (Exception e) {
            HandlerUtils.sendError(exchange, 500, "创建失败: " + e.getMessage());
        }
    }

    // ---- switch ----

    private void handleSwitch(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        String name = reqMap != null ? (String) reqMap.get("name") : null;

        if (name == null || name.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "Missing required field: name");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendError(exchange, 500, "DataManager not available");
            return;
        }

        try {
            dm.switchWorld(name.trim());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("name", name.trim());
            resp.put("message", "已切换到剧本 '" + name.trim() + "'");
            HandlerUtils.sendJson(exchange, 200, resp);
        } catch (IOException e) {
            HandlerUtils.sendJson(exchange, 400,
                    Map.of("success", false, "message", "切换失败: " + e.getMessage()));
        }
    }

    // ---- delete ----

    private void handleDelete(HttpExchange exchange, String name) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendError(exchange, 500, "DataManager not available");
            return;
        }

        if (!dm.listWorlds().contains(name)) {
            HandlerUtils.sendJson(exchange, 404,
                    Map.of("success", false, "message", "剧本 '" + name + "' 不存在"));
            return;
        }

        String activeWorld = dm.getActiveWorld();
        if (name.equals(activeWorld)) {
            HandlerUtils.sendJson(exchange, 400,
                    Map.of("success", false, "message", "不能删除当前活动的剧本，请先切换到其他剧本"));
            return;
        }

        try {
            Path worldDir = dm.getDataRoot().resolve("worlds").resolve(name);
            deleteRecursive(worldDir.toFile());
            // 刷新 DataManager 以反映变化
            dm.reload();
            HandlerUtils.sendJson(exchange, 200,
                    Map.of("success", true, "name", name, "message", "剧本 '" + name + "' 已删除"));
        } catch (Exception e) {
            HandlerUtils.sendError(exchange, 500, "删除失败: " + e.getMessage());
        }
    }

    private void deleteRecursive(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!f.delete()) {
            throw new RuntimeException("Failed to delete: " + f.getAbsolutePath());
        }
    }

    // ---- get file ----

    private void handleGetFile(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String fileName = HandlerUtils.getQueryParam(query, "name");
        if (fileName == null || fileName.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "Missing query param: name (e.g. world.md)");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendJson(exchange, 503,
                    Map.of("name", fileName, "content", "", "error", "DataManager not available"));
            return;
        }

        // 检查是否有活动世界
        String activeWorld = dm.getActiveWorld();
        if (activeWorld == null || activeWorld.isBlank()) {
            HandlerUtils.sendJson(exchange, 200,
                    Map.of("name", fileName, "content", "", "warning", "没有活动剧本"));
            return;
        }

        String content;
        try {
            content = switch (fileName) {
                case "world.md" -> dm.getEffectiveWorldContext();
                case "entities.md" -> dm.getEffectiveEntityContext();
                case "rules.md" -> dm.getEffectiveRuleContext();
                default -> null;
            };
        } catch (Exception e) {
            HandlerUtils.sendJson(exchange, 200,
                    Map.of("name", fileName, "content", "", "error", "读取失败: " + e.getMessage()));
            return;
        }

        if (content == null) {
            HandlerUtils.sendError(exchange, 400, "Unknown file: " + fileName + ". Supported: world.md, entities.md, rules.md");
            return;
        }

        HandlerUtils.sendJson(exchange, 200,
                Map.of("name", fileName, "content", content));
    }

    // ---- save file ----

    private void handleSaveFile(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        String fileName = reqMap != null ? (String) reqMap.get("name") : null;
        String content = reqMap != null ? (String) reqMap.get("content") : null;

        if (fileName == null || fileName.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "Missing required field: name");
            return;
        }
        if (content == null) {
            HandlerUtils.sendError(exchange, 400, "Missing required field: content");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendError(exchange, 500, "DataManager not available");
            return;
        }

        try {
            switch (fileName) {
                case "world.md" -> dm.updateWorldFile(content);
                case "entities.md" -> dm.updateEntitiesFile(content);
                case "rules.md" -> dm.updateRulesFile(content);
                default -> {
                    HandlerUtils.sendError(exchange, 400, "Unknown file: " + fileName);
                    return;
                }
            }
            HandlerUtils.sendJson(exchange, 200,
                    Map.of("success", true, "name", fileName, "message", fileName + " 已保存"));
        } catch (Exception e) {
            HandlerUtils.sendError(exchange, 500, "保存失败: " + e.getMessage());
        }
    }
}
