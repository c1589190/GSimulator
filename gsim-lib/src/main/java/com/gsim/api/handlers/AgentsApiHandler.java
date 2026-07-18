package com.gsim.api.handlers;

import com.gsim.agent.AgentInstance;
import com.gsim.agent.AgentStatus;
import com.gsim.agent.management.AgentSseManager;
import com.gsim.agent.management.AgentsManager;
import com.gsim.api.ApiResponse;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 生命周期 HTTP API — 仿 WorldApiV2Handler 的路径段路由模式。
 *
 * <pre>
 * POST /api/agents/run                     启动 Agent（异步）
 * GET  /api/agents                         列出 Agent
 * GET  /api/agents/{instanceId}            查询 Agent 状态
 * GET  /api/agents/{instanceId}/events     SSE 事件流
 * GET  /api/agents/{instanceId}/output     获取输出
 * POST /api/agents/{instanceId}/cancel     取消 Agent
 * </pre>
 */
public class AgentsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/agents";

    private final AgentsManager agentsManager;
    private final AgentSseManager sseManager;

    public AgentsApiHandler(AgentsManager agentsManager, AgentSseManager sseManager) {
        this.agentsManager = agentsManager;
        this.sseManager = sseManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
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
        int n = segs.length;

        // POST /api/agents/run
        if (n == 1 && "run".equals(segs[0]) && "POST".equals(method)) {
            handleRun(exchange);
            return;
        }

        // GET /api/agents
        if (n == 0 && "GET".equals(method)) {
            handleList(exchange);
            return;
        }

        // /api/agents/{instanceId}/...
        if (n >= 1) {
            String instanceId = segs[0];

            if (n == 1 && "GET".equals(method)) {
                handleGet(exchange, instanceId);
                return;
            }
            if (n == 2 && "events".equals(segs[1]) && "GET".equals(method)) {
                handleEvents(exchange, instanceId);
                return;
            }
            if (n == 2 && "output".equals(segs[1]) && "GET".equals(method)) {
                handleOutput(exchange, instanceId);
                return;
            }
            if (n == 2 && "cancel".equals(segs[1]) && "POST".equals(method)) {
                handleCancel(exchange, instanceId);
                return;
            }
        }

        BaseApiHandler.sendNotFound(exchange, "Unknown route");
    }

    // ── POST /api/agents/run ──

    private void handleRun(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        @SuppressWarnings("unchecked")
        Map<String, Object> req = body.isBlank() ? Map.of() : JsonUtils.fromJson(body, Map.class);

        String configId = str(req, "configId");
        if (configId == null || configId.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "configId is required");
            return;
        }
        String cacheId = str(req, "cacheId");
        String prompt = str(req, "prompt");
        if (prompt == null || prompt.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "prompt is required");
            return;
        }
        String parentInstanceId = str(req, "parentInstanceId");

        AgentInstance instance = agentsManager.runAgent(configId, cacheId, prompt, parentInstanceId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", instance.instanceId());
        data.put("configId", instance.configId());
        data.put("sessionId", instance.sessionId());
        data.put("taskId", instance.taskId());
        data.put("cacheId", instance.cacheId());
        data.put("parentInstanceId", instance.parentInstanceId());
        data.put("status", instance.status().name());
        data.put("eventsUrl", "/api/agents/" + instance.instanceId() + "/events");
        data.put("outputUrl", "/api/agents/" + instance.instanceId() + "/output");

        BaseApiHandler.sendJson(exchange, 201, ApiResponse.ok("Agent started: " + instance.instanceId(), data));
    }

    // ── GET /api/agents ──

    private void handleList(HttpExchange exchange) throws IOException {
        String statusStr = parseQueryParam(exchange, "status");
        String configId = parseQueryParam(exchange, "configId");
        String parentId = parseQueryParam(exchange, "parentInstanceId");

        AgentStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = AgentStatus.valueOf(statusStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<AgentInstance> agents = agentsManager.listAgents(configId, status, parentId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agents", agents.stream().map(AgentsApiHandler::toMap).toList());
        data.put("count", agents.size());

        BaseApiHandler.sendOk(exchange, "Agents listed", data);
    }

    // ── GET /api/agents/{instanceId} ──

    private void handleGet(HttpExchange exchange, String instanceId) throws IOException {
        AgentInstance agent = agentsManager.getAgent(instanceId);
        if (agent == null) {
            BaseApiHandler.sendError(exchange, 404, "Agent not found: " + instanceId);
            return;
        }

        BaseApiHandler.sendOk(exchange, "Agent: " + instanceId, toMap(agent));
    }

    // ── GET /api/agents/{instanceId}/events ──

    private void handleEvents(HttpExchange exchange, String instanceId) throws IOException {
        AgentInstance agent = agentsManager.getAgent(instanceId);
        if (agent == null) {
            BaseApiHandler.sendError(exchange, 404, "Agent not found: " + instanceId);
            return;
        }

        sseManager.streamEvents(exchange, instanceId, agent.sessionId(), agent.taskId(), agentsManager);
    }

    // ── GET /api/agents/{instanceId}/output ──

    private void handleOutput(HttpExchange exchange, String instanceId) throws IOException {
        AgentInstance agent = agentsManager.getAgent(instanceId);
        if (agent == null) {
            BaseApiHandler.sendError(exchange, 404, "Agent not found: " + instanceId);
            return;
        }

        String output = agentsManager.getAgentOutput(instanceId);
        if (output == null) {
            if (agent.status() == AgentStatus.RUNNING || agent.status() == AgentStatus.PENDING) {
                BaseApiHandler.sendError(
                        exchange, 202, "Agent still running. Check events at /api/agents/" + instanceId + "/events");
                return;
            }
            BaseApiHandler.sendError(exchange, 404, "No output available for: " + instanceId);
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instanceId", instanceId);
        data.put("status", agent.status().name());
        data.put("output", output);

        BaseApiHandler.sendOk(exchange, "Agent output: " + instanceId, data);
    }

    // ── POST /api/agents/{instanceId}/cancel ──

    private void handleCancel(HttpExchange exchange, String instanceId) throws IOException {
        AgentInstance agent = agentsManager.getAgent(instanceId);
        if (agent == null) {
            BaseApiHandler.sendError(exchange, 404, "Agent not found: " + instanceId);
            return;
        }

        boolean cancelled = agentsManager.cancelAgent(instanceId);
        if (cancelled) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("instanceId", instanceId);
            data.put("status", "CANCELLED");
            BaseApiHandler.sendOk(exchange, "Agent cancelled: " + instanceId, data);
        } else {
            BaseApiHandler.sendError(exchange, 400, "Cannot cancel agent in status: " + agent.status());
        }
    }

    // ── helpers ──

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static String parseQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static Map<String, Object> toMap(AgentInstance a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", a.instanceId());
        m.put("configId", a.configId());
        m.put("sessionId", a.sessionId());
        m.put("taskId", a.taskId());
        m.put("cacheId", a.cacheId());
        m.put("parentInstanceId", a.parentInstanceId());
        m.put("prompt", a.prompt());
        m.put("status", a.status().name());
        m.put("createdAt", a.createdAt() != null ? a.createdAt().toString() : null);
        m.put("finishedAt", a.finishedAt() != null ? a.finishedAt().toString() : null);
        m.put("error", a.error());
        return m;
    }
}
