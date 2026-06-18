package com.gsim.api;

import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API 管理器 — 负责 HttpServer 生命周期和路由注册。
 *
 * <p>使用方式：
 * <pre>
 *   ApiManager apiManager = new ApiManager(config, ctx, eventBus);
 *   apiManager.start();
 *   // ...
 *   apiManager.stop();
 * </pre>
 */
public class ApiManager {

    private static final Logger log = LoggerFactory.getLogger(ApiManager.class);

    private final ApiConfig apiConfig;
    private final ApplicationContext ctx;
    private final EventBus eventBus;
    private HttpServer server;
    private ExecutorService executor;
    private boolean forceEnabled = false;

    public ApiManager(ApiConfig apiConfig, ApplicationContext ctx, EventBus eventBus) {
        this.apiConfig = apiConfig;
        this.ctx = ctx;
        this.eventBus = eventBus;
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
        ApiRouter router = new ApiRouter(server, ctx, eventBus);
        router.registerAll();

        // 使用虚拟线程执行器 (Java 21+)
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        server.start();
        log.info("HTTP API server started on {}:{}", apiConfig.getHost(), apiConfig.getPort());
        System.out.println("🌐 HTTP API: " + apiConfig.getBaseUrl());
        System.out.println("   GET  /api/status          — 应用状态");
        System.out.println("   POST /api/command          — 执行命令");
        System.out.println("   POST /api/command/stream   — SSE 流式命令");
        System.out.println("   GET  /api/campaigns        — Campaign 管理");
        System.out.println("   POST /api/import/url       — URL 导入");
        System.out.println("   GET  /api/searchdb         — 搜索知识库");
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
     * 强制启用 API（覆盖配置中的 enabled=false）。
     * 当 CLI 指定 --http 时调用。
     */
    public void forceEnable() {
        this.forceEnabled = true;
        log.info("API force-enabled via --http CLI flag");
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : apiConfig.getPort();
    }
}
