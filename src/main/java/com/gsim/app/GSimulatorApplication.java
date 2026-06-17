package com.gsim.app;

import com.gsim.interaction.ConsoleInteractionAdapter;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.commands.ExitCommand;
import com.gsim.interaction.commands.HelpCommand;
import com.gsim.interaction.commands.StatusCommand;
import com.gsim.interaction.commands.NewTurnCommand;
import com.gsim.interaction.commands.PlayerCommand;
import com.gsim.interaction.commands.ActionsCommand;
import com.gsim.interaction.commands.ClearActionsCommand;
import com.gsim.interaction.commands.SaveCommand;
import com.gsim.interaction.commands.LoadCommand;
import com.gsim.interaction.commands.TurnCommand;
import com.gsim.interaction.commands.ImportCommand;
import com.gsim.interaction.commands.ToolCommand;
import com.gsim.importdata.ImportManager;
import com.gsim.chroma.FakeChromaClient;
import com.gsim.campaign.Campaign;
import com.gsim.campaign.Turn;
import com.gsim.tool.LocalFileSearchService;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.WikiSearchTool;

import java.nio.file.Path;

/**
 * GSimulator 应用启动器。
 * 负责依赖注入、命令注册、REPL 启动。
 */
public class GSimulatorApplication {

    private final ApplicationContext ctx;
    private final ConsoleInteractionAdapter adapter;

    public GSimulatorApplication() {
        this.ctx = new ApplicationContext();
        InteractionManager manager = ctx.getInteractionManager();

        // 创建 CLI 适配器（必须在注册命令前，因为 ExitCommand 需要 adapter 引用）
        this.adapter = new ConsoleInteractionAdapter(manager, ctx.getInteractionSession());

        // 注册命令
        registerCommands(manager);
    }

    private void registerCommands(InteractionManager manager) {
        // /help — 显示所有命令
        manager.registerCommand(new HelpCommand(manager::getCommands));

        // /status — 显示状态
        manager.registerCommand(new StatusCommand());

        // /exit — 退出
        manager.registerCommand(new ExitCommand(adapter::shutdown));

        // Phase 3: Campaign / Turn / PlayerAction
        manager.registerCommand(new NewTurnCommand());
        manager.registerCommand(new PlayerCommand());
        manager.registerCommand(new ActionsCommand());
        manager.registerCommand(new ClearActionsCommand());
        manager.registerCommand(new SaveCommand());
        manager.registerCommand(new LoadCommand());
        manager.registerCommand(new TurnCommand());

        // Phase 6: /import (local + URL)
        ImportManager importManager = new ImportManager(ctx.getConfig(), new FakeChromaClient());
        manager.registerCommand(new ImportCommand(ctx.getConfig(), importManager));

        // Tool 系统: /tool wiki_search
        ToolRegistry toolRegistry = new ToolRegistry();
        Path wikiDir = ctx.getConfig().getImportDir().resolve("web").resolve("prts.wiki");
        LocalFileSearchService searchService = new LocalFileSearchService(wikiDir);
        toolRegistry.register(new WikiSearchTool(searchService));
        manager.registerCommand(new ToolCommand(toolRegistry));

        // TODO Phase 4+: /run, /searchdb
    }

    /**
     * 启动应用。
     */
    public void start() throws Exception {
        // 初始化目录
        ctx.initialize();

        // 自动创建默认 campaign 和 turn
        initDefaultState();

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
