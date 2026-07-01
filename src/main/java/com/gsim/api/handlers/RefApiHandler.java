package com.gsim.api.handlers;

import com.gsim.ref.RefResolver;
import com.gsim.ref.RefResolver.ResolvedRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 统一 @ 引用解析 API — 按 {@code @source:path} 格式读取任意来源的文档/元素。
 */
public class RefApiHandler implements HttpHandler {

    private final Path worldsDir;
    private final Supplier<String> activeWorldId;
    private final Path importDir;
    private final Supplier<com.gsim.doc.DocStore> docStore;

    public RefApiHandler(Path worldsDir, Supplier<String> activeWorldId,
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

        String ref = parseQueryParam(exchange, "ref");
        if (ref == null || ref.isBlank()) {
            BaseApiHandler.sendError(exchange, 400,
                    "Query parameter 'ref' is required. Format: @import:|@world:|@doc:");
            return;
        }

        try {
            ResolvedRef resolved = RefResolver.resolve(ref,
                    worldsDir, activeWorldId.get(), importDir, docStore.get());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", resolved.source());
            data.put("id", resolved.id());
            data.put("title", resolved.title());
            data.put("content", resolved.content());
            BaseApiHandler.sendOk(exchange, "Resolved: " + ref, data);
        } catch (IllegalArgumentException e) {
            BaseApiHandler.sendError(exchange, 404, e.getMessage());
        } catch (IllegalStateException e) {
            BaseApiHandler.sendError(exchange, 400, e.getMessage());
        }
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
