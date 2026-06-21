package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
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
 * /api/experiences — 经验管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/experiences            — 列出所有经验</li>
 *   <li>GET    /api/experiences/{id}       — 查看经验详情</li>
 *   <li>GET    /api/experiences/search?q=  — 搜索经验</li>
 *   <li>POST   /api/experiences            — 添加经验</li>
 * </ul>
 */
public class ExperiencesApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/experiences";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public ExperiencesApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
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
                    String query = exchange.getRequestURI().getQuery();
                    String q = extractQueryParam(query, "q");

                    InteractionResult result;
                    if (q != null && !q.isBlank()) {
                        result = ctx.getInteractionManager().handle("/exp search " + q, session);
                    } else {
                        result = ctx.getInteractionManager().handle("/exp list", session);
                    }
                    BaseApiHandler.sendOk(exchange, result.message(),
                            Map.of("displayText", result.displayText(), "success", result.success()));
                } else if ("POST".equals(method)) {
                    String body = BaseApiHandler.readBody(exchange);
                    Map<?, ?> reqMap = JsonUtils.fromJson(body, Map.class);
                    Object rawTitle = reqMap != null ? reqMap.get("title") : null;
                    Object rawContent = reqMap != null ? reqMap.get("content") : null;
                    String title = rawTitle != null ? rawTitle.toString() : "";
                    String content = rawContent != null ? rawContent.toString() : "";

                    if (title.isBlank() || content.isBlank()) {
                        BaseApiHandler.sendError(exchange, 400, "Missing required fields: title, content");
                        return;
                    }

                    InteractionResult result = ctx.getInteractionManager().handle("/exp add " + title + " " + content, session);
                    BaseApiHandler.sendOk(exchange, result.success() ? "Experience added" : "Add failed",
                            Map.of("success", result.success(), "message", result.displayText()));
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else {
                if (!"GET".equals(method)) {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
                    return;
                }
                InteractionResult result = ctx.getInteractionManager().handle("/exp show " + segs[0], session);
                BaseApiHandler.sendOk(exchange, result.success() ? "Experience found" : "Experience not found",
                        Map.of("id", segs[0], "displayText", result.displayText(), "success", result.success()));
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private String extractQueryParam(String query, String key) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (key.equals(kv[0]) && kv.length > 1) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
