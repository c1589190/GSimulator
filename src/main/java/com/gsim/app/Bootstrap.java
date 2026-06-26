package com.gsim.app;

import com.gsim.cache.CacheSession;
import com.gsim.cache.CacheStore;
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

    // result
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private ContextRenderer contextRenderer;
    private String worldId;
    private String activeNodeId;

    public Bootstrap(Path worldsDir, Path promptsDir) {
        this.worldsDir = worldsDir;
        this.promptsDir = promptsDir;
    }

    public BootstrapResult boot() {
        // 1. List worlds
        List<WorldIndexManager.WorldEntry> worlds = WorldIndexManager.listWorlds(worldsDir);

        // 2. If no worlds, auto-create default
        if (worlds.isEmpty()) {
            worldId = "default";
            WorldIndexManager.createWorld(worldsDir, worldId, "默认世界");
        } else {
            worldId = worlds.get(0).id();
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

        // 6. Load Orchestrator cache, or create new
        String orchestratorSession = ActiveStateManager.orchestratorSession(active);
        if (orchestratorSession != null) {
            activeCache = CacheStore.load(worldsDir, worldId, orchestratorSession);
        }

        if (activeCache == null) {
            activeCache = CacheStore.createNew(worldsDir, worldId, "Orchestrator", activeNodeId);
            // Inject initial system prompt
            String systemPrompt = contextRenderer.renderSystemPrompt("OrchestratorAgent", worldInfo);
            activeCache.addMessage(Map.of("role", "system", "content", systemPrompt));
            CacheStore.save(worldsDir, worldId, activeCache);
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
