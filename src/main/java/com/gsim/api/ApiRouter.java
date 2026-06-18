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

    public ApiRouter(HttpServer server, ApplicationContext ctx, EventBus eventBus) {
        this.server = server;
        this.ctx = ctx;
        this.eventBus = eventBus;
    }

    /**
     * 注册所有路由。
     */
    public void registerAll() {
        // 状态
        server.createContext("/api/status", new StatusApiHandler(ctx));

        // 命令
        server.createContext("/api/command", new CommandApiHandler(ctx, eventBus));
        server.createContext("/api/command/stream", new StreamCommandHandler(ctx, eventBus));

        // Campaign / Turn / Action
        server.createContext("/api/campaigns", new CampaignsApiHandler(ctx, eventBus));

        // Import
        server.createContext("/api/import", new ImportApiHandler(ctx, eventBus));

        // SearchDB
        server.createContext("/api/searchdb", new SearchDbApiHandler(ctx, eventBus));

        // Logs & Outputs
        server.createContext("/api/logs", new LogsOutputsApiHandler(ctx));
        server.createContext("/api/outputs", new LogsOutputsApiHandler(ctx));

        // Branches
        server.createContext("/api/branches", new BranchesApiHandler(ctx, eventBus));
    }
}
