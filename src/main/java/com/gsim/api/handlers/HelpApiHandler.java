package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.app.ApplicationContext;
import com.gsim.interaction.InteractionCommand;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.*;

/**
 * /api/help — 帮助 API，返回所有已注册命令列表。
 *
 * <p>端点：GET /api/help
 */
public class HelpApiHandler implements HttpHandler {

    private final ApplicationContext ctx;

    public HelpApiHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use GET.");
            return;
        }

        try {
            Map<String, InteractionCommand> commands = ctx.getInteractionManager().getCommands();
            List<Map<String, Object>> list = new ArrayList<>();

            for (var entry : commands.entrySet()) {
                InteractionCommand cmd = entry.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", cmd.name());
                m.put("description", cmd.description());
                m.put("usage", cmd.usage());
                list.add(m);
            }

            // 按名称排序
            list.sort(Comparator.comparing(m -> (String) m.get("name")));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("commands", list);
            data.put("count", list.size());

            BaseApiHandler.sendOk(exchange, "Available commands", data);
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }
}
