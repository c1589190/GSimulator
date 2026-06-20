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
 * /api/skills — 技能管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET /api/skills            — 列出所有技能</li>
 *   <li>GET /api/skills/{id}       — 查看技能详情</li>
 *   <li>GET /api/skills/search?q=  — 搜索技能</li>
 * </ul>
 */
public class SkillsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/skills";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public SkillsApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        if (!"GET".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        try {
            InteractionSession session = sessionManager.getOrCreateSession("default");

            if (segs.length == 0) {
                // 检查是否有 ?q= 查询参数
                String query = exchange.getRequestURI().getQuery();
                String q = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if ("q".equals(kv[0]) && kv.length > 1) {
                            q = java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }

                InteractionResult result;
                if (q != null && !q.isBlank()) {
                    result = ctx.getInteractionManager().handle("/skill search " + q, session);
                } else {
                    result = ctx.getInteractionManager().handle("/skill list", session);
                }
                BaseApiHandler.sendOk(exchange, result.message(),
                        Map.of("displayText", result.displayText(), "success", result.success()));
            } else {
                InteractionResult result = ctx.getInteractionManager().handle("/skill show " + segs[0], session);
                BaseApiHandler.sendOk(exchange, result.success() ? "Skill found" : "Skill not found",
                        Map.of("id", segs[0], "displayText", result.displayText(), "success", result.success()));
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
