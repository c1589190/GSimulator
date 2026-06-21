package com.gsim.webui.handlers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gsim.api.ApiTask;
import com.gsim.api.ApiTaskStatus;
import com.gsim.api.SseWriter;
import com.gsim.api.TaskManager;
import com.gsim.app.ApplicationContext;
import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataManager;
import com.gsim.event.EventBus;
import com.gsim.event.FilteredEventSink;
import com.gsim.event.GSimEvent;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
            } else if (path.equals("/chat/cancel") && "POST".equals(method)) {
                handleCancel(exchange);
            } else if (path.equals("/chat/messages") && "GET".equals(method)) {
                handleMessages(exchange);
            } else if (path.equals("/chat/context") && "GET".equals(method)) {
                handleContext(exchange);
            } else if (path.equals("/chat/context-bar") && "GET".equals(method)) {
                handleContextBar(exchange);
            } else {
                HandlerUtils.sendError(exchange, 404, "Unknown chat endpoint");
            }
        } catch (Exception e) {
            HandlerUtils.logError("ChatHandler", method, path, e);
            HandlerUtils.sendError(exchange, 500, "Internal server error");
        }
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        String message;
        String sessionId;

        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            Map<String, String> form = HandlerUtils.parseFormEncoded(body);
            message = form.getOrDefault("message", "");
            sessionId = form.getOrDefault("sessionId", "default");
        } else {
            Map<String, Object> req = JsonUtils.fromJson(body,
                    new TypeReference<Map<String, Object>>() {});
            message = (String) req.getOrDefault("message", "");
            sessionId = (String) req.getOrDefault("sessionId", "default");
        }

        if (message.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "message is required");
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

        HandlerUtils.sendJson(exchange, 200, result);
    }

    private void handleCancel(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String taskId = HandlerUtils.getQueryParam(query, "taskId");
        if (taskId == null || taskId.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "taskId query param required");
            return;
        }

        TaskManager tm = ctx.getApiManager().getTaskManager();
        boolean cancelled = tm.cancelTask(taskId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("cancelled", cancelled);
        HandlerUtils.sendJson(exchange, 200, result);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String taskId = HandlerUtils.getQueryParam(query, "taskId");
        if (taskId == null || taskId.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "taskId query param required");
            return;
        }

        TaskManager tm = ctx.getApiManager().getTaskManager();
        if (tm.getTask(taskId) == null) {
            HandlerUtils.sendError(exchange, 404, "Task not found: " + taskId);
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
        var dm = ctx.getDataManager();
        if (dm == null || dm.getActiveBranch() == null) {
            HandlerUtils.sendHtml(exchange, 200,
                    "<div class=\"text-gray-600 text-sm\">发送消息开始对话</div>");
            return;
        }

        try {
            Path dataRoot = dm.getDataRoot();
            BranchMessageStore store = new BranchMessageStore(dm, dataRoot);
            List<BranchMessage> messages = store.listMessages(dm.getActiveBranch());

            if (messages.isEmpty()) {
                HandlerUtils.sendHtml(exchange, 200,
                        "<div class=\"text-gray-600 text-sm\">发送消息开始对话</div>");
                return;
            }

            StringBuilder html = new StringBuilder();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.of("Asia/Shanghai"));

            for (BranchMessage msg : messages) {
                String role = msg.role();
                String type = msg.type();
                String content = msg.content();
                if (content == null || content.isBlank()) continue;

                // 跳过纯工具消息，只显示 user 和 assistant
                if ("tool".equals(role)) continue;
                if ("system".equals(role) && !"error".equals(type)) continue;

                String cssClass;
                String label;
                if ("user".equals(role)) {
                    cssClass = "msg-user";
                    label = "You";
                } else if ("error".equals(type)) {
                    cssClass = "msg-assistant";
                    label = "Error";
                    content = "<span class=\"text-red-400\">" + escapeHtml(content) + "</span>";
                    html.append("<div class=\"").append(cssClass).append(" rounded p-2 text-sm mb-1\">")
                        .append("<span class=\"text-xs text-gray-500\">").append(label)
                        .append(" · ").append(fmt.format(msg.createdAt())).append("</span>")
                        .append("<div>").append(content).append("</div></div>\n");
                    continue;
                } else {
                    cssClass = "msg-assistant";
                    label = "Agent";
                }

                html.append("<div class=\"").append(cssClass).append(" rounded p-2 text-sm mb-1\">")
                    .append("<span class=\"text-xs text-gray-500\">").append(label)
                    .append(" · ").append(fmt.format(msg.createdAt())).append("</span>")
                    .append("<div>").append(escapeHtml(content)).append("</div></div>\n");
            }

            String result = html.isEmpty()
                    ? "<div class=\"text-gray-600 text-sm\">发送消息开始对话</div>"
                    : html.toString();
            HandlerUtils.sendHtml(exchange, 200, result);
        } catch (Exception e) {
            HandlerUtils.logError("ChatHandler", "GET", "/chat/messages", e);
            HandlerUtils.sendHtml(exchange, 200,
                    "<div class=\"text-gray-600 text-sm\">加载消息失败</div>");
        }
    }

    private void handleContext(HttpExchange exchange) throws IOException {
        var dm = ctx.getDataManager();
        Map<String, Object> context = new LinkedHashMap<>();
        if (dm != null) {
            context.put("activeBranchId", dm.getActiveBranch());
            context.put("activeWorld", dm.getActiveWorld());
            context.put("activeRootId", dm.getActiveRootId());
        }
        HandlerUtils.sendJson(exchange, 200, context);
    }

    private void handleContextBar(HttpExchange exchange) throws IOException {
        var dm = ctx.getDataManager();
        String world = dm != null ? dm.getActiveWorld() : null;
        String branch = dm != null ? dm.getActiveBranch() : null;

        StringBuilder html = new StringBuilder();
        if (world != null && !world.isBlank()) {
            html.append("世界: <span class=\"text-green-400\">").append(escapeHtml(world)).append("</span>");
        }
        if (branch != null && !branch.isBlank()) {
            if (!html.isEmpty()) html.append(" &nbsp;|&nbsp; ");
            html.append("分支: <span class=\"text-green-400\">").append(escapeHtml(branch)).append("</span>");
        }
        if (html.isEmpty()) {
            html.append("分支: —");
        }

        HandlerUtils.sendHtml(exchange, 200, html.toString());
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static boolean isTerminal(ApiTaskStatus status) {
        return status == ApiTaskStatus.DONE
                || status == ApiTaskStatus.FAILED
                || status == ApiTaskStatus.CANCELLED;
    }
}
