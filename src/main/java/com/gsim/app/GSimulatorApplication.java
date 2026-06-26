package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.cache.CacheSession;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
import com.gsim.compact.ToolResultCompactor;
import com.gsim.context.ContextRenderer;
import com.gsim.interaction.ConsoleInteractionAdapter;
import com.gsim.player.PlayerProfileManager;
import com.gsim.tool.PlayerProfileGetTool;
import com.gsim.tool.PlayerProfileListTool;
import com.gsim.tool.PlayerProfileNoteTool;
import com.gsim.tool.PlayerProfileUpdateTool;
import com.gsim.tool.ToolRegistry;
import com.gsim.worldinfo.WorldInformation;
import com.gsim.worldinfo.tool.QueryCheckpointTool;
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

    // -- Bootstrap result wiring --
    private OrchestratorAgent orchestrator;
    private WorldInformation worldInfo;
    private CacheSession activeCache;
    private ContextRenderer contextRenderer;
    private Path worldsDir;

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
        this.worldsDir = config.getDataDir().resolve("worlds");

        // Store BootstrapResult data
        if (bootResult != null) {
            this.worldInfo = bootResult.worldInfo();
            this.activeCache = bootResult.activeCache();
            this.contextRenderer = bootResult.contextRenderer();
        }

        ToolRegistry toolRegistry = ctx.getToolRegistry();

        // 创建 CLI 适配器（命令稍后注入）
        this.adapter = new ConsoleInteractionAdapter(null, ctx.getInteractionSession(),
                config.getDataDir());

        // 注册 Agent 工具
        registerAgentTools(toolRegistry);

        // 注册 world info 查询工具
        registerWorldInfoTools(toolRegistry);

        // 创建命令并注入到 adapter
        wireCommands();

        // 注入 FreeMarker 渲染的系统 prompt
        injectSystemPrompt();

        // 创建 WebUiServer
        com.gsim.webui.WebUiConfig webUiConfig =
                com.gsim.webui.WebUiConfig.from(config);
        this.webUiServer = new com.gsim.webui.WebUiServer(webUiConfig, ctx);
    }

    private void registerAgentTools(ToolRegistry toolRegistry) {
        // Player profiles (direct path-based)
        Path dataDir = config.getDataDir();
        Path playersFile = dataDir.resolve("players.md");
        PlayerProfileManager profileManager = new PlayerProfileManager(playersFile);

        toolRegistry.register(new PlayerProfileListTool(profileManager));
        toolRegistry.register(new PlayerProfileGetTool(profileManager));
        toolRegistry.register(new PlayerProfileUpdateTool(profileManager));
        toolRegistry.register(new PlayerProfileNoteTool(profileManager));

        // Import doc tools
        var importDocService = new com.gsim.importing.ImportDocumentService(config.getImportDir());
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentListTool(importDocService));
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentReadTool(importDocService));
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentSearchTool(importDocService));

        // CLI progress sink
        var cliProgressSink = new com.gsim.agent.CliAgentProgressSink(System.out, true);
        var eventBusSink = new com.gsim.agent.EventBusAgentProgressSink(ctx.getEventBus());
        this.compositeSink = new com.gsim.agent.CompositeAgentProgressSink(
                cliProgressSink, eventBusSink);

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

        // Tool result compactor
        if (config.isCompactEnabled() && ctx.getLlmManager() != null) {
            var toolResultCompactor = new ToolResultCompactor(
                    ctx.getLlmManager(),
                    config.getCompactToolResultThreshold(),
                    config.getCompactLlmModel(),
                    config.getCompactLlmTemperature(),
                    cliProgressSink);
            this.orchestrator.setToolResultCompactor(toolResultCompactor);
            this.orchestrator.setToolResultThreshold(config.getCompactToolResultThreshold());
        }

        adapter.setStreamEnabled(config.isLlmStreamEnabled());

        // Agent control flow tools
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        toolRegistry.register(new com.gsim.agent.tool.ActivateToolGroupsTool(toolGroupManager));

        // Sub-agent tools
        var agentConfigStore = new com.gsim.agent.config.AgentConfigStore();
        var agentFactory = new com.gsim.agent.core.AgentFactory(
                agentConfigStore, ctx.getLlmManager(), toolRegistry, compositeSink, config.getLlmModel());
        this.orchestrator.registerSubAgentTools(toolRegistry, agentFactory);
    }

    private void registerWorldInfoTools(ToolRegistry toolRegistry) {
        if (worldInfo == null) {
            log.warn("WorldInformation not available, skipping world info tool registration");
            return;
        }
        Supplier<WorldInformation> wiSupplier = () -> this.worldInfo;
        toolRegistry.register(new QueryCheckpointTool(wiSupplier));
        toolRegistry.register(new QueryKeywordTool(wiSupplier));
        toolRegistry.register(new QueryNodeTool(wiSupplier));
        toolRegistry.register(new WriteElementTool(wiSupplier, worldsDir));
        log.info("Registered 4 world info query tools (query_checkpoint, query_keyword, query_node, write_element)");
    }

    private void wireCommands() {
        // Re-bootstrap callback (called when /world switch or /node goto/create changes state)
        Runnable onChanged = () -> {
            log.info("World/node changed — re-bootstrap needed");
            // In a full implementation this would re-run Bootstrap.boot();
            // For now, just log. The command output already informs the user.
        };
        WorldCommand wc = new WorldCommand(worldsDir, onChanged);
        NodeCommand nc = new NodeCommand(worldsDir, () -> worldInfo, onChanged);
        ChatCommand cc = new ChatCommand(worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default",
                () -> activeCache);
        adapter.setNewCommands(wc, nc, cc);
        log.info("Wired /world, /node, /chat commands into ConsoleInteractionAdapter");
    }

    private void injectSystemPrompt() {
        if (orchestrator == null) {
            log.warn("Orchestrator not initialized, skipping system prompt injection");
            return;
        }
        if (contextRenderer == null || worldInfo == null) {
            log.warn("ContextRenderer or WorldInformation not available, skipping system prompt injection");
            return;
        }
        try {
            String rendered = contextRenderer.renderSystemPrompt("OrchestratorAgent", worldInfo);
            orchestrator.setSystemPrompt(rendered);
            log.info("Injected FreeMarker-rendered system prompt into OrchestratorAgent ({} chars)", rendered.length());
        } catch (Exception e) {
            log.error("Failed to inject system prompt: {}", e.getMessage(), e);
        }
    }

    /**
     * 启动应用。
     */
    public void start() throws Exception {
        ctx.initialize();

        if (httpMode) {
            ctx.getApiManager().forceEnable();
        }

        if (httpMode || config.isApiEnabled()) {
            ctx.getApiManager().start();
        }

        if (webuiMode) {
            webUiServer.forceEnable();
        }
        if (webuiMode || config.isWebUiEnabled()) {
            webUiServer.start();
            cliWsServer = new com.gsim.webui.CliWebSocketServer(ctx, 8712, compositeSink);
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
                System.out.println("执行 /config init 配置 LLM，或 /config status 查看当前状态。");
                System.out.println();
            }
            Thread.startVirtualThread(() -> {
                try { adapter.start(); } catch (Exception e) {
                    log.error("CLI REPL crashed: {}", e.getMessage(), e); }
            });
        }

        System.out.println();
        System.out.println("✅ GSimulator 已启动");
        System.out.println("   CLI REPL:  当前终端（输入 /help）");
        System.out.println("   Web GUI:   http://" + config.getWebUiHost() + ":" + config.getWebUiPort());
        System.out.println("   CLI WS:    ws://" + config.getWebUiHost() + ":8712");
        System.out.println("   HTTP API:  http://" + config.getApiHost() + ":" + config.getApiPort());
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
