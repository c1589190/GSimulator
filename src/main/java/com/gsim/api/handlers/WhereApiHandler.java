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
 * /api/where — 当前位置信息 API。
 *
 * <p>端点：GET /api/where
 */
public class WhereApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public WhereApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        try {
            InteractionSession session = sessionManager.getOrCreateSession("default");
            InteractionResult result = ctx.getInteractionManager().handle("/where", session);

            BaseApiHandler.sendOk(exchange, result.message(),
                    Map.of("displayText", result.displayText(), "success", result.success()));
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
