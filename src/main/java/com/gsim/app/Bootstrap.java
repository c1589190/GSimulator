package com.gsim.app;

import com.gsim.cache.CacheInfo;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.cache.CachesManager;
import com.gsim.context.ContextRenderer;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.WorldIndexManager;
import com.gsim.worldinfo.loader.WorldInfoBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full startup sequence: load worlds/ → WorldInformation → Cache → Context.
 */
public final class Bootstrap {

    private final Path worldsDir;
    private final Path promptsDir;
    private final CachesManager cachesManager;

    // result
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private ContextRenderer contextRenderer;
    private String worldId;
    private String activeNodeId;

    public Bootstrap(Path worldsDir, Path promptsDir, CachesManager cachesManager) {
        this.worldsDir = worldsDir;
        this.promptsDir = promptsDir;
        this.cachesManager = cachesManager;
    }

    /** 使用默认缓存选择（最新 cache 或新建）。 */
    public BootstrapResult boot() {
        return boot(null, null);
    }

    /**
     * 启动并选择指定缓存。
     *
     * @param selectedSessionId 选中的 cache sessionId，null 表示自动选择最新或新建。
     */
    public BootstrapResult boot(String selectedSessionId) {
        return boot(selectedSessionId, null);
    }

    /**
     * 启动并选择指定缓存和 world。
     *
     * @param selectedSessionId 选中的 cache sessionId，null 表示自动选择最新或新建。
     * @param targetWorldId     目标 world ID，null 表示自动选择第一个 world。
     */
    public BootstrapResult boot(String selectedSessionId, String targetWorldId) {
        // 1. 确定 worldId
        if (targetWorldId != null && !targetWorldId.isBlank()) {
            var meta = WorldIndexManager.loadWorldMeta(worldsDir, targetWorldId);
            if (meta == null) {
                throw new IllegalArgumentException("World 不存在: " + targetWorldId);
            }
            worldId = targetWorldId;
        } else {
            // 原有逻辑：从列表中选取第一个
            List<WorldIndexManager.WorldEntry> worlds = WorldIndexManager.listWorlds(worldsDir);

            if (worlds.isEmpty()) {
                worldId = "default";
                WorldIndexManager.createWorld(worldsDir, worldId, "默认世界");
            } else {
                worldId = worlds.get(0).id();
            }
        }

        // 3. Read active state
        ActiveStateManager.ActiveState active = ActiveStateManager.load(worldsDir, worldId);
        if (active == null) {
            activeNodeId = "n0000";
        } else {
            activeNodeId = active.nodeId();
        }

        // 4. Build WorldInformation
        worldInfo = WorldInfoBuilder.build(worldsDir, worldId, activeNodeId);
        if (worldInfo == null) {
            throw new IllegalStateException("Failed to load world: " + worldId);
        }

        // 5. Initialize context renderer
        contextRenderer = new ContextRenderer(promptsDir);

        // 6. Load Orchestrator cache — select or create
        if (selectedSessionId != null && !selectedSessionId.isBlank()) {
            // 用户显式选择了某个 cache
            activeCache = cachesManager.loadCache(worldId, selectedSessionId);
            if (activeCache == null) {
                System.out.println("⚠️  指定的缓存不存在: " + selectedSessionId + "，创建新缓存");
            }
        }

        if (activeCache == null) {
            // 自动选择：最新 Orchestrator cache
            List<CacheInfo> orchCaches = cachesManager.listCaches(worldId, "orchestrator");
            if (!orchCaches.isEmpty()) {
                // 取最新的（列表已按 createdAt 降序排列）
                activeCache = cachesManager.loadCache(worldId, orchCaches.get(0).sessionId());
            }
        }

        if (activeCache == null) {
            // 新建
            activeCache = cachesManager.createCache(worldId, "Orchestrator", activeNodeId);
            CacheStore.save(worldsDir, activeCache);
        }

        return new BootstrapResult(worldId, activeNodeId, worldInfo, activeCache, contextRenderer);
    }

    // -- accessors (for use by Main after boot) --
    public WorldInformation worldInfo() { return worldInfo; }
    public CacheSession activeCache() { return activeCache; }
    public ContextRenderer contextRenderer() { return contextRenderer; }
    public String worldId() { return worldId; }

    // -- result record --
    public record BootstrapResult(
        String worldId,
        String activeNodeId,
        WorldInformation worldInfo,
        CacheSession activeCache,
        ContextRenderer contextRenderer
    ) {}
}
