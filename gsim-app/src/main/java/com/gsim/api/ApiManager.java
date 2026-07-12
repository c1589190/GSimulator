package com.gsim.api;

import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.gsim.llm.LlmConfigManager;
import com.gsim.llm.LlmProviderRegistry;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * HTTP API 管理器 — 负责 HttpServer 生命周期和路由注册。
 */
public class ApiManager {

    private static final Logger log = LoggerFactory.getLogger(ApiManager.class);

    private final ApiConfig apiConfig;
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
    private HttpServer server;
    private ExecutorService executor;
    private boolean forceEnabled = false;
    private boolean monitorMode = false;
    private com.gsim.agent.management.AgentsManager agentsManager;
    private com.gsim.agent.management.AgentSseManager agentSseManager;
    private com.gsim.agent.management.AgentCacheStore agentCacheStore;
    private com.gsim.agent.config.AgentConfigManager agentConfigManager;

    public ApiManager(ApiConfig apiConfig, ApplicationContext ctx, EventBus eventBus,
                      Path worldsDir, Path importDir, Supplier<String> activeWorldId,
                      Supplier<com.gsim.doc.DocStore> docStore,
                      LlmConfigManager llmConfigManager, LlmProviderRegistry llmRegistry,
                      com.gsim.agent.management.AgentsManager agentsManager,
                      com.gsim.agent.management.AgentSseManager agentSseManager,
                      com.gsim.agent.management.AgentCacheStore agentCacheStore,
                      com.gsim.agent.config.AgentConfigManager agentConfigManager) {
        this.apiConfig = apiConfig;
        this.ctx = ctx;
        this.eventBus = eventBus;
        this.sessionManager = new SessionManager(ctx);
        this.taskManager = new TaskManager(ctx, sessionManager, eventBus);
        this.worldsDir = worldsDir;
        this.importDir = importDir;
        this.activeWorldId = activeWorldId;
        this.docStore = docStore;
        this.llmConfigManager = llmConfigManager;
        this.llmRegistry = llmRegistry;
        this.agentsManager = agentsManager;
        this.agentSseManager = agentSseManager;
        this.agentCacheStore = agentCacheStore;
        this.agentConfigManager = agentConfigManager;
    }

    /**
     * 启动 HTTP 服务器。
     */
    public void start() throws IOException {
        if (!apiConfig.isEnabled() && !forceEnabled) {
            log.info("HTTP API is disabled. Skipping server start.");
            return;
        }

        InetSocketAddress address = new InetSocketAddress(apiConfig.getHost(), apiConfig.getPort());
        server = HttpServer.create(address, 0);  // 0 = default backlog

        // 注册所有路由
        boolean cliMonitor = ctx.getConfig().isCliMonitorHttpApi();
        ApiRouter router = new ApiRouter(server, ctx, eventBus, sessionManager, taskManager,
                worldsDir, importDir, activeWorldId, docStore,
                llmConfigManager, llmRegistry, monitorMode, cliMonitor,
                agentsManager, agentSseManager, agentCacheStore, agentConfigManager);
        router.registerAll();

        // 使用虚拟线程执行器 (Java 21+)
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.start();
        log.info("HTTP API server started on {}:{}", apiConfig.getHost(), apiConfig.getPort());
        System.out.println("🌐 HTTP API: " + apiConfig.getBaseUrl());
        System.out.println("   GET  /api/status              — 应用状态");
        System.out.println("   POST /api/tasks               — 创建任务（推荐）");
        System.out.println("   GET  /api/tasks               — 任务列表");
        System.out.println("   GET  /api/tasks/{id}          — 任务状态");
        System.out.println("   GET  /api/tasks/{id}/events   — SSE 任务事件流");
        System.out.println("   POST /api/tasks/{id}/cancel   — 取消任务");
        System.out.println("   POST /api/command             — 执行命令（旧）");
        System.out.println("   POST /api/command/stream      — SSE 流式命令（旧）");
        System.out.println("   POST /api/import/url          — URL 导入");
    }

    /**
     * 停止 HTTP 服务器。
     */
    public void stop() {
        if (server != null) {
            server.stop(2);  // 最多等 2 秒
            log.info("HTTP API server stopped.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    /**
     * 强制启用 HTTP API（即使配置中 api.enabled=false）。
     */
    public void forceEnable() {
        this.forceEnabled = true;
    }

    /**
     * 启用监控模式（终端实时打印 HTTP 请求/响应）。
     */
    public void setMonitorMode(boolean enabled) {
        this.monitorMode = enabled;
    }

    /** 延迟注入 Agent 管理器（由 GSimulatorApplication 在 wiring 完成后调用）。
     *  必须在 start() 之前调用。 */
    public void injectAgentManagers(
            com.gsim.agent.management.AgentsManager agentsManager,
            com.gsim.agent.management.AgentSseManager agentSseManager,
            com.gsim.agent.management.AgentCacheStore agentCacheStore,
            com.gsim.agent.config.AgentConfigManager agentConfigManager) {
        this.agentsManager = agentsManager;
        this.agentSseManager = agentSseManager;
        this.agentCacheStore = agentCacheStore;
        this.agentConfigManager = agentConfigManager;
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : apiConfig.getPort();
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }
}
