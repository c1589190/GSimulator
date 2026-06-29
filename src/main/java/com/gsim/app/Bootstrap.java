package com.gsim.app;

import com.gsim.cache.CacheInfo;
import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
import com.gsim.cache.CachesManager;
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

        // 5. Load Orchestrator cache — 仅加载指定缓存或新建
        //    不再自动选取最新缓存；调用方（Main CLI / WebUI）负责选择。
        if (selectedSessionId != null && !selectedSessionId.isBlank()) {
            activeCache = cachesManager.loadCache(worldId, selectedSessionId);
            if (activeCache == null) {
                System.out.println("⚠️  指定的缓存不存在: " + selectedSessionId + "，将创建新缓存");
            }
        }

        if (activeCache == null) {
            // 新建（无指定缓存 或 加载失败）
            activeCache = cachesManager.createCache(worldId, "Orchestrator", activeNodeId);
            CacheStore.save(worldsDir, activeCache);
        }

        return new BootstrapResult(worldId, activeNodeId, worldInfo, activeCache);
    }

    // -- accessors (for use by Main after boot) --
    public WorldInformation worldInfo() { return worldInfo; }
    public CacheSession activeCache() { return activeCache; }
    public String worldId() { return worldId; }

    // -- result record --
    public record BootstrapResult(
        String worldId,
        String activeNodeId,
        WorldInformation worldInfo,
        CacheSession activeCache
    ) {}
}
