package com.gsim.webui.handlers;

import com.gsim.app.ApplicationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * 知识库面板处理器（Phase 2 实现）。
 */
public class KnowledgeHandler implements HttpHandler {

    @SuppressWarnings("unused")
    private final ApplicationContext ctx;

    public KnowledgeHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(501, -1);
        exchange.close();
    }
}
