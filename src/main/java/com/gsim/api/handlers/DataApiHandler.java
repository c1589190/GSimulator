package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.SessionManager;
import com.gsim.app.ApplicationContext;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.data.DataSearchResult;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * /api/data — 世界数据管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/data/status             — 数据状态</li>
 *   <li>GET    /api/data/worlds             — 列出世界</li>
 *   <li>POST   /api/data/worlds             — 创建世界</li>
 *   <li>POST   /api/data/worlds/switch      — 切换世界</li>
 *   <li>GET    /api/data/branches           — 列出分支</li>
 *   <li>POST   /api/data/branches           — 创建分支</li>
 *   <li>POST   /api/data/branches/switch    — 切换分支</li>
 *   <li>GET    /api/data/timeline           — 时间线</li>
 *   <li>GET    /api/data/input              — 读取 input.md</li>
 *   <li>POST   /api/data/input              — 追加 input</li>
 *   <li>DELETE /api/data/input              — 清空 input</li>
 *   <li>GET    /api/data/documents          — 列出文档</li>
 *   <li>GET    /api/data/documents/{id}     — 查看文档</li>
 *   <li>GET    /api/data/search?q=...       — 搜索文档</li>
 *   <li>POST   /api/data/reload             — 重新加载</li>
 * </ul>
 */
