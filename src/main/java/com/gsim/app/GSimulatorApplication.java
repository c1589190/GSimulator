package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.compact.ToolResultCompactor;
import com.gsim.interaction.ConsoleInteractionAdapter;
import com.gsim.player.PlayerProfileManager;
import com.gsim.tool.PlayerProfileGetTool;
import com.gsim.tool.PlayerProfileListTool;
import com.gsim.tool.PlayerProfileNoteTool;
import com.gsim.tool.PlayerProfileUpdateTool;
import com.gsim.tool.ToolRegistry;

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
    private final boolean cliMode;
    private final boolean httpMode;
    private final boolean webuiMode;
    private final com.gsim.webui.WebUiServer webUiServer;
    private com.gsim.webui.CliWebSocketServer cliWsServer;
    private com.gsim.agent.CompositeAgentProgressSink compositeSink;

    public GSimulatorApplication(AppConfig config) {
        this(config, true, false, false);
    }

    public GSimulatorApplication(AppConfig config, boolean cliMode, boolean httpMode) {
        this(config, cliMode, httpMode, false);
    }

    public GSimulatorApplication(AppConfig config, boolean cliMode, boolean httpMode, boolean webuiMode) {
        this.config = config;
        this.cliMode = cliMode;
        this.httpMode = httpMode;
        this.webuiMode = webuiMode;
        this.ctx = new ApplicationContext(config);
        ToolRegistry toolRegistry = ctx.getToolRegistry();

        // 创建 CLI 适配器
        this.adapter = new ConsoleInteractionAdapter(null, ctx.getInteractionSession(),
                config.getDataDir());

        // 注册 Agent 工具
        registerAgentTools(toolRegistry);

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
        var orchestrator = new OrchestratorAgent(
                ctx.getLlmManager(), toolRegistry, config.getLlmModel(),
                compositeSink,
                (httpMode || webuiMode) ? new com.gsim.agent.AutoApprovePermissionGate()
                         : new com.gsim.agent.CliToolPermissionGate(),
                toolGroupManager);
        orchestrator.setMaxToolRounds(config.getAgentToolLoopMaxRounds());
        orchestrator.setStreamEnabled(config.isLlmStreamEnabled());

        // Tool result compactor
        if (config.isCompactEnabled() && ctx.getLlmManager() != null) {
            var toolResultCompactor = new ToolResultCompactor(
                    ctx.getLlmManager(),
                    config.getCompactToolResultThreshold(),
                    config.getCompactLlmModel(),
                    config.getCompactLlmTemperature(),
                    cliProgressSink);
            orchestrator.setToolResultCompactor(toolResultCompactor);
            orchestrator.setToolResultThreshold(config.getCompactToolResultThreshold());
        }

        adapter.setStreamEnabled(config.isLlmStreamEnabled());

        // Agent control flow tools
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        toolRegistry.register(new com.gsim.agent.tool.ActivateToolGroupsTool(toolGroupManager));

        // Sub-agent tools
        var agentConfigStore = new com.gsim.agent.config.AgentConfigStore();
        var agentFactory = new com.gsim.agent.core.AgentFactory(
                agentConfigStore, ctx.getLlmManager(), toolRegistry, compositeSink, config.getLlmModel());
        orchestrator.registerSubAgentTools(toolRegistry, agentFactory);
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
