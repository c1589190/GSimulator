package com.gsim.webui.handlers;

import com.gsim.llm.LlmConfigManager;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/llm REST API handler — LLM provider 配置管理。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/llm/providers — 列出所有 provider</li>
 *   <li>GET /api/llm/providers/{id} — 获取单个 provider 详情</li>
 *   <li>POST /api/llm/providers/{id} — 更新 provider 字段</li>
 *   <li>POST /api/llm/test — 测试 provider 连通性</li>
 * </ul>
 */
public class LlmApiHandler implements HttpHandler {

    private final LlmConfigManager configManager;
    private final LlmProviderRegistry registry;

    public LlmApiHandler(LlmConfigManager configManager, LlmProviderRegistry registry) {
        this.configManager = configManager;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String prefix = "/api/llm";

        try {
            if (path.equals(prefix + "/providers") && "GET".equalsIgnoreCase(method)) {
                handleListProviders(exchange);
            } else if (path.startsWith(prefix + "/providers/") && "GET".equalsIgnoreCase(method)) {
                String id = path.substring((prefix + "/providers/").length());
                handleGetProvider(exchange, id);
            } else if (path.startsWith(prefix + "/providers/") && "POST".equalsIgnoreCase(method)) {
                String id = path.substring((prefix + "/providers/").length());
                handleUpdateProvider(exchange, id);
            } else if (path.equals(prefix + "/test") && "POST".equalsIgnoreCase(method)) {
                handleTestProvider(exchange);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (Exception e) {
            HandlerUtils.logError("LlmApi", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleListProviders(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> providers = configManager.listProviders();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("providers", providers);
        resp.put("count", providers.size());
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleGetProvider(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> provider = configManager.getProvider(id);
        if (provider == null) {
            HandlerUtils.sendJson(exchange, 404, Map.of("error", "Provider not found: " + id));
            return;
        }
        HandlerUtils.sendJson(exchange, 200, provider);
    }

    private void handleUpdateProvider(HttpExchange exchange, String id) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JsonUtils.fromJson(rawBody, Map.class);
        if (body == null || !body.containsKey("field") || !body.containsKey("value")) {
            HandlerUtils.sendJson(exchange, 400,
                    Map.of("error", "field and value are required"));
            return;
        }
        String field = body.get("field").toString();
        String value = body.get("value").toString();

        var result = configManager.updateProvider(id, field, value);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.success());
        resp.put("message", result.message());
        HandlerUtils.sendJson(exchange, result.success() ? 200 : 400, resp);
    }

    private void handleTestProvider(HttpExchange exchange) throws IOException {
        String rawBody = new String(exchange.getRequestBody().readAllBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JsonUtils.fromJson(rawBody, Map.class);
        String id = body != null ? (String) body.getOrDefault("id", "base") : "base";

        String result = configManager.testProvider(id, registry);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", result);
        resp.put("providerId", id);
        HandlerUtils.sendJson(exchange, 200, resp);
    }
}