public class DataApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/data";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public DataApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0) {
                handleDataStatus(exchange);
                return;
            }

            switch (segs[0]) {
                case "status" -> handleDataStatus(exchange);
                case "worlds" -> {
                    if (segs.length >= 2 && "switch".equals(segs[1])) {
                        handleWorldSwitch(exchange, method);
                    } else {
                        if ("GET".equals(method)) handleListWorlds(exchange);
                        else if ("POST".equals(method)) handleCreateWorld(exchange);
                        else BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "branches" -> {
                    if (segs.length >= 2 && "switch".equals(segs[1])) {
                        handleBranchSwitch(exchange, method);
                    } else {
                        if ("GET".equals(method)) handleListBranches(exchange);
                        else if ("POST".equals(method)) handleCreateBranch(exchange);
                        else BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                    }
                }
                case "timeline" -> handleTimeline(exchange);
                case "input" -> {
                    if ("GET".equals(method)) handleGetInput(exchange);
                    else if ("POST".equals(method)) handleAppendInput(exchange);
                    else if ("DELETE".equals(method)) handleClearInput(exchange);
                    else BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
                case "documents" -> {
                    if (segs.length >= 2) {
                        handleGetDocument(exchange, segs[1]);
                    } else {
                        handleListDocuments(exchange);
                    }
                }
                case "search" -> handleSearch(exchange);
                case "reload" -> handleReload(exchange, method);
                default -> BaseApiHandler.sendNotFound(exchange, "Unknown data sub-resource: " + segs[0]);
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ---- Status ----

    private void handleDataStatus(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "Data manager not available", Map.of("available", false));
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", true);
        data.put("activeWorld", dm.getActiveWorld());
        data.put("activeBranch", dm.getActiveBranch());
        data.put("worldsCount", dm.listWorlds().size());
        data.put("branchesCount", dm.listBranches().size());
        data.put("docCount", dm.docCount());
        data.put("needsRootBootstrap", dm.needsRootBootstrap());

        BaseApiHandler.sendOk(exchange, "Data status", data);
    }

    // ---- Worlds ----

    private void handleListWorlds(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "Data manager not available", Map.of("worlds", List.of()));
            return;
        }

        List<String> worlds = dm.listWorlds();
        String active = dm.getActiveWorld();
        List<Map<String, Object>> list = worlds.stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", w);
            m.put("isActive", w.equals(active));
            return m;
        }).collect(Collectors.toList());

        BaseApiHandler.sendOk(exchange, "Worlds listed",
                Map.of("worlds", list, "count", list.size(), "activeWorld", active));
    }

    private void handleCreateWorld(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        Object rawName = reqMap != null ? reqMap.get("name") : null;
        String name = rawName != null ? rawName.toString() : "";

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data world create " + name, session);

        BaseApiHandler.sendOk(exchange, result.success() ? "World created" : "World creation failed",
                Map.of("name", name, "success", result.success(), "message", result.displayText()));
    }

    private void handleWorldSwitch(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        Object rawName = reqMap != null ? reqMap.get("name") : null;
        String name = rawName != null ? rawName.toString() : "";

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data world switch " + name, session);

        BaseApiHandler.sendOk(exchange, result.success() ? "World switched" : "World switch failed",
                Map.of("name", name, "success", result.success(), "message", result.displayText()));
    }

    // ---- Branches ----

    private void handleListBranches(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "Data manager not available", Map.of("branches", List.of()));
            return;
        }

        List<DataDocument> branches = dm.listBranches();
        String active = dm.getActiveBranch();
        List<Map<String, Object>> list = branches.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("type", b.type());
            m.put("frontMatter", b.frontMatter());
            m.put("isActive", b.id().equals(active));
            return m;
        }).collect(Collectors.toList());

        BaseApiHandler.sendOk(exchange, "Branches listed",
                Map.of("branches", list, "count", list.size(), "activeBranch", active));
    }

    private void handleCreateBranch(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        Object rawName = reqMap != null ? reqMap.get("name") : null;
        String name = rawName != null ? rawName.toString() : "";
        Object rawWorldTime = reqMap != null ? reqMap.get("worldTime") : null;
        String worldTime = rawWorldTime != null ? rawWorldTime.toString() : "";

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        String cmd = "/data branch create " + name;
        if (!worldTime.isBlank()) cmd += " " + worldTime;
        InteractionResult result = ctx.getInteractionManager().handle(cmd, session);

        BaseApiHandler.sendOk(exchange, result.success() ? "Branch created" : "Branch creation failed",
                Map.of("name", name, "success", result.success(), "message", result.displayText()));
    }

    private void handleBranchSwitch(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        Object rawBranchId = reqMap != null ? reqMap.get("branchId") : null;
        String branchId = rawBranchId != null ? rawBranchId.toString() : "";

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data branch switch " + branchId, session);

        BaseApiHandler.sendOk(exchange, result.success() ? "Branch switched" : "Branch switch failed",
                Map.of("branchId", branchId, "success", result.success(), "message", result.displayText()));
    }

    // ---- Timeline ----

    private void handleTimeline(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "Data manager not available", Map.of("tree", List.of()));
            return;
        }

        List<DataManager.TreeNode> tree = dm.getTimelineTree();
        List<Map<String, Object>> nodes = treeToMap(tree);
        BaseApiHandler.sendOk(exchange, "Timeline retrieved",
                Map.of("tree", nodes, "count", countTreeNodes(tree)));
    }

    private List<Map<String, Object>> treeToMap(List<DataManager.TreeNode> nodes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DataManager.TreeNode n : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("name", n.name());
            m.put("children", treeToMap(n.children()));
            result.add(m);
        }
        return result;
    }

    private int countTreeNodes(List<DataManager.TreeNode> nodes) {
        int count = nodes.size();
        for (DataManager.TreeNode n : nodes) count += countTreeNodes(n.children());
        return count;
    }

    // ---- Input ----

    private void handleGetInput(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "Data manager not available", Map.of("content", ""));
            return;
        }

        String input = dm.getInputBody();
        BaseApiHandler.sendOk(exchange, "Input retrieved",
                Map.of("content", input != null ? input : "", "approxChars", input != null ? input.length() : 0));
    }

    private void handleAppendInput(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
        Object rawText = reqMap != null ? reqMap.get("text") : null;
        String text = rawText != null ? rawText.toString() : "";

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data input " + text, session);

        BaseApiHandler.sendOk(exchange, result.success() ? "Input appended" : "Input append failed",
                Map.of("success", result.success(), "message", result.displayText()));
    }

    private void handleClearInput(HttpExchange exchange) throws IOException {
        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data input clear", session);

        BaseApiHandler.sendOk(exchange, result.success() ? "Input cleared" : "Input clear failed",
                Map.of("success", result.success(), "message", result.displayText()));
    }

    // ---- Documents ----

    private void handleListDocuments(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "Data manager not available", Map.of("documents", List.of()));
            return;
        }

        // 支持 ?type= 过滤
        String query = exchange.getRequestURI().getQuery();
        String typeFilter = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if ("type".equals(kv[0]) && kv.length > 1) {
                    typeFilter = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }

        List<DataDocument> docs = (typeFilter != null && !typeFilter.isBlank())
                ? dm.listByType(typeFilter) : dm.listAll();

        List<Map<String, Object>> list = docs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("name", d.name());
            m.put("type", d.type());
            m.put("path", d.rawPath().toString());
            m.put("frontMatter", d.frontMatter());
            return m;
        }).collect(Collectors.toList());

        BaseApiHandler.sendOk(exchange, "Documents listed",
                Map.of("documents", list, "count", list.size(), "typeFilter", typeFilter));
    }

    private void handleGetDocument(HttpExchange exchange, String docId) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendError(exchange, 503, "Data manager not available");
            return;
        }

        DataDocument doc = dm.readById(docId);
        if (doc == null) {
            BaseApiHandler.sendNotFound(exchange, "Document not found: " + docId);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", doc.id());
        data.put("name", doc.name());
        data.put("type", doc.type());
        data.put("path", doc.rawPath().toString());
        data.put("frontMatter", doc.frontMatter());
        data.put("body", doc.fullContent());

        BaseApiHandler.sendOk(exchange, "Document found", data);
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String q = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if ("q".equals(kv[0]) && kv.length > 1) {
                    q = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }

        if (q == null || q.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing query parameter: q");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendError(exchange, 503, "Data manager not available");
            return;
        }

        List<DataSearchResult> results = dm.search(q, 10);
        List<Map<String, Object>> list = results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("name", r.name());
            m.put("type", r.type());
            m.put("path", r.path());
            m.put("score", r.score());
            m.put("snippet", r.snippet());
            return m;
        }).collect(Collectors.toList());

        BaseApiHandler.sendOk(exchange, "Search completed",
                Map.of("query", q, "results", list, "count", list.size()));
    }

    private void handleReload(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data reload", session);

        BaseApiHandler.sendOk(exchange, result.success() ? "Data reloaded" : "Reload failed",
                Map.of("success", result.success(), "message", result.displayText()));
    }
}
