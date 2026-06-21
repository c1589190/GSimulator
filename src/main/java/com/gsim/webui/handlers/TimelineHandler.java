package com.gsim.webui.handlers;

import com.gsim.app.ApplicationContext;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.JsonUtils;
import com.gsim.webui.MermaidRenderer;
import com.gsim.webui.TemplateRenderer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Timeline API handler.
 *
 * <p>GET  /timeline          — delegate to PageHandler for HTML fragment
 * <p>GET  /timeline/data     — branch tree JSON + Mermaid syntax
 * <p>GET  /timeline/node     — node detail HTML fragment
 * <p>POST /timeline/activate — switch active branch
 */
public class TimelineHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final PageHandler pageHandler;

    public TimelineHandler(ApplicationContext ctx, PageHandler pageHandler) {
        this.ctx = ctx;
        this.pageHandler = pageHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            // GET /timeline (exact) — delegate to PageHandler for HTML fragment
            if (path.equals("/timeline") && "GET".equals(method)) {
                pageHandler.handle(exchange);
                return;
            }

            if (path.equals("/timeline/data") && "GET".equals(method)) {
                handleData(exchange);
            } else if (path.equals("/timeline/node") && "GET".equals(method)) {
                handleNode(exchange);
            } else if (path.equals("/timeline/activate") && "POST".equals(method)) {
                handleActivate(exchange);
            } else {
                HandlerUtils.sendError(exchange, 404, "Unknown timeline endpoint");
            }
        } catch (Exception e) {
            HandlerUtils.logError("TimelineHandler", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleData(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        Map<String, Object> data = MermaidRenderer.renderTimeline(dm);
        HandlerUtils.sendJson(exchange, 200, data);
    }

    private void handleNode(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String nodeId = HandlerUtils.getQueryParam(query, "id");
        if (nodeId == null || nodeId.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "id query param required");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            HandlerUtils.sendError(exchange, 500, "DataManager not available");
            return;
        }

        DataDocument doc = dm.readById(nodeId);
        if (doc == null) {
            HandlerUtils.sendError(exchange, 404, "Node not found: " + nodeId);
            return;
        }

        Map<String, String> fm = doc.frontMatter();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("id", doc.id());
        vars.put("name", doc.name());
        vars.put("turn", fm.getOrDefault("turn", "0"));
        vars.put("worldTime", fm.getOrDefault("world_time", "?"));
        vars.put("status", fm.getOrDefault("status", "active"));
        vars.put("updated", doc.updated());
        vars.put("isActive", doc.id().equals(dm.getActiveBranch()));

        String html = TemplateRenderer.render("fragments/node-detail", vars);
        HandlerUtils.sendHtml(exchange, 200, html);
    }

    private void handleActivate(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        Map<String, Object> req;
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            req = new LinkedHashMap<>(HandlerUtils.parseFormEncoded(body));
        } else {
            req = JsonUtils.fromJson(body, Map.class);
        }

        String branchId = (String) req.get("branchId");
        if (branchId == null || branchId.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "branchId is required");
            return;
        }

        InteractionSession session = ctx.getSessionManager() != null
                ? ctx.getSessionManager().getOrCreateSession("default")
                : null;
        if (session == null) {
            HandlerUtils.sendError(exchange, 500, "Session not available");
            return;
        }

        InteractionResult result = ctx.getInteractionManager()
                .handle("/data branch switch " + branchId, session);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.success());
        resp.put("message", result.displayText());
        resp.put("branchId", branchId);
        HandlerUtils.sendJson(exchange, result.success() ? 200 : 400, resp);
    }

}
