package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.app.ApplicationContext;
import com.gsim.knowledge.KnowledgeStoreStatus;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /api/knowledge — 知识库管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/knowledge/status — 知识库状态</li>
 * </ul>
 */
public class KnowledgeApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/knowledge";

    private final ApplicationContext ctx;

    public KnowledgeApiHandler(ApplicationContext ctx) {
        this.ctx = ctx;
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
            if (segs.length == 0 || "status".equals(segs[0])) {
                handleStatus(exchange);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown knowledge sub-resource: " + segs[0]);
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        SQLiteKnowledgeStore store = ctx.getKnowledgeStore(ctx.getActiveRootId());
        if (store == null) {
            BaseApiHandler.sendOk(exchange, "Knowledge store not available",
                    Map.of("available", false));
            return;
        }

        KnowledgeStoreStatus s = store.status();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", true);
        data.put("dbPath", s.dbPath());
        data.put("version", s.version());
        data.put("documentCount", s.documentCount());
        data.put("chunkCount", s.chunkCount());
        data.put("embeddingProfilesCount", s.embeddingProfilesCount());
        data.put("chunkEmbeddingsCount", s.chunkEmbeddingsCount());
        data.put("ftsAvailable", s.ftsAvailable());
        data.put("activeEmbeddingProfileId", s.activeEmbeddingProfileId());
        data.put("defaultCollection", s.defaultCollection());

        BaseApiHandler.sendOk(exchange, "Knowledge store status", data);
    }
}
