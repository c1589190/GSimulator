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
import java.io.OutputStream;
import java.net.URLDecoder;
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
                sendError(exchange, 404, "Unknown knowledge endpoint");
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeHandler] Error handling " + method + " " + path + ": " + e.getMessage());
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleSearchGet(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String q = getQueryParam(query, "q");
        String mode = getQueryParam(query, "mode");
        String topKStr = getQueryParam(query, "topK");

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
            Map<String, String> form = parseFormEncoded(body);
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
            sendHtml(exchange, 200, html);
            return;
        }

        KnowledgeStore store = ctx.getKnowledgeStore();
        KnowledgeSearchService searchService = ctx.getKnowledgeSearchService();

        if (store == null) {
            // No active root — return empty results
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", q);
            vars.put("mode", mode);
            vars.put("count", 0);
            vars.put("results", List.of());
            String html = TemplateRenderer.render("fragments/search-results", vars);
            sendHtml(exchange, 200, html);
            return;
        }

        List<KnowledgeSearchResult> results;

        if ("semantic".equalsIgnoreCase(mode)) {
            if (searchService == null) {
                results = List.of();
            } else {
                KnowledgeSearchResponse resp = searchService.semanticSearch(q, "default", topK);
                if (resp.success()) {
                    results = resp.items();
                } else {
                    results = List.of();
                }
            }
        } else {
            // keyword mode (default)
            results = store.searchKeyword(q, "default", topK);
        }

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
        sendHtml(exchange, 200, html);
    }

    private void handleDetail(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String id = getQueryParam(query, "id");

        if (id == null || id.isBlank()) {
            sendError(exchange, 400, "id query param required");
            return;
        }

        KnowledgeStore store = ctx.getKnowledgeStore();
        if (store == null) {
            sendError(exchange, 500, "Knowledge store not available");
            return;
        }

        Optional<KnowledgeChunk> chunkOpt = store.getChunk(id);
        if (chunkOpt.isEmpty()) {
            sendError(exchange, 404, "Chunk not found: " + id);
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
        sendHtml(exchange, 200, html);
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
