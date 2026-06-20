package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.dto.ImportUrlRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.importdata.ImportManager;
import com.gsim.importdata.ImportResult;
import com.gsim.webimport.WebImportManager;
import com.gsim.webimport.WebImportRequest;
import com.gsim.webimport.WebImportResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Import API handler。
 *
 * <p>路由：
 * <ul>
 *   <li>POST /api/import/local — 本地文件导入</li>
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
            if ("/api/import/local".equals(path)) {
                handleLocalImport(exchange);
            } else if ("/api/import/url".equals(path)) {
                handleUrlImport(exchange);
            } else if ("/api/import/wiki-allpages".equals(path)) {
                handleWikiAllPages(exchange);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown import endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Import failed: " + e.getMessage());
        }
    }

    private void handleLocalImport(HttpExchange exchange) throws IOException {
        eventBus.publish(GSimEvent.of("api", "import_progress",
                Map.of("message", "Starting local import...")));

        var config = ctx.getConfig();
        // NOTE: Import pipeline not yet connected to SQLite KnowledgeStore.
        // Passing null chromaClient — ImportManager.doImport() returns IMPORT_PIPELINE_NOT_IMPLEMENTED.
        ImportManager importManager = new ImportManager(config, null);
        ImportResult result = importManager.doImport();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalFiles", result.totalFiles());
        data.put("successCount", result.successCount());
        data.put("failCount", result.failCount());
        data.put("successFiles", result.successFiles());
        data.put("failFiles", result.failFiles());
        data.put("logPath", result.logPath());
        data.put("summary", result.summary());

        eventBus.publish(GSimEvent.of("api", "import_progress",
                Map.of("message", "Import completed: " + result.summary())));

        BaseApiHandler.sendOk(exchange, "Import completed", data);
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

    private void handleWikiAllPages(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        ImportUrlRequest req = JsonBodyParser.parse(body, ImportUrlRequest.class);

        if (req.url() == null || req.url().isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: url (MediaWiki API base URL)");
            return;
        }

        eventBus.publish(GSimEvent.of("api", "import_progress",
                Map.of("message", "Starting wiki all-pages import: " + req.url())));

        // 通过 InteractionManager 调用 /import --wiki-allpages
        var session = new com.gsim.interaction.InteractionSession(
                ctx.getInteractionContext(), ctx.getConfig(),
                ctx.getCampaignService(), ctx.getTurnService(), ctx.getPlayerActionService(),
                ctx.getToolRegistry(), ctx.getLlmManager());
        String cmd = "/import " + req.url() + " --wiki-allpages";
        if (req.maxPages() > 0) {
            cmd += " --max-pages " + req.maxPages();
        }
        var result = ctx.getInteractionManager().handle(cmd, session);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", req.url());
        data.put("success", result.success());
        data.put("message", result.displayText());

        eventBus.publish(GSimEvent.of("api", "import_progress",
                Map.of("message", "Wiki all-pages import completed")));

        BaseApiHandler.sendOk(exchange, "Wiki all-pages import completed", data);
    }
}
