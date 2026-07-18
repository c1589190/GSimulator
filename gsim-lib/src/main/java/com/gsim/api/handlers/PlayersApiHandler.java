package com.gsim.api.handlers;

import com.gsim.api.SessionManager;
import com.gsim.app.ApplicationContext;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * /api/players — 玩家档案管理 API。
 * @deprecated 从未注册到 ApiRouter，功能由 World element (players checkpoint) 替代。
 */
@Deprecated
public class PlayersApiHandler implements HttpHandler {
    private static final String PREFIX = "/api/players";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public PlayersApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));

            if (segs.length == 0) {
                if (!"GET".equals(method)) {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
                    return;
                }
                InteractionResult result = ctx.getInteractionManager().handle("/players list", session);
                BaseApiHandler.sendOk(
                        exchange,
                        result.message(),
                        Map.of("displayText", result.displayText(), "success", result.success()));
            } else {
                String playerName = segs[0];
                if ("GET".equals(method)) {
                    InteractionResult result =
                            ctx.getInteractionManager().handle("/players show " + playerName, session);
                    BaseApiHandler.sendOk(
                            exchange,
                            result.success() ? "Player found" : "Player not found",
                            Map.of(
                                    "name",
                                    playerName,
                                    "displayText",
                                    result.displayText(),
                                    "success",
                                    result.success()));
                } else if ("POST".equals(method)) {
                    String body = BaseApiHandler.readBody(exchange);
                    Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
                    Object rawField = reqMap != null ? reqMap.get("field") : null;
                    Object rawContent = reqMap != null ? reqMap.get("content") : null;
                    String field = rawField != null ? rawField.toString() : "";
                    String content = rawContent != null ? rawContent.toString() : "";

                    if (field.isBlank()) {
                        BaseApiHandler.sendError(exchange, 400, "Missing required field: field");
                        return;
                    }

                    InteractionResult result = ctx.getInteractionManager()
                            .handle("/players set " + playerName + " " + field + " " + content, session);
                    BaseApiHandler.sendOk(
                            exchange,
                            result.success() ? "Player updated" : "Update failed",
                            Map.of(
                                    "name",
                                    playerName,
                                    "field",
                                    field,
                                    "success",
                                    result.success(),
                                    "message",
                                    result.displayText()));
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
