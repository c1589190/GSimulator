package com.gsim.api.handlers;

import com.gsim.doc.DocCacheManager;
import com.gsim.text.TextEditor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本缓存 HTTP API — 查看、读取、编辑文本缓存。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/caches — 列出所有缓存条目</li>
 *   <li>GET /api/caches/{cacheId} — 读取缓存内容（支持 offset/limit/title_only）</li>
 *   <li>POST /api/caches/{cacheId}/edit — 对缓存文本执行编辑操作，返回新 @cache:id</li>
 * </ul>
 */
public class CachesHandler implements HttpHandler {

    private static final String PREFIX = "/api/caches";

    private final DocCacheManager cacheManager;

    public CachesHandler(Path worldsDir) {
        this.cacheManager = new DocCacheManager(
                worldsDir.resolveSibling("docs").resolve(".cache"));
        try {
            this.cacheManager.init();
        } catch (IOException ignored) {
        }
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
            if (segs.length == 1 && "GET".equals(method)) {
                handleRead(exchange, segs[0]);
                return;
            }
            if (segs.length == 2 && "edit".equals(segs[1]) && "POST".equals(method)) {
                handleEdit(exchange, segs[0]);
                return;
            }
            BaseApiHandler.sendNotFound(exchange, "Unknown caches endpoint");
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── GET /api/caches ──

    private void handleList(HttpExchange exchange) throws IOException {
        List<DocCacheManager.CacheInfo> caches = cacheManager.list();
        List<Map<String, Object>> items = new ArrayList<>();
        for (var ci : caches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ci.id());
            item.put("size", ci.size());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("total", cacheManager.count());
        data.put("caches", items);
        BaseApiHandler.sendOk(exchange, "Caches listed", data);
    }

    // ── GET /api/caches/{cacheId} ──

    private void handleRead(HttpExchange exchange, String cacheId) throws IOException {
        String content = cacheManager.get(cacheId);
        if (content == null) {
            BaseApiHandler.sendError(exchange, 404, "Cache not found: " + cacheId);
            return;
        }

        String offsetStr = parseQueryParam(exchange, "offset");
        String limitStr = parseQueryParam(exchange, "limit");
        boolean titleOnly = "true".equalsIgnoreCase(parseQueryParam(exchange, "title_only"));

        int offset = offsetStr != null ? Math.max(0, Integer.parseInt(offsetStr)) : 0;
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 200;

        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;

        if (titleOnly) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cacheId", cacheId);
            data.put("totalLines", totalLines);
            data.put("size", content.length());
            data.put("preview", lines.length > 0 ? truncate(lines[0], 200) : "");
            BaseApiHandler.sendOk(exchange, "Cache info: " + cacheId, data);
            return;
        }

        int start = Math.min(offset, totalLines);
        int end = Math.min(start + limit, totalLines);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cacheId", cacheId);
        data.put("offset", start);
        data.put("limit", limit);
        data.put("totalLines", totalLines);
        data.put("returnedLines", end - start);
        data.put("hasMore", end < totalLines);
        data.put("nextOffset", end < totalLines ? end : null);
        data.put("ref", "@cache:" + cacheId);
        data.put("content", sb.toString());
        BaseApiHandler.sendOk(exchange, "Cache read: " + cacheId, data);
    }

    // ── POST /api/caches/{cacheId}/edit ──

    private void handleEdit(HttpExchange exchange, String cacheId) throws IOException {
        String content = cacheManager.get(cacheId);
        if (content == null) {
            BaseApiHandler.sendError(exchange, 404, "Cache not found: " + cacheId);
            return;
        }

        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of()
                : com.gsim.util.JsonUtils.fromJson(body, Map.class);

        List<TextEditor.Op> ops = new ArrayList<>();

        String selectLines = str(req, "select_lines");
        if (!selectLines.isEmpty()) ops.add(new TextEditor.SelectLines(selectLines));

        String deleteLines = str(req, "delete_lines");
        if (!deleteLines.isEmpty()) ops.add(new TextEditor.DeleteLines(deleteLines));

        int insertAt = parseInt(req, "insert_at", -1);
        String insertText = str(req, "insert_text");
        if (insertAt >= 0 && !insertText.isEmpty()) {
            ops.add(new TextEditor.InsertLines(insertAt, insertText));
        }

        String replaceSpec = str(req, "replace_spec");
        String replaceText = str(req, "replace_text");
        if (!replaceSpec.isEmpty() && !replaceText.isEmpty()) {
            ops.add(new TextEditor.ReplaceLines(replaceSpec, replaceText));
        }

        String replaceFrom = str(req, "replace_from");
        String replaceTo = str(req, "replace_to");
        if (!replaceFrom.isEmpty()) {
            ops.add(new TextEditor.ReplaceKeyword(replaceFrom, replaceTo));
        }

        String maskKw = str(req, "mask_kw");
        if (!maskKw.isEmpty()) ops.add(new TextEditor.MaskKeyword(maskKw));

        String maskLinesSpec = str(req, "mask_lines_spec");
        if (!maskLinesSpec.isEmpty()) ops.add(new TextEditor.MaskLines(maskLinesSpec));

        if (ops.isEmpty()) {
            BaseApiHandler.sendError(exchange, 400, "At least one edit operation is required");
            return;
        }

        TextEditor.EditResult result = TextEditor.edit(content, ops);

        String newCacheId;
        try {
            newCacheId = cacheManager.put("edit", result.text());
        } catch (IOException e) {
            BaseApiHandler.sendError(exchange, 500, "Failed to cache result: " + e.getMessage());
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCacheId", cacheId);
        data.put("newCacheId", newCacheId);
        data.put("ref", "@cache:" + newCacheId);
        data.put("originalLines", result.originalLines());
        data.put("resultLines", result.resultLines());
        data.put("appliedOps", result.appliedOps());
        data.put("summary", result.summary());
        BaseApiHandler.sendOk(exchange, "Edit complete: " + result.summary(), data);
    }

    // ── Helpers ──

    @SuppressWarnings("unchecked")
    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : "";
    }

    private static int parseInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return def;
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

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
