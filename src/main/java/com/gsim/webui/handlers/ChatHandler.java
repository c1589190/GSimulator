package com.gsim.webui.handlers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gsim.api.ApiTask;
import com.gsim.api.ApiTaskStatus;
import com.gsim.api.SseWriter;
import com.gsim.api.TaskManager;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.FilteredEventSink;
import com.gsim.event.GSimEvent;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对话 API handler。
 *
 * <p>POST /chat/send     — 发送消息，返回 { taskId, streamUrl }
 * <p>GET  /chat/stream   — SSE 流式事件
 * <p>GET  /chat/messages — 消息历史
 * <p>GET  /chat/context  — 分支上下文
 * <p>GET  /chat          — 委托给 PageHandler 返回 HTML 片段
 */
public class ChatHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final PageHandler pageHandler;

    public ChatHandler(ApplicationContext ctx, PageHandler pageHandler) {
        this.ctx = ctx;
        this.pageHandler = pageHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            // GET /chat (exact) — delegate to PageHandler for HTML fragment
            if (path.equals("/chat") && "GET".equals(method)) {
                pageHandler.handle(exchange);
                return;
            }

            if (path.equals("/chat/send") && "POST".equals(method)) {
                handleSend(exchange);
            } else if (path.equals("/chat/stream") && "GET".equals(method)) {
                handleStream(exchange);
            } else if (path.equals("/chat/messages") && "GET".equals(method)) {
                handleMessages(exchange);
            } else if (path.equals("/chat/context") && "GET".equals(method)) {
                handleContext(exchange);
            } else {
                sendError(exchange, 404, "Unknown chat endpoint");
            }
        } catch (Exception e) {
            System.err.println("[ChatHandler] Error handling " + method + " " + path + ": " + e.getMessage());
            e.printStackTrace();
            sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        String message;
        String sessionId;

        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            Map<String, String> form = parseFormEncoded(body);
            message = form.getOrDefault("message", "");
            sessionId = form.getOrDefault("sessionId", "default");
        } else {
            Map<String, Object> req = JsonUtils.fromJson(body,
                    new TypeReference<Map<String, Object>>() {});
            message = (String) req.getOrDefault("message", "");
            sessionId = (String) req.getOrDefault("sessionId", "default");
        }

        if (message.isBlank()) {
            sendError(exchange, 400, "message is required");
            return;
        }

        // 通过 ApiManager 的 TaskManager 创建任务
        TaskManager tm = ctx.getApiManager().getTaskManager();
        String cmd = "/chat " + message;
        ApiTask task = tm.createCommandTask(sessionId, cmd);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.taskId());
        result.put("streamUrl", "/chat/stream?taskId=" + task.taskId());
        result.put("status", task.status().name());

        sendJson(exchange, 200, result);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String taskId = getQueryParam(query, "taskId");
        if (taskId == null || taskId.isBlank()) {
            sendError(exchange, 400, "taskId query param required");
            return;
        }

        TaskManager tm = ctx.getApiManager().getTaskManager();
        if (tm.getTask(taskId) == null) {
            sendError(exchange, 404, "Task not found: " + taskId);
            return;
        }

        SseWriter sse = new SseWriter(exchange);
        sse.sendHeaders();

        // 订阅 EventBus，过滤此 taskId
        EventBus eventBus = ctx.getEventBus();
        FilteredEventSink sink = new FilteredEventSink(
                event -> taskId.equals(event.taskId()),
                event -> {
                    try {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("type", event.type());
                        data.put("taskId", event.taskId());
                        data.put("time", event.time().toString());
                        data.putAll(event.data());
                        sse.writeEvent(event.type(), data);
                    } catch (IOException e) {
                        // SSE 连接已关闭
                    }
                }
        );
        eventBus.subscribe(sink);

        // 保持连接直到 done 事件或超时
        try {
            long deadline = System.currentTimeMillis() + 300_000; // 5 分钟超时
            while (System.currentTimeMillis() < deadline) {
                ApiTask task = tm.getTask(taskId);
                if (task != null && isTerminal(task.status())) {
                    break;
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            eventBus.unsubscribe(sink);
            sse.close();
        }
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        // 返回当前分支的消息列表
        var dm = ctx.getDataManager();
        if (dm == null) {
            sendJson(exchange, 200, Map.of("messages", List.of()));
            return;
        }
        // 简化：返回空列表，实际消息在模板中通过 hx-get 加载
        sendJson(exchange, 200, Map.of("messages", List.of(), "branchId",
                dm.getActiveBranch() != null ? dm.getActiveBranch() : ""));
    }

    private void handleContext(HttpExchange exchange) throws IOException {
        var dm = ctx.getDataManager();
        Map<String, Object> context = new LinkedHashMap<>();
        if (dm != null) {
            context.put("activeBranchId", dm.getActiveBranch());
            context.put("activeWorld", dm.getActiveWorld());
            context.put("activeRootId", dm.getActiveRootId());
        }
        sendJson(exchange, 200, context);
    }

    // ---- helpers ----

    private static Map<String, String> parseFormEncoded(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) return params;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private static void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = JsonUtils.toJsonCompact(data).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        sendJson(exchange, status, Map.of("error", msg));
    }

    private static String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equals(k)) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static boolean isTerminal(ApiTaskStatus status) {
        return status == ApiTaskStatus.DONE
                || status == ApiTaskStatus.FAILED
                || status == ApiTaskStatus.CANCELLED;
    }
}
