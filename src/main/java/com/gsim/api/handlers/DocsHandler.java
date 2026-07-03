package com.gsim.api.handlers;

import com.gsim.doc.DocCacheManager;
import com.gsim.doc.DocStore;
import com.gsim.doc.DocType;
import com.gsim.doc.Document;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 文档管理 HTTP API — DocStore CRUD + 关键词搜索。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/docs?type=&tag= — 列出文档</li>
 *   <li>GET /api/docs/search?q= — 关键词搜索</li>
 *   <li>GET /api/docs/{docId}?offset=&limit= — 读取文档</li>
 *   <li>POST /api/docs — 创建文档</li>
 *   <li>PATCH /api/docs/{docId} — 更新文档（content/title/tags）</li>
 *   <li>DELETE /api/docs/{docId} — 删除文档</li>
 * </ul>
 */
public class DocsHandler implements HttpHandler {

    private static final String PREFIX = "/api/docs";

    private final Supplier<DocStore> docStoreSupplier;
    private final DocCacheManager cacheManager;

    public DocsHandler(Supplier<DocStore> docStoreSupplier, Path worldsDir) {
        this.docStoreSupplier = docStoreSupplier;
        this.cacheManager = new DocCacheManager(
                worldsDir.resolveSibling("docs").resolve(".cache"));
        try { this.cacheManager.init(); } catch (IOException ignored) {}
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0 && "GET".equals(method)) {
                handleList(exchange);
                return;
            }
            if (segs.length == 0 && "POST".equals(method)) {
                handleCreate(exchange);
                return;
            }
            if (segs.length == 1 && "search".equals(segs[0]) && "GET".equals(method)) {
                handleSearch(exchange);
                return;
            }
            if (segs.length == 1 && "GET".equals(method)) {
                handleRead(exchange, segs[0]);
                return;
            }
            if (segs.length == 1 && "PATCH".equals(method)) {
                handleUpdate(exchange, segs[0]);
                return;
            }
            if (segs.length == 1 && "DELETE".equals(method)) {
                handleDelete(exchange, segs[0]);
                return;
            }
            BaseApiHandler.sendNotFound(exchange, "Unknown docs endpoint");
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── GET /api/docs ──

    private void handleList(HttpExchange exchange) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        String typeFilter = parseQueryParam(exchange, "type");
        String tagFilter = parseQueryParam(exchange, "tag");

        DocType type = null;
        if (typeFilter != null && !typeFilter.isBlank()) type = DocType.fromKey(typeFilter);

