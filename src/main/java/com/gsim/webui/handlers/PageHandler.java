package com.gsim.webui.handlers;

import com.gsim.app.ApplicationContext;
import com.gsim.webui.TemplateRenderer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 页面路由处理器。
 *
 * <p>GET / → index.html（完整页面）
 * <p>GET /chat → 对话面板片段
 * <p>GET /timeline → 时间线面板片段
 * <p>GET /knowledge → 知识库面板片段
 */
public class PageHandler implements HttpHandler {

    private final ApplicationContext ctx;

    public PageHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (!"GET".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String html;
        String contentType = "text/html; charset=utf-8";

        switch (path) {
            case "/" -> {
                var dm = ctx.getDataManager();
                String activeBranch = dm != null ? dm.getActiveBranch() : null;
                String activeWorld = dm != null ? dm.getActiveWorld() : null;
                html = TemplateRenderer.render("index", Map.of(
                        "activeBranchId", activeBranch != null ? activeBranch : "—",
                        "activeWorld", activeWorld != null ? activeWorld : "—"
                ));
            }
            case "/chat" -> html = TemplateRenderer.render("fragments/chat-panel");
            case "/timeline" -> html = TemplateRenderer.render("fragments/timeline-panel");
            case "/scenario" -> html = TemplateRenderer.render("fragments/scenario-manager");
            case "/knowledge" -> html = TemplateRenderer.render("fragments/knowledge-panel");
            default -> {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
        }

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
