package com.gsim.webui.handlers;

import com.gsim.util.IdGenerator;
import com.gsim.util.JsonUtils;
import com.gsim.webui.MermaidGraphBuilder;
import com.gsim.webui.TemplateRenderer;
import com.gsim.worldinfo.Checkpoint;
import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * /timeline REST API handler — 时间线 + 节点管理。
 *
 * <p>注册在 WebUiServer 的 /timeline context 下。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /timeline/data → Mermaid 图 + 节点列表 JSON</li>
 *   <li>GET /timeline/node?id= → 节点详情 HTML 片段</li>
 *   <li>POST /timeline/nodes/{id}/activate → 切换活跃节点</li>
 *   <li>POST /timeline/nodes → 创建子节点</li>
 * </ul>
 */
public class TimelineApiHandler implements HttpHandler {

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;
    private final Supplier<String> worldId;

    public TimelineApiHandler(Supplier<WorldInformation> worldInfo,
                               Path worldsDir,
                               Supplier<String> worldId) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
        this.worldId = worldId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if ("/timeline/data".equals(path) && "GET".equalsIgnoreCase(method)) {
                handleTimelineData(exchange);
            } else if ("/timeline/node".equals(path) && "GET".equalsIgnoreCase(method)) {
                handleNodeDetail(exchange);
            } else if (path.startsWith("/timeline/nodes/") && path.endsWith("/activate")
                    && "POST".equalsIgnoreCase(method)) {
                String nodeId = path.substring(
                        "/timeline/nodes/".length(),
                        path.length() - "/activate".length());
                handleActivateNode(exchange, nodeId);
            } else if ("/timeline/nodes".equals(path) && "POST".equalsIgnoreCase(method)) {
                handleCreateNode(exchange);
            } else if ("/timeline/activate".equals(path) && "POST".equalsIgnoreCase(method)) {
                handleActivateAlias(exchange);  // 兼容旧前端路径
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (Exception e) {
            HandlerUtils.logError("TimelineApi", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── GET /timeline/data ──

    private void handleTimelineData(HttpExchange exchange) throws IOException {
        WorldInformation wi = worldInfo.get();
        Map<String, Object> resp = new LinkedHashMap<>();

        if (wi != null) {
            resp.put("mermaid", MermaidGraphBuilder.build(wi.branchChain(), wi.activeNodeId()));

            List<Map<String, Object>> nodes = new ArrayList<>();
            for (NodeSnapshot snap : wi.branchChain()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", snap.nodeId());
                node.put("name", snap.nodeId());
                node.put("turn", snap.turn());
                node.put("worldTime", snap.worldTime());
                node.put("isActive", snap.nodeId().equals(wi.activeNodeId()));
                nodes.add(node);
            }
            resp.put("nodes", nodes);
            resp.put("activeBranchId", wi.activeNodeId());
        } else {
            resp.put("mermaid", "");
            resp.put("nodes", List.of());
            resp.put("activeBranchId", "");
        }

        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── GET /timeline/node?id= ──

    private void handleNodeDetail(HttpExchange exchange) throws IOException {
        String nodeId = HandlerUtils.getQueryParam(exchange.getRequestURI().getQuery(), "id");
        WorldInformation wi = worldInfo.get();

        if (nodeId == null || wi == null) {
            HandlerUtils.sendHtml(exchange, 200,
                    "<div class='text-gray-500'>Node not found</div>");
            return;
        }

        NodeSnapshot node = wi.nodeById(nodeId);
        if (node == null) {
            HandlerUtils.sendHtml(exchange, 200,
                    "<div class='text-gray-500'>Node not found: " + nodeId + "</div>");
            return;
        }

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("id", node.nodeId());
        vars.put("name", node.nodeId());
        vars.put("turn", node.turn());
        vars.put("worldTime", node.worldTime());
        vars.put("status", node.status());
        vars.put("updated", node.createdAt());  // 模板期望 ${updated}
        vars.put("createdAt", node.createdAt());
        vars.put("isActive", nodeId.equals(wi.activeNodeId()));
        vars.put("checkpoints", new ArrayList<>(node.checkpoints().entrySet()));

        String html = TemplateRenderer.render("fragments/node-detail", vars);
        HandlerUtils.sendHtml(exchange, 200, html);
    }

    // ── POST /timeline/activate (兼容旧前端路径，从 body 读 branchId) ──

    private void handleActivateAlias(HttpExchange exchange) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        // 前端发送 hx-vals='{"branchId":"..."}' → 解析为 form-encoded 或 JSON
        String branchId = null;
        if (rawBody.startsWith("{")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JsonUtils.fromJson(rawBody, Map.class);
            branchId = body != null ? (String) body.get("branchId") : null;
        } else {
            Map<String, String> params = HandlerUtils.parseFormEncoded(rawBody);
            branchId = params.get("branchId");
        }
        if (branchId == null || branchId.isBlank()) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", "branchId is required"));
            return;
        }
        handleActivateNode(exchange, branchId);
    }

    // ── POST /timeline/nodes/{id}/activate ──

    private void handleActivateNode(HttpExchange exchange, String nodeId) throws IOException {
        String wid = worldId.get();
        WorldInformation wi = worldInfo.get();
        if (wi == null || wi.nodeById(nodeId) == null) {
            HandlerUtils.sendJson(exchange, 404,
                    Map.of("error", "Node not found in current world"));
            return;
        }

        ActiveStateManager.ActiveState current = ActiveStateManager.load(worldsDir, wid);
        Map<String, String> sessions = current != null ? current.sessions() : new LinkedHashMap<>();
        ActiveStateManager.ActiveState updated =
                new ActiveStateManager.ActiveState(nodeId, sessions);
        ActiveStateManager.save(worldsDir, wid, updated);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("activeNodeId", nodeId);
        resp.put("message", "Switched to node " + nodeId);
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    // ── POST /timeline/nodes (Task 10) ──

    private void handleCreateNode(HttpExchange exchange) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = rawBody.isBlank() ? Map.of() : JsonUtils.fromJson(rawBody, Map.class);
        String worldTime = body != null ? body.getOrDefault("worldTime", "").toString() : "";
        String wid = worldId.get();
        WorldInformation wi = worldInfo.get();

        if (wi == null) {
            HandlerUtils.sendJson(exchange, 400, Map.of("error", "No active world"));
            return;
        }

        int nextTurn = wi.activeNode().turn() + 1;
        String newNodeId = IdGenerator.nodeId();
        NodeSnapshot newNode = new NodeSnapshot(
                newNodeId, wi.activeNodeId(), nextTurn, worldTime,
                "active", Instant.now().toString(),
                new LinkedHashMap<>());

        Path nodeFile = NodeLoader.nodeFile(worldsDir, wid, newNodeId);
        NodeLoader.save(nodeFile, newNode);

        ActiveStateManager.ActiveState current = ActiveStateManager.load(worldsDir, wid);
        Map<String, String> sessions = current != null ? current.sessions() : new LinkedHashMap<>();
        ActiveStateManager.ActiveState updated =
                new ActiveStateManager.ActiveState(newNodeId, sessions);
        ActiveStateManager.save(worldsDir, wid, updated);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("node", Map.of(
                "id", newNode.nodeId(),
                "turn", newNode.turn(),
                "worldTime", newNode.worldTime(),
                "parentId", newNode.parentId()
        ));
        resp.put("message", "Node created and activated");
        HandlerUtils.sendJson(exchange, 201, resp);
    }
}
