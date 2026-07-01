package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.util.IdGenerator;
import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;
import com.gsim.worldinfo.loader.WorldInfoBuilder;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.gsim.worldinfo.loader.WorldIndexManager.WorldEntry;
import com.gsim.worldinfo.loader.WorldIndexManager.WorldMeta;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * World 管理 API — World CRUD + Node 操作。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/world-manager — 列出所有 World</li>
 *   <li>POST /api/world-manager — 创建 World</li>
 *   <li>DELETE /api/world-manager/{worldId} — 删除 World</li>
 *   <li>GET /api/world-manager/{worldId}/nodes — 节点列表</li>
 *   <li>GET /api/world-manager/{worldId}/nodes/active — 活跃节点</li>
 *   <li>GET /api/world-manager/{worldId}/nodes/{nodeId} — 获取节点</li>
 *   <li>POST /api/world-manager/{worldId}/nodes — 创建子节点</li>
 *   <li>POST /api/world-manager/{worldId}/nodes/active — 切换活跃节点</li>
 *   <li>POST /api/world-manager/{worldId}/nodes/{nodeId}/goto-parent — 回到父节点</li>
 * </ul>
 */
public class WorldManagerApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/world-manager";
    private static final Pattern NODE_FILE_PATTERN = Pattern.compile("n(\\d{4})\\.json$");

    private final Path worldsDir;
    private final Supplier<String> activeWorldId;

    public WorldManagerApiHandler(Path worldsDir, Supplier<String> activeWorldId) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0 && "GET".equals(method)) {
                handleListWorlds(exchange);
            } else if (segs.length == 0 && "POST".equals(method)) {
                handleCreateWorld(exchange);
            } else if (segs.length == 1 && "DELETE".equals(method)) {
                handleDeleteWorld(exchange, segs[0]);
            } else if (segs.length >= 2 && "nodes".equals(segs[1])) {
                handleNodeRoutes(exchange, method, segs);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown world-manager endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── World CRUD ──

    private void handleListWorlds(HttpExchange exchange) throws IOException {
        List<WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worlds", worlds);
        data.put("activeWorld", active);
        BaseApiHandler.sendOk(exchange, "Worlds listed", data);
    }

    private void handleCreateWorld(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String name = req != null ? (String) req.getOrDefault("name", "") : "";
        String id = req != null ? (String) req.getOrDefault("id", "") : "";
        if (name.isBlank()) name = id;
        if (id.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "id is required");
            return;
        }
        if (!id.matches("[a-zA-Z0-9_\\-]+")) {
            BaseApiHandler.sendError(exchange, 400,
                    "id must contain only alphanumeric, dash, or underscore characters");
            return;
        }
        if (Files.exists(worldsDir.resolve(id))) {
            BaseApiHandler.sendError(exchange, 409, "World already exists: " + id);
            return;
        }
        try {
            WorldMeta meta = WorldIndexManager.createWorld(worldsDir, id, name);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", meta.id());
            data.put("name", meta.name());
            data.put("createdAt", meta.createdAt());
            data.put("currentNodeId", meta.currentNodeId());
            BaseApiHandler.sendOk(exchange, "World created: " + id, data);
        } catch (IllegalArgumentException e) {
            BaseApiHandler.sendError(exchange, 400, e.getMessage());
        }
    }

    private void handleDeleteWorld(HttpExchange exchange, String worldId) throws IOException {
        Path worldPath = worldsDir.resolve(worldId);
        if (!Files.exists(worldPath)) {
            BaseApiHandler.sendError(exchange, 404, "World not found: " + worldId);
            return;
        }
        try {
            deleteRecursive(worldPath);
            List<WorldEntry> entries = WorldIndexManager.listWorlds(worldsDir);
            List<WorldEntry> filtered = new ArrayList<>();
            for (var e : entries) {
                if (!e.id().equals(worldId)) filtered.add(e);
            }
            Files.writeString(WorldIndexManager.indexFile(worldsDir), JsonUtils.toJson(filtered));
        } catch (IOException e) {
            BaseApiHandler.sendError(exchange, 500, "Failed to delete world: " + e.getMessage());
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", worldId);
        BaseApiHandler.sendOk(exchange, "World deleted: " + worldId, data);
    }

    // ── Node routes ──

    private void handleNodeRoutes(HttpExchange exchange, String method, String[] segs) throws IOException {
        String worldId = segs[0];
        if (!Files.exists(WorldIndexManager.worldFile(worldsDir, worldId))) {
            BaseApiHandler.sendError(exchange, 404, "World not found: " + worldId);
            return;
        }

        // GET /{worldId}/nodes
        if (segs.length == 2 && "GET".equals(method)) {
            handleListNodes(exchange, worldId);
            return;
        }
        // POST /{worldId}/nodes
        if (segs.length == 2 && "POST".equals(method)) {
            handleCreateNode(exchange, worldId);
            return;
        }
        // GET /{worldId}/nodes/active
        if (segs.length == 3 && "active".equals(segs[2]) && "GET".equals(method)) {
            handleGetActiveNode(exchange, worldId);
            return;
        }
        // POST /{worldId}/nodes/active
        if (segs.length == 3 && "active".equals(segs[2]) && "POST".equals(method)) {
            handleSwitchActiveNode(exchange, worldId);
            return;
        }
        // GET /{worldId}/nodes/{nodeId}
        if (segs.length == 3 && !"active".equals(segs[2]) && "GET".equals(method)) {
            handleGetNode(exchange, worldId, segs[2]);
            return;
        }
        // POST /{worldId}/nodes/{nodeId}/goto-parent
        if (segs.length == 4 && "goto-parent".equals(segs[3]) && "POST".equals(method)) {
            handleGotoParent(exchange, worldId, segs[2]);
            return;
        }

        BaseApiHandler.sendNotFound(exchange, "Unknown node endpoint");
    }

    private void handleListNodes(HttpExchange exchange, String worldId) throws IOException {
        ActiveStateManager.ActiveState activeState = ActiveStateManager.load(worldsDir, worldId);
        if (activeState == null) {
            BaseApiHandler.sendError(exchange, 404, "World has no active state: " + worldId);
            return;
        }
        WorldInformation wi = WorldInfoBuilder.build(worldsDir, worldId, activeState.nodeId());
        if (wi == null) {
            BaseApiHandler.sendError(exchange, 404, "Cannot load world information for: " + worldId);
            return;
        }

        String mode = parseQueryParam(exchange, "mode");
        boolean tree = "tree".equalsIgnoreCase(mode);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeSnapshot node : wi.branchChain()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("nodeId", node.nodeId());
            n.put("parentId", node.parentId());
            n.put("turn", node.turn());
            n.put("worldTime", node.worldTime());
            n.put("status", node.status());
            n.put("createdAt", node.createdAt());
            n.put("isActive", node.nodeId().equals(activeState.nodeId()));
            if (tree) {
                n.put("checkpointCount", node.checkpoints().size());
                n.put("checkpoints", node.checkpoints().keySet());
            }
            nodes.add(n);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("worldId", worldId);
        data.put("activeNodeId", activeState.nodeId());
        data.put("mode", tree ? "tree" : "flat");
        data.put("count", nodes.size());
        data.put("nodes", nodes);
        BaseApiHandler.sendOk(exchange, "Nodes listed", data);
    }

    private void handleGetActiveNode(HttpExchange exchange, String worldId) throws IOException {
        ActiveStateManager.ActiveState activeState = ActiveStateManager.load(worldsDir, worldId);
        if (activeState == null) {
            BaseApiHandler.sendError(exchange, 404, "World has no active state: " + worldId);
            return;
        }
        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, activeState.nodeId());
        if (!Files.exists(nodeFile)) {
            BaseApiHandler.sendError(exchange, 404, "Active node file missing: " + activeState.nodeId());
            return;
        }
        NodeSnapshot node = NodeLoader.load(nodeFile);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.nodeId());
        data.put("parentId", node.parentId());
        data.put("turn", node.turn());
        data.put("worldTime", node.worldTime());
        data.put("status", node.status());
        data.put("createdAt", node.createdAt());
        data.put("isRoot", node.isRoot());
        data.put("checkpointCount", node.checkpoints().size());
        data.put("checkpoints", node.checkpoints().keySet());
        BaseApiHandler.sendOk(exchange, "Active node: " + activeState.nodeId(), data);
    }

    private void handleGetNode(HttpExchange exchange, String worldId, String nodeId) throws IOException {
        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) {
            BaseApiHandler.sendError(exchange, 404, "Node not found: " + nodeId);
            return;
        }
        NodeSnapshot node = NodeLoader.load(nodeFile);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", node.nodeId());
        data.put("parentId", node.parentId());
        data.put("turn", node.turn());
        data.put("worldTime", node.worldTime());
        data.put("status", node.status());
        data.put("createdAt", node.createdAt());
        data.put("isRoot", node.isRoot());
        data.put("checkpointCount", node.checkpoints().size());
        data.put("checkpoints", node.checkpoints().keySet());
        BaseApiHandler.sendOk(exchange, "Node: " + nodeId, data);
    }

    private void handleCreateNode(HttpExchange exchange, String worldId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String worldTime = req != null ? (String) req.get("worldTime") : null;
        if (worldTime == null || worldTime.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "worldTime is required");
            return;
        }

        ActiveStateManager.ActiveState activeState = ActiveStateManager.load(worldsDir, worldId);
        if (activeState == null) {
            BaseApiHandler.sendError(exchange, 400, "World has no active state: " + worldId);
            return;
        }

        WorldInformation wi = WorldInfoBuilder.build(worldsDir, worldId, activeState.nodeId());
        if (wi == null) {
            BaseApiHandler.sendError(exchange, 500, "Failed to load world: " + worldId);
            return;
        }

        int nextTurn = wi.activeNode().turn() + 1;
        String parentId = wi.activeNodeId();

        seedNodeCounterFromDisk(worldId);
        String newNodeId = IdGenerator.nodeId();
        String title = req != null ? (String) req.getOrDefault("title", null) : null;
        String note = req != null ? (String) req.getOrDefault("note", null) : null;

        NodeSnapshot child = new NodeSnapshot(
                newNodeId, parentId, nextTurn, worldTime,
                "active", Instant.now().toString(),
                new LinkedHashMap<>());

        NodeLoader.save(NodeLoader.nodeFile(worldsDir, worldId, newNodeId), child);

        Map<String, String> sessions = activeState.sessions();
        ActiveStateManager.save(worldsDir, worldId,
                new ActiveStateManager.ActiveState(newNodeId, sessions));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodeId", newNodeId);
        data.put("parentId", parentId);
        data.put("turn", nextTurn);
        data.put("worldTime", worldTime);
        if (title != null && !title.isBlank()) data.put("title", title);
        if (note != null && !note.isBlank()) data.put("note", note);
        BaseApiHandler.sendOk(exchange, "Node created: " + newNodeId, data);
    }

    private void handleSwitchActiveNode(HttpExchange exchange, String worldId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String targetNodeId = req != null ? (String) req.get("nodeId") : null;
        if (targetNodeId == null || targetNodeId.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "nodeId is required");
            return;
        }

        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, targetNodeId);
        if (!Files.exists(nodeFile)) {
            BaseApiHandler.sendError(exchange, 404, "Node not found: " + targetNodeId);
            return;
        }

        ActiveStateManager.ActiveState current = ActiveStateManager.load(worldsDir, worldId);
        if (current == null) {
            BaseApiHandler.sendError(exchange, 400, "World has no active state: " + worldId);
            return;
        }

        // Verify the target node is reachable (in the chain from root)
        NodeSnapshot targetNode = NodeLoader.load(nodeFile);
        if (!targetNode.isRoot()) {
            Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, targetNode.parentId());
            if (!Files.exists(parentFile)) {
                BaseApiHandler.sendError(exchange, 400,
                        "Parent node not found for: " + targetNodeId + " — node is orphaned");
                return;
            }
        }

        ActiveStateManager.save(worldsDir, worldId,
                new ActiveStateManager.ActiveState(targetNodeId, current.sessions()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("previousNodeId", current.nodeId());
        data.put("activeNodeId", targetNodeId);
        BaseApiHandler.sendOk(exchange, "Switched to node: " + targetNodeId, data);
    }

    private void handleGotoParent(HttpExchange exchange, String worldId, String nodeId) throws IOException {
        Path nodeFile = NodeLoader.nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) {
            BaseApiHandler.sendError(exchange, 404, "Node not found: " + nodeId);
            return;
        }

        NodeSnapshot node = NodeLoader.load(nodeFile);
        if (node.isRoot()) {
            BaseApiHandler.sendError(exchange, 400, "Already at root node, no parent to go to");
            return;
        }

        String parentId = node.parentId();
        Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, parentId);
        if (!Files.exists(parentFile)) {
            BaseApiHandler.sendError(exchange, 404, "Parent node not found: " + parentId);
            return;
        }

        ActiveStateManager.ActiveState current = ActiveStateManager.load(worldsDir, worldId);
        if (current == null) {
            BaseApiHandler.sendError(exchange, 400, "World has no active state: " + worldId);
            return;
        }

        ActiveStateManager.save(worldsDir, worldId,
                new ActiveStateManager.ActiveState(parentId, current.sessions()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("previousNodeId", nodeId);
        data.put("newActiveNodeId", parentId);
        BaseApiHandler.sendOk(exchange, "Moved to parent: " + parentId, data);
    }

    // ── Helpers ──

    private void seedNodeCounterFromDisk(String worldId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.isDirectory(nodesDir)) return;
        int max = -1;
        try (Stream<Path> files = Files.list(nodesDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = NODE_FILE_PATTERN.matcher(p.getFileName().toString());
                if (m.find()) {
                    int num = Integer.parseInt(m.group(1));
                    if (num > max) max = num;
                }
            }
        } catch (IOException ignored) {
        }
        if (max >= 0) {
            IdGenerator.seedNodeCounter(max + 1);
        }
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
