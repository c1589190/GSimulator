package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.app.ApplicationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/status — 返回当前应用状态。
 */
public class StatusApiHandler implements HttpHandler {

    private static final String API_VERSION = "0.1";

    private final ApplicationContext ctx;

    public StatusApiHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, ApiResponse.fail("Method not allowed"));
            return;
        }

        try {
            var config = ctx.getConfig();

            Map<String, Object> status = new LinkedHashMap<>();
            status.put("llmEnabled", config.isLlmConfigured());
            status.put("chromaEnabled", config.isChromaEnabled());
            status.put("apiVersion", API_VERSION);

            sendResponse(exchange, 200, ApiResponse.ok("Status retrieved", status));
        } catch (Exception e) {
            sendResponse(exchange, 500, ApiResponse.fail("Failed to get status: " + e.getMessage()));
        }
    }

    static void sendResponse(HttpExchange exchange, int statusCode, ApiResponse response) throws IOException {
        byte[] body = response.toJson().getBytes(StandardCharsets.UTF_8);
        BaseApiHandler.addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }
}
