package com.gsim.api.handlers;

import com.gsim.doc.DocStore;
import com.gsim.doc.Document;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /api/help — Agent API 引导手册。
 *
 * <p>返回 agent-api-guide Doc 的完整内容，包含 @ 引用系统、编辑管道、省 Token 最佳实践。
 */
public class HelpHandler implements HttpHandler {

    private static final String GUIDE_DOC_ID = "agent-api-guide";

    private final Supplier<DocStore> docStoreSupplier;

    public HelpHandler(Supplier<DocStore> docStoreSupplier) {
        this.docStoreSupplier = docStoreSupplier;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        DocStore store = docStoreSupplier.get();
        if (store == null) {
            BaseApiHandler.sendError(exchange, 503, "DocStore not available");
            return;
        }

        Document doc = store.get(GUIDE_DOC_ID);
        if (doc == null) {
            BaseApiHandler.sendError(
                    exchange, 404, "Guide not found. Create it first: POST /api/docs with docId=" + GUIDE_DOC_ID);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docId", GUIDE_DOC_ID);
        data.put("ref", "@doc:" + GUIDE_DOC_ID);
        data.put("title", doc.title());
        data.put("version", doc.version());
        data.put("updatedAt", doc.updatedAt());
        data.put("content", doc.content());

        BaseApiHandler.sendOk(exchange, "Agent API Guide", data);
    }
}
