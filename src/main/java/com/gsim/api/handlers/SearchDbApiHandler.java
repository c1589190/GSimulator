package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.dto.SearchDbRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * SearchDB API handler — 预留搜索接口。
 *
 * <p>路由：
 * <ul>
 *   <li>GET  /api/searchdb?q=关键词</li>
 *   <li>POST /api/searchdb</li>
 * </ul>
 *
 * <p>如果 LocalKnowledgeStore 未完成，返回明确提示。
 */
public class SearchDbApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final EventBus eventBus;

    public SearchDbApiHandler(ApplicationContext ctx, EventBus eventBus) {
        this.ctx = ctx;
        this.eventBus = eventBus;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            switch (method) {
                case "GET" -> handleGet(exchange);
                case "POST" -> handlePost(exchange);
                default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Search failed: " + e.getMessage());
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
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

        eventBus.publish(GSimEvent.of("api", "search_progress",
                Map.of("message", "Searching: " + q)));

        // 预留：ChromaDB / LocalKnowledgeStore 未完成
        BaseApiHandler.sendOk(exchange, "SearchDB is not yet implemented. LocalKnowledgeStore coming in future phase.",
                Map.of("query", q, "results", List.of(), "note", "not_implemented"));
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        SearchDbRequest req = JsonBodyParser.parse(body, SearchDbRequest.class);

        if (req.query() == null || req.query().isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: query");
            return;
        }

        eventBus.publish(GSimEvent.of("api", "search_progress",
                Map.of("message", "Searching: " + req.query())));

        // 预留：ChromaDB / LocalKnowledgeStore 未完成
        BaseApiHandler.sendOk(exchange, "SearchDB is not yet implemented. LocalKnowledgeStore coming in future phase.",
                Map.of("query", req.query(), "topK", req.topK(), "results", List.of(), "note", "not_implemented"));
    }
}
