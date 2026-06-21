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
import java.io.OutputStream;
import java.net.URLDecoder;
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
                sendError(exchange, 404, "Unknown timeline endpoint");
            }
        } catch (Exception e) {
            System.err.println("[TimelineHandler] Error handling " + method + " " + path + ": " + e.getMessage());
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleData(HttpExchange exchange) throws IOException {
        DataManager dm = ctx.getDataManager();
        Map<String, Object> data = MermaidRenderer.renderTimeline(dm);
        sendJson(exchange, 200, data);
    }

    private void handleNode(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String nodeId = getQueryParam(query, "id");
        if (nodeId == null || nodeId.isBlank()) {
            sendError(exchange, 400, "id query param required");
            return;
        }

        DataManager dm = ctx.getDataManager();
        if (dm == null) {
            sendError(exchange, 500, "DataManager not available");
            return;
        }

        DataDocument doc = dm.readById(nodeId);
        if (doc == null) {
            sendError(exchange, 404, "Node not found: " + nodeId);
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
        sendHtml(exchange, 200, html);
    }

    private void handleActivate(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        Map<String, Object> req;
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            req = new LinkedHashMap<>(parseFormEncoded(body));
        } else {
            req = JsonUtils.fromJson(body, Map.class);
        }

        String branchId = (String) req.get("branchId");
        if (branchId == null || branchId.isBlank()) {
            sendError(exchange, 400, "branchId is required");
            return;
        }

        InteractionSession session = ctx.getSessionManager() != null
                ? ctx.getSessionManager().getOrCreateSession("default")
                : null;
        if (session == null) {
            sendError(exchange, 500, "Session not available");
            return;
        }

        InteractionResult result = ctx.getInteractionManager()
                .handle("/data branch switch " + branchId, session);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.success());
        resp.put("message", result.displayText());
        resp.put("branchId", branchId);
        sendJson(exchange, result.success() ? 200 : 400, resp);
    }

    // ---- helpers ----

    private static Map<String, String> parseFormEncoded(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return params;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = JsonUtils.toJsonCompact(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        sendJson(exchange, status, Map.of("error", msg));
    }

    private static String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
