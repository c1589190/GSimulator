package com.gsim.api.handlers;

import com.gsim.api.SessionManager;
import com.gsim.api.SseWriter;
import com.gsim.api.TaskManager;
import com.gsim.api.ApiTask;
import com.gsim.api.dto.CommandRequest;
import com.gsim.api.JsonBodyParser;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.FilteredEventSink;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/command/stream — SSE 流式命令接口（旧接口，保留兼容）。
 *
 * <p>内部创建 task，通过 TaskManager 执行，转发该 task 的事件。
 * <p>推荐使用 POST /api/tasks + GET /api/tasks/{taskId}/events 替代。
 */
public class StreamCommandHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final EventBus eventBus;
    private final SessionManager sessionManager;
    private final TaskManager taskManager;

    public StreamCommandHandler(ApplicationContext ctx, EventBus eventBus,
                                SessionManager sessionManager, TaskManager taskManager) {
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.taskManager = taskManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        CommandRequest request;
        try {
            String body = BaseApiHandler.readBody(exchange);
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
        sessionManager.getOrCreateSession(sessionId);

        // 1. 预留任务（PENDING，不开始执行）
        ApiTask task = taskManager.reserveTask(sessionId, request.command());
        String taskId = task.taskId();

        // 2. 创建 SSE 写入器并发送响应头
        SseWriter sse = new SseWriter(exchange);
        sse.sendHeaders();

        // 3. 先订阅过滤后的 sink，再开始执行任务
        //    确保不会漏掉 task 的事件
        FilteredEventSink sink = new FilteredEventSink(sse, null, taskId);
        eventBus.subscribe(sink);

        // 4. 开始执行任务
        taskManager.executePendingTask(taskId);

        try {
            // 5. 等待任务完成（最长 5 分钟）
            taskManager.waitForCompletion(taskId, 300_000);

            // 6. 确保发送 done 事件
            var finalTask = taskManager.getTask(taskId);
            Map<String, Object> doneData = new LinkedHashMap<>();
            doneData.put("sessionId", sessionId);
            doneData.put("taskId", taskId);
            if (finalTask != null) {
                doneData.put("status", finalTask.status().name());
            }
            sse.writeEvent("done", doneData);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sse.writeEvent("error", Map.of("message", "Interrupted", "taskId", taskId));
        } finally {
            eventBus.unsubscribe(sink);
            sink.close();
            sse.close();
        }
    }
}
