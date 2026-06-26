package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.dto.ImportUrlRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.interaction.InteractionSession;
import com.gsim.webimport.WebImportManager;
import com.gsim.webimport.WebImportRequest;
import com.gsim.webimport.WebImportResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Import API handler (URL import only).
 *
 * <p>路由：
 * <ul>
 *   <li>POST /api/import/url   — URL 网页导入</li>
 * </ul>
 */
public class ImportApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final EventBus eventBus;

    public ImportApiHandler(ApplicationContext ctx, EventBus eventBus) {
        this.ctx = ctx;
        this.eventBus = eventBus;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        if (!"POST".equals(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        try {
            if ("/api/import/url".equals(path)) {
                handleUrlImport(exchange);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown import endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Import failed: " + e.getMessage());
        }
    }

    private void handleUrlImport(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        ImportUrlRequest req = JsonBodyParser.parse(body, ImportUrlRequest.class);

        if (req.url() == null || req.url().isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: url");
            return;
        }

        eventBus.publish(GSimEvent.of("api", "import_progress",
                Map.of("message", "Starting URL import: " + req.url())));

        var config = ctx.getConfig();
        WebImportManager importManager = new WebImportManager(config.getImportDir());
        WebImportRequest webReq = WebImportRequest.builder(java.net.URI.create(req.url()))
                .fetchOnly(req.fetchOnly())
                .crawlEnabled(!req.fetchOnly())
                .maxPages(req.maxPages())
                .maxDepth(req.depth())
                .delayMillis(req.delayMs())
                .userAgent(config.getWebResearchUserAgent())
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

        eventBus.publish(GSimEvent.of("api", "import_progress",
                Map.of("message", "URL import completed: " + result.pagesFetched() + " pages fetched")));

        BaseApiHandler.sendOk(exchange, "URL import completed", data);
    }
}
