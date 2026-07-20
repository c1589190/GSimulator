package com.gsim.mcp;

import com.gsim.agent.AgentConfigStore;
import com.gsim.agent.tool.CreateSubAgentConfigTool;
import com.gsim.agent.tool.UpdateSubAgentConfigTool;
import com.gsim.cache.tool.CacheEditTool;
import com.gsim.cache.tool.CacheGetTool;
import com.gsim.cache.tool.CacheListTool;
import com.gsim.doc.tool.DocCreateTool;
import com.gsim.doc.tool.DocDeleteTool;
import com.gsim.doc.tool.DocListTool;
import com.gsim.doc.tool.DocReadTool;
import com.gsim.doc.tool.DocSearchTool;
import com.gsim.doc.tool.DocWriteTool;
import com.gsim.importing.ImportDocumentService;
import com.gsim.importing.tool.ImportDocumentListTool;
import com.gsim.importing.tool.ImportDocumentReadTool;
import com.gsim.importing.tool.ImportDocumentSearchTool;
import com.gsim.tool.MediaWikiSearchTool;
import com.gsim.tool.ToolRegistry;
import com.gsim.worldinfo.tool.WorldCreateTool;
import com.gsim.worldinfo.tool.WorldDeleteTool;
import com.gsim.worldinfo.tool.WorldListTool;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a minimal {@link ToolRegistry} populated with standalone-compatible
 * core tools for MCP use without requiring a full {@code GSimulatorApplication}.
 *
 * @deprecated Use {@code com.gsim.Main --no-cli} which creates the full
 *             {@code GSimulatorApplication} and registers all tools via
 *             {@code ToolRegistry}. This minimal registry will be removed.
 */
@Deprecated
public final class McpStandaloneToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpStandaloneToolRegistry.class);

    private McpStandaloneToolRegistry() {}

    /**
     * Creates a ToolRegistry populated with file-backed core tools.
     *
     * @param worldsDir the GSim worlds directory
     * @param importDir the import documents directory (may be null)
     * @return a populated ToolRegistry
     */
    public static ToolRegistry create(Path worldsDir, Path importDir) {
        ToolRegistry registry = new ToolRegistry();

        // ── World tools ──
        registry.register(new WorldListTool(worldsDir, () -> "default"));
        registry.register(new WorldCreateTool(worldsDir));
        registry.register(new WorldDeleteTool(worldsDir));

        // ── Import doc tools ──
        if (importDir == null) {
            importDir = Path.of("import");
        }
        var importDocService = new ImportDocumentService(importDir);
        registry.register(new ImportDocumentListTool(importDocService));
        registry.register(new ImportDocumentReadTool(importDocService));
        registry.register(new ImportDocumentSearchTool(importDocService));

        // ── Doc CRUD tools (file-backed) ──
        Path docsDir = worldsDir.resolveSibling("docs");
        var docStore = new com.gsim.doc.DocStore(docsDir);
        try {
            docStore.init();
        } catch (java.io.IOException e) {
            log.warn("Failed to init DocStore: {}", e.getMessage());
        }
        var docCacheManager = new com.gsim.doc.DocCacheManager(docsDir.resolve(".cache"));
        try {
            docCacheManager.init();
        } catch (java.io.IOException e) {
            log.warn("Failed to init DocCacheManager: {}", e.getMessage());
        }
        registry.register(new DocListTool(docStore));
        registry.register(new DocReadTool(docStore, docCacheManager));
        registry.register(new DocCreateTool(docStore, docCacheManager, null));
        registry.register(new DocWriteTool(docStore, docCacheManager, null));
        registry.register(new DocSearchTool(docStore, null, null));
        registry.register(new DocDeleteTool(docStore));

        // ── Cache tools (file-backed) ──
        registry.register(new CacheListTool(docsDir));
        registry.register(new CacheGetTool(docsDir));
        registry.register(new CacheEditTool(docsDir));

        // ── LLM tools ──
        Path llmsPath = worldsDir.resolveSibling("llms.json");
        registry.register(new com.gsim.agent.tool.ListLlmProvidersTool(com.gsim.llm.LlmProviderRegistry.fromConfig(
                new com.gsim.llm.LlmsConfigLoader(llmsPath).load().file())));

        // ── Agent config tools ──
        Path agentsDir = worldsDir.resolveSibling("agents");
        var agentConfigStore = new AgentConfigStore();
        try {
            agentConfigStore.reload(agentsDir);
        } catch (Exception e) {
            log.warn("Failed to load agent configs: {}", e.getMessage());
        }
        registry.register(new CreateSubAgentConfigTool(agentsDir, agentConfigStore));
        registry.register(new UpdateSubAgentConfigTool(agentsDir, agentConfigStore));
        registry.register(new com.gsim.agent.tool.ListAgentConfigTool(agentConfigStore));
        registry.register(new com.gsim.agent.tool.DeleteAgentConfigTool(agentConfigStore, agentsDir));

        // ── Misc tools ──
        registry.register(new MediaWikiSearchTool());
        registry.register(new com.gsim.tool.StatusTool(worldsDir, () -> registry));

        // ── Ref resolver (import doc refs) ──
        var refTool = new com.gsim.ref.ResolveRefTool(worldsDir, "default", importDir, docStore, docCacheManager);
        registry.register(refTool);

        log.info(
                "McpStandaloneToolRegistry created with {} tools",
                registry.all().size());
        return registry;
    }
}
