package com.gsim.webui.handlers;

import com.gsim.app.ApplicationContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * 对话面板处理器（Phase 2 实现）。
 */
public class ChatHandler implements HttpHandler {

    @SuppressWarnings("unused")
    private final ApplicationContext ctx;

    public ChatHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(501, -1);
        exchange.close();
    }
}
