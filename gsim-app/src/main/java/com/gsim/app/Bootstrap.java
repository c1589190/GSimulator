package com.gsim.app;

import com.gsim.core.cache.CacheSession;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.cache.CachesManager;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.core.worldinfo.loader.WorldManager;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates the full startup sequence: load worlds/ → WorldInformation → Cache → Context.
 */
public final class Bootstrap {

    private final Path worldsDir;
    private final Path promptsDir;
    private final CachesManager cachesManager;
    private final WorldManager worldManager;

    // result
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private String worldId;
    private String activeNodeId;

    public Bootstrap(Path worldsDir, Path promptsDir, CachesManager cachesManager) {
        this.worldsDir = worldsDir;
        this.promptsDir = promptsDir;
        this.cachesManager = cachesManager;
        this.worldManager = new WorldManager(worldsDir);
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
            var meta = worldManager.loadMeta(targetWorldId);
            if (meta == null) {
                throw new IllegalArgumentException("World 不存在: " + targetWorldId);
            }
            worldId = targetWorldId;
        } else {
            // 原有逻辑：从列表中选取第一个
            List<WorldIndexManager.WorldEntry> worlds = worldManager.listWorlds();

            if (worlds.isEmpty()) {
                worldId = "default";
                WorldIndexManager.createWorld(worldsDir, worldId, "默认世界");
            } else {
                worldId = worlds.get(0).id();
            }
        }

        // 3. 通过 WorldManager 统一读取世界信息与活跃节点
        //    ActiveState 不再追踪 nodeId — 由 discover 从磁盘扫描推导完整节点集合，
        //    不依赖任何硬编码锚点（此前硬编码 n0000 导致后续回合节点对 MCP 不可见）
        worldInfo = worldManager.loadWorld(worldId);
        if (worldInfo == null) {
            throw new IllegalStateException("Failed to load world: " + worldId);
        }
        activeNodeId = worldInfo.activeNodeId();

        // 5. Load Orchestrator cache — 仅加载指定缓存或新建
        //    不再自动选取最新缓存；调用方（Main CLI / WebUI）负责选择。
        if (selectedSessionId != null && !selectedSessionId.isBlank()) {
            activeCache = cachesManager.loadCache(selectedSessionId);
            if (activeCache == null) {
                System.out.println("⚠️  指定的缓存不存在: " + selectedSessionId + "，将创建新缓存");
            }
        }

        if (activeCache == null) {
            // 新建（无指定缓存 或 加载失败）
            activeCache = cachesManager.createCache("Orchestrator");
            CacheStore.save(activeCache);
        }

        return new BootstrapResult(worldId, activeNodeId, worldInfo, activeCache);
    }

    // -- accessors (for use by Main after boot) --
    public WorldInformation worldInfo() {
        return worldInfo;
    }

    public CacheSession activeCache() {
        return activeCache;
    }

    public String worldId() {
        return worldId;
    }

    // -- result record --
    public record BootstrapResult(
            String worldId, String activeNodeId, WorldInformation worldInfo, CacheSession activeCache) {}
}
