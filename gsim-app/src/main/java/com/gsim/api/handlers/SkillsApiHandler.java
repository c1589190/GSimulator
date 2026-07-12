package com.gsim.api.handlers;

import com.gsim.doc.DocStore;
import com.gsim.doc.DocType;
import com.gsim.doc.Document;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Skill 管理 API — 直接调 DocStore（skills 已迁移为 DocType.SKILL 文档）。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/skills — 列出所有 Skill 文档</li>
 *   <li>GET /api/skills/search?q= — 关键词搜索 Skill</li>
 *   <li>GET /api/skills/{id} — 读取 Skill 详情</li>
 * </ul>
 */
public class SkillsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/skills";

    private final Supplier<DocStore> docStoreSupplier;

    public SkillsApiHandler(Supplier<DocStore> docStoreSupplier) {
        this.docStoreSupplier = docStoreSupplier;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        if (!"GET".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        try {
            if (segs.length == 0) {
                String q = parseQueryParam(exchange, "q");
                if (q != null && !q.isBlank()) {
                    handleSearch(exchange, q);
                } else {
                    handleList(exchange);
                }
            } else {
                handleRead(exchange, segs[0]);
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        List<Document> docs = store.list(DocType.SKILL, null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Document doc : docs) {
            items.add(skillToMap(doc));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("skills", items);
        BaseApiHandler.sendOk(exchange, "Skills listed", data);
    }

    private void handleSearch(HttpExchange exchange, String q) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        String query = q.toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Document doc : store.list(DocType.SKILL, null)) {
            if (results.size() >= 10) break;
            String content = doc.content();
            if (content == null) continue;
            int idx = content.toLowerCase().indexOf(query);
            if (idx >= 0) {
                Map<String, Object> item = skillToMap(doc);
                int start = Math.max(0, idx - 30);
                int end = Math.min(content.length(), idx + query.length() + 50);
                item.put("snippet", (start > 0 ? "..." : "") + content.substring(start, end)
                        + (end < content.length() ? "..." : ""));
                results.add(item);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", q);
        data.put("count", results.size());
        data.put("results", results);
        BaseApiHandler.sendOk(exchange, "Skills search", data);
    }

    private void handleRead(HttpExchange exchange, String skillId) throws IOException {
        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }
        Document doc = store.get(skillId);
        if (doc == null) {
            BaseApiHandler.sendError(exchange, 404, "Skill not found: " + skillId);
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

        Map<String, Object> data = skillToMap(doc);
        data.put("offset", start);
        data.put("limit", limit);
        data.put("totalLines", totalLines);
        data.put("content", sb.toString());
        BaseApiHandler.sendOk(exchange, "Skill: " + skillId, data);
    }

    private Map<String, Object> skillToMap(Document doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.id());
        m.put("title", doc.title());
        m.put("tags", doc.tags());
        m.put("version", doc.version());
        m.put("summary", doc.summary());
        return m;
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
