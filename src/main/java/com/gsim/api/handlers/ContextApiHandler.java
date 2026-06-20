package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.SessionManager;
import com.gsim.api.JsonBodyParser;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.SessionMessage;
import com.gsim.data.DataManager;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.*;

/**
 * /api/context — 上下文会话管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET  /api/context/session    — 当前 ContextSession 状态</li>
 *   <li>POST /api/context/reset      — 重置 ContextSession</li>
 *   <li>POST /api/context/close      — 关闭 ContextSession</li>
 *   <li>GET  /api/context/base       — BaseContextSnapshot markdown（不含 session messages）</li>
 *   <li>GET  /api/context/rendered   — baseContext + session messages（LLM 输入）</li>
 *   <li>GET  /api/context/messages   — 当前 ContextSession 的消息列表</li>
 *   <li>GET  /api/context/debug-full — 完整 debug 上下文</li>
 * </ul>
 */
public class ContextApiHandler implements HttpHandler {

    private final ContextSessionManager ctxSessionManager;
    private final BranchContextRenderer renderer;
    private final DataManager dataManager;
    private final SessionManager sessionManager;

    public ContextApiHandler(ContextSessionManager ctxSessionManager,
                              BranchContextRenderer renderer,
                              DataManager dataManager,
                              SessionManager sessionManager) {
        this.ctxSessionManager = ctxSessionManager;
        this.renderer = renderer;
        this.dataManager = dataManager;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] segments = BaseApiHandler.pathSegments(exchange, "/api/context");

        try {
            if (segments.length == 0) {
                handleGetSession(exchange);
            } else {
                switch (segments[0]) {
                    case "session" -> handleGetSession(exchange);
                    case "reset" -> handleReset(exchange, method);
                    case "close" -> handleClose(exchange, method);
                    case "base" -> handleGetBase(exchange);
                    case "rendered" -> handleGetRendered(exchange);
                    case "messages" -> handleGetMessages(exchange);
                    case "debug-full" -> handleDebugFull(exchange);
                    default -> BaseApiHandler.sendNotFound(exchange, "Unknown context sub-resource: " + segments[0]);
                }
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleGetSession(HttpExchange exchange) throws IOException {
        if (ctxSessionManager == null) {
            BaseApiHandler.sendOk(exchange, "Context session manager not available",
                    Map.of("active", false, "available", false));
            return;
        }
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        Map<String, Object> data = new LinkedHashMap<>();
        if (active.isPresent()) {
            data.put("session", active.get());
            data.put("active", true);
        } else {
            data.put("active", false);
            data.put("message", "No active ContextSession");
        }
        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Context session", data));
    }

    private void handleReset(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        String reason = "api reset";
        try {
            String body = BaseApiHandler.readBody(exchange);
            Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
            if (reqMap != null && reqMap.containsKey("reason")) {
                reason = String.valueOf(reqMap.get("reason"));
            }
        } catch (Exception ignored) {}

        ContextSession newSession = ctxSessionManager.resetSession("default", reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contextSessionId", newSession.sessionId());
        data.put("baseContextId", newSession.baseContextId());

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Context reset", data));
    }

    private void handleClose(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        String reason = "api close";
        try {
            String body = BaseApiHandler.readBody(exchange);
            Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
            if (reqMap != null && reqMap.containsKey("reason")) {
                reason = String.valueOf(reqMap.get("reason"));
            }
        } catch (Exception ignored) {}

        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        if (active.isEmpty()) {
            BaseApiHandler.sendJson(exchange, 404, ApiResponse.fail("No active ContextSession"));
            return;
        }

        ctxSessionManager.closeSession(active.get().sessionId(), reason);
        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Context closed", Map.of()));
    }

    /**
     * GET /api/context/base — 只返回 BaseContextSnapshot markdown，不含 session messages。
     */
    private void handleGetBase(HttpExchange exchange) throws IOException {
        if (ctxSessionManager == null || renderer == null || dataManager == null) {
            BaseApiHandler.sendOk(exchange, "Context services not available",
                    Map.of("markdown", "", "approxChars", 0, "available", false));
            return;
        }
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        String markdown;

        if (active.isPresent()) {
            markdown = ctxSessionManager.getBaseContextMarkdown("default");
            if (markdown == null) {
                // 回退：重新渲染（session 存在但 .md 文件丢失）
                var contextDir = dataManager.getDataRoot().resolve("worlds")
                        .resolve(dataManager.getActiveWorld()).resolve("context");
                markdown = renderer.renderBaseContext(contextDir).markdown();
            }
        } else {
            // 无活跃 session：渲染但不创建 session
            var contextDir = dataManager.getDataRoot().resolve("worlds")
                    .resolve(dataManager.getActiveWorld()).resolve("context");
            markdown = renderer.renderBaseContext(contextDir).markdown();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markdown", markdown);
        data.put("approxChars", markdown.length());

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Base context", data));
    }

    /**
     * GET /api/context/rendered — baseContext + session messages（LLM 输入）。
     */
    private void handleGetRendered(HttpExchange exchange) throws IOException {
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");

        String markdown;
        if (active.isPresent()) {
            markdown = ctxSessionManager.renderForLlm("default", "");
        } else {
            // 自动创建 session 并渲染
            ctxSessionManager.getOrCreateActiveSession("default", dataManager.getActiveBranch());
            markdown = ctxSessionManager.renderForLlm("default", "");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markdown", markdown);
        data.put("approxChars", markdown.length());

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Rendered context for LLM", data));
    }

    private void handleGetMessages(HttpExchange exchange) throws IOException {
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        if (active.isEmpty()) {
            BaseApiHandler.sendJson(exchange, 404, ApiResponse.fail("No active ContextSession"));
            return;
        }

        var messages = ctxSessionManager.getSessionMessages(active.get().sessionId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messages", messages);
        data.put("count", messages.size());

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Session messages", data));
    }

    private void handleDebugFull(HttpExchange exchange) throws IOException {
        String markdown = renderer.renderFullDebugContextAsMarkdown();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markdown", markdown);
        data.put("approxChars", markdown.length());

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Debug full context", data));
    }
}
