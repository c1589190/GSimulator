package com.gsim.api;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * HTTP API 请求监控 Filter — 终端彩色输出请求/响应摘要。
 *
 * <p>当 {@code cli.monitor.http_api=true} 时启用，在 CLI 中实时显示 HTTP API 交互。
 * 支持同时运行 CLI REPL + HTTP API 监控。
 */
public class ApiMonitorFilter extends Filter {

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ANSI
    private static final String CYAN = "\033[36m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String DIM = "\033[2m";
    private static final String RESET = "\033[0m";

    private volatile boolean enabled = false;

    /**
     * 启用或禁用监控过滤器。
     *
     * @param enabled true 为启用彩色请求/响应日志输出，false 为禁用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 检查监控过滤器当前是否启用。
     *
     * @return 启用返回 true，否则返回 false
     */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        if (!enabled) {
            chain.doFilter(exchange);
            return;
        }

        long start = System.currentTimeMillis();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String fullPath = query != null ? path + "?" + query : path;

        // Request body size
        int contentLen = 0;
        String contentLenStr = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLenStr != null) {
            try {
                contentLen = Integer.parseInt(contentLenStr);
            } catch (NumberFormatException ignored) {
            }
        }

        // Before — cyan method + dim path
        String time = TF.format(LocalTime.now());
        String methodColor =
                switch (method) {
                    case "GET" -> GREEN;
                    case "POST", "PATCH", "PUT" -> YELLOW;
                    case "DELETE" -> RED;
                    default -> CYAN;
                };
        System.out.println(DIM + time + RESET + " " + methodColor + method + RESET + " " + DIM + fullPath + RESET
                + (contentLen > 0 ? " " + DIM + "(" + contentLen + "B)" + RESET : ""));

        // Execute
        chain.doFilter(exchange);

        // After — status code + elapsed
        long elapsed = System.currentTimeMillis() - start;
        int status = exchange.getResponseCode();

        String statusColor = status < 300 ? GREEN : status < 400 ? YELLOW : RED;
        System.out.println("  " + statusColor + status + RESET + "  " + DIM + elapsed + "ms" + RESET
                + (status >= 400 ? " " + RED + "✗" + RESET : ""));
    }

    @Override
    public String description() {
        return "API Monitor — colored HTTP request/response logging";
    }
}
