package com.gsim.webui.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 静态资源服务 — 从 classpath:/webui/static/ 读取文件。
 */
public class StaticHandler implements HttpHandler {

    private static final String PREFIX = "/static/";
    private static final String BASE = "/webui/static/";

    private static String mimeType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        String resourcePath = BASE + path.substring(PREFIX.length());
        if (resourcePath.contains("..")) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", mimeType(path));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
