package com.gsim.webui;

import com.gsim.app.ApplicationContext;
import com.gsim.webui.handlers.PageHandler;
import com.gsim.webui.handlers.StaticHandler;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WebUI 独立 HTTP 服务器，端口 8711。
 */
public class WebUiServer {

    private static final Logger log = LoggerFactory.getLogger(WebUiServer.class);

    private final WebUiConfig config;
    private final ApplicationContext ctx;
    private HttpServer server;
    private ExecutorService executor;
    private boolean forceEnabled = false;

    /** 延迟注册的 handler（在 start() 之前添加，start() 时统一注册）。 */
    private final List<Map.Entry<String, HttpHandler>> deferredHandlers = new ArrayList<>();

    public WebUiServer(WebUiConfig config, ApplicationContext ctx) {
        this.config = config;
        this.ctx = ctx;
    }

    public void forceEnable() {
        this.forceEnabled = true;
    }

    public void start() throws IOException {
        if (!config.enabled() && !forceEnabled) {
            log.info("WebUI is disabled. Skipping server start.");
            return;
        }

        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        server = HttpServer.create(address, 0);

        registerHandlers();

        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();

        log.info("WebUI server started on {}:{}", config.host(), config.port());
        System.out.println("🌐 Web GUI: " + config.getBaseUrl());
    }

    private void registerHandlers() {
        server.createContext("/static", new StaticHandler());
        PageHandler pageHandler = new PageHandler(ctx);
        server.createContext("/", pageHandler);

        // 注册延迟添加的 handler（在 start() 之前通过 registerHandler 添加的）
        for (var entry : deferredHandlers) {
            server.createContext(entry.getKey(), entry.getValue());
            log.info("Registered deferred handler: {} -> {}", entry.getKey(),
                    entry.getValue().getClass().getSimpleName());
        }
    }

    /**
     * 注册额外的 HTTP handler。可在 start() 之前调用，也可在 start() 之后（如果 server 已创建）。
     */
    public void registerHandler(String path, HttpHandler handler) {
        if (server != null) {
            server.createContext(path, handler);
            log.info("Registered handler: {} -> {}", path, handler.getClass().getSimpleName());
        } else {
            deferredHandlers.add(Map.entry(path, handler));
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(2);
            server = null;
            log.info("WebUI server stopped.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("WebUI executor did not terminate within 3s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return server != null;
    }
}
