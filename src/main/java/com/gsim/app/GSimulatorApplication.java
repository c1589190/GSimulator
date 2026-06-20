package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.branch.BranchAnalysisTool;
import com.gsim.compact.ContextCompactor;
import com.gsim.compact.ToolResultCompactor;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessageStore;
import com.gsim.interaction.ConsoleInteractionAdapter;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.commands.*;
import com.gsim.importdata.ImportManager;
import com.gsim.campaign.Campaign;
import com.gsim.campaign.Turn;
import com.gsim.chat.NodeAgentChatService;
import com.gsim.context.BranchContextRenderer;
import com.gsim.data.DataManager;
import com.gsim.experience.ExperienceManager;
import com.gsim.player.PlayerProfileManager;
import com.gsim.skill.SkillManager;
import com.gsim.tool.PlayerInputTool;
import com.gsim.tool.PlayerProfileGetTool;
import com.gsim.tool.PlayerProfileListTool;
import com.gsim.tool.PlayerProfileNoteTool;
import com.gsim.tool.PlayerProfileUpdateTool;
import com.gsim.tool.ToolRegistry;

import java.nio.file.Path;

/**
 * GSimulator 应用启动器。
 * 负责依赖注入、命令注册、REPL 启动。
 */
public class GSimulatorApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GSimulatorApplication.class);

    private final ApplicationContext ctx;
    private final ConsoleInteractionAdapter adapter;
    private final AppConfig config;
    private final boolean cliMode;
    private final boolean httpMode;

    public GSimulatorApplication(AppConfig config) {
        this(config, true, false);
    }

    public GSimulatorApplication(AppConfig config, boolean cliMode, boolean httpMode) {
        this.config = config;
        this.cliMode = cliMode;
        this.httpMode = httpMode;
        this.ctx = new ApplicationContext(config);
        InteractionManager manager = ctx.getInteractionManager();

        // 创建 CLI 适配器（必须在注册命令前，因为 ExitCommand 需要 adapter 引用）
        this.adapter = new ConsoleInteractionAdapter(manager, ctx.getInteractionSession(),
                () -> ctx.getDataManager());

        // 注册命令
        registerCommands(manager);
    }

    private void registerCommands(InteractionManager manager) {
        ToolRegistry toolRegistry = ctx.getToolRegistry();

        // /help — 显示所有命令
        manager.registerCommand(new HelpCommand(manager::getCommands));

        // /where — 当前位置信息
        manager.registerCommand(new com.gsim.interaction.commands.WhereCommand(ctx));

        // /config — 配置管理
        manager.registerCommand(new ConfigCommand());

        // /status — 显示状态
        manager.registerCommand(new StatusCommand());

        // /exit — 退出
        manager.registerCommand(new ExitCommand(adapter::shutdown));

        // Data / Skill / Experience 系统
        Path dataRoot = config.getDataDir();
        DataManager dataManager = new DataManager(dataRoot);

        // 注册 PlayerInputTool（Agent 可调用写入 input.md）
        toolRegistry.register(new PlayerInputTool(dataManager));

        // PlayerProfileManager — 玩家档案管理
        PlayerProfileManager profileManager = new PlayerProfileManager(dataManager);

        // 注册 PlayerProfile Tools
        toolRegistry.register(new PlayerProfileListTool(profileManager));
        toolRegistry.register(new PlayerProfileGetTool(profileManager));
        toolRegistry.register(new PlayerProfileUpdateTool(profileManager));
        toolRegistry.register(new PlayerProfileNoteTool(profileManager));

        // Import 文档读取工具 — 统一 LOCAL_IMPORT + WIKI_DOWNLOADED，不依赖 active root
        var importDocService = new com.gsim.importing.ImportDocumentService(config.getImportDir());
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentListTool(importDocService));
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentReadTool(importDocService));
        toolRegistry.register(new com.gsim.importing.tool.ImportDocumentSearchTool(importDocService));

        // 注入 branch context suppliers 到 KnowledgeToolFactory（用于 upsert 自动填充 + search 祖先过滤）
        ctx.getKnowledgeToolFactory().setContextSuppliers(
                () -> dataManager.getActiveRootId(),
                () -> dataManager.getActiveBranchId(),
                () -> {
                    try {
                        var activeBranch = dataManager.getActiveBranch();
                        if (activeBranch == null) return java.util.List.of();
                        var chain = dataManager.getBranchChain(activeBranch);
                        return chain.stream().map(d -> d.id()).toList();
                    } catch (Exception e) {
                        log.warn("Failed to resolve visible branch IDs: {}", e.getMessage());
                        return java.util.List.of();
                    }
                });

        // BranchMessageStore / BranchAnalyzer
        BranchMessageStore messageStore = new BranchMessageStore(dataManager, dataRoot);
        BranchAnalyzer branchAnalyzer = new BranchAnalyzer(dataManager, messageStore, profileManager);
        toolRegistry.register(new BranchAnalysisTool(branchAnalyzer));

        // Phase 3: Campaign / Turn / PlayerAction
        manager.registerCommand(new NewTurnCommand());
        manager.registerCommand(new PlayerCommand(dataManager));
        manager.registerCommand(new PlayersCommand(profileManager));
        manager.registerCommand(new ActionsCommand());
        manager.registerCommand(new ClearActionsCommand());
        manager.registerCommand(new SaveCommand());
        manager.registerCommand(new LoadCommand());
        manager.registerCommand(new TurnCommand());

        // Phase 6: /import
        ImportManager importManager = new ImportManager(config, null);
        manager.registerCommand(new ImportCommand(config, importManager));

        // Tool 系统
        manager.registerCommand(new ToolCommand(toolRegistry));

        SkillManager skillManager = new SkillManager(dataRoot);
        skillManager.setDataManager(dataManager);
        ExperienceManager expManager = new ExperienceManager(dataRoot);

        // 注入 DataManager
        ctx.setDataManager(dataManager);

        // Context Session + Knowledge — 如果已有 active root，初始化
        BranchContextRenderer contextRenderer;
        com.gsim.context.session.ContextSessionManager ctxSessionManager;
        OrchestratorAgent orchestrator;
        NodeAgentChatService chatService;
        ChatCommand chatCommand;

        if (!dataManager.needsRootBootstrap()) {
            contextRenderer = buildContextRenderer(dataManager, dataRoot, messageStore, branchAnalyzer);
            ctxSessionManager = buildContextSessionSystem(contextRenderer, dataManager, dataRoot);
            ctx.setBranchContextRenderer(contextRenderer);
            ctx.setContextSessionManager(ctxSessionManager);
            ctx.resolveKnowledgeForActiveRoot();
        } else {
            // 空 data — 创建占位 renderer 和 session manager（bootstrap 后会重建）
            contextRenderer = buildContextRenderer(dataManager, dataRoot, messageStore, branchAnalyzer);
            ctxSessionManager = null;
            ctx.setBranchContextRenderer(contextRenderer);
            ctx.setContextSessionManager(null);
        }

        // Knowledge / Embedding 管理命令
        manager.registerCommand(new KnowledgeCommand(ctx.getKnowledgeStore()));
        manager.registerCommand(new EmbeddingCommand(ctx.getEmbeddingProfileManager()));

        // 注册 Memory Tools（如果 summary/pin store 可用）
        registerMemoryToolsIfAvailable(dataManager, dataRoot, messageStore, branchAnalyzer);

        // 设置 root 就绪回调（用于自然语言 bootstrap 后重新注册 memory tools）
        ctx.setOnRootReadyCallback(() -> {
            var dm = ctx.getDataManager();
            if (dm != null) {
                registerMemoryToolsIfAvailable(dm, dataRoot, messageStore, branchAnalyzer);
            }
        });

        manager.registerCommand(new DataCommand(dataManager));
        manager.registerCommand(new SkillCommand(skillManager));
        manager.registerCommand(new ExpCommand(expManager));
        if (ctxSessionManager != null) {
            manager.registerCommand(new ContextCommand(contextRenderer, dataManager, dataRoot, ctxSessionManager));
        }

        // Orchestrator + Chat（CLI 模式默认开启进度输出 + 写入确认）
        var cliProgressSink = new com.gsim.agent.CliAgentProgressSink(
                System.out, true);

        // 工具组管理器（每轮对话重置，激活不跨轮保留）
        var toolGroupManager = new com.gsim.agent.ToolGroupManager();

        orchestrator = new OrchestratorAgent(
                ctx.getLlmManager(), toolRegistry, config.getLlmModel(),
                cliProgressSink,
                new com.gsim.agent.CliToolPermissionGate(),
                toolGroupManager);
        orchestrator.setContextHistoryConfig(new OrchestratorAgent.ContextHistoryConfig(
                config.getContextSessionHistoryTurns(),
                config.getContextSessionMessageMaxChars()));
        orchestrator.setMaxToolRounds(config.getAgentToolLoopMaxRounds());
        orchestrator.setStreamEnabled(config.isLlmStreamEnabled());

        // ---- Compact 子系统 ----
        if (config.isCompactEnabled() && ctx.getLlmManager() != null) {
            var compactor = new ContextCompactor(ctx.getLlmManager(), config, cliProgressSink);
            var toolResultCompactor = new ToolResultCompactor(
                    ctx.getLlmManager(),
                    config.getCompactToolResultThreshold(),
                    config.getCompactLlmModel(),
                    config.getCompactLlmTemperature(),
                    cliProgressSink);
            orchestrator.setToolResultCompactor(toolResultCompactor);
            orchestrator.setToolResultThreshold(config.getCompactToolResultThreshold());

            // 注册 /compact 命令（仅在有 ContextSessionManager 时可用）
            if (ctxSessionManager != null) {
                manager.registerCommand(new CompactCommand(ctxSessionManager, compactor, orchestrator));
            }
        }

        // 通知 CLI adapter 流式模式（流式内容已在过程中直接输出）
        adapter.setStreamEnabled(config.isLlmStreamEnabled());

        // 注册控制流工具：finish_action（Agent 必须调用此工具才能结束每轮对话）
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        // 注册工具组激活工具：activate_tool_groups（Agent 按需激活工具组）
        toolRegistry.register(new com.gsim.agent.tool.ActivateToolGroupsTool(toolGroupManager));

        chatService = new NodeAgentChatService(dataManager, contextRenderer, orchestrator,
                ctxSessionManager, dataRoot, ctx);
        chatCommand = new ChatCommand(chatService);
        manager.registerCommand(chatCommand);

        // 注册 Root Tools（带权限门禁的根节点管理工具，必须晚于 chatService 创建）
        var rootToolFactory = new com.gsim.root.tool.RootToolFactory(dataManager, newRootId -> {
            try {
                var newRenderer = buildContextRenderer(dataManager, dataRoot, messageStore, branchAnalyzer);
                var newCtxSessionMgr = buildContextSessionSystem(newRenderer, dataManager, dataRoot);
                ctx.setBranchContextRenderer(newRenderer);
                ctx.setContextSessionManager(newCtxSessionMgr);
                ctx.resolveKnowledgeForActiveRoot();
                registerMemoryToolsIfAvailable(dataManager, dataRoot, messageStore, branchAnalyzer);
                chatService.onRootChanged(newRenderer, newCtxSessionMgr, dataRoot, ctx);
            } catch (Exception e) {
                log.error("Failed to switch root to '{}' via root tool: {}", newRootId, e.getMessage());
            }
        });
        for (var tool : rootToolFactory.createAll()) {
            toolRegistry.register(tool);
        }

        // 注册 SimulationContent Tools（单回合推演内容保存 + 回合结算）
        Runnable onBranchChanged = () -> {
            var current = ctx.getContextSessionManager();
            if (current != null) {
                current.resetSession("default", "branch switched via agent tool");
                log.debug("ContextSession reset due to branch switch");
            }
        };
        toolRegistry.register(new com.gsim.branch.tool.SimulationContentAppendTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.SimulationContentListTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.SimulationContentGetTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.SimulationContentUpdateTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.TurnSettlementSaveTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.TurnSettlementSaveLastResponseTool(
                dataManager, orchestrator::getLastAssistantDraft));
        toolRegistry.register(new com.gsim.branch.tool.TurnSettlementGetTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.BranchCreateChildTool(dataManager, onBranchChanged));
        toolRegistry.register(new com.gsim.branch.tool.BranchSwitchTool(dataManager, onBranchChanged));
        toolRegistry.register(new com.gsim.branch.tool.BranchGotoParentTool(dataManager, onBranchChanged));
        toolRegistry.register(new com.gsim.branch.tool.BranchNextTurnTool(dataManager, onBranchChanged));
        toolRegistry.register(new com.gsim.branch.tool.BranchListTool(dataManager));

        // 注册 PlayerAction Tools（branch 节点内的玩家行动记录）
        toolRegistry.register(new com.gsim.branch.tool.PlayerActionAppendTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.PlayerActionListTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.PlayerActionGetTool(dataManager));
        toolRegistry.register(new com.gsim.branch.tool.PlayerActionUpdateTool(dataManager));

        // /sim /run — deprecated wrappers
        manager.registerCommand(new SimCommand(chatService));
        manager.registerCommand(new RunCommand(chatService));

        manager.registerCommand(new NextTurnCommand(dataManager));
        manager.registerCommand(new NodeCommand(dataManager));
        manager.registerCommand(new BranchCommand(branchAnalyzer));

        // Pin + Messages
        var pinManager = buildPinManager(dataManager, dataRoot);
        manager.registerCommand(new PinCommand(pinManager, dataManager));
        MessagesCommand messagesCommand = new MessagesCommand(messageStore, dataManager);
        manager.registerCommand(messagesCommand);

        // /root 命令 — 根管理（需要 onRootChanged 回调）
        manager.registerCommand(new RootCommand(dataManager, ctx.getScopedStoreFactory(), newRootId -> {
            try {
                // 重建 ContextSession 系统
                var newRenderer = buildContextRenderer(dataManager, dataRoot, messageStore, branchAnalyzer);
                var newCtxSessionMgr = buildContextSessionSystem(newRenderer, dataManager, dataRoot);
                ctx.setBranchContextRenderer(newRenderer);
                ctx.setContextSessionManager(newCtxSessionMgr);
                ctx.resolveKnowledgeForActiveRoot();
                registerMemoryToolsIfAvailable(dataManager, dataRoot, messageStore, branchAnalyzer);
                // 更新 chat service
                chatService.onRootChanged(newRenderer, newCtxSessionMgr, dataRoot, ctx);
                log.info("Root switched to '{}', context session and knowledge store re-initialized", newRootId);
            } catch (Exception e) {
                log.error("Failed to switch root to '{}': {}", newRootId, e.getMessage());
            }
        }));

        // 如果 data 为空，提示用户
        if (dataManager.needsRootBootstrap()) {
            System.out.println();
            System.out.println("当前没有 root。");
            System.out.println("输入一句话将快速创建基础 root，之后可继续在对话中完善世界观。");
            System.out.println("或使用 /root create <rootId> <初始设定>。");
            System.out.println();
        }

        adapter.setChatService(chatService, chatCommand, messagesCommand);
    }

    /**
     * 启动应用。
     */
    public void start() throws Exception {
        // 初始化目录
        ctx.initialize();

        // 自动创建默认 campaign 和 turn
        initDefaultState();

        // 强制启用 API（如果 --http 指定了）
        if (httpMode) {
            ctx.getApiManager().forceEnable();
        }

        // HTTP 模式：启动 API 服务器
        if (httpMode || config.isApiEnabled()) {
            ctx.getApiManager().start();
        }

        // CLI 模式：启动 REPL
        if (cliMode) {
            // 如果 LLM 未配置，打印提示
            if (!config.isLlmConfigured()) {
                System.out.println();
                System.out.println("⚠️  LLM 未配置。以下功能不可用:");
                System.out.println("   /chat — Agent 对话");
                System.out.println("   /sim  — 推演结算");
                System.out.println("   /run  — 旧版推演");
                System.out.println();
                System.out.println("执行 /config init 配置 LLM，或 /config status 查看当前状态。");
                System.out.println();
            }

            // 启动 REPL
            adapter.start();
        } else if (httpMode) {
            // 仅 HTTP 模式：保持服务器运行
            System.out.println();
            System.out.println("GSimulator HTTP API 模式运行中。按 Ctrl+C 退出。");
            System.out.println("API 地址: http://" + config.getApiHost() + ":" + config.getApiPort());
            Thread.currentThread().join();
        }
    }

    /**
     * 停止应用。
     */
    public void stop() {
        ctx.shutdown();
    }

    private void initDefaultState() {
        var campaignService = ctx.getCampaignService();
        var turnService = ctx.getTurnService();
        var interactionContext = ctx.getInteractionContext();

        // 创建或加载默认 campaign
        Campaign campaign = campaignService.getOrCreateDefault();
        interactionContext.setCurrentCampaignId(campaign.campaignId());
        campaignService.setCurrentTurnId(null); // 等 turn 创建后再设置

        // 创建或加载第一个 turn
        Turn turn = turnService.getOrCreateFirst(campaign.campaignId());
        interactionContext.setCurrentTurnId(turn.turnId());
        campaignService.setCurrentTurnId(turn.turnId());
        campaignService.addTurnId(turn.turnId());

        // 加载已有行动
        ctx.getPlayerActionService().loadActions(campaign.campaignId(), turn.turnId());
    }

    public ApplicationContext getContext() {
        return ctx;
    }

    // ---- Helper builders ----

    private BranchContextRenderer buildContextRenderer(DataManager dm, Path dataRoot,
                                                        BranchMessageStore messageStore,
                                                        BranchAnalyzer branchAnalyzer) {
        Path worldDir;
        if (!dm.needsRootBootstrap()) {
            worldDir = dataRoot.resolve("worlds").resolve(dm.getActiveRootId());
        } else {
            worldDir = dataRoot.resolve("worlds").resolve("__pending__");
        }
        var summaryStore = new com.gsim.context.summary.NodeSummaryStore(worldDir);
        var pinStore = new com.gsim.context.memory.PinnedConstraintStore(worldDir);
        var pathRenderer = new com.gsim.context.summary.BranchPathSummaryRenderer(dm, summaryStore);
        return new BranchContextRenderer(dm, dataRoot, messageStore, branchAnalyzer,
                pathRenderer, summaryStore, pinStore);
    }

    private com.gsim.context.session.ContextSessionManager buildContextSessionSystem(
            BranchContextRenderer renderer, DataManager dm, Path dataRoot) {
        Path worldDir = dataRoot.resolve("worlds").resolve(dm.getActiveRootId());
        var sessionStore = new com.gsim.context.session.ContextSessionStore(worldDir);
        return new com.gsim.context.session.ContextSessionManager(sessionStore, renderer, dm, worldDir);
    }

    private com.gsim.context.memory.PinnedConstraintManager buildPinManager(DataManager dm, Path dataRoot) {
        Path worldDir;
        if (!dm.needsRootBootstrap()) {
            worldDir = dataRoot.resolve("worlds").resolve(dm.getActiveRootId());
        } else {
            worldDir = dataRoot.resolve("worlds").resolve("__pending__");
        }
        var pinStore = new com.gsim.context.memory.PinnedConstraintStore(worldDir);
        return new com.gsim.context.memory.PinnedConstraintManager(pinStore);
    }

    /** 注册 Memory Tools（可多次调用，root 切换后重新注册）。 */
    private void registerMemoryToolsIfAvailable(DataManager dm, Path dataRoot,
                                                 BranchMessageStore messageStore, BranchAnalyzer branchAnalyzer) {
        if (dm.needsRootBootstrap()) {
            return; // 没有 root 就不注册 memory tools
        }
        Path worldDir = dataRoot.resolve("worlds").resolve(dm.getActiveRootId());
        if (!java.nio.file.Files.exists(worldDir)) return;

        var tr = ctx.getToolRegistry();
        var summaryStore = new com.gsim.context.summary.NodeSummaryStore(worldDir);
        var pathRenderer = new com.gsim.context.summary.BranchPathSummaryRenderer(dm, summaryStore);
        var pinStore = new com.gsim.context.memory.PinnedConstraintStore(worldDir);
        var pinManager = new com.gsim.context.memory.PinnedConstraintManager(pinStore);

        // register (overwrites existing) — idempotent on repeated calls
        tr.register(new com.gsim.context.memory.BranchPathTool(pathRenderer));
        tr.register(new com.gsim.context.memory.BranchNodeGetTool(dm, messageStore));
        tr.register(new com.gsim.context.memory.BranchNodeSearchTool(dm, summaryStore));
        tr.register(new com.gsim.context.memory.BranchLogFilterTool(dm));
        tr.register(new com.gsim.context.memory.BranchPinGetTool(pinManager));
        tr.register(new com.gsim.context.memory.BranchPinAddTool(pinManager));
    }

}
