package com.gsim.api.handlers;

import com.gsim.agent.config.AgentConfigManager;
import com.gsim.api.ApiResponse;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 配置 CRUD HTTP API。
 *
 * <pre>
 * GET    /api/agent-configs                列出所有配置（摘要）
 * GET    /api/agent-configs/{configId}     获取配置详情
 * POST   /api/agent-configs                创建新配置
 * PATCH  /api/agent-configs/{configId}     更新配置字段
 * DELETE /api/agent-configs/{configId}     删除配置
 * </pre>
 */
public class AgentConfigsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/agent-configs";

    private final AgentConfigManager configManager;

    public AgentConfigsApiHandler(AgentConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            route(exchange, method, segs);
        } catch (IllegalArgumentException e) {
            BaseApiHandler.sendError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void route(HttpExchange exchange, String method, String[] segs) throws IOException {
        if (segs.length == 0) {
            switch (method) {
                case "GET" -> handleList(exchange);
                case "POST" -> handleCreate(exchange);
                default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET or POST.");
            }
            return;
        }

        if (segs.length == 1) {
            switch (method) {
                case "GET" -> handleGet(exchange, segs[0]);
                case "PATCH" -> handleUpdate(exchange, segs[0]);
                case "DELETE" -> handleDelete(exchange, segs[0]);
                default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed.");
            }
            return;
        }

        BaseApiHandler.sendNotFound(exchange, "Unknown route");
    }

    // ── GET /api/agent-configs ──

    private void handleList(HttpExchange exchange) throws IOException {
        var agents = configManager.listAgents();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configs", agents);
        data.put("count", agents.size());
        BaseApiHandler.sendOk(exchange, "Agent configs listed", data);
    }

    // ── GET /api/agent-configs/{configId} ──

    private void handleGet(HttpExchange exchange, String configId) throws IOException {
        var agent = configManager.getAgent(configId);
        if (agent == null) {
            BaseApiHandler.sendError(exchange, 404, "Agent config not found: " + configId);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("config", agent);
        BaseApiHandler.sendOk(exchange, "Agent config: " + configId, data);
    }

    // ── POST /api/agent-configs ──

    private void handleCreate(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        var result = configManager.createAgent(body);
        if (result.success()) {
            BaseApiHandler.sendJson(exchange, 201, ApiResponse.ok(
                    result.message(), Map.of("configId", result.message())));
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }

    // ── PATCH /api/agent-configs/{configId} ──

    private void handleUpdate(HttpExchange exchange, String configId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of()
                : JsonUtils.fromJson(body, Map.class);

        String field = str(req, "field");
        String value = str(req, "value");
        if (field == null || value == null) {
            BaseApiHandler.sendError(exchange, 400, "field and value are required");
            return;
        }

        var result = configManager.updateAgent(configId, field, value);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configId", configId);
        data.put("field", field);
        if (result.success()) {
            BaseApiHandler.sendOk(exchange, result.message(), data);
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }

    // ── DELETE /api/agent-configs/{configId} ──

    private void handleDelete(HttpExchange exchange, String configId) throws IOException {
        var result = configManager.deleteAgent(configId);
        if (result.success()) {
            BaseApiHandler.sendOk(exchange, result.message(),
                    Map.of("configId", configId, "action", "deleted"));
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : v != null ? v.toString() : "";
    }
}
