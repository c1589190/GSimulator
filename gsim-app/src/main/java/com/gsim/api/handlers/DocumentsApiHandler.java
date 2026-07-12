package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.OperationLog;
import com.gsim.api.dto.ImportUrlRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.importing.ImportDocumentService;
import com.gsim.importing.ImportDocumentService.ImportDocumentException;
import com.gsim.importing.ImportDocumentService.ImportDocumentInfo;
import com.gsim.importing.ImportDocumentService.ImportDocumentReadResult;
import com.gsim.importing.ImportDocumentService.ImportDocumentSearchMatch;
import com.gsim.util.JsonUtils;
import com.gsim.webimport.WebImportManager;
import com.gsim.webimport.WebImportRequest;
import com.gsim.webimport.WebImportResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档管理 API — 导入文档 CRUD + 搜索 + Web 导入。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/documents — 列出所有文档</li>
 *   <li>GET /api/documents/search — 全文搜索</li>
 *   <li>GET /api/documents/{documentId} — 读取文档</li>
 *   <li>POST /api/documents — 上传文档</li>
 *   <li>DELETE /api/documents/{documentId} — 删除文档</li>
 *   <li>POST /api/documents/import-url — 触发 Web 导入</li>
 * </ul>
 */
public class DocumentsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/documents";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "markdown");

    private final Path importDir;
    private final EventBus eventBus;
    private final ApplicationContext ctx;

    public DocumentsApiHandler(Path importDir, EventBus eventBus, ApplicationContext ctx) {
        this.importDir = importDir;
        this.eventBus = eventBus;
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            // GET / or GET /?source=...
            if (segs.length == 0 && "GET".equals(method)) {
                handleListDocuments(exchange);
                return;
            }
            // POST / (upload)
            if (segs.length == 0 && "POST".equals(method)) {
                handleUploadDocument(exchange);
                return;
            }
            // GET /search
            if (segs.length == 1 && "search".equals(segs[0]) && "GET".equals(method)) {
                handleSearchDocuments(exchange);
                return;
            }
            // POST /import-url
            if (segs.length == 1 && "import-url".equals(segs[0]) && "POST".equals(method)) {
                handleImportUrl(exchange);
                return;
            }
            // GET /{documentId}
            if (segs.length == 1 && "GET".equals(method)) {
                handleReadDocument(exchange, segs[0]);
                return;
            }
            // DELETE /{documentId}
            if (segs.length == 1 && "DELETE".equals(method)) {
                handleDeleteDocument(exchange, segs[0]);
                return;
            }

            BaseApiHandler.sendNotFound(exchange, "Unknown documents endpoint");
        } catch (ImportDocumentException e) {
            BaseApiHandler.sendError(exchange, 400, e.errorCode() + ": " + e.getMessage());
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ── Document operations ──

    private void handleListDocuments(HttpExchange exchange) throws IOException {
        ImportDocumentService service = new ImportDocumentService(importDir);
        String sourceFilter = parseQueryParam(exchange, "source");

        List<ImportDocumentInfo> docs = service.listDocuments();
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (ImportDocumentInfo doc : docs) {
            if (sourceFilter != null && !sourceFilter.isBlank()
                    && !sourceFilter.equals(doc.source())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("documentId", doc.documentId());
            item.put("source", doc.source());
            item.put("displayName", doc.displayName());
            item.put("relativePath", doc.relativePath());
            item.put("sizeBytes", doc.sizeBytes());
            item.put("charCount", doc.charCount());
            item.put("lastModified", doc.lastModified());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", items.size());
        data.put("documents", items);
        BaseApiHandler.sendOk(exchange, "Documents listed", data);
    }

    private void handleReadDocument(HttpExchange exchange, String documentId) throws IOException {
        String offsetStr = parseQueryParam(exchange, "offset");
        String limitStr = parseQueryParam(exchange, "limit");
        String fullStr = parseQueryParam(exchange, "full");

        int offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 8000;
        boolean full = "true".equalsIgnoreCase(fullStr);

        ImportDocumentService service = new ImportDocumentService(importDir);
        ImportDocumentReadResult result = service.readDocument(documentId, offset, limit, full);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", result.documentId());
        data.put("source", result.source());
        data.put("displayName", result.displayName());
        data.put("originalLength", result.originalLength());
        data.put("offset", result.offset());
        data.put("limit", result.limit());
        data.put("returnedRange", result.returnedRange());
        data.put("truncated", result.truncated());
        data.put("nextOffset", result.nextOffset());
        data.put("content", result.content());
        BaseApiHandler.sendOk(exchange, "Document read: " + documentId, data);
    }

    private void handleSearchDocuments(HttpExchange exchange) throws IOException {
        String query = parseQueryParam(exchange, "query");
        if (query == null || query.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Query parameter 'query' is required");
            return;
        }
        String documentId = parseQueryParam(exchange, "documentId");
        String source = parseQueryParam(exchange, "source");
        String maxResultsStr = parseQueryParam(exchange, "maxResults");
        String contextCharsStr = parseQueryParam(exchange, "contextChars");
        String caseSensitiveStr = parseQueryParam(exchange, "caseSensitive");

        int maxResults = maxResultsStr != null ? Integer.parseInt(maxResultsStr) : 10;
        int contextChars = contextCharsStr != null ? Integer.parseInt(contextCharsStr) : 300;
        boolean caseSensitive = "true".equalsIgnoreCase(caseSensitiveStr);

        ImportDocumentService service = new ImportDocumentService(importDir);
        List<ImportDocumentSearchMatch> matches = service.searchDocuments(
                query, documentId, source, maxResults, contextChars, caseSensitive);

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (ImportDocumentSearchMatch match : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("documentId", match.documentId());
            item.put("source", match.source());
            item.put("displayName", match.displayName());
            item.put("offset", match.offset());
            item.put("preview", match.preview());
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("count", items.size());
        data.put("results", items);
        BaseApiHandler.sendOk(exchange, "Search results for: " + query, data);
    }

    private void handleUploadDocument(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);
        String name = req != null ? (String) req.get("name") : null;
        String content = req != null ? (String) req.get("content") : null;

        if (name == null || name.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "name is required");
            return;
        }
        if (content == null || content.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "content is required");
            return;
        }

        // Validate extension
        String lowerName = name.toLowerCase();
        boolean validExt = false;
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lowerName.endsWith("." + ext)) {
                validExt = true;
                break;
            }
        }
        if (!validExt) {
            BaseApiHandler.sendError(exchange, 400,
                    "Unsupported file type. Allowed extensions: txt, md, markdown");
            return;
        }

        // Security: prevent path traversal in filename
        String safeName = Path.of(name).getFileName().toString();
        Path targetFile = importDir.resolve(safeName).normalize();
        if (!targetFile.startsWith(importDir.toAbsolutePath().normalize())) {
            BaseApiHandler.sendError(exchange, 403, "Path traversal rejected");
            return;
        }

        Files.createDirectories(importDir);
        Files.writeString(targetFile, content, StandardCharsets.UTF_8);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", safeName);
        data.put("name", safeName);
        data.put("sizeBytes", Files.size(targetFile));
        OperationLog.get().record(null, "document.upload", "POST",
                "/api/documents", "uploaded: " + safeName,
                Map.of("name", safeName, "sizeBytes", Files.size(targetFile)), true);
        BaseApiHandler.sendOk(exchange, "Document uploaded: " + safeName, data);
    }

    private void handleDeleteDocument(HttpExchange exchange, String documentId) throws IOException {
        Path file = importDir.resolve(documentId).normalize();
        if (!file.startsWith(importDir.toAbsolutePath().normalize())) {
            BaseApiHandler.sendError(exchange, 403, "Path traversal rejected: " + documentId);
            return;
        }
        if (!Files.exists(file)) {
            BaseApiHandler.sendError(exchange, 404, "Document not found: " + documentId);
            return;
        }

        Files.delete(file);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", documentId);
        OperationLog.get().record(null, "document.delete", "DELETE",
                "/api/documents/" + documentId, "deleted: " + documentId, null, true);
        BaseApiHandler.sendOk(exchange, "Document deleted: " + documentId, data);
    }

    private void handleImportUrl(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        ImportUrlRequest req = JsonBodyParser.parse(body, ImportUrlRequest.class);

        if (req.url() == null || req.url().isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: url");
            return;
        }

        if (eventBus != null) {
            eventBus.publish(GSimEvent.of("api", "import_progress",
                    Map.of("message", "Starting URL import: " + req.url())));
        }

        WebImportManager importManager = new WebImportManager(importDir);
        WebImportRequest webReq = WebImportRequest.builder(java.net.URI.create(req.url()))
                .fetchOnly(req.fetchOnly())
                .crawlEnabled(!req.fetchOnly())
                .maxPages(req.maxPages())
                .maxDepth(req.depth())
                .delayMillis(req.delayMs())
                .userAgent(ctx.getConfig().getWebResearchUserAgent())
                .build();
        WebImportResult result = importManager.execute(webReq);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", result.url());
        data.put("host", result.host());
        data.put("pagesFetched", result.pagesFetched());
        data.put("pagesSkipped", result.pagesSkipped());
        data.put("pagesFailed", result.pagesFailed());
        data.put("filesWritten", result.filesWritten());
        if (result.writtenFiles() != null) {
            data.put("writtenFiles", result.writtenFiles().stream().map(Object::toString).toList());
        }
        if (result.errors() != null) {
            data.put("errors", result.errors());
        }

        if (eventBus != null) {
            eventBus.publish(GSimEvent.of("api", "import_progress",
                    Map.of("message", "URL import completed: " + result.pagesFetched() + " pages fetched")));
        }

        OperationLog.get().record(null, "document.import_url", "POST",
                "/api/documents/import-url", "imported from: " + req.url(),
                Map.of("url", req.url(), "pagesFetched", result.pagesFetched(),
                        "filesWritten", result.filesWritten()), true);
        BaseApiHandler.sendOk(exchange, "URL import completed", data);
    }

    // ── Helpers ──

    private static String parseQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }
}
