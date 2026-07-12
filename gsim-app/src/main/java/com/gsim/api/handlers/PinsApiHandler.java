package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.SessionManager;
import com.gsim.app.ApplicationContext;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * /api/pins — 硬约束管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET  /api/pins — 列出所有硬约束</li>
 *   <li>POST /api/pins — 添加硬约束</li>
 * </ul>
 */
public class PinsApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public PinsApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));

            if ("GET".equals(method)) {
                InteractionResult result = ctx.getInteractionManager().handle("/pin list", session);
                BaseApiHandler.sendOk(exchange, result.message(),
                        Map.of("displayText", result.displayText(), "success", result.success()));
            } else if ("POST".equals(method)) {
                String body = BaseApiHandler.readBody(exchange);
                Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
                Object rawText = reqMap != null ? reqMap.get("text") : null;
                String text = rawText != null ? rawText.toString() : "";

                if (text.isBlank()) {
                    BaseApiHandler.sendError(exchange, 400, "Missing required field: text");
                    return;
                }

                InteractionResult result = ctx.getInteractionManager().handle("/pin add " + text, session);
                BaseApiHandler.sendOk(exchange, result.success() ? "Pin added" : "Pin add failed",
                        Map.of("success", result.success(), "message", result.displayText()));
            } else {
                BaseApiHandler.sendError(exchange, 405, "Method not allowed");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
