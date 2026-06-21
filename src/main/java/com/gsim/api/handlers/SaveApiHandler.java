package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.SessionManager;
import com.gsim.app.ApplicationContext;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Map;

/**
 * /api/save — 手动保存状态 API。
 *
 * <p>端点：POST /api/save
 */
public class SaveApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public SaveApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        try {
            InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));
            InteractionResult result = ctx.getInteractionManager().handle("/save", session);

            BaseApiHandler.sendOk(exchange, result.success() ? "State saved" : "Save failed",
                    Map.of("success", result.success(), "message", result.displayText()));
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
