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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, List<String>> pendingUploads = new ConcurrentHashMap<>();
    private final Path importDir;

    public ChatHandler(ApplicationContext ctx, PageHandler pageHandler) {
        this.ctx = ctx;
        this.pageHandler = pageHandler;
        this.importDir = ctx.getConfig().getImportDir();
        try {
            Files.createDirectories(importDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create import dir: " + importDir, e);
        }
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
            } else if (path.equals("/chat/upload") && "POST".equals(method)) {
                handleUpload(exchange);
            } else if (path.equals("/chat/uploads") && "GET".equals(method)) {
                handleListUploads(exchange);
            } else if (path.equals("/chat/uploads") && "DELETE".equals(method)) {
                handleClearUploads(exchange);
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

        // 注入上传文件提示
        List<String> uploads = pendingUploads.remove(sessionId);
        if (uploads != null && !uploads.isEmpty()) {
            message = "(用户上传了文件: " + String.join(", ", uploads) + ")\n\n" + message;
        }

        // 通过 ApiManager 的 TaskManager 创建 PENDING 任务（不自动启动）
        // 任务将在 SSE 客户端连接后由 handleStream() 启动，
        // 确保订阅者不会错过任何事件。
        TaskManager tm = ctx.getApiManager().getTaskManager();
        String cmd = "/chat " + message;
        ApiTask task = tm.createCommandTask(sessionId, cmd, false);

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
        ApiTask preTask = tm.getTask(taskId);
        if (preTask == null) {
            HandlerUtils.sendError(exchange, 404, "Task not found: " + taskId);
            return;
        }

        SseWriter sse = new SseWriter(exchange);
        sse.sendHeaders();

        // 先订阅 EventBus，再启动任务执行 —— 确保不错过任何事件
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

        // 订阅就绪后再启动任务（只启动 PENDING 状态的任务）
        try {
            if (preTask.status() == ApiTaskStatus.PENDING) {
                tm.executePendingTask(taskId);
            } else if (preTask.status() == ApiTaskStatus.RUNNING) {
                // 客户端重连场景：任务已在运行，只订阅新事件
            }
            // 如果任务已完成（DONE/FAILED/CANCELLED），直接返回 ——
            // 客户端会收到空的 SSE 然后走 onerror 刷新消息

            // 保持连接直到任务终止或超时
            long deadline = System.currentTimeMillis() + 300_000; // 5 分钟超时
            long lastKeepAlive = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                ApiTask task = tm.getTask(taskId);
                if (task != null && isTerminal(task.status())) {
                    break;
                }
                // 每 15 秒发送 keep-alive 注释，防止代理/浏览器超时断开
                if (System.currentTimeMillis() - lastKeepAlive > 15_000) {
                    sse.writeComment("keepalive");
                    lastKeepAlive = System.currentTimeMillis();
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // executePendingTask 可能抛异常（任务已被其他人启动等）
            try {
                sse.writeEvent("command_error",
                        Map.of("error", "Task start failed: " + e.getMessage()));
            } catch (IOException ignored) {}
        } finally {
            eventBus.unsubscribe(sink);
            sse.close();
        }
    }

    private void handleMessages(HttpExchange exchange) throws IOException {
        var dm = ctx.getDataManager();
        if (dm == null || dm.getActiveBranch() == null) {
            if (isJsonRequested(exchange)) {
                HandlerUtils.sendJson(exchange, 200, Map.of("messages", List.of()));
            } else {
                HandlerUtils.sendHtml(exchange, 200,
                        "<div class=\"text-gray-600 text-sm\">发送消息开始对话</div>");
            }
            return;
        }

        try {
            Path dataRoot = dm.getDataRoot();
            BranchMessageStore store = new BranchMessageStore(dm, dataRoot);
            List<BranchMessage> messages = store.listMessages(dm.getActiveBranch());

            // ---- JSON API（前端模块化渲染用）----
            if (isJsonRequested(exchange)) {
                List<Map<String, Object>> msgList = new ArrayList<>();
                for (BranchMessage msg : messages) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", msg.id());
                    m.put("role", msg.role());
                    m.put("type", msg.type());
                    m.put("content", msg.content());
                    m.put("toolName", msg.toolName());
                    m.put("createdAt", msg.createdAt().toString());
                    msgList.add(m);
                }
                HandlerUtils.sendJson(exchange, 200, Map.of("messages", msgList));
                return;
            }

            // ---- HTML 渲染（向后兼容）----
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
            if (isJsonRequested(exchange)) {
                HandlerUtils.sendJson(exchange, 500,
                        Map.of("error", "Failed to load messages: " + e.getMessage()));
            } else {
                HandlerUtils.sendHtml(exchange, 200,
                        "<div class=\"text-gray-600 text-sm\">加载消息失败</div>");
            }
        }
    }

    /** 检查请求是否希望 JSON 响应（?format=json 或 Accept: application/json）。 */
    private static boolean isJsonRequested(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("format=json")) return true;
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        return accept != null && accept.contains("application/json");
    }

    // ---- 文件上传 ----

    private void handleUpload(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String sessionId = HandlerUtils.getQueryParam(query, "sessionId");
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        String filename = HandlerUtils.getQueryParam(query, "filename");
        if (filename == null || filename.isBlank()) {
            HandlerUtils.sendError(exchange, 400, "filename query param required");
            return;
        }

        // 安全化文件名：去除路径分隔符，保留基本文件名
        String safeName = filename.replaceAll("[\\\\/]", "_").replaceAll("\\s+", "_");
        if (safeName.isEmpty() || ".".equals(safeName) || "..".equals(safeName)) {
            HandlerUtils.sendError(exchange, 400, "Invalid filename");
            return;
        }

        // 读取原始文件字节
        byte[] fileBytes = exchange.getRequestBody().readAllBytes();
        if (fileBytes.length == 0) {
            HandlerUtils.sendError(exchange, 400, "Empty file");
            return;
        }

        // 写入 import/ 目录，冲突时追加序号
        Path dest = importDir.resolve(safeName);
        int seq = 1;
        String base = safeName.contains(".") ? safeName.substring(0, safeName.lastIndexOf('.')) : safeName;
        String ext = safeName.contains(".") ? safeName.substring(safeName.lastIndexOf('.')) : "";
        while (Files.exists(dest)) {
            dest = importDir.resolve(base + "(" + seq + ")" + ext);
            seq++;
        }
        Files.write(dest, fileBytes);
        String savedName = dest.getFileName().toString();

        // 记录到待上传列表
        pendingUploads.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(savedName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("filename", savedName);
        HandlerUtils.sendJson(exchange, 200, result);
    }

    private void handleListUploads(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String sessionId = HandlerUtils.getQueryParam(query, "sessionId");
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";

        List<String> files = pendingUploads.getOrDefault(sessionId, List.of());
        HandlerUtils.sendJson(exchange, 200, Map.of("files", files));
    }

    private void handleClearUploads(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String sessionId = HandlerUtils.getQueryParam(query, "sessionId");
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";

        pendingUploads.remove(sessionId);
        HandlerUtils.sendJson(exchange, 200, Map.of("success", true));
    }

    // ---- 上下文 ----

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
