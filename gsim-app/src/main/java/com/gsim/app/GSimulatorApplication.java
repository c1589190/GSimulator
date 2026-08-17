package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.agent.bridge.AgentBridge;
import com.gsim.agent.bridge.CoreToolContext;
import com.gsim.agent.bridge.WorldInfoToolContext;
import com.gsim.agentlib.tool.ToolRegistry;
import com.gsim.commands.AgentCommand;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.LlmCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
import com.gsim.core.cache.CacheSession;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldManager;
import com.gsim.interaction.ConsoleInteractionAdapter;
import java.nio.file.Path;

/**
 * GSimulator 应用启动器。
 * 负责依赖注入、REPL 启动。
 */
public class GSimulatorApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GSimulatorApplication.class);

    private final ApplicationContext ctx;
    private final ConsoleInteractionAdapter adapter;
    private final AppConfig config;
    private final boolean interactive;
    private final boolean agentEnabled;
    private final com.gsim.webui.WebUiServer webUiServer;
    private com.gsim.webui.CliWebSocketServer cliWsServer;
    private com.gsim.core.event.CompositeAgentProgressSink compositeSink;
    private com.gsim.agent.core.AgentFactory agentFactory;

    // -- Bootstrap result wiring --
    private OrchestratorAgent orchestrator;
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private Path worldsDir;
    private WorldCommand worldCommand;
    private NodeCommand nodeCommand;
    private ChatCommand chatCommand;
    private com.gsim.core.compact.CacheCompactor cacheCompactor;
    private com.gsim.commands.CompactCommand compactCommand;
    private com.gsim.core.doc.DocCacheManager docCacheManager;
    private com.gsim.core.config.CoreConfig coreConfig;
    private com.gsim.core.ref.InlineRefResolver inlineRefResolver;
    private final WorldManager worldManager;

    /** 当前活跃的 world ID（供 world_list 等工具使用）。 */
    private final java.util.concurrent.atomic.AtomicReference<String> activeWorldId =
            new java.util.concurrent.atomic.AtomicReference<>("default");

    /** Backward-compat for tests. */
    public GSimulatorApplication(AppConfig config) {
        this(config, null, true);
    }

    /**
     * Creates the application assembly with the agent runtime enabled (backward-compat).
     *
     * @param config      application configuration
     * @param bootResult  bootstrap result (world info, active cache); may be null for tests
     * @param interactive true for CLI mode (permission gate asks user), false for MCP/headless
     */
    public GSimulatorApplication(AppConfig config, Bootstrap.BootstrapResult bootResult, boolean interactive) {
        this(config, bootResult, interactive, true);
    }

    /**
     * Creates the application assembly.
     *
     * @param config       application configuration
     * @param bootResult   bootstrap result (world info, active cache); may be null for tests
     * @param interactive  true for CLI mode (permission gate asks user), false for MCP/headless
     * @param agentEnabled true to wire the agent runtime (orchestrator, agent tools, CLI, WebUI);
     *                     false for MCP-only mode (World/Doc/Gsimap management without agent)
     */
    public GSimulatorApplication(
            AppConfig config, Bootstrap.BootstrapResult bootResult, boolean interactive, boolean agentEnabled) {
        this.config = config;
        this.interactive = interactive;
        this.agentEnabled = agentEnabled;
        this.ctx = new ApplicationContext(config);
        this.worldsDir = config.worldsDir();
        this.worldManager = new WorldManager(this.worldsDir);

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
        com.gsim.core.cache.CacheStore.setCachesRoot(cachesRoot);
        // 迁移旧缓存（如有）
        Path oldCachesDir = worldsDir.resolve("default").resolve("caches");
        Path newCachesDir = cachesRoot.resolve("default");
        if (java.nio.file.Files.isDirectory(oldCachesDir) && !java.nio.file.Files.isDirectory(newCachesDir)) {
            try {
                java.nio.file.Files.createDirectories(cachesRoot);
                java.nio.file.Files.move(oldCachesDir, newCachesDir);
                log.info("Migrated caches: {} -> {}", oldCachesDir, newCachesDir);
            } catch (Exception e) {
                log.warn("Failed to migrate caches: {}", e.getMessage());
            }
        }

        // 创建 CLI 适配器（命令稍后注入）—— 仅 agent 模式
        this.adapter = agentEnabled
                ? new ConsoleInteractionAdapter(null, ctx.getInteractionSession(), config.getDataDir())
                : null;

        // DocCacheManager（doc 工具与 worldinfo 工具共用，需提前创建）
        Path docsDir = worldsDir.resolveSibling("docs");
        this.docCacheManager = new com.gsim.core.doc.DocCacheManager(docsDir.resolve(".cache"));
        try {
            this.docCacheManager.init();
        } catch (java.io.IOException e) {
            log.warn("Failed to init DocCacheManager: {}", e.getMessage());
        }

        // 组合进度 sink：agent 模式追加 CLI + EventBus sink，MCP 模式仅 SessionPool
        var sessionPoolBridge = new com.gsim.core.session.SessionPoolBridge(ctx.getSessionPool(), "default");
        if (agentEnabled) {
            var jlineTerminal = adapter.getJlineTerminal();
            var cliProgressSink = jlineTerminal != null
                    ? com.gsim.agent.CliAgentProgressSink.fromJlineTerminal(jlineTerminal)
                    : new com.gsim.agent.CliAgentProgressSink(System.out, true);
            var eventBusSink = new com.gsim.agent.EventBusAgentProgressSink(ctx.getEventBus());
            this.compositeSink = new com.gsim.core.event.CompositeAgentProgressSink(
                    cliProgressSink, eventBusSink, sessionPoolBridge);
        } else {
            this.compositeSink = new com.gsim.core.event.CompositeAgentProgressSink(sessionPoolBridge);
        }

        // ── 核心业务对象构造（原 registerCoreTools 内，整体上移）──

        // Import doc tools
        var importDocService = new com.gsim.core.importing.ImportDocumentService(config.getImportDir());

        // DocCacheManager 需在 doc 工具注册前创建（T0.1 遗留的双重初始化，幂等，保持现状）
        this.docCacheManager = new com.gsim.core.doc.DocCacheManager(docsDir.resolve(".cache"));
        try {
            this.docCacheManager.init();
        } catch (java.io.IOException e) {
            log.warn("Failed to init DocCacheManager: {}", e.getMessage());
        }

        // ── 统一文档管理工具（docs 工具组）──
        var docStore = ctx.getDocStore(docsDir);
        try {
            docStore.init();
            // 迁移旧 skills/ 目录（如有）
            Path oldSkillsDir = worldsDir.resolveSibling("skills");
            if (java.nio.file.Files.isDirectory(oldSkillsDir)) {
                int migrated = docStore.migrateFromSkills(oldSkillsDir);
                if (migrated > 0) {
                    log.info("Migrated {} skills from {} to docs/", migrated, oldSkillsDir);
                }
            }
            // 确保 agent-api-guide 存在（首次启动自动创建）
            if (docStore.get("agent-api-guide") == null) {
                var guideResource = getClass().getClassLoader().getResourceAsStream("gsim/agent-api-guide.md");
                if (guideResource != null) {
                    String guideContent =
                            new String(guideResource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    docStore.create(
                            "agent-api-guide",
                            com.gsim.core.doc.DocType.OTHER,
                            "Agent API 引导手册",
                            guideContent,
                            java.util.List.of("guide", "api", "agent"));
                    log.info("Auto-created agent-api-guide doc");
                }
            }
        } catch (java.io.IOException e) {
            log.warn("Failed to init DocStore: {}", e.getMessage());
        }
        var embeddingClient = ctx.getEmbeddingClient();
        var docIndex = ctx.getSkillIndex(docsDir); // SkillIndex reused for docs embdb
        try {
            docIndex.ensureDir();
        } catch (java.io.IOException e) {
            log.warn("Failed to create embdb dir: {}", e.getMessage());
        }

        // ── CoreConfig（主链视图）+ 内嵌引用解析器（write_element 阈值/引用展开用）──
        // CoreConfig 从 ConfigLoader 主链构造：AppConfig 已解析 core.doc.* 键，此处取其类型化值
        this.coreConfig = com.gsim.core.config.CoreConfig.from(
                java.util.Map.of(
                        com.gsim.core.config.CoreConfig.STAGING_THRESHOLD,
                        String.valueOf(config.stagingThreshold()),
                        com.gsim.core.config.CoreConfig.QUERY_STAGING_THRESHOLD,
                        String.valueOf(config.queryStagingThreshold())),
                java.util.Map.of());
        this.inlineRefResolver = new com.gsim.core.ref.InlineRefResolver(docStore, importDocService);

        // 注册核心工具（World/Doc/Import，始终注册）—— 经 gsim-agent 桥接层
        AgentBridge.registerCoreTools(
                toolRegistry,
                new CoreToolContext(
                        worldsDir,
                        config.getImportDir(),
                        importDocService,
                        docStore,
                        docCacheManager,
                        docIndex,
                        embeddingClient,
                        activeWorldId::get,
                        compositeSink));

        // 注册 Agent 工具（仅 agent 模式）
        if (agentEnabled) {
            registerAgentTools(toolRegistry);
        }

        // Node change callback — 从磁盘重新 discover 完整节点集合（宽松加载：断链/孤儿全保留）
        Runnable onNodeChanged = () -> {
            if (worldInfo == null) return;
            try {
                String wid = worldInfo.worldId();
                var newWi = worldManager.loadWorld(wid);
                if (newWi != null) {
                    this.worldInfo = newWi;
                }
                log.info(
                        "WorldInformation rebuilt after node change: world={} nodes={}",
                        wid,
                        newWi != null ? newWi.branchChain().size() : -1);
            } catch (Exception e) {
                log.error("Failed to rebuild WorldInformation after node change: {}", e.getMessage());
            }
        };

        // 注册 world info + node 管理工具 —— 经 gsim-agent 桥接层
        // worldInfo 为可变字段，以 () -> worldInfo 惰性供应，节点创建/世界切换重建后对工具可见（与迁移前 this.worldInfo 动态读语义等价）
        AgentBridge.registerWorldInfoTools(
                toolRegistry,
                new WorldInfoToolContext(
                        worldsDir,
                        () -> worldInfo,
                        activeWorldId::get,
                        docCacheManager,
                        onNodeChanged,
                        docStore,
                        inlineRefResolver,
                        coreConfig));

        if (agentEnabled) {
            // 创建命令并注入到 adapter
            wireCommands(onNodeChanged);

            // 将静态系统提示词写入缓存（首次启动时，缓存为空）
            initCacheSystemPrompt();
        }

        // 创建 WebUiServer（仅 agent 模式）
        this.webUiServer =
                agentEnabled ? new com.gsim.webui.WebUiServer(com.gsim.webui.WebUiConfig.from(config), ctx) : null;

        // 注册 ChatApiHandler（在 WebUiServer start 之前）
        if (agentEnabled && chatCommand != null) {
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
                    () -> worldInfo, worldsDir, () -> worldInfo != null ? worldInfo.worldId() : "default");
            webUiServer.registerHandler("/timeline/data", timelineHandler);
            webUiServer.registerHandler("/timeline/node", timelineHandler);
            webUiServer.registerHandler("/timeline/nodes", timelineHandler);
            log.info("Registered TimelineApiHandler on /timeline/*");

            // LlmApiHandler + AgentApiHandler — 配置管理
            var llmApiHandler = new com.gsim.webui.handlers.LlmApiHandler(
                    new com.gsim.core.llm.LlmConfigManager(
                            config.getLlmsPath(), config.getLlmTimeoutSeconds()),
                    ctx.getLlmProviderRegistry());
            webUiServer.registerHandler("/api/llm", llmApiHandler);

            var agentApiHandler = new com.gsim.webui.handlers.AgentApiHandler(
                    new com.gsim.agent.config.AgentConfigManager(agentFactory.store(), config.agentsDir()));
            webUiServer.registerHandler("/api/agents", agentApiHandler);

            var worldApiHandler = new com.gsim.webui.handlers.WorldApiHandler(
                    worldsDir, () -> worldInfo != null ? worldInfo.worldId() : "default");
            webUiServer.registerHandler("/api/worlds", worldApiHandler);

            log.info("Registered LlmApiHandler + AgentApiHandler + WorldApiHandler");
        }
    }

    private void registerAgentTools(ToolRegistry toolRegistry) {
        // Agent progress sinks: CLI + EventBus (SSE) + SessionPool (unified async pool)
        // Use JLine terminal output for proper scroll/cursor coordination
        var jlineTerminal = adapter.getJlineTerminal();
        var cliProgressSink = jlineTerminal != null
                ? com.gsim.agent.CliAgentProgressSink.fromJlineTerminal(jlineTerminal)
                : new com.gsim.agent.CliAgentProgressSink(System.out, true);
        var eventBusSink = new com.gsim.agent.EventBusAgentProgressSink(ctx.getEventBus());
        var sessionPoolBridge = new com.gsim.core.session.SessionPoolBridge(ctx.getSessionPool(), "default");
        this.compositeSink =
                new com.gsim.core.event.CompositeAgentProgressSink(cliProgressSink, eventBusSink, sessionPoolBridge);

        // Tool group manager
        var toolGroupManager = new com.gsim.agent.ToolGroupManager();

        // Orchestrator
        this.orchestrator = new OrchestratorAgent(
                ctx.getLlmManager(),
                toolRegistry,
                config.getLlmModel(),
                compositeSink,
                !interactive
                        ? new com.gsim.agent.AutoApprovePermissionGate()
                        : new com.gsim.agent.CliToolPermissionGate(),
                toolGroupManager);
        this.orchestrator.setMaxToolRounds(config.getAgentToolLoopMaxRounds());
        this.orchestrator.setStreamEnabled(config.isLlmStreamEnabled());

        adapter.setStreamEnabled(config.isLlmStreamEnabled());

        // MediaWiki search (Wikipedia + any MediaWiki site)
        toolRegistry.register(new com.gsim.agent.tools.search.MediaWikiSearchTool());

        // Agent control flow tools
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        toolRegistry.register(new com.gsim.agent.tool.ActivateToolGroupsTool(toolGroupManager));

        // Sub-agent tools — 从 agents/ 目录加载配置
        var agentConfigStore = new com.gsim.agent.AgentConfigStore();
        agentConfigStore.reload(config.agentsDir());
        this.agentFactory = new com.gsim.agent.core.AgentFactory(
                agentConfigStore,
                ctx.getLlmProviderRegistry(),
                toolRegistry,
                compositeSink,
                config.getLlmModel(),
                worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default");

        // ── Agent 管理层（仿 World/Docs 体系：Store → Manager → HTTP API）──
        Path cachesBaseDir = worldsDir.resolveSibling("caches");
        var agentCacheStore = new com.gsim.agent.management.AgentCacheStore(cachesBaseDir, agentConfigStore);
        agentCacheStore.init();
        var agentsManager = new com.gsim.agent.management.AgentsManager(
                agentConfigStore,
                agentCacheStore,
                ctx.getLlmProviderRegistry(),
                toolRegistry,
                compositeSink,
                ctx.getEventBus(),
                config.getLlmModel(),
                worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default");
        log.info("Agent management layer initialized (Store + Manager)");
        this.orchestrator.registerSubAgentTools(toolRegistry, this.agentFactory, this.docCacheManager);

        // 将 AgentsManager 注入到 DispatchSubAgentTool（新路径优先）
        var dispatchTool = toolRegistry.get("dispatch_sub_agent");
        if (dispatchTool instanceof com.gsim.agent.tool.DispatchSubAgentTool d) {
            d.setAgentsManager(agentsManager);
        }

        // SubAgent cache 管理工具
        var worldIdSupplier = new java.util.function.Supplier<String>() {
            public String get() {
                return worldInfo != null ? worldInfo.worldId() : "default";
            }
        };
        toolRegistry.register(new com.gsim.agent.tool.ListSubAgentCachesTool(ctx.getCachesManager(), worldIdSupplier));
        toolRegistry.register(new com.gsim.agent.tool.ViewSubAgentCacheTool(ctx.getCachesManager(), worldIdSupplier));
        toolRegistry.register(new com.gsim.agent.tool.ViewSubAgentOutputTool(ctx.getCachesManager(), worldIdSupplier));

        // LLM provider 列表 + 动态创建 SubAgent 配置
        toolRegistry.register(new com.gsim.agent.tool.ListLlmProvidersTool(ctx.getLlmProviderRegistry()));
        toolRegistry.register(new com.gsim.agent.tool.CreateSubAgentConfigTool(config.agentsDir(), agentConfigStore));
        toolRegistry.register(new com.gsim.agent.tool.UpdateSubAgentConfigTool(config.agentsDir(), agentConfigStore));
        toolRegistry.register(new com.gsim.agent.tool.ListAgentConfigTool(agentConfigStore));
        toolRegistry.register(new com.gsim.agent.tool.DeleteAgentConfigTool(agentConfigStore, config.agentsDir()));

        // ── Cache compactor（按 id="compact" 查找 llms.json 中的 provider）──
        var compactProvider = ctx.getLlmProviderRegistry().get("compact");
        var compactLlm = (compactProvider instanceof com.gsim.core.llm.LlmManager m) ? m : null;
        if (compactLlm != null) {
            log.info("Using compact LLM provider: id={}", compactLlm.providerId());
        } else {
            compactLlm = ctx.getLlmManager();
            log.info("No 'compact' provider in llms.json, using default LLM for compaction");
        }
        this.cacheCompactor = new com.gsim.core.compact.CacheCompactor(compactLlm, 4096);

        // Compact Cache 工具（Agent 可调用）
        toolRegistry.register(new com.gsim.agent.tool.CompactCacheTool(
                ctx.getCachesManager(),
                cacheCompactor,
                compositeSink,
                worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default"));
    }

    /**
     * 切换到指定 world：只加载世界数据，不触碰活跃缓存。
     * 返回 null 表示成功，否则返回错误消息。
     */
    private String switchToWorld(String worldId) {
        try {
            // 1. 验证 world 存在
            var meta = worldManager.loadMeta(worldId);
            if (meta == null) return "World 不存在: " + worldId;

            // 2. 加载目标 world 的完整节点集合（discover 宽松加载，从磁盘扫描，不依赖硬编码锚点）
            // 3. Build WorldInformation（不经过 Bootstrap，避免触碰缓存）
            var newWi = worldManager.loadWorld(worldId);
            if (newWi == null) {
                return "Failed to load world: " + worldId;
            }

            // 4. 更新应用状态（worldInfo / activeWorldId，不触碰 activeCache）
            this.worldInfo = newWi;
            this.activeWorldId.set(worldId);
            ctx.setActiveRootId(worldId);

            // 5. 更新缓存中的 nodeId（信息性字段，不影响对话内容）
            if (this.activeCache != null) {
                this.activeCache.setNodeId(newWi.activeNodeId());
                com.gsim.core.cache.CacheStore.save(worldsDir, this.activeCache);
            }

            log.info(
                    "[switchToWorld] switched to world={} node={} root={} (cache unchanged: {})",
                    worldId,
                    newWi.activeNodeId(),
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
                com.gsim.core.cache.CacheStore.appendAndSave(worldsDir, s, msg.toCacheMap());
            }
        });
    }

    private void wireCommands(Runnable onNodeChanged) {
        // Write-through cache saver: every Agent message persisted immediately
        // 使用 activeWorldId（动态读取），而非捕获构造时的固定值
        updateMessageSaver();

        WorldCommand wc = new WorldCommand(worldsDir, this::switchToWorld);
        NodeCommand nc = new NodeCommand(worldsDir, () -> worldInfo, onNodeChanged);
        ChatCommand cc = new ChatCommand(
                worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default",
                () -> activeCache,
                (userInput, priorMessages) -> orchestrator.run(userInput, priorMessages));
        cc.setCancelCallback(orchestrator::cancel);
        cc.setJlineTerminal(adapter.getJlineTerminal());
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
                    ctx.getCachesManager(),
                    cacheCompactor,
                    compositeSink,
                    (userInput, priorMessages) -> orchestrator.run(userInput, priorMessages),
                    worldsDir,
                    () -> worldInfo != null ? worldInfo.worldId() : "default");
            adapter.setCompactCommand(compactCommand);

            // Board 指令（公开展示板）
            var boardDocsDir = worldsDir.resolveSibling("docs");
            var boardDocStore = ctx.getDocStore(boardDocsDir);
            var boardCommand = new com.gsim.commands.BoardCommand(boardDocStore, worldInfo);
            adapter.setBoardCommand(boardCommand);
        }
        adapter.setNewCommands(wc, nc, cc);

        // Expose commands to ApplicationContext for WebUI handlers
        ctx.setChatCommand(cc);
        ctx.setWorldCommand(wc);
        ctx.setNodeCommand(nc);

        // LLM + Agent config management commands
        var llmConfigManager = new com.gsim.core.llm.LlmConfigManager(
                config.getLlmsPath(), config.getLlmTimeoutSeconds());
        var agentConfigManager = new com.gsim.agent.config.AgentConfigManager(agentFactory.store(), config.agentsDir());
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
        if (activeCache.messageCount() > 0) return; // 已有内容，不重复注入

        var orchConfig = (agentFactory != null && agentFactory.store() != null)
                ? agentFactory.store().get("orchestrator")
                : null;
        String sp = orchConfig != null ? orchConfig.fullSystemPrompt() : null;
        if (sp == null || sp.isBlank()) return;

        activeCache.addMessage(java.util.Map.of("role", "system", "content", sp));
        com.gsim.core.cache.CacheStore.save(worldsDir, activeCache);
        log.info("Prepended static system prompt to cache {} ({} chars)", activeCache.sessionId(), sp.length());
    }

    /**
     * 启动 HTTP 服务（WebUI + WebSocket），始终启动，不阻塞。
     */
    public void startHttpServers() throws Exception {
        ctx.initialize();

        // WebUI server (port 8710) — always on
        webUiServer.forceEnable();
        webUiServer.start();

        // CLI WebSocket server（端口由 gsim.properties 的 cli.ws.port 配置，默认 8712）— always on
        cliWsServer = new com.gsim.webui.CliWebSocketServer(ctx, config.getCliWsPort(), compositeSink);
        cliWsServer.setCommands(worldCommand, nodeCommand, chatCommand);
        try {
            cliWsServer.start();
        } catch (Exception e) {
            log.warn("CLI WebSocket server failed to start: {}", e.getMessage());
        }
    }

    /**
     * 启动 CLI REPL（阻塞直到用户退出）。
     * 仅在交互模式下调用。
     */
    public void startCliRepl() throws Exception {
        if (!config.isLlmConfigured()) {
            System.out.println();
            System.out.println("⚠️  LLM 未配置。以下功能不可用:");
            System.out.println("   /chat — Agent 对话");
            System.out.println("   /sim  — 推演结算");
            System.out.println();
            System.out.println("编辑 " + config.getLlmsPath() + " 设置 API Key，或设置 LLM_API_KEY 环境变量。");
            System.out.println(
                    "当前已从 llms.json 加载 " + ctx.getLlmProviderRegistry().size() + " 个 provider，");
            System.out.println("但 API Key 无效（401 错误）。");
            System.out.println();
        }

        System.out.println();
        System.out.println("✅ GSimulator 已启动");
        System.out.println("   CLI REPL:  当前终端（输入 /help）");
        System.out.println("   Web GUI:   http://" + config.getWebUiHost() + ":" + config.getWebUiPort());
        System.out.println("   CLI WS:    ws://" + config.getWebUiHost() + ":" + config.getCliWsPort());
        System.out.println();

        adapter.start();
    }

    public void stop() {
        if (webUiServer != null) webUiServer.stop();
        if (cliWsServer != null) cliWsServer.stop();
        ctx.shutdown();
    }

    public ApplicationContext getContext() {
        return ctx;
    }

    /** Returns a dynamic supplier for the current active world ID. */
    public java.util.function.Supplier<String> getActiveWorldIdSupplier() {
        return activeWorldId::get;
    }
}
