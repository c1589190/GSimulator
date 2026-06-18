package com.gsim.api.handlers;

import com.gsim.api.*;
import com.gsim.api.dto.CommandRequest;
import com.gsim.event.EventBus;
import com.gsim.event.FilteredEventSink;
import com.gsim.event.GSimEvent;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/tasks — 任务管理 API。
 *
 * <p>路由：
 * <ul>
 *   <li>POST /api/tasks            — 创建任务</li>
 *   <li>GET  /api/tasks            — 列出所有任务</li>
 *   <li>GET  /api/tasks/{taskId}         — 查询任务状态</li>
 *   <li>GET  /api/tasks/{taskId}/events  — SSE 订阅任务事件</li>
 *   <li>POST /api/tasks/{taskId}/cancel  — 取消任务</li>
 * </ul>
 */
public class TasksApiHandler implements HttpHandler {

    private final TaskManager taskManager;
    private final SessionManager sessionManager;
    private final EventBus eventBus;

    public TasksApiHandler(TaskManager taskManager, SessionManager sessionManager, EventBus eventBus) {
        this.taskManager = taskManager;
        this.sessionManager = sessionManager;
        this.eventBus = eventBus;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] segments = BaseApiHandler.pathSegments(exchange, "/api/tasks");

        try {
            if (segments.length == 0) {
                handleRoot(exchange, method);
            } else if (segments.length == 1) {
                handleTask(exchange, method, segments[0]);
            } else if (segments.length == 2) {
                handleSubResource(exchange, method, segments[0], segments[1]);
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown path");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // --- /api/tasks ---

    private void handleRoot(HttpExchange exchange, String method) throws IOException {
        switch (method) {
            case "POST" -> handleCreateTask(exchange);
            case "GET" -> handleListTasks(exchange);
            default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET or POST.");
        }
    }

    private void handleCreateTask(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CommandRequest request;
        try {
            request = JsonBodyParser.parse(body, CommandRequest.class);
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 400, "Invalid JSON body: " + e.getMessage());
            return;
        }

        if (request.command() == null || request.command().isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: command");
            return;
        }

        String sessionId = request.sessionId();
        sessionManager.getOrCreateSession(sessionId); // 确保 session 存在

        ApiTask task = taskManager.createCommandTask(sessionId, request.command());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.taskId());
        data.put("status", task.status().name());

        BaseApiHandler.sendJson(exchange, 201, ApiResponse.ok("Task created", data));
    }

    private void handleListTasks(HttpExchange exchange) throws IOException {
        List<ApiTask> tasks = taskManager.listTasks();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tasks", tasks);
        data.put("count", tasks.size());

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Tasks listed", data));
    }

    // --- /api/tasks/{taskId} ---

    private void handleTask(HttpExchange exchange, String method, String taskId) throws IOException {
        if (!"GET".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        ApiTask task = taskManager.getTask(taskId);
        if (task == null) {
            BaseApiHandler.sendJson(exchange, 404, ApiResponse.fail("Task not found: " + taskId, "NOT_FOUND"));
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);

        BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Task found", data));
    }

    // --- /api/tasks/{taskId}/{sub} ---

    private void handleSubResource(HttpExchange exchange, String method, String taskId, String sub) throws IOException {
        switch (sub) {
            case "events" -> handleTaskEvents(exchange, taskId);
            case "cancel" -> handleTaskCancel(exchange, method, taskId);
            default -> BaseApiHandler.sendNotFound(exchange, "Unknown sub-resource: " + sub);
        }
    }

    private void handleTaskEvents(HttpExchange exchange, String taskId) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        ApiTask initialTask = taskManager.getTask(taskId);
        if (initialTask == null) {
            BaseApiHandler.sendJson(exchange, 404, ApiResponse.fail("Task not found: " + taskId, "NOT_FOUND"));
            return;
        }

        // 发送 SSE 响应头
        SseWriter sse = new SseWriter(exchange);
        sse.sendHeaders();

        // 创建过滤 sink — 只接收该 taskId 的事件
        FilteredEventSink sink = new FilteredEventSink(sse, null, taskId);
        eventBus.subscribe(sink);

        try {
            // 如果任务已经是终态，直接发送已有的事件摘要
            ApiTask task = taskManager.getTask(taskId);
            if (task != null && isTerminal(task.status())) {
                // 发送 task 当前状态作为 result
                sse.writeEvent("task_status", JsonUtils.toJsonCompact(buildTaskSummary(task)));
            }

            // 等待任务完成（最长 5 分钟）
            taskManager.waitForCompletion(taskId, 300_000);

            // 确保发送 done 事件（如果后台线程尚未发布）
            task = taskManager.getTask(taskId);
            if (task != null) {
                Map<String, Object> doneData = new LinkedHashMap<>();
                doneData.put("sessionId", task.sessionId());
                doneData.put("taskId", taskId);
                doneData.put("status", task.status().name());
                sse.writeEvent("done", doneData);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sse.writeEvent("error", Map.of("message", "Interrupted", "taskId", taskId));
        } finally {
            eventBus.unsubscribe(sink);
            sink.close();
            sse.close();
        }
    }

    private void handleTaskCancel(HttpExchange exchange, String method, String taskId) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        ApiTask task = taskManager.getTask(taskId);
        if (task == null) {
            BaseApiHandler.sendJson(exchange, 404, ApiResponse.fail("Task not found: " + taskId, "NOT_FOUND"));
            return;
        }

        boolean cancelled = taskManager.cancelTask(taskId);
        if (cancelled) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("status", "CANCELLED");
            BaseApiHandler.sendJson(exchange, 200, ApiResponse.ok("Task cancelled", data));
        } else {
            BaseApiHandler.sendJson(exchange, 400,
                    ApiResponse.fail("Task cannot be cancelled in status: " + task.status(), "INVALID_STATE"));
        }
    }

    private boolean isTerminal(ApiTaskStatus status) {
        return status == ApiTaskStatus.DONE
                || status == ApiTaskStatus.FAILED
                || status == ApiTaskStatus.CANCELLED;
    }

    private Map<String, Object> buildTaskSummary(ApiTask task) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", task.taskId());
        summary.put("sessionId", task.sessionId());
        summary.put("command", task.command());
        summary.put("status", task.status().name());
        summary.put("startedAt", task.startedAt() != null ? task.startedAt().toString() : null);
        summary.put("finishedAt", task.finishedAt() != null ? task.finishedAt().toString() : null);
        if (task.result() != null) {
            summary.putAll(task.result());
        }
        if (task.error() != null) {
            summary.put("error", task.error());
        }
        return summary;
    }
}
