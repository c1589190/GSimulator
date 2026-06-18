package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.dto.CommandRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.interaction.InteractionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/command — 执行 CLI 命令并返回结果。
 * 复用现有 InteractionManager，CLI 和 HTTP 共用业务逻辑。
 */
public class CommandApiHandler implements HttpHandler {

    private final ApplicationContext ctx;
    private final EventBus eventBus;

    public CommandApiHandler(ApplicationContext ctx, EventBus eventBus) {
        this.ctx = ctx;
        this.eventBus = eventBus;
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

        try {
            // 发送 command_started 事件
            eventBus.publish(GSimEvent.of(request.sessionId(), "command_started",
                    Map.of("command", request.command())));

            // 复用 InteractionManager 执行业务逻辑
            var session = ctx.getInteractionSession();
            InteractionResult result = ctx.getInteractionManager().handle(request.command(), session);

            // 构建响应
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", request.command());
            data.put("success", result.success());
            data.put("message", result.message());
            data.put("displayText", result.displayText());
            if (result.outputFiles() != null && !result.outputFiles().isEmpty()) {
                data.put("outputFiles", result.outputFiles());
            }
            if (result.errors() != null && !result.errors().isEmpty()) {
                data.put("errors", result.errors());
            }

            if (result.success()) {
                eventBus.publish(GSimEvent.of(request.sessionId(), "command_done", Map.of()));
                BaseApiHandler.sendOk(exchange, "Command executed", data);
            } else {
                eventBus.publish(GSimEvent.of(request.sessionId(), "command_error",
                        Map.of("error", result.message())));
                BaseApiHandler.sendJson(exchange, 400, ApiResponse.fail(result.message(), data));
            }
        } catch (Exception e) {
            eventBus.publish(GSimEvent.of(request.sessionId(), "command_error",
                    Map.of("error", e.getMessage())));
            BaseApiHandler.sendError(exchange, 500, "Command execution failed: " + e.getMessage());
        }
    }
}
