package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.cache.CacheSession;
import com.gsim.commands.AgentCommand;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.LlmCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
import com.gsim.interaction.ConsoleInteractionAdapter;
import com.gsim.tool.ToolRegistry;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.tool.CreateCheckpointTool;
import com.gsim.worldinfo.tool.NodeCreateTool;
import com.gsim.worldinfo.tool.NodeGotoParentTool;
import com.gsim.worldinfo.tool.NodeListTool;
import com.gsim.worldinfo.tool.NodeStatusTool;
import com.gsim.worldinfo.tool.NodeSwitchTool;
import com.gsim.worldinfo.tool.QueryCheckpointTool;
import com.gsim.worldinfo.tool.QueryElementTool;
import com.gsim.worldinfo.tool.QueryKeywordTool;
import com.gsim.worldinfo.tool.QueryNodeTool;
import com.gsim.worldinfo.tool.WriteElementTool;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * GSimulator 应用启动器。
 * 负责依赖注入、REPL 启动。
 */
public class GSimulatorApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GSimulatorApplication.class);

    private final ApplicationContext ctx;
    private final ConsoleInteractionAdapter adapter;
    private final AppConfig config;
    private final boolean cliMode;
    private final boolean httpMode;
    private final boolean webuiMode;
    private final com.gsim.webui.WebUiServer webUiServer;
    private com.gsim.webui.CliWebSocketServer cliWsServer;
    private com.gsim.agent.CompositeAgentProgressSink compositeSink;
    private com.gsim.agent.core.AgentFactory agentFactory;

    // -- Bootstrap result wiring --
    private OrchestratorAgent orchestrator;
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private Path worldsDir;
    private WorldCommand worldCommand;
    private NodeCommand nodeCommand;
    private ChatCommand chatCommand;
    private com.gsim.compact.CacheCompactor cacheCompactor;
    private com.gsim.commands.CompactCommand compactCommand;

    /** 当前活跃的 world ID（供 world_list 等工具使用）。 */
    private final java.util.concurrent.atomic.AtomicReference<String> activeWorldId =
            new java.util.concurrent.atomic.AtomicReference<>("default");

    public GSimulatorApplication(AppConfig config) {
        this(config, true, false, false);
    }

    public GSimulatorApplication(AppConfig config, boolean cliMode, boolean httpMode) {
        this(config, cliMode, httpMode, false);
    }

    public GSimulatorApplication(AppConfig config, boolean cliMode, boolean httpMode, boolean webuiMode) {
        this(config, cliMode, httpMode, webuiMode, null);
    }

    public GSimulatorApplication(AppConfig config, boolean cliMode, boolean httpMode, boolean webuiMode,
                                  Bootstrap.BootstrapResult bootResult) {
        this.config = config;
        this.cliMode = cliMode;
        this.httpMode = httpMode;
        this.webuiMode = webuiMode;
        this.ctx = new ApplicationContext(config);
        this.worldsDir = config.worldsDir();

        // Store BootstrapResult data
        if (bootResult != null) {
            this.worldInfo = bootResult.worldInfo();
            this.activeCache = bootResult.activeCache();
            this.activeWorldId.set(bootResult.worldId());
            // Wire into ApplicationContext so PageHandler/WebUI see the active world
            ctx.setActiveRootId(bootResult.worldId());
        }

        ToolRegistry toolRegistry = ctx.getToolRegistry();

        // 初始化 Cache 根目录（peer to worldsDir）
        Path cachesRoot = worldsDir.resolveSibling("caches");
        com.gsim.cache.CacheStore.setCachesRoot(cachesRoot);
        // 迁移旧缓存（如有）
        Path oldCachesDir = worldsDir.resolve("default").resolve("caches");
        Path newCachesDir = cachesRoot.resolve("default");
        if (java.nio.file.Files.isDirectory(oldCachesDir)
                && !java.nio.file.Files.isDirectory(newCachesDir)) {
            try {
                java.nio.file.Files.createDirectories(cachesRoot);
                java.nio.file.Files.move(oldCachesDir, newCachesDir);
                log.info("Migrated caches: {} -> {}", oldCachesDir, newCachesDir);
            } catch (Exception e) {
                log.warn("Failed to migrate caches: {}", e.getMessage());
            }
        }

        // 创建 CLI 适配器（命令稍后注入）
        this.adapter = new ConsoleInteractionAdapter(null, ctx.getInteractionSession(),
                config.getDataDir());

        // 注册 Agent 工具
        registerAgentTools(toolRegistry);

        // Node change callback — 重建 WorldInformation 以反映节点变更
        Runnable onNodeChanged = () -> {
            if (worldInfo == null) return;
            try {
                String wid = worldInfo.worldId();
                var active = com.gsim.worldinfo.loader.ActiveStateManager.load(worldsDir, wid);
                String activeNodeId = active != null ? active.nodeId() : worldInfo.activeNodeId();
                var newWi = com.gsim.worldinfo.loader.WorldInfoBuilder.build(worldsDir, wid, activeNodeId);
                this.worldInfo = newWi;
                log.info("WorldInformation rebuilt after node change: world={} activeNode={}",
                        wid, activeNodeId);
            } catch (Exception e) {
                log.error("Failed to rebuild WorldInformation after node change: {}", e.getMessage());
            }
        };

        // 注册 world info + node 管理工具
        registerWorldInfoTools(toolRegistry, onNodeChanged);

        // 创建命令并注入到 adapter
        wireCommands(onNodeChanged);

        // 将静态系统提示词写入缓存（首次启动时，缓存为空）
        initCacheSystemPrompt();

        // 创建 WebUiServer
        com.gsim.webui.WebUiConfig webUiConfig =
                com.gsim.webui.WebUiConfig.from(config);
        this.webUiServer = new com.gsim.webui.WebUiServer(webUiConfig, ctx);

        // 注册 ChatApiHandler（在 WebUiServer start 之前）
        if (chatCommand != null) {
            var chatApiHandler = new com.gsim.webui.handlers.ChatApiHandler(
                    chatCommand,
                    () -> worldInfo,
                    () -> activeCache,
                    s -> this.activeCache = s,
                    worldsDir,
                    () -> worldInfo != null ? worldInfo.worldId() : "default",
                    ctx.getCachesManager(),
                    ctx.getSessionPool());
            webUiServer.registerHandler("/chat", chatApiHandler);
            webUiServer.registerHandler("/api/chat", chatApiHandler);
            log.info("Registered ChatApiHandler on /chat and /api/chat");

            // TimelineApiHandler
            var timelineHandler = new com.gsim.webui.handlers.TimelineApiHandler(
                    () -> worldInfo,
                    worldsDir,
                    () -> worldInfo != null ? worldInfo.worldId() : "default");
            webUiServer.registerHandler("/timeline/data", timelineHandler);
            webUiServer.registerHandler("/timeline/node", timelineHandler);
            webUiServer.registerHandler("/timeline/nodes", timelineHandler);
            log.info("Registered TimelineApiHandler on /timeline/*");

            // LlmApiHandler + AgentApiHandler — 配置管理
            var llmApiHandler = new com.gsim.webui.handlers.LlmApiHandler(
                    new com.gsim.llm.LlmConfigManager(config.getLlmsPath()),
                    ctx.getLlmProviderRegistry());
            webUiServer.registerHandler("/api/llm", llmApiHandler);

            var agentApiHandler = new com.gsim.webui.handlers.AgentApiHandler(
                    new com.gsim.agent.config.AgentConfigManager(
                            agentFactory.store(), config.agentsDir()));
            webUiServer.registerHandler("/api/agents", agentApiHandler);

            var worldApiHandler = new com.gsim.webui.handlers.WorldApiHandler(
                    worldsDir,
                    () -> worldInfo != null ? worldInfo.worldId() : "default");
            webUiServer.registerHandler("/api/worlds", worldApiHandler);

            log.info("Registered LlmApiHandler + AgentApiHandler + WorldApiHandler");
        }
    }

    private void registerAgentTools(ToolRegistry toolRegistry) {
        // Import doc tools
        var importDocService = new com.gsim.importing.ImportDocumentService(config.getImportDir());
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentListTool(importDocService));
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentReadTool(importDocService));
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentSearchTool(importDocService));

        // Agent progress sinks: CLI + EventBus (SSE) + SessionPool (unified async pool)
        var cliProgressSink = new com.gsim.agent.CliAgentProgressSink(System.out, true);
        var eventBusSink = new com.gsim.agent.EventBusAgentProgressSink(ctx.getEventBus());
        var sessionPoolBridge = new com.gsim.session.SessionPoolBridge(
                ctx.getSessionPool(), "default");
        this.compositeSink = new com.gsim.agent.CompositeAgentProgressSink(
                cliProgressSink, eventBusSink, sessionPoolBridge);

        // Tool group manager
        var toolGroupManager = new com.gsim.agent.ToolGroupManager();

        // Orchestrator
        this.orchestrator = new OrchestratorAgent(
                ctx.getLlmManager(), toolRegistry, config.getLlmModel(),
                compositeSink,
                (httpMode || webuiMode) ? new com.gsim.agent.AutoApprovePermissionGate()
                         : new com.gsim.agent.CliToolPermissionGate(),
                toolGroupManager);
        this.orchestrator.setMaxToolRounds(config.getAgentToolLoopMaxRounds());
        this.orchestrator.setStreamEnabled(config.isLlmStreamEnabled());

        adapter.setStreamEnabled(config.isLlmStreamEnabled());

        // MediaWiki search (Wikipedia + any MediaWiki site)
        toolRegistry.register(new com.gsim.tool.MediaWikiSearchTool());

        // Agent control flow tools
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        toolRegistry.register(new com.gsim.agent.tool.ActivateToolGroupsTool(toolGroupManager));

        // Sub-agent tools — 从 agents/ 目录加载配置
        var agentConfigStore = new com.gsim.agent.config.AgentConfigStore();
        agentConfigStore.reload(config.agentsDir());
        this.agentFactory = new com.gsim.agent.core.AgentFactory(
                agentConfigStore, ctx.getLlmProviderRegistry(), toolRegistry,
                compositeSink, config.getLlmModel(),
                worldsDir, () -> worldInfo != null ? worldInfo.worldId() : "default");
        this.orchestrator.registerSubAgentTools(toolRegistry, this.agentFactory);

        // SubAgent cache 管理工具
        var worldIdSupplier = new java.util.function.Supplier<String>() {
            public String get() { return worldInfo != null ? worldInfo.worldId() : "default"; }
        };
        toolRegistry.register(new com.gsim.agent.tool.ListSubAgentCachesTool(
                ctx.getCachesManager(), worldIdSupplier));
        toolRegistry.register(new com.gsim.agent.tool.ViewSubAgentCacheTool(
                ctx.getCachesManager(), worldIdSupplier));
        toolRegistry.register(new com.gsim.agent.tool.ViewSubAgentOutputTool(
                ctx.getCachesManager(), worldIdSupplier));

        // LLM provider 列表 + 动态创建 SubAgent 配置
        toolRegistry.register(new com.gsim.agent.tool.ListLlmProvidersTool(
                ctx.getLlmProviderRegistry()));
        toolRegistry.register(new com.gsim.agent.tool.CreateSubAgentConfigTool(
                config.agentsDir(), agentConfigStore));
        toolRegistry.register(new com.gsim.agent.tool.UpdateSubAgentConfigTool(
                config.agentsDir(), agentConfigStore));

        // ── Skill 管理工具 ──
        Path skillsDir = worldsDir.resolveSibling("skills");
        try {
            java.nio.file.Files.createDirectories(skillsDir);
        } catch (java.io.IOException e) {
            log.warn("Failed to create skills dir: {}", e.getMessage());
        }
        var embeddingClient = ctx.getEmbeddingClient();
        var skillIndex = ctx.getSkillIndex(skillsDir);
        try {
            skillIndex.ensureDir();
        } catch (java.io.IOException e) {
            log.warn("Failed to create embdb dir: {}", e.getMessage());
        }

        toolRegistry.register(new com.gsim.skill.tool.SkillListTool(skillsDir, skillIndex));
        toolRegistry.register(new com.gsim.skill.tool.SkillReadTool(skillsDir));
        toolRegistry.register(new com.gsim.skill.tool.SkillCreateTool(skillsDir));
        toolRegistry.register(new com.gsim.skill.tool.SkillWriteTool(skillsDir));
        toolRegistry.register(new com.gsim.skill.tool.SkillSearchTool(skillIndex, embeddingClient));
        toolRegistry.register(new com.gsim.skill.tool.SkillIndexTool(skillsDir, skillIndex, embeddingClient));

        // ── Cache compactor（按 id="compact" 查找 llms.json 中的 provider）──
        var compactProvider = ctx.getLlmProviderRegistry().get("compact");
        var compactLlm = (compactProvider instanceof com.gsim.llm.LlmManager m) ? m : null;
        if (compactLlm != null) {
            log.info("Using compact LLM provider: id={}", compactLlm.providerId());
        } else {
            compactLlm = ctx.getLlmManager();
            log.info("No 'compact' provider in llms.json, using default LLM for compaction");
        }
        this.cacheCompactor = new com.gsim.compact.CacheCompactor(compactLlm, 4096);

        // Compact Cache 工具（Agent 可调用）
        toolRegistry.register(new com.gsim.agent.tool.CompactCacheTool(
                ctx.getCachesManager(), cacheCompactor, compositeSink,
                worldsDir, () -> worldInfo != null ? worldInfo.worldId() : "default"));

        log.info("Registered 6 skill + 1 compact_cache tools (skillsDir={}, embedding={})",
                skillsDir, embeddingClient != null && embeddingClient.isConfigured() ? "enabled" : "disabled");
    }

    private void registerWorldInfoTools(ToolRegistry toolRegistry, Runnable onNodeChanged) {
        // World management tools — don't depend on WorldInformation being loaded
        toolRegistry.register(new com.gsim.worldinfo.tool.WorldListTool(
                worldsDir, activeWorldId::get));
        toolRegistry.register(new com.gsim.worldinfo.tool.WorldCreateTool(worldsDir));
        toolRegistry.register(new com.gsim.worldinfo.tool.WorldSwitchTool(
                worldsDir, this::switchToWorld));

        if (worldInfo == null) {
            log.warn("WorldInformation not available, skipping world info tool registration");
            return;
        }
        Supplier<WorldInformation> wiSupplier = () -> this.worldInfo;

        // Query tools
        toolRegistry.register(new QueryCheckpointTool(wiSupplier));
        toolRegistry.register(new QueryKeywordTool(wiSupplier));
        toolRegistry.register(new QueryNodeTool(wiSupplier));
        toolRegistry.register(new QueryElementTool(wiSupplier));

        // Write tools
        toolRegistry.register(new WriteElementTool(wiSupplier, worldsDir));
        toolRegistry.register(new CreateCheckpointTool(wiSupplier, worldsDir));

        // Node management tools
        toolRegistry.register(new NodeListTool(wiSupplier));
        toolRegistry.register(new NodeStatusTool(wiSupplier));
        toolRegistry.register(new NodeCreateTool(wiSupplier, worldsDir, onNodeChanged));
        toolRegistry.register(new NodeSwitchTool(wiSupplier, worldsDir, onNodeChanged));
        toolRegistry.register(new NodeGotoParentTool(wiSupplier, worldsDir, onNodeChanged));

        log.info("Registered 14 world info + node + world mgmt tools");
    }

    /**
     * 切换到指定 world：只加载世界数据，不触碰活跃缓存。
     * 返回 null 表示成功，否则返回错误消息。
     */
    private String switchToWorld(String worldId) {
        try {
            // 1. 验证 world 存在
            var meta = com.gsim.worldinfo.loader.WorldIndexManager.loadWorldMeta(worldsDir, worldId);
            if (meta == null) return "World 不存在: " + worldId;

            // 2. 加载目标 world 的 active state
            var active = com.gsim.worldinfo.loader.ActiveStateManager.load(worldsDir, worldId);
            String activeNodeId = active != null ? active.nodeId() : "n0000";

            // 3. Build WorldInformation（不经过 Bootstrap，避免触碰缓存）
            var newWi = com.gsim.worldinfo.loader.WorldInfoBuilder.build(worldsDir, worldId, activeNodeId);
            if (newWi == null) {
                return "Failed to load world: " + worldId;
            }

            // 4. 更新应用状态（worldInfo / activeWorldId，不触碰 activeCache）
            this.worldInfo = newWi;
            this.activeWorldId.set(worldId);
            ctx.setActiveRootId(worldId);

            // 5. 更新缓存中的 nodeId（信息性字段，不影响对话内容）
            if (this.activeCache != null) {
                this.activeCache.setNodeId(activeNodeId);
                com.gsim.cache.CacheStore.save(worldsDir, this.activeCache);
            }

            log.info("[switchToWorld] switched to world={} node={} root={} (cache unchanged: {})",
                    worldId, activeNodeId,
                    worldInfo != null ? worldInfo.rootNodeId() : "?",
                    this.activeCache != null ? this.activeCache.sessionId() : "none");
            return null;
        } catch (Exception e) {
            log.error("[switchToWorld] failed: {}", e.getMessage(), e);
            return e.getMessage();
        }
    }

    /** 更新 orchestrator 的 messageSaver，使其写入当前活跃 world 的缓存目录。
     *  使用缓存自身的 worldId 决定路径，而非外部字段，确保一致性。 */
    private void updateMessageSaver() {
        if (orchestrator == null) return;
        orchestrator.setMessageSaver(msg -> {
            CacheSession s = activeCache;
            if (s != null) {
                com.gsim.cache.CacheStore.appendAndSave(worldsDir, s,
                        msg.toCacheMap());
            }
        });
    }

    private void wireCommands(Runnable onNodeChanged) {
        // Write-through cache saver: every Agent message persisted immediately
        // 使用 activeWorldId（动态读取），而非捕获构造时的固定值
        updateMessageSaver();

        WorldCommand wc = new WorldCommand(worldsDir, this::switchToWorld);
        NodeCommand nc = new NodeCommand(worldsDir, () -> worldInfo, onNodeChanged);
        ChatCommand cc = new ChatCommand(worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default",
                () -> activeCache,
                (userInput, priorMessages) -> orchestrator.run(userInput, priorMessages));
        cc.setCancelCallback(orchestrator::cancel);
        cc.setActiveCacheSetter(s -> {
            this.activeCache = s;
            updateMessageSaver();
        });
        this.worldCommand = wc;
        this.nodeCommand = nc;
        this.chatCommand = cc;

        // Compact command（如果 cacheCompactor 可用）
        if (cacheCompactor != null) {
            this.compactCommand = new com.gsim.commands.CompactCommand(
                    ctx.getCachesManager(), cacheCompactor, compositeSink,
                    (userInput, priorMessages) -> orchestrator.run(userInput, priorMessages),
                    worldsDir,
                    () -> worldInfo != null ? worldInfo.worldId() : "default");
            adapter.setCompactCommand(compactCommand);
        }
        adapter.setNewCommands(wc, nc, cc);

        // Expose commands to ApplicationContext for WebUI handlers
        ctx.setChatCommand(cc);
        ctx.setWorldCommand(wc);
        ctx.setNodeCommand(nc);

        // LLM + Agent config management commands
        var llmConfigManager = new com.gsim.llm.LlmConfigManager(config.getLlmsPath());
        var agentConfigManager = new com.gsim.agent.config.AgentConfigManager(
                agentFactory.store(), config.agentsDir());
        LlmCommand llmCmd = new LlmCommand(llmConfigManager, ctx.getLlmProviderRegistry());
        AgentCommand agentCmd = new AgentCommand(agentConfigManager);
        adapter.setConfigCommands(llmCmd, agentCmd);
        ctx.setLlmCommand(llmCmd);
        ctx.setAgentCommand(agentCmd);

        log.info("Wired /world, /node, /chat, /llm, /agent commands into ConsoleInteractionAdapter");
    }

    /**
     * 将静态系统提示词作为第一条消息写入缓存（仅当缓存为空时执行）。
     * 以后每轮 Agent 运行都会从缓存的 prior messages 中自动加载，无需动态注入。
     */
    private void initCacheSystemPrompt() {
        if (activeCache == null || orchestrator == null) return;
        if (activeCache.messageCount() > 0) return;  // 已有内容，不重复注入

        var orchConfig = (agentFactory != null && agentFactory.store() != null)
                ? agentFactory.store().get("orchestrator")
                : null;
        String sp = orchConfig != null ? orchConfig.fullSystemPrompt() : null;
        if (sp == null || sp.isBlank()) return;

        activeCache.addMessage(java.util.Map.of("role", "system", "content", sp));
        com.gsim.cache.CacheStore.save(worldsDir, activeCache);
        log.info("Prepended static system prompt to cache {} ({} chars)",
                activeCache.sessionId(), sp.length());
    }

    /**
     * 启动应用。
     */
    public void start() throws Exception {
        ctx.initialize();

        if (httpMode) {
            ctx.getApiManager().forceEnable();
            ctx.getApiManager().start();
        }

        if (webuiMode) {
            webUiServer.forceEnable();
            webUiServer.start();
            cliWsServer = new com.gsim.webui.CliWebSocketServer(ctx, 8712, compositeSink);
            cliWsServer.setCommands(worldCommand, nodeCommand, chatCommand);
            try { cliWsServer.start(); } catch (Exception e) {
                log.warn("CLI WebSocket server failed to start: {}", e.getMessage()); }
        }

        // CLI REPL
        if (cliMode) {
            if (!config.isLlmConfigured()) {
                System.out.println();
                System.out.println("⚠️  LLM 未配置。以下功能不可用:");
                System.out.println("   /chat — Agent 对话");
                System.out.println("   /sim  — 推演结算");
                System.out.println();
                System.out.println("编辑 " + config.getLlmsPath() + " 设置 API Key，或设置 LLM_API_KEY 环境变量。");
                System.out.println("当前已从 llms.json 加载 " + ctx.getLlmProviderRegistry().size() + " 个 provider，");
                System.out.println("但 API Key 无效（401 错误）。");
                System.out.println();
            }
            Thread.startVirtualThread(() -> {
                try { adapter.start(); } catch (Exception e) {
                    log.error("CLI REPL crashed: {}", e.getMessage(), e); }
            });
        }

        System.out.println();
        System.out.println("✅ GSimulator 已启动");
        if (cliMode) {
            System.out.println("   CLI REPL:  当前终端（输入 /help）");
        }
        if (httpMode) {
            System.out.println("   HTTP API:  http://" + config.getApiHost() + ":" + config.getApiPort());
        }
        if (webuiMode) {
            System.out.println("   Web GUI:   http://" + config.getWebUiHost() + ":" + config.getWebUiPort());
            System.out.println("   CLI WS:    ws://" + config.getWebUiHost() + ":8712");
        }
        System.out.println();
        Thread.currentThread().join();
    }

    public void stop() {
        if (webUiServer != null) webUiServer.stop();
        if (cliWsServer != null) cliWsServer.stop();
        ctx.shutdown();
    }

    public ApplicationContext getContext() {
        return ctx;
    }
}
