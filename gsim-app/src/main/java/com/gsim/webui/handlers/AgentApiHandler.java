package com.gsim.webui.handlers;

import com.gsim.agent.config.AgentConfigManager;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/agents REST API handler — Agent 配置管理。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/agents — 列出所有 agent 配置</li>
 *   <li>GET /api/agents/{id} — 获取单个 agent 详情</li>
 *   <li>POST /api/agents/{id} — 更新 agent 字段</li>
 *   <li>POST /api/agents/reload — 重新加载所有 agent 配置</li>
 * </ul>
 */
public class AgentApiHandler implements HttpHandler {

    private final AgentConfigManager configManager;

    public AgentApiHandler(AgentConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String prefix = "/api/agents";

        try {
            if (path.equals(prefix) && "GET".equalsIgnoreCase(method)) {
                handleListAgents(exchange);
            } else if (path.equals(prefix + "/reload") && "POST".equalsIgnoreCase(method)) {
                handleReload(exchange);
            } else if (path.startsWith(prefix + "/") && "GET".equalsIgnoreCase(method)) {
                String id = path.substring((prefix + "/").length());
                handleGetAgent(exchange, id);
            } else if (path.startsWith(prefix + "/") && "POST".equalsIgnoreCase(method)) {
                String id = path.substring((prefix + "/").length());
                handleUpdateAgent(exchange, id);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        } catch (Exception e) {
            HandlerUtils.logError("AgentApi", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleListAgents(HttpExchange exchange) throws IOException {
        List<Map<String, Object>> agents = configManager.listAgents();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("agents", agents);
        resp.put("count", agents.size());
        HandlerUtils.sendJson(exchange, 200, resp);
    }

    private void handleGetAgent(HttpExchange exchange, String id) throws IOException {
        Map<String, Object> agent = configManager.getAgent(id);
        if (agent == null) {
            HandlerUtils.sendJson(exchange, 404, Map.of("error", "Agent not found: " + id));
            return;
        }
        HandlerUtils.sendJson(exchange, 200, agent);
    }

    private void handleUpdateAgent(HttpExchange exchange, String id) throws IOException {
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

        var result = configManager.updateAgent(id, field, value);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.success());
        resp.put("message", result.message());
        HandlerUtils.sendJson(exchange, result.success() ? 200 : 400, resp);
    }

    private void handleReload(HttpExchange exchange) throws IOException {
        String msg = configManager.reload();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", msg);
        HandlerUtils.sendJson(exchange, 200, resp);
    }
}
