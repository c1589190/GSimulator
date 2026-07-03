package com.gsim.api;

import com.gsim.api.handlers.*;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.llm.LlmConfigManager;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.worldinfo.manager.WorldManager;
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
    private final Supplier<com.gsim.doc.DocStore> docStore;
    private final LlmConfigManager llmConfigManager;
    private final LlmProviderRegistry llmRegistry;
    private final WorldManager worldManager;

    public ApiRouter(HttpServer server, ApplicationContext ctx, EventBus eventBus,
                     SessionManager sessionManager, TaskManager taskManager,
                     Path worldsDir, Path importDir, Supplier<String> activeWorldId,
                     Supplier<com.gsim.doc.DocStore> docStore,
                     LlmConfigManager llmConfigManager, LlmProviderRegistry llmRegistry) {
        this.server = server;
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.taskManager = taskManager;
        this.worldsDir = worldsDir;
        this.importDir = importDir;
        this.activeWorldId = activeWorldId;
        this.docStore = docStore;
        this.llmConfigManager = llmConfigManager;
        this.llmRegistry = llmRegistry;
        this.worldManager = new WorldManager(worldsDir);
    }

    public void registerAll() {
        // ── 基础 ──
        register("/api/status", new StatusApiHandler(ctx));
        register("/api/tools", new ToolsApiHandler(ctx, sessionManager));

        // ── 命令（旧接口，保留兼容）──
        register("/api/command", new CommandApiHandler(ctx, eventBus, sessionManager));
        register("/api/command/stream",
                new StreamCommandHandler(ctx, eventBus, sessionManager, taskManager));

        // ── 任务 API ──
        register("/api/tasks", new TasksApiHandler(taskManager, sessionManager, eventBus));

        // ── Import ──
        register("/api/import", new ImportApiHandler(ctx, eventBus));

        // ── Logs ──
        register("/api/logs", new LogsOutputsApiHandler(ctx));
        register("/api/outputs", new LogsOutputsApiHandler(ctx));
        register("/api/logs/operations", new OperationsLogHandler());

        // ── World API v2（统一层级入口）──
        register("/api/world", new WorldApiV2Handler(worldManager, worldsDir));

        // ── World API v1（向后兼容）──
        register("/api/world-manager", new WorldManagerApiHandler(worldsDir, activeWorldId));
        register("/api/world-manager-data", new WorldDataApiHandler(worldsDir));

        // ── 文档管理 ──
        register("/api/documents", new DocumentsApiHandler(importDir, eventBus, ctx));
        register("/api/docs", new DocsHandler(docStore, worldsDir));

        // ── 统一引用 + 搜索 ──
        register("/api/ref", new RefApiHandler(worldsDir, activeWorldId, importDir, docStore));
        register("/api/search", new UnifiedSearchHandler(worldsDir, activeWorldId, importDir, docStore));

        // ── LLM Provider 管理 ──
        register("/api/llm", new LlmApiHandler(llmConfigManager, llmRegistry));

        // ── 文本缓存管理 ──
        register("/api/caches", new CachesHandler(worldsDir));

        // ── Roots + Skills（直调 Manager）──
        register("/api/roots", new RootsApiHandler(worldManager, worldsDir, activeWorldId));
        register("/api/skills", new SkillsApiHandler(docStore));
    }

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
