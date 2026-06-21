package com.gsim.webui.handlers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gsim.app.ApplicationContext;
import com.gsim.knowledge.KnowledgeChunk;
import com.gsim.knowledge.KnowledgeSearchResult;
import com.gsim.knowledge.KnowledgeSearchResponse;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.KnowledgeStore;
import com.gsim.util.JsonUtils;
import com.gsim.webui.TemplateRenderer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Knowledge base search API handler.
 *
 * <p>GET  /knowledge             — delegate to PageHandler for HTML fragment
 * <p>GET  /knowledge/search      — execute keyword/semantic search (query params)
 * <p>POST /knowledge/search      — execute search from body (JSON or url-encoded)
 * <p>GET  /knowledge/detail      — get chunk detail HTML fragment
 */
public class KnowledgeHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final PageHandler pageHandler;

    public KnowledgeHandler(ApplicationContext ctx, PageHandler pageHandler) {
        this.ctx = ctx;
        this.pageHandler = pageHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            // GET /knowledge (exact) — delegate to PageHandler for HTML fragment
            if (path.equals("/knowledge") && "GET".equals(method)) {
                pageHandler.handle(exchange);
                return;
            }

            if (path.equals("/knowledge/search") && "GET".equals(method)) {
                handleSearchGet(exchange);
            } else if (path.equals("/knowledge/search") && "POST".equals(method)) {
                handleSearchPost(exchange);
            } else if (path.equals("/knowledge/detail") && "GET".equals(method)) {
                handleDetail(exchange);
            } else {
                HandlerUtils.sendError(exchange, 404, "Unknown knowledge endpoint");
            }
        } catch (Exception e) {
            HandlerUtils.logError("KnowledgeHandler", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleSearchGet(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String q = HandlerUtils.getQueryParam(query, "q");
        String mode = HandlerUtils.getQueryParam(query, "mode");
        String topKStr = HandlerUtils.getQueryParam(query, "topK");

        if (mode == null || mode.isBlank()) mode = "keyword";
        int topK = parseTopK(topKStr);

        executeSearch(exchange, q, mode, topK);
    }

    private void handleSearchPost(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        String q;
        String mode;
        int topK;

        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            Map<String, String> form = HandlerUtils.parseFormEncoded(body);
            q = form.getOrDefault("q", "");
            mode = form.getOrDefault("mode", "keyword");
            topK = parseTopK(form.getOrDefault("topK", "10"));
        } else {
            Map<String, Object> req = JsonUtils.fromJson(body,
                    new TypeReference<Map<String, Object>>() {});
            q = (String) req.getOrDefault("q", "");
            mode = (String) req.getOrDefault("mode", "keyword");
            Object topKObj = req.getOrDefault("topK", 10);
            if (topKObj instanceof Number n) {
                topK = n.intValue();
                if (topK < 1) topK = 1;
                if (topK > 100) topK = 100;
            } else {
                topK = 10;
            }
        }

        executeSearch(exchange, q, mode, topK);
    }

    private void executeSearch(HttpExchange exchange, String q, String mode, int topK) throws IOException {
        if (q == null || q.isBlank()) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", "");
            vars.put("mode", mode);
            vars.put("count", 0);
            vars.put("results", List.of());
            String html = TemplateRenderer.render("fragments/search-results", vars);
            HandlerUtils.sendHtml(exchange, 200, html);
            return;
        }

        KnowledgeStore store = ctx.getKnowledgeStore();
        KnowledgeSearchService searchService = ctx.getKnowledgeSearchService();

        if ("semantic".equalsIgnoreCase(mode)) {
            if (searchService == null) {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("query", q);
                vars.put("mode", mode);
                vars.put("count", 0);
                vars.put("results", List.of());
                vars.put("errorMessage", "Semantic search not available: no embedding profile configured.");
                String html = TemplateRenderer.render("fragments/search-results", vars);
                HandlerUtils.sendHtml(exchange, 200, html);
                return;
            }
            // store==null is fine — semantic search uses its own store
            KnowledgeSearchResponse resp = searchService.semanticSearch(q, "default", topK);
            if (resp.success() && resp.items() != null) {
                sendSearchResults(exchange, q, mode, resp.items());
            } else {
                String errorMsg = resp.error() != null ? resp.error() : "Semantic search failed";
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("query", q);
                vars.put("mode", mode);
                vars.put("count", 0);
                vars.put("results", List.of());
                vars.put("errorMessage", errorMsg);
                String html = TemplateRenderer.render("fragments/search-results", vars);
                HandlerUtils.sendHtml(exchange, 200, html);
            }
            return;
        }

        // keyword mode (default)
        if (store == null) {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", q);
            vars.put("mode", mode);
            vars.put("count", 0);
            vars.put("results", List.of());
            String html = TemplateRenderer.render("fragments/search-results", vars);
            HandlerUtils.sendHtml(exchange, 200, html);
            return;
        }

        List<KnowledgeSearchResult> results = store.searchKeyword(q, "default", topK);
        sendSearchResults(exchange, q, mode, results);
    }

    private void sendSearchResults(HttpExchange exchange, String q, String mode, List<KnowledgeSearchResult> results) throws IOException {
        if (results == null) results = List.of();

        List<Map<String, Object>> resultMaps = new ArrayList<>(results.size());
        for (KnowledgeSearchResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", r.chunkId());
            m.put("docId", r.docId());
            m.put("title", r.title() != null ? r.title() : "");
            m.put("snippet", r.snippet() != null ? r.snippet() : "");
            m.put("score", String.format("%.3f", r.finalScore()));
            m.put("sourceUri", r.sourceUri() != null ? r.sourceUri() : "");
            m.put("collection", r.collection() != null ? r.collection() : "");
            resultMaps.add(m);
        }

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("query", q);
        vars.put("mode", mode);
        vars.put("count", resultMaps.size());
        vars.put("results", resultMaps);

        String html = TemplateRenderer.render("fragments/search-results", vars);
        HandlerUtils.sendHtml(exchange, 200, html);
    }

    private void handleDetail(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String id = HandlerUtils.getQueryParam(query, "id");

        if (id == null || id.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "id query param required");
            return;
        }

        KnowledgeStore store = ctx.getKnowledgeStore();
        if (store == null) {
            HandlerUtils.sendError(exchange, 500, "Knowledge store not available");
            return;
        }

        Optional<KnowledgeChunk> chunkOpt = store.getChunk(id);
        if (chunkOpt.isEmpty()) {
            HandlerUtils.sendError(exchange, 404, "Chunk not found: " + id);
            return;
        }

        KnowledgeChunk chunk = chunkOpt.get();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("chunkId", chunk.chunkId());
        vars.put("title", chunk.title() != null ? chunk.title() : "");
        vars.put("text", chunk.text() != null ? chunk.text() : "");
        vars.put("docId", chunk.docId() != null ? chunk.docId() : "");
        vars.put("collection", chunk.collection() != null ? chunk.collection() : "");

        String html = TemplateRenderer.render("fragments/search-detail", vars);
        HandlerUtils.sendHtml(exchange, 200, html);
    }

    // ---- helpers ----

    private static int parseTopK(String topKStr) {
        if (topKStr == null || topKStr.isBlank()) return 10;
        try {
            int topK = Integer.parseInt(topKStr);
            if (topK < 1) return 1;
            if (topK > 100) return 100;
            return topK;
        } catch (NumberFormatException e) {
            return 10;
        }
    }

}
