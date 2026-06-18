package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.dto.CreateBranchRequest;
import com.gsim.api.dto.CreateEdgeRequest;
import com.gsim.api.dto.CreateNodeRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Branch / Node / Edge API handler — 预留可视化分支管理接口。
 *
 * <p>路由：
 * <ul>
 *   <li>GET    /api/branches</li>
 *   <li>POST   /api/branches</li>
 *   <li>GET    /api/branches/{id}</li>
 *   <li>POST   /api/branches/{id}/activate</li>
 *   <li>GET    /api/branches/{id}/nodes</li>
 *   <li>POST   /api/branches/{id}/nodes</li>
 *   <li>GET    /api/branches/{id}/edges</li>
 *   <li>POST   /api/branches/{id}/edges</li>
 * </ul>
 *
 * <p>第一版返回 not implemented，但 DTO 和路由已就绪。
 */
public class BranchesApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/branches";

    private final ApplicationContext ctx;
    private final EventBus eventBus;

    public BranchesApiHandler(ApplicationContext ctx, EventBus eventBus) {
        this.ctx = ctx;
        this.eventBus = eventBus;
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

    private void handleListBranches(HttpExchange exchange) throws IOException {
        BaseApiHandler.sendOk(exchange, "Branch management not yet implemented",
                Map.of("branches", List.of(), "note", "not_implemented"));
    }

    private void handleCreateBranch(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateBranchRequest req = JsonBodyParser.parse(body, CreateBranchRequest.class);
        BaseApiHandler.sendOk(exchange, "Branch creation not yet implemented",
                Map.of("requested", req.name(), "note", "not_implemented"));
    }

    private void handleGetBranch(HttpExchange exchange, String branchId) throws IOException {
        BaseApiHandler.sendOk(exchange, "Branch retrieval not yet implemented",
                Map.of("branchId", branchId, "note", "not_implemented"));
    }

    private void handleActivateBranch(HttpExchange exchange, String branchId) throws IOException {
        BaseApiHandler.sendOk(exchange, "Branch activation not yet implemented",
                Map.of("branchId", branchId, "note", "not_implemented"));
    }

    private void handleListNodes(HttpExchange exchange, String branchId) throws IOException {
        BaseApiHandler.sendOk(exchange, "Node listing not yet implemented",
                Map.of("branchId", branchId, "nodes", List.of(), "note", "not_implemented"));
    }

    private void handleCreateNode(HttpExchange exchange, String branchId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateNodeRequest req = JsonBodyParser.parse(body, CreateNodeRequest.class);
        BaseApiHandler.sendOk(exchange, "Node creation not yet implemented",
                Map.of("branchId", branchId, "requested", req.label(), "note", "not_implemented"));
    }

    private void handleListEdges(HttpExchange exchange, String branchId) throws IOException {
        BaseApiHandler.sendOk(exchange, "Edge listing not yet implemented",
                Map.of("branchId", branchId, "edges", List.of(), "note", "not_implemented"));
    }

    private void handleCreateEdge(HttpExchange exchange, String branchId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateEdgeRequest req = JsonBodyParser.parse(body, CreateEdgeRequest.class);
        BaseApiHandler.sendOk(exchange, "Edge creation not yet implemented",
                Map.of("branchId", branchId, "from", req.fromNodeId(), "to", req.toNodeId(), "note", "not_implemented"));
    }
}
