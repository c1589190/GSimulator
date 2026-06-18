package com.gsim.api.handlers;

import com.gsim.api.JsonBodyParser;
import com.gsim.api.SseWriter;
import com.gsim.api.dto.CommandRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.event.SseEventSink;
import com.gsim.interaction.InteractionResult;
import com.gsim.util.IdGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/command/stream — SSE 流式命令接口。
 *
 * <p>返回 Server-Sent Events 流，事件类型包括：
 * <ul>
 *   <li>command_started — 命令开始</li>
 *   <li>run_stage — 推演阶段</li>
 *   <li>llm_delta / llm_reasoning_delta — LLM 流式输出</li>
 *   <li>tool_started / tool_done — 工具调用</li>
 *   <li>result — 结果</li>
 *   <li>done — 结束</li>
 * </ul>
 */
public class StreamCommandHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final EventBus eventBus;

    public StreamCommandHandler(ApplicationContext ctx, EventBus eventBus) {
        this.ctx = ctx;
        this.eventBus = eventBus;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // 仅接受 POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        // 检查 Accept 头（可选，宽松处理）
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

        String taskId = IdGenerator.generate("task");

        // 创建 SSE 写入器
        SseWriter sse = new SseWriter(exchange);
        sse.sendHeaders();

        // 订阅 SseEventSink，将 EventBus 事件桥接到 SSE
        SseEventSink sseSink = new SseEventSink(exchange.getResponseBody());
        // 注意：我们使用 SseWriter 来发送事件，不直接使用 SseEventSink 的原始流
        // 而是创建一个包装的 sink
        var bridgeSink = new BridgeEventSink(sse);
        eventBus.subscribe(bridgeSink);

        try {
            // 发送 command_started
            Map<String, Object> startedData = new LinkedHashMap<>();
            startedData.put("sessionId", request.sessionId());
            startedData.put("command", request.command());
            startedData.put("taskId", taskId);
            sse.writeEvent("command_started", startedData);

            // 执行命令
            var session = ctx.getInteractionSession();
            InteractionResult result = ctx.getInteractionManager().handle(request.command(), session);

            // 发送结果
            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("sessionId", request.sessionId());
            resultData.put("taskId", taskId);
            resultData.put("success", result.success());
            resultData.put("message", result.message());
            resultData.put("displayText", result.displayText());
            if (result.outputFiles() != null && !result.outputFiles().isEmpty()) {
                resultData.put("outputFiles", result.outputFiles());
            }
            if (result.errors() != null && !result.errors().isEmpty()) {
                resultData.put("errors", result.errors());
            }
            sse.writeEvent("result", resultData);

            if (!result.success()) {
                sse.writeEvent("command_error",
                        Map.of("error", result.message(), "sessionId", request.sessionId()));
            }
        } catch (Exception e) {
            // 错误不导致 JVM 崩溃
            sse.writeEvent("command_error",
                    Map.of("error", e.getMessage(), "sessionId", request.sessionId()));
        } finally {
            // 发送完成事件
            sse.writeEvent("done", Map.of("sessionId", request.sessionId(), "taskId", taskId));
            eventBus.unsubscribe(bridgeSink);
            sse.close();
        }
    }

    /**
     * 桥接 EventSink → SseWriter。
     * 将 EventBus 事件转换为 SSE 格式。
     */
    private static class BridgeEventSink implements com.gsim.event.EventSink {
        private final SseWriter sse;

        BridgeEventSink(SseWriter sse) {
            this.sse = sse;
        }

        @Override
        public void accept(GSimEvent event) {
            try {
                sse.writeEvent(event.type(), SseEventSink.toSseJson(event));
            } catch (IOException e) {
                // 客户端断开 — 静默处理
            }
        }
    }
}
