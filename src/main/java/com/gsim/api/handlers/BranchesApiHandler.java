package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.SessionManager;
import com.gsim.api.dto.CreateBranchRequest;
import com.gsim.api.dto.CreateEdgeRequest;
import com.gsim.api.dto.CreateNodeRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.event.EventBus;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Branch / Node / Edge API handler — 分支管理接口。
 *
 * <p>路由：
 * <ul>
 *   <li>GET    /api/branches              — 列出所有分支</li>
 *   <li>POST   /api/branches              — 创建分支</li>
 *   <li>GET    /api/branches/{id}         — 分支详情</li>
 *   <li>POST   /api/branches/{id}/activate — 切换活动分支</li>
 *   <li>GET    /api/branches/{id}/nodes   — 分支节点列表</li>
 *   <li>POST   /api/branches/{id}/nodes   — 创建时间节点</li>
 *   <li>GET    /api/branches/{id}/edges   — 分支边（父子关系）</li>
 *   <li>POST   /api/branches/{id}/edges   — 创建边（预留）</li>
 * </ul>
 */
public class BranchesApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/branches";

    private final ApplicationContext ctx;
    private final EventBus eventBus;
    private final SessionManager sessionManager;

    public BranchesApiHandler(ApplicationContext ctx, EventBus eventBus, SessionManager sessionManager) {
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0) {
                // /api/branches
                switch (method) {
                    case "GET" -> handleListBranches(exchange);
                    case "POST" -> handleCreateBranch(exchange);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 1) {
                // /api/branches/{id}
                if ("GET".equals(method)) {
                    handleGetBranch(exchange, segs[0]);
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 2 && "activate".equals(segs[1])) {
                // /api/branches/{id}/activate
                if ("POST".equals(method)) {
                    handleActivateBranch(exchange, segs[0]);
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 2 && "nodes".equals(segs[1])) {
                // /api/branches/{id}/nodes
                String branchId = segs[0];
                switch (method) {
                    case "GET" -> handleListNodes(exchange, branchId);
                    case "POST" -> handleCreateNode(exchange, branchId);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 2 && "edges".equals(segs[1])) {
                // /api/branches/{id}/edges
                String branchId = segs[0];
                switch (method) {
                    case "GET" -> handleListEdges(exchange, branchId);
                    case "POST" -> handleCreateEdge(exchange, branchId);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown branches endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ---- Branch list / create ----

    private void handleListBranches(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "DataManager not available", Map.of("branches", List.of()));
            return;
        }

        List<DataDocument> branches = dm.listBranches();
        List<Map<String, Object>> list = new ArrayList<>();
        for (DataDocument b : branches) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("type", b.type());
            m.put("frontMatter", b.frontMatter());
            m.put("isActive", b.id().equals(dm.getActiveBranch()));
            list.add(m);
        }

        BaseApiHandler.sendOk(exchange, "Branches retrieved",
                Map.of("branches", list, "count", list.size(), "activeBranchId", dm.getActiveBranch()));
    }

    private void handleCreateBranch(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateBranchRequest req = JsonBodyParser.parse(body, CreateBranchRequest.class);

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        String cmd = "/data branch create " + req.name();
        if (req.description() != null && !req.description().isBlank()) {
            cmd += " " + req.description();
        }
        InteractionResult result = ctx.getInteractionManager().handle(cmd, session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested", req.name());
        data.put("success", result.success());
        data.put("message", result.displayText());

        BaseApiHandler.sendOk(exchange, result.success() ? "Branch created" : "Branch creation failed", data);
    }

    // ---- Branch detail / activate ----

    private void handleGetBranch(HttpExchange exchange, String branchId) throws IOException {
        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/branch analyze " + branchId, session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branchId", branchId);
        data.put("success", result.success());
        data.put("analysis", result.displayText());

        BaseApiHandler.sendOk(exchange, result.success() ? "Branch analysis" : "Branch not found", data);
    }

    private void handleActivateBranch(HttpExchange exchange, String branchId) throws IOException {
        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        InteractionResult result = ctx.getInteractionManager().handle("/data branch switch " + branchId, session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branchId", branchId);
        data.put("success", result.success());
        data.put("message", result.displayText());

        BaseApiHandler.sendOk(exchange, result.success() ? "Branch activated" : "Activation failed", data);
    }

    // ---- Nodes ----

    private void handleListNodes(HttpExchange exchange, String branchId) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "DataManager not available", Map.of("nodes", List.of()));
            return;
        }

        List<Map<String, Object>> nodes = new ArrayList<>();

        // 当前分支本身
        try {
            DataDocument branchDoc = dm.readById(branchId);
            if (branchDoc != null) {
                Map<String, Object> current = new LinkedHashMap<>();
                current.put("id", branchDoc.id());
                current.put("name", branchDoc.name());
                current.put("type", "branch");
                current.put("isActive", branchId.equals(dm.getActiveBranch()));
                current.put("frontMatter", branchDoc.frontMatter());
                nodes.add(current);
            }
        } catch (Exception ignored) {}

        // 子分支
        List<DataDocument> children = dm.getChildBranches(branchId);
        for (DataDocument child : children) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", child.id());
            m.put("name", child.name());
            m.put("type", "child_branch");
            m.put("isActive", child.id().equals(dm.getActiveBranch()));
            m.put("frontMatter", child.frontMatter());
            nodes.add(m);
        }

        BaseApiHandler.sendOk(exchange, "Nodes retrieved",
                Map.of("branchId", branchId, "nodes", nodes, "count", nodes.size()));
    }

    private void handleCreateNode(HttpExchange exchange, String branchId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateNodeRequest req = JsonBodyParser.parse(body, CreateNodeRequest.class);

        InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
        String cmd = "/nextturn " + req.label();
        if (req.type() != null && !req.type().isBlank()) {
            cmd += " " + req.type();
        }
        InteractionResult result = ctx.getInteractionManager().handle(cmd, session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branchId", branchId);
        data.put("label", req.label());
        data.put("success", result.success());
        data.put("message", result.displayText());

        BaseApiHandler.sendOk(exchange, result.success() ? "Node created" : "Node creation failed", data);
    }

    // ---- Edges ----

    private void handleListEdges(HttpExchange exchange, String branchId) throws IOException {
        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            BaseApiHandler.sendOk(exchange, "DataManager not available", Map.of("edges", List.of()));
            return;
        }

        List<Map<String, Object>> edges = new ArrayList<>();

        // 父子关系作为边
        List<DataDocument> branchChain = dm.getBranchChain(branchId);
        for (int i = 0; i < branchChain.size() - 1; i++) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", branchChain.get(i).id());
            edge.put("to", branchChain.get(i + 1).id());
            edge.put("type", "parent_to_child");
            edges.add(edge);
        }

        List<DataDocument> children = dm.getChildBranches(branchId);
        for (DataDocument child : children) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", branchId);
            edge.put("to", child.id());
            edge.put("type", "parent_to_child");
            edges.add(edge);
        }

        BaseApiHandler.sendOk(exchange, "Edges retrieved",
                Map.of("branchId", branchId, "edges", edges, "count", edges.size()));
    }

    private void handleCreateEdge(HttpExchange exchange, String branchId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateEdgeRequest req = JsonBodyParser.parse(body, CreateEdgeRequest.class);

        // branch 间关系目前不支持直接创建 edge，返回提示
        BaseApiHandler.sendOk(exchange, "Edge creation not supported: use /data branch create to connect branches",
                Map.of("branchId", branchId, "from", req.fromNodeId(), "to", req.toNodeId(),
                        "note", "branch edges are auto-managed via parent-child relationships"));
    }
}
