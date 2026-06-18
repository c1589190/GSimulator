package com.gsim.app;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.chat.BranchMessageStore;
import com.gsim.interaction.ConsoleInteractionAdapter;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.commands.*;
import com.gsim.importdata.ImportManager;
import com.gsim.chroma.FakeChromaClient;
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

    private final ApplicationContext ctx;
    private final ConsoleInteractionAdapter adapter;
    private final AppConfig config;

    public GSimulatorApplication(AppConfig config) {
        this.config = config;
        this.ctx = new ApplicationContext(config);
        InteractionManager manager = ctx.getInteractionManager();

        // 创建 CLI 适配器（必须在注册命令前，因为 ExitCommand 需要 adapter 引用）
        this.adapter = new ConsoleInteractionAdapter(manager, ctx.getInteractionSession());

        // 注册命令
        registerCommands(manager);
    }

    private void registerCommands(InteractionManager manager) {
        ToolRegistry toolRegistry = ctx.getToolRegistry();

        // /help — 显示所有命令
        manager.registerCommand(new HelpCommand(manager::getCommands));

        // /config — 配置管理
        manager.registerCommand(new ConfigCommand());

        // /status — 显示状态
        manager.registerCommand(new StatusCommand());

        // /exit — 退出
        manager.registerCommand(new ExitCommand(adapter::shutdown));

        // Data / Skill / Experience 系统（先初始化，PlayerCommand 依赖 DataManager）
        Path dataRoot = config.getDataDir();
        DataManager dataManager = new DataManager(dataRoot);

        // 注册 PlayerInputTool（Agent 可调用写入 input.md）
        toolRegistry.register(new PlayerInputTool(dataManager));

        // PlayerProfileManager — 玩家档案管理
        PlayerProfileManager profileManager = new PlayerProfileManager(dataManager);

        // 注册 PlayerProfile Tools（Agent 可调用管理玩家档案）
        toolRegistry.register(new PlayerProfileListTool(profileManager));
        toolRegistry.register(new PlayerProfileGetTool(profileManager));
        toolRegistry.register(new PlayerProfileUpdateTool(profileManager));
        toolRegistry.register(new PlayerProfileNoteTool(profileManager));

        // BranchMessageStore — 统一的消息块存储
        BranchMessageStore messageStore = new BranchMessageStore(dataManager, dataRoot);

        // Phase 3: Campaign / Turn / PlayerAction
        manager.registerCommand(new NewTurnCommand());
        manager.registerCommand(new PlayerCommand(dataManager));
        manager.registerCommand(new PlayersCommand(profileManager));
        manager.registerCommand(new ActionsCommand());
        manager.registerCommand(new ClearActionsCommand());
        manager.registerCommand(new SaveCommand());
        manager.registerCommand(new LoadCommand());
        manager.registerCommand(new TurnCommand());

        // Phase 6: /import (local + URL)
        ImportManager importManager = new ImportManager(config, new FakeChromaClient());
        manager.registerCommand(new ImportCommand(config, importManager));

        // Tool 系统: /tool wiki_search
        manager.registerCommand(new ToolCommand(toolRegistry));
        SkillManager skillManager = new SkillManager(dataRoot);
        skillManager.setDataManager(dataManager);
        ExperienceManager expManager = new ExperienceManager(dataRoot);
        BranchContextRenderer contextRenderer = new BranchContextRenderer(dataManager, dataRoot, messageStore);
        manager.registerCommand(new DataCommand(dataManager));
        manager.registerCommand(new SkillCommand(skillManager));
        manager.registerCommand(new ExpCommand(expManager));
        manager.registerCommand(new ContextCommand(contextRenderer, dataManager, dataRoot));

        // Phase 7+: /run (legacy), /sim, /nextturn, /node
        OrchestratorAgent orchestrator = new OrchestratorAgent(
                ctx.getLlmClient(), toolRegistry, config.getLlmModel());
        manager.registerCommand(new RunCommand(orchestrator));
        manager.registerCommand(new SimCommand(dataManager, contextRenderer, orchestrator, messageStore));
        manager.registerCommand(new NextTurnCommand(dataManager));
        manager.registerCommand(new NodeCommand(dataManager));

        // Chat 系统
        NodeAgentChatService chatService = new NodeAgentChatService(dataManager, contextRenderer, orchestrator);
        ChatCommand chatCommand = new ChatCommand(chatService);
        manager.registerCommand(chatCommand);

        // Messages 命令
        MessagesCommand messagesCommand = new MessagesCommand(messageStore, dataManager);
        manager.registerCommand(messagesCommand);

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
}
