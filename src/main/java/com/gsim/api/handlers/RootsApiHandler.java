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
 * /api/roots — 根节点工作区管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/roots         — 列出所有根节点</li>
 *   <li>GET    /api/roots/status  — 根节点状态</li>
 *   <li>POST   /api/roots         — 创建根节点</li>
 *   <li>POST   /api/roots/switch  — 切换根节点</li>
 *   <li>DELETE /api/roots/{id}    — 删除根节点</li>
 * </ul>
 */
public class RootsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/roots";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public RootsApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            InteractionSession session = sessionManager.getOrCreateSession(BaseApiHandler.resolveSessionId(exchange));

            if (segs.length == 0) {
                if ("GET".equals(method)) {
                    InteractionResult result = ctx.getInteractionManager().handle("/root list", session);
                    BaseApiHandler.sendOk(exchange, result.message(),
                            Map.of("displayText", result.displayText(), "success", result.success()));
                } else if ("POST".equals(method)) {
                    String body = BaseApiHandler.readBody(exchange);
                    Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
                    Object rawRootId = reqMap != null ? reqMap.get("rootId") : null;
                    Object rawDescription = reqMap != null ? reqMap.get("description") : null;
                    String rootId = rawRootId != null ? rawRootId.toString() : "";
                    String description = rawDescription != null ? rawDescription.toString() : "";

                    if (rootId.isBlank()) {
                        BaseApiHandler.sendError(exchange, 400, "Missing required field: rootId");
                        return;
                    }

                    InteractionResult result = ctx.getInteractionManager().handle(
                            "/root create " + rootId + " " + description, session);
                    BaseApiHandler.sendOk(exchange, result.success() ? "Root created" : "Root creation failed",
                            Map.of("rootId", rootId, "success", result.success(), "message", result.displayText()));
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 1) {
                if ("status".equals(segs[0])) {
                    InteractionResult result = ctx.getInteractionManager().handle("/root status", session);
                    BaseApiHandler.sendOk(exchange, result.message(),
                            Map.of("displayText", result.displayText(), "success", result.success()));
                } else if ("switch".equals(segs[0])) {
                    if (!"POST".equals(method)) {
                        BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
                        return;
                    }
                    String body = BaseApiHandler.readBody(exchange);
                    Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
                    Object rawRootId2 = reqMap != null ? reqMap.get("rootId") : null;
                    String rootId2 = rawRootId2 != null ? rawRootId2.toString() : "";

                    if (rootId2.isBlank()) {
                        BaseApiHandler.sendError(exchange, 400, "Missing required field: rootId");
                        return;
                    }

                    InteractionResult result = ctx.getInteractionManager().handle("/root switch " + rootId2, session);
                    BaseApiHandler.sendOk(exchange, result.success() ? "Root switched" : "Root switch failed",
                            Map.of("rootId", rootId2, "success", result.success(), "message", result.displayText()));
                } else {
                    // DELETE /api/roots/{id}
                    if (!"DELETE".equals(method)) {
                        BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                        return;
                    }
                    InteractionResult result = ctx.getInteractionManager().handle("/root delete " + segs[0], session);
                    BaseApiHandler.sendOk(exchange, result.success() ? "Root deleted" : "Root delete failed",
                            Map.of("rootId", segs[0], "success", result.success(), "message", result.displayText()));
                }
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown roots endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
