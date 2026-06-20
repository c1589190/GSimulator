package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.SessionManager;
import com.gsim.app.ApplicationContext;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.*;

/**
 * /api/tools — 工具管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/tools        — 列出所有已注册工具</li>
 *   <li>POST   /api/tools/search — 搜索本地文件</li>
 * </ul>
 */
public class ToolsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/tools";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public ToolsApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0) {
                if ("GET".equals(method)) {
                    handleListTools(exchange);
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
                }
            } else if (segs.length == 1 && "search".equals(segs[0])) {
                if ("POST".equals(method)) {
                    handleSearch(exchange);
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
                }
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown tools endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleListTools(HttpExchange exchange) throws IOException {
        ToolRegistry registry = ctx.getToolRegistry();
        Map<String, AgentTool> tools = registry.all();

        List<Map<String, Object>> list = new ArrayList<>();
        for (var entry : tools.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", entry.getKey());
            m.put("description", entry.getValue().description());
            list.add(m);
        }

        BaseApiHandler.sendOk(exchange, "Tools listed",
                Map.of("tools", list, "count", list.size()));
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap;
        try {
            reqMap = com.gsim.util.JsonUtils.fromJson(body, Map.class);
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        Object rawQuery = reqMap != null ? reqMap.get("query") : null;
        String query = rawQuery != null ? rawQuery.toString() : "";
        if (query.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: query");
            return;
        }

        InteractionSession session = sessionManager.getOrCreateSession("default");
        InteractionResult result = ctx.getInteractionManager().handle("/tool wiki_search " + query, session);

        BaseApiHandler.sendOk(exchange, result.success() ? "Search completed" : "Search failed",
                Map.of("query", query, "success", result.success(), "displayText", result.displayText()));
    }
}
