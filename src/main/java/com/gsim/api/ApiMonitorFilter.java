package com.gsim.api;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * HTTP API 请求监控 Filter — 终端实时输出请求/响应摘要。
 *
 * <p>配合 {@code --monitor} 模式使用，禁用 CLI REPL，只显示 HTTP 交互。
 */
public class ApiMonitorFilter extends Filter {

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        long start = System.currentTimeMillis();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String fullPath = query != null ? path + "?" + query : path;
        int contentLen = 0;
        String contentLenStr = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLenStr != null) {
            try { contentLen = Integer.parseInt(contentLenStr); } catch (NumberFormatException ignored) {}
        }

        // Before
        String time = TF.format(LocalTime.now());
        StringBuilder line = new StringBuilder();
        line.append("\n── ").append(time).append(" ").append(method).append(" ").append(fullPath);
        if (contentLen > 0) line.append("  (").append(contentLen).append("B)");
        System.out.println(line);

        // Execute
        chain.doFilter(exchange);

        // After
        long elapsed = System.currentTimeMillis() - start;
        int status = exchange.getResponseCode();
        long respLen = exchange.getResponseHeaders().containsKey("Content-Length")
                ? Long.parseLong(exchange.getResponseHeaders().getFirst("Content-Length"))
                : -1;

        StringBuilder result = new StringBuilder();
        result.append(status).append("  ");
        if (respLen >= 0) result.append(respLen).append("B  ");
        result.append(elapsed).append("ms");
        if (status >= 400) result.append(" ⚠");
        System.out.println(result);
    }

    @Override
    public String description() {
        return "API Monitor — logs all HTTP requests/responses to stdout";
    }
}
