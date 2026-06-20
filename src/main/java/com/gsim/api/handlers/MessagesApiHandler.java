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
 * /api/messages — 消息历史 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/messages           — 当前分支消息历史</li>
 *   <li>GET /api/messages/{branchId} — 指定分支消息历史</li>
 * </ul>
 */
public class MessagesApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/messages";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public MessagesApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
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
            String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

            InteractionResult result;
            if (segs.length == 0) {
                result = ctx.getInteractionManager().handle("/messages", session);
            } else {
                result = ctx.getInteractionManager().handle("/messages show " + segs[0], session);
            }

            BaseApiHandler.sendOk(exchange, result.message(),
                    Map.of("displayText", result.displayText(), "success", result.success()));
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
