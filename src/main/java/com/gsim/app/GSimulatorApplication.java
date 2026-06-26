package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.cache.CacheSession;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
import com.gsim.compact.ToolResultCompactor;
import com.gsim.context.ContextRenderer;
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
    private ContextRenderer contextRenderer;
    private Path worldsDir;
    private WorldCommand worldCommand;
    private NodeCommand nodeCommand;
    private ChatCommand chatCommand;

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
            this.contextRenderer = bootResult.contextRenderer();
            // Wire into ApplicationContext so PageHandler/WebUI see the active world
            ctx.setActiveRootId(bootResult.worldId());
        }

        ToolRegistry toolRegistry = ctx.getToolRegistry();

        // 创建 CLI 适配器（命令稍后注入）
        this.adapter = new ConsoleInteractionAdapter(null, ctx.getInteractionSession(),
                config.getDataDir());

        // 注册 Agent 工具
        registerAgentTools(toolRegistry);

        // Node change callback — shared between tools and commands
        Runnable onNodeChanged = () -> {
            log.info("World/node changed — re-bootstrap needed");
            // In a full implementation this would re-run Bootstrap.boot();
        };

        // 注册 world info + node 管理工具
        registerWorldInfoTools(toolRegistry, onNodeChanged);

        // 创建命令并注入到 adapter
        wireCommands(onNodeChanged);

        // 注入 FreeMarker 渲染的系统 prompt
        injectSystemPrompt();

        // 创建 WebUiServer
        com.gsim.webui.WebUiConfig webUiConfig =
                com.gsim.webui.WebUiConfig.from(config);
        this.webUiServer = new com.gsim.webui.WebUiServer(webUiConfig, ctx);
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
        this.agentFactory = new com.gsim.agent.core.AgentFactory(
                agentConfigStore, ctx.getLlmManager(), toolRegistry, compositeSink, config.getLlmModel(),
                worldsDir, () -> worldInfo != null ? worldInfo.worldId() : "default");
        this.orchestrator.registerSubAgentTools(toolRegistry, this.agentFactory);
    }

    private void registerWorldInfoTools(ToolRegistry toolRegistry, Runnable onNodeChanged) {
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

        log.info("Registered 11 world info + node tools (query_node, query_checkpoint, " +
                "query_keyword, query_element, write_element, create_checkpoint, " +
                "node_list, node_status, node_create, node_switch, node_goto_parent)");
    }

    private void wireCommands(Runnable onNodeChanged) {
        // Write-through cache saver: every Agent message persisted immediately
        String wid = worldInfo != null ? worldInfo.worldId() : "default";
        orchestrator.setMessageSaver(msg -> {
            CacheSession s = activeCache;
            if (s != null) {
                com.gsim.cache.CacheStore.appendAndSave(worldsDir, wid, s,
                        java.util.Map.of("role", msg.role(), "content",
                                msg.content() != null ? msg.content() : ""));
            }
        });

        WorldCommand wc = new WorldCommand(worldsDir, onNodeChanged);
        NodeCommand nc = new NodeCommand(worldsDir, () -> worldInfo, onNodeChanged);
        ChatCommand cc = new ChatCommand(worldsDir,
                () -> worldInfo != null ? worldInfo.worldId() : "default",
                () -> activeCache,
                (userInput, priorMessages) -> orchestrator.run(userInput, priorMessages));
        cc.setCancelCallback(orchestrator::cancel);
        this.worldCommand = wc;
        this.nodeCommand = nc;
        this.chatCommand = cc;
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
