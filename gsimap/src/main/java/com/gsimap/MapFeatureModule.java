package com.gsimap;

import com.gsim.app.AppConfig;
import com.gsim.platform.FeatureContext;
import com.gsim.platform.FeatureModule;
import com.gsimap.http.GsimapHttpServer;
import com.gsimap.service.MapService;
import com.gsimap.tool.GsimapToolRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gsimap 功能模块 -- 六角格地图功能包。
 *
 * <p>实现 {@link FeatureModule}，向核心 {@code ToolRegistry} 注册 25 个
 * 地图 MCP 工具，并启动地图编辑器 HTTP 服务器（默认 :8711）。默认启用。
 */
public final class MapFeatureModule implements FeatureModule {

    private static final Logger log = LoggerFactory.getLogger(MapFeatureModule.class);

    private MapService mapService;
    private GsimapHttpServer httpServer;

    @Override
    public String name() {
        return "gsimap";
    }

    @Override
    public boolean isEnabled(AppConfig config) {
        // 地图功能包默认始终启用
        return true;
    }

    @Override
    public void register(FeatureContext ctx) {
        this.mapService = new MapService(ctx.worldsDir());
        GsimapToolRegistrar.registerAll(ctx.toolRegistry(), mapService);
        log.info("[gsimap] registered {} map tools", countMapTools(ctx));
    }

    @Override
    public void start() {
        int port = Integer.parseInt(
                System.getProperty("gsimap.port", System.getenv().getOrDefault("GSIMAP_PORT", "8711")));
        this.httpServer = new GsimapHttpServer(port, mapService);
        try {
            this.httpServer.start();
            log.info("[gsimap] map editor UI: http://127.0.0.1:{}", port);
        } catch (java.io.IOException e) {
            log.error("[gsimap] failed to start map editor UI: {}", e.getMessage());
            this.httpServer = null;
        }
    }

    @Override
    public void stop() {
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    /** 统计已注册的地图工具数量（用于启动日志）。 */
    private int countMapTools(FeatureContext ctx) {
        int count = 0;
        for (var tool : ctx.toolRegistry().all().values()) {
            if (tool.name().startsWith("gsimap_")) {
                count++;
            }
        }
        return count;
    }
}