        List<Document> docs = store.list(type, tagFilter);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document doc : docs) {
            items.add(docToMap(doc));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("total", store.count());
        data.put("documents", items);
        BaseApiHandler.sendOk(exchange, "Documents listed", data);
    }

    // ── GET /api/docs/search?q= ──

    private void handleSearch(HttpExchange exchange) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        String q = parseQueryParam(exchange, "q");
        if (q == null || q.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Query parameter 'q' is required");
            return;
        }
        int limit = parseInt(parseQueryParam(exchange, "limit"), 10);

        String query = q.toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Document doc : store.list(null, null)) {
            if (results.size() >= limit) break;
            String content = doc.content();
            if (content == null) continue;
            int idx = content.toLowerCase().indexOf(query);
            if (idx >= 0) {
                int start = Math.max(0, idx - 30);
                int end = Math.min(content.length(), idx + query.length() + 50);
                String snippet = (start > 0 ? "..." : "") + content.substring(start, end)
                        + (end < content.length() ? "..." : "");

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("docId", doc.id());
                item.put("type", doc.type().key());
                item.put("title", doc.title());
                item.put("snippet", snippet);
                results.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", q);
        data.put("count", results.size());
        data.put("results", results);
        BaseApiHandler.sendOk(exchange, "Search results", data);
    }

    // ── GET /api/docs/{docId} ──

    private void handleRead(HttpExchange exchange, String docId) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        Document doc = store.get(docId);
        if (doc == null) {
            BaseApiHandler.sendError(exchange, 404, "Document not found: " + docId);
            return;
        }

        int offset = parseInt(parseQueryParam(exchange, "offset"), 0);
        int limit = parseInt(parseQueryParam(exchange, "limit"), 200);

        String[] lines = doc.content() != null ? doc.content().split("\n", -1) : new String[0];
        int totalLines = lines.length;
        int start = Math.min(offset, totalLines);
        int end = Math.min(start + limit, totalLines);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) sb.append(lines[i]).append("\n");

        Map<String, Object> data = docToMap(doc);
        data.put("offset", start);
        data.put("limit", limit);
        data.put("totalLines", totalLines);
        data.put("returnedLines", end - start);
        data.put("hasMore", end < totalLines);
        data.put("nextOffset", end < totalLines ? end : null);
        data.put("content", sb.toString());
        BaseApiHandler.sendOk(exchange, "Document: " + docId, data);
    }

    // ── POST /api/docs ──

    private void handleCreate(HttpExchange exchange) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }

        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of()
                : com.gsim.util.JsonUtils.fromJson(body, Map.class);

        String docId = str(req, "docId");
        String typeStr = str(req, "type");
        String title = str(req, "title");
        String content = str(req, "content");
        String tagsStr = str(req, "tags");

        if (docId.isEmpty()) {
            BaseApiHandler.sendError(exchange, 400, "docId is required");
            return;
        }
        if (!docId.matches("^[a-zA-Z0-9_-]+$")) {
            BaseApiHandler.sendError(exchange, 400, "docId must contain only alphanumeric, dash, or underscore");
            return;
        }
        if (title.isEmpty()) title = docId;

        // 解析 @cache: 引用
        if (!content.isEmpty()) content = cacheManager.resolve(content);

        DocType type = typeStr.isEmpty() ? DocType.OTHER : DocType.fromKey(typeStr);
        List<String> tags = List.of();
        if (!tagsStr.isEmpty()) tags = List.of(tagsStr.split("\\s*,\\s*"));

        try {
            Document doc = store.create(docId, type, title, content, tags);
            if (doc == null) {
                BaseApiHandler.sendError(exchange, 409, "Document already exists: " + docId);
                return;
            }
            BaseApiHandler.sendOk(exchange, "Document created: " + docId, docToMap(doc));
        } catch (IOException e) {
            BaseApiHandler.sendError(exchange, 500, "Failed to create document: " + e.getMessage());
        }
    }

    // ── PATCH /api/docs/{docId} ──

    private void handleUpdate(HttpExchange exchange, String docId) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        if (!store.exists(docId)) {
            BaseApiHandler.sendError(exchange, 404, "Document not found: " + docId);
            return;
        }

        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of()
                : com.gsim.util.JsonUtils.fromJson(body, Map.class);

        String content = str(req, "content");
        String title = str(req, "title");
        String tagsStr = str(req, "tags");

        // 解析 @cache: 引用
        if (!content.isEmpty()) content = cacheManager.resolve(content);

        try {
            Document updated = null;

            if (!content.isEmpty()) {
                updated = store.updateContent(docId, content);
            }

            if (!title.isEmpty() || !tagsStr.isEmpty()) {
                List<String> tags = tagsStr.isEmpty() ? null
                        : List.of(tagsStr.split("\\s*,\\s*"));
                Document current = store.get(docId);
                updated = store.updateMeta(docId,
                        title.isEmpty() && current != null ? current.title() : title,
                        tags != null ? tags : (current != null ? current.tags() : List.of()));
            }

            if (updated == null) {
                BaseApiHandler.sendError(exchange, 400, "No fields to update (provide content, title, or tags)");
                return;
            }

            BaseApiHandler.sendOk(exchange, "Document updated: " + docId, docToMap(updated));
        } catch (IOException e) {
            BaseApiHandler.sendError(exchange, 500, "Failed to update document: " + e.getMessage());
        }
    }

    // ── DELETE /api/docs/{docId} ──

    private void handleDelete(HttpExchange exchange, String docId) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        try {
            boolean deleted = store.delete(docId);
            if (!deleted) {
                BaseApiHandler.sendError(exchange, 404, "Document not found: " + docId);
                return;
            }
            BaseApiHandler.sendOk(exchange, "Document deleted: " + docId,
                    Map.of("docId", docId, "deleted", true));
        } catch (IOException e) {
            BaseApiHandler.sendError(exchange, 500, "Failed to delete document: " + e.getMessage());
        }
    }

    // ── Helpers ──

    private Map<String, Object> docToMap(Document doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", doc.id());
        m.put("type", doc.type().key());
        m.put("title", doc.title());
        m.put("tags", doc.tags());
        m.put("version", doc.version());
        m.put("updatedAt", doc.updatedAt());
        m.put("summary", doc.summary());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : "";
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

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
}
