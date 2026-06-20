package com.gsim.api;

import com.gsim.api.handlers.*;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
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
        server.createContext("/api/status", new StatusApiHandler(ctx));

        // 帮助 — 列出所有命令
        server.createContext("/api/help", new HelpApiHandler(ctx));

        // 当前位置信息
        server.createContext("/api/where", new WhereApiHandler(ctx, sessionManager));

        // 命令（旧接口，保留兼容）
        server.createContext("/api/command", new CommandApiHandler(ctx, eventBus, sessionManager));
        server.createContext("/api/command/stream",
                new StreamCommandHandler(ctx, eventBus, sessionManager, taskManager));

        // 任务 API（新接口，推荐使用）
        server.createContext("/api/tasks", new TasksApiHandler(taskManager, sessionManager, eventBus));

        // Campaign / Turn / Action
        server.createContext("/api/campaigns", new CampaignsApiHandler(ctx, eventBus));

        // Import
        server.createContext("/api/import", new ImportApiHandler(ctx, eventBus));

        // SearchDB
        server.createContext("/api/searchdb", new SearchDbApiHandler(ctx, eventBus, sessionManager));

        // Logs & Outputs
        server.createContext("/api/logs", new LogsOutputsApiHandler(ctx));
        server.createContext("/api/outputs", new LogsOutputsApiHandler(ctx));

        // Branches
        server.createContext("/api/branches", new BranchesApiHandler(ctx, eventBus, sessionManager));

        // 配置管理
        server.createContext("/api/config", new ConfigApiHandler(ctx, sessionManager));

        // 知识库
        server.createContext("/api/knowledge", new KnowledgeApiHandler(ctx));

        // Embedding
        server.createContext("/api/embedding", new EmbeddingApiHandler(ctx));

        // 数据管理
        server.createContext("/api/data", new DataApiHandler(ctx, sessionManager));

        // 技能
        server.createContext("/api/skills", new SkillsApiHandler(ctx, sessionManager));

        // 经验
        server.createContext("/api/experiences", new ExperiencesApiHandler(ctx, sessionManager));

        // 玩家档案
        server.createContext("/api/players", new PlayersApiHandler(ctx, sessionManager));

        // 手动保存
        server.createContext("/api/save", new SaveApiHandler(ctx, sessionManager));

        // 压缩上下文
        server.createContext("/api/compact", new CompactApiHandler(ctx, sessionManager));

        // 硬约束（Pins）
        server.createContext("/api/pins", new PinsApiHandler(ctx, sessionManager));

        // 消息历史
        server.createContext("/api/messages", new MessagesApiHandler(ctx, sessionManager));

        // 根节点工作区
        server.createContext("/api/roots", new RootsApiHandler(ctx, sessionManager));

        // 工具
        server.createContext("/api/tools", new ToolsApiHandler(ctx, sessionManager));

        // Context (new)
        server.createContext("/api/context",
                new ContextApiHandler(ctx.getContextSessionManager(),
                        ctx.getBranchContextRenderer(),
                        ctx.getDataManager(),
                        ctx.getSessionManager()));
    }
}
