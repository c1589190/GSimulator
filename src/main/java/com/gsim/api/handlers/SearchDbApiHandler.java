package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.SessionManager;
import com.gsim.api.dto.SearchDbRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.knowledge.KnowledgeSearchResponse;
import com.gsim.knowledge.KnowledgeSearchResult;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.KnowledgeStore;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SearchDB API handler — 知识库搜索接口。
 *
 * <p>路由：
 * <ul>
 *   <li>GET  /api/searchdb?q=关键词[&topK=10][&mode=keyword|semantic]</li>
 *   <li>POST /api/searchdb</li>
 * </ul>
 *
 * <p>支持 keyword（FTS5）和 semantic（embedding）两种搜索模式。
 * keyword 搜索永远可用；semantic 需要 embedding profile 已配置。
 */
public class SearchDbApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final EventBus eventBus;
    private final SessionManager sessionManager;

    public SearchDbApiHandler(ApplicationContext ctx, EventBus eventBus, SessionManager sessionManager) {
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
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
        String mode = "keyword";
        int topK = 10;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if ("q".equals(kv[0]) && kv.length > 1) {
                    q = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                } else if ("topK".equals(kv[0]) && kv.length > 1) {
                    try { topK = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
                } else if ("mode".equals(kv[0]) && kv.length > 1) {
                    mode = kv[1];
                }
            }
        }

        if (q == null || q.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing query parameter: q");
            return;
        }

        eventBus.publish(GSimEvent.of("api", "search_progress",
                Map.of("message", "Searching: " + q + " (mode=" + mode + ")")));

        Map<String, Object> data = doSearch(q, topK, mode, exchange);
        BaseApiHandler.sendOk(exchange, "Search completed", data);
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        SearchDbRequest req = JsonBodyParser.parse(body, SearchDbRequest.class);

        if (req.query() == null || req.query().isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: query");
            return;
        }

        String mode = req.mode() != null ? req.mode() : "keyword";
        int topK = req.topK() > 0 ? req.topK() : 10;

        eventBus.publish(GSimEvent.of("api", "search_progress",
                Map.of("message", "Searching: " + req.query() + " (mode=" + mode + ")")));

        Map<String, Object> data = doSearch(req.query(), topK, mode, exchange);
        BaseApiHandler.sendOk(exchange, "Search completed", data);
    }

    private Map<String, Object> doSearch(String query, int topK, String mode, HttpExchange exchange) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("topK", topK);
        data.put("mode", mode);

        // 1. 先尝试知识库 keyword search（FTS5，永远可用）
        SQLiteKnowledgeStore store = ctx.getKnowledgeStore();
        List<Map<String, Object>> results = new ArrayList<>();
        String modeUsed = mode;

        if (store != null) {
            if ("semantic".equals(mode)) {
                // 尝试语义搜索
                KnowledgeSearchService searchService = ctx.getKnowledgeSearchService();
                if (searchService != null) {
                    try {
                        KnowledgeSearchResponse response = searchService.semanticSearch(query, null, topK);
                        if (response.items() != null) {
                            results = response.items().stream()
                                    .map(this::toResultMap)
                                    .collect(Collectors.toList());
                        }
                        if (response.errorCode() != null) {
                            modeUsed = "keyword (semantic unavailable: " + response.errorCode() + ", fallback)";
                        }
                    } catch (Exception e) {
                        modeUsed = "keyword (semantic error: " + e.getMessage() + ", fallback)";
                    }
                } else {
                    modeUsed = "keyword (semantic unavailable, no search service, fallback)";
                }
                if (results.isEmpty()) {
                    List<KnowledgeSearchResult> kwResults = store.searchKeyword(query, null, topK);
                    results = kwResults.stream()
                            .map(this::toResultMap)
                            .collect(Collectors.toList());
                }
            } else {
                // keyword search
                List<KnowledgeSearchResult> kwResults = store.searchKeyword(query, null, topK);
                results = kwResults.stream()
                        .map(this::toResultMap)
                        .collect(Collectors.toList());
            }
        }

        // 2. 如果知识库无结果，尝试本地文件搜索（wiki txt）
        if (results.isEmpty()) {
            String sid = BaseApiHandler.resolveSessionId(exchange);
            InteractionSession session = sessionManager.getOrCreateSession(sid);
            InteractionResult result = ctx.getInteractionManager().handle("/tool wiki_search " + query, session);
            if (result.success()) {
                List<Map<String, Object>> fileResults = new ArrayList<>();
                Map<String, Object> fileResult = new LinkedHashMap<>();
                fileResult.put("source", "local_files");
                fileResult.put("snippet", result.displayText());
                fileResults.add(fileResult);
                data.put("localFileResults", fileResults);
            }
        }

        data.put("modeUsed", modeUsed);
        data.put("results", results);
        data.put("resultCount", results.size());

        return data;
    }

    private Map<String, Object> toResultMap(KnowledgeSearchResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkId", r.chunkId());
        m.put("docId", r.docId());
        m.put("title", r.title());
        m.put("collection", r.collection());
        m.put("snippet", r.snippet());
        m.put("vectorScore", r.vectorScore());
        m.put("keywordScore", r.keywordScore());
        m.put("finalScore", r.finalScore());
        m.put("searchMode", r.searchMode());
        m.put("source", "knowledge_store");
        if (r.sourceUri() != null) {
            m.put("sourceUri", r.sourceUri());
        }
        return m;
    }
}
