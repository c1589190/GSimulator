package com.gsim.api.handlers;

import com.gsim.doc.Document;
import com.gsim.importing.ImportDocumentService;
import com.gsim.importing.ImportDocumentService.ImportDocumentSearchMatch;
import com.gsim.worldinfo.ElementRef;
import com.gsim.worldinfo.KeywordIndex.SearchHit;
import com.gsim.worldinfo.KeywordIndex.SearchResult;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.WorldInfoBuilder;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 统一跨源搜索 API — 在 World、Import、Doc 三种来源中同时搜索。
 */
public class UnifiedSearchHandler implements HttpHandler {

    private final Path worldsDir;
    private final Supplier<String> activeWorldId;
    private final Path importDir;
    private final Supplier<com.gsim.doc.DocStore> docStore;

    public UnifiedSearchHandler(Path worldsDir, Supplier<String> activeWorldId,
                                Path importDir, Supplier<com.gsim.doc.DocStore> docStore) {
        this.worldsDir = worldsDir;
        this.activeWorldId = activeWorldId;
        this.importDir = importDir;
        this.docStore = docStore;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        String query = parseQueryParam(exchange, "q");
        if (query == null || query.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Query parameter 'q' is required");
            return;
        }

        String scope = parseQueryParam(exchange, "scope");
        if (scope == null || scope.isBlank()) scope = "all";
        int limit = parseInt(parseQueryParam(exchange, "limit"), 10);

        try {
            List<Map<String, Object>> results = new ArrayList<>();

            if ("all".equals(scope) || "world".equals(scope)) {
                results.addAll(searchWorld(query, limit));
            }
            if ("all".equals(scope) || "import".equals(scope)) {
                results.addAll(searchImport(query, limit));
            }
            if ("all".equals(scope) || "doc".equals(scope)) {
                results.addAll(searchDoc(query, limit));
            }

            // Trim to limit
            if (results.size() > limit) {
                results = results.subList(0, limit);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            data.put("scope", scope);
            data.put("count", results.size());
            data.put("results", results);
            BaseApiHandler.sendOk(exchange, "Search results for: " + query, data);
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Search failed: " + e.getMessage());
        }
    }

    // ── World search ──

    private List<Map<String, Object>> searchWorld(String query, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            String worldId = activeWorldId.get();
            if (worldId == null || !Files.exists(WorldIndexManager.worldFile(worldsDir, worldId))) {
                return results;
            }
            ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
            if (active == null) return results;

            WorldInformation wi = WorldInfoBuilder.build(worldsDir, worldId, active.nodeId());
            if (wi == null) return results;

            SearchResult sr = wi.keywordIndex().search(query, limit, 0);
            for (SearchHit hit : sr.items()) {
                ElementRef ref = hit.elementRef();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", "world");
                item.put("ref", ref.nodeId() + ":" + ref.checkpointId() + ":" + ref.element().key());
                item.put("title", ref.element().key() + " @" + ref.nodeId());
                item.put("snippet", hit.snippet());
                item.put("score", hit.score());
                results.add(item);
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    // ── Import search ──

    private List<Map<String, Object>> searchImport(String query, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            ImportDocumentService service = new ImportDocumentService(importDir);
            List<ImportDocumentSearchMatch> matches = service.searchDocuments(
                    query, null, null, limit, 300, false);
            for (ImportDocumentSearchMatch match : matches) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", "import");
                item.put("ref", "@import:" + match.documentId());
                item.put("title", match.displayName());
                item.put("snippet", match.preview());
                item.put("score", 0);
                results.add(item);
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    // ── Doc search ──

    private List<Map<String, Object>> searchDoc(String query, int limit) {
        List<Map<String, Object>> results = new ArrayList<>();
        var ds = docStore.get();
        if (ds == null) return results;
        try {
            for (Document doc : ds.list(null, null)) {
                if (results.size() >= limit) break;
                String content = doc.content();
                if (content == null) continue;
                String searchContent = content.toLowerCase();
                String searchQuery = query.toLowerCase();
                int idx = searchContent.indexOf(searchQuery);
                if (idx >= 0) {
                    int start = Math.max(0, idx - 30);
                    int end = Math.min(content.length(), idx + query.length() + 50);
                    String snippet = (start > 0 ? "..." : "") + content.substring(start, end)
                            + (end < content.length() ? "..." : "");

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source", "doc");
                    item.put("ref", "@doc:" + doc.id());
                    item.put("title", doc.title());
                    item.put("snippet", snippet);
                    item.put("score", 1);
                    results.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    // ── Helpers ──

    private static String parseQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
