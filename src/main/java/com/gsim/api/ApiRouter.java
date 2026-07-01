package com.gsim.api;

import com.gsim.api.handlers.*;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * API 路由注册器 — 将所有 API handler 注册到 HttpServer。
 */
public class ApiRouter {

    private final HttpServer server;
    private final ApplicationContext ctx;
    private final EventBus eventBus;
    private final SessionManager sessionManager;
    private final TaskManager taskManager;
    private final Path worldsDir;
    private final Path importDir;
    private final Supplier<String> activeWorldId;

    public ApiRouter(HttpServer server, ApplicationContext ctx, EventBus eventBus,
                     SessionManager sessionManager, TaskManager taskManager,
                     Path worldsDir, Path importDir, Supplier<String> activeWorldId) {
        this.server = server;
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.taskManager = taskManager;
        this.worldsDir = worldsDir;
        this.importDir = importDir;
        this.activeWorldId = activeWorldId;
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

        // Logs & Outputs
        register("/api/logs", new LogsOutputsApiHandler(ctx));
        register("/api/outputs", new LogsOutputsApiHandler(ctx));

        // 配置管理
        register("/api/config", new ConfigApiHandler(ctx, sessionManager));

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

        // World 管理 (CRUD + Node 操作)
        register("/api/world-manager", new WorldManagerApiHandler(worldsDir, activeWorldId));

        // World 数据 (Checkpoint + Element 查询与写入)
        register("/api/world-manager-data", new WorldDataApiHandler(worldsDir));

        // 文档管理 (CRUD + 搜索 + Web 导入)
        register("/api/documents", new DocumentsApiHandler(importDir, eventBus, ctx));
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
