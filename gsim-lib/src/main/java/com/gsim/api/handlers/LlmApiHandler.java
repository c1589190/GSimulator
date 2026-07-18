package com.gsim.api.handlers;

import com.gsim.llm.LlmConfigManager;
import com.gsim.llm.LlmProviderRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM Provider 管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/llm — 列出所有 provider</li>
 *   <li>GET /api/llm/{id} — 查看 provider 详情</li>
 *   <li>POST /api/llm — 添加 provider（body: {id, name?, baseUrl, model, apiKey?}）</li>
 *   <li>PATCH /api/llm/{id} — 修改 provider 字段（body: {field, value}）</li>
 *   <li>DELETE /api/llm/{id} — 删除 provider</li>
 *   <li>POST /api/llm/{id}/test — 测试连通性</li>
 * </ul>
 */
public class LlmApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/llm";

    private final LlmConfigManager configManager;
    private final LlmProviderRegistry registry;

    public LlmApiHandler(LlmConfigManager configManager, LlmProviderRegistry registry) {
        this.configManager = configManager;
        this.registry = registry;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            // GET /api/llm
            if (segs.length == 0 && "GET".equals(method)) {
                handleList(exchange);
                return;
            }
            // POST /api/llm
            if (segs.length == 0 && "POST".equals(method)) {
                handleAdd(exchange);
                return;
            }
            // GET /api/llm/{id}
            if (segs.length == 1 && "GET".equals(method)) {
                handleShow(exchange, segs[0]);
                return;
            }
            // PATCH /api/llm/{id}
            if (segs.length == 1 && "PATCH".equals(method)) {
                handleUpdate(exchange, segs[0]);
                return;
            }
            // DELETE /api/llm/{id}
            if (segs.length == 1 && "DELETE".equals(method)) {
                handleRemove(exchange, segs[0]);
                return;
            }
            // POST /api/llm/{id}/test
            if (segs.length == 2 && "test".equals(segs[1]) && "POST".equals(method)) {
                handleTest(exchange, segs[0]);
                return;
            }

            BaseApiHandler.sendNotFound(exchange, "Unknown LLM endpoint");
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, e.getMessage());
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> providers = configManager.listProviders();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", providers.size());
        data.put("providers", providers);
        BaseApiHandler.sendOk(exchange, "LLM providers listed", data);
    }

    private void handleShow(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> p = configManager.getProvider(id);
        if (p == null) {
            BaseApiHandler.sendError(exchange, 404, "Provider not found: " + id);
            return;
        }
        BaseApiHandler.sendOk(exchange, "Provider: " + id, Map.of("provider", p));
    }

    private void handleAdd(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : com.gsim.util.JsonUtils.fromJson(body, Map.class);
        String id = str(req, "id");
        String name = str(req, "name");
        String baseUrl = str(req, "baseUrl");
        String model = str(req, "model");
        String apiKey = str(req, "apiKey");

        var result = configManager.addProvider(id, name, baseUrl, apiKey != null ? apiKey : "", model);
        if (result.success()) {
            BaseApiHandler.sendOk(exchange, result.message());
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }

    private void handleUpdate(HttpExchange exchange, String id) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : com.gsim.util.JsonUtils.fromJson(body, Map.class);
        String field = str(req, "field");
        String value = str(req, "value");
        if (field == null || value == null) {
            BaseApiHandler.sendError(exchange, 400, "field and value are required");
            return;
        }
        var result = configManager.updateProvider(id, field, value);
        if (result.success()) {
            BaseApiHandler.sendOk(exchange, result.message());
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }

    private void handleRemove(HttpExchange exchange, String id) throws IOException {
        var result = configManager.removeProvider(id);
        if (result.success()) {
            BaseApiHandler.sendOk(exchange, result.message());
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }

    private void handleTest(HttpExchange exchange, String id) throws IOException {
        String result = configManager.testProvider(id, registry);
        boolean ok = result.startsWith("Connected OK");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerId", id);
        data.put("connected", ok);
        data.put("detail", result);
        BaseApiHandler.sendOk(exchange, result, data);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }
}
