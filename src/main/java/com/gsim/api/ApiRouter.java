package com.gsim.api;

import com.gsim.api.handlers.*;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * API 路由注册器 — 将所有 API handler 注册到 HttpServer。
 */
public class ApiRouter {

    private final HttpServer server;
    private final ApplicationContext ctx;
    private final EventBus eventBus;
    private final SessionManager sessionManager;
    private final TaskManager taskManager;

    public ApiRouter(HttpServer server, ApplicationContext ctx, EventBus eventBus,
                     SessionManager sessionManager, TaskManager taskManager) {
        this.server = server;
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.taskManager = taskManager;
    }

    /**
     * 注册所有路由。
     */
    public void registerAll() {
        // 状态
        register("/api/status", new StatusApiHandler(ctx));

        // 帮助 — 列出所有命令
        register("/api/help", new HelpApiHandler(ctx));

        // 当前位置信息
        register("/api/where", new WhereApiHandler(ctx, sessionManager));

        // 命令（旧接口，保留兼容）
        register("/api/command", new CommandApiHandler(ctx, eventBus, sessionManager));
        register("/api/command/stream",
                new StreamCommandHandler(ctx, eventBus, sessionManager, taskManager));

        // 任务 API（新接口，推荐使用）
        register("/api/tasks", new TasksApiHandler(taskManager, sessionManager, eventBus));

        // Import
        register("/api/import", new ImportApiHandler(ctx, eventBus));

        // SearchDB
        register("/api/searchdb", new SearchDbApiHandler(ctx, eventBus, sessionManager));

        // Logs & Outputs
        register("/api/logs", new LogsOutputsApiHandler(ctx));
        register("/api/outputs", new LogsOutputsApiHandler(ctx));

        // 配置管理
        register("/api/config", new ConfigApiHandler(ctx, sessionManager));

        // 知识库
        register("/api/knowledge", new KnowledgeApiHandler(ctx));

        // Embedding
        register("/api/embedding", new EmbeddingApiHandler(ctx));

        // 技能
        register("/api/skills", new SkillsApiHandler(ctx, sessionManager));

        // 经验
        register("/api/experiences", new ExperiencesApiHandler(ctx, sessionManager));

        // 玩家档案
        register("/api/players", new PlayersApiHandler(ctx, sessionManager));

        // 手动保存
        register("/api/save", new SaveApiHandler(ctx, sessionManager));

        // 压缩上下文
        register("/api/compact", new CompactApiHandler(ctx, sessionManager));

        // 硬约束（Pins）
        register("/api/pins", new PinsApiHandler(ctx, sessionManager));

        // 消息历史
        register("/api/messages", new MessagesApiHandler(ctx, sessionManager));

        // 根节点工作区
        register("/api/roots", new RootsApiHandler(ctx, sessionManager));

        // 工具
        register("/api/tools", new ToolsApiHandler(ctx, sessionManager));
    }

    /**
     * 注册 handler，自动包装 CORS 预检处理。
     * 对 OPTIONS 请求返回 204 + CORS headers，其他请求委托给真实 handler。
     */
    private void register(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                com.gsim.api.handlers.BaseApiHandler.handlePreflight(exchange);
            } else {
                handler.handle(exchange);
            }
        });
    }
}
