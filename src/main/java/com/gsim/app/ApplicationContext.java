package com.gsim.app;

import com.gsim.campaign.CampaignService;
import com.gsim.campaign.TurnService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.InteractionSession;
import com.gsim.storage.DataPaths;
import com.gsim.util.TimeProvider;

/**
 * 应用上下文 — 整个应用的单例依赖容器。
 * 不使用 Spring DI，手动装配依赖。
 */
public class ApplicationContext {

    private final AppConfig config;
    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;

    private final CampaignService campaignService;
    private final TurnService turnService;
    private final PlayerActionService playerActionService;
    private final InteractionContext interactionContext;
    private final InteractionSession interactionSession;
    private final InteractionManager interactionManager;

    public ApplicationContext() {
        this.config = new AppConfig();
        this.dataPaths = new DataPaths(config);
        this.timeProvider = new TimeProvider();

        // 服务层
        this.campaignService = new CampaignService(dataPaths, timeProvider);
        this.turnService = new TurnService(dataPaths, timeProvider);
        this.playerActionService = new PlayerActionService(dataPaths, timeProvider);

        // 交互层
        this.interactionContext = new InteractionContext();
        this.interactionSession = new InteractionSession(
                interactionContext, config,
                campaignService, turnService, playerActionService);
        this.interactionManager = new InteractionManager();
    }

    /**
     * 初始化：创建目录、注册命令等。
     */
    public void initialize() throws Exception {
        dataPaths.initialize();
    }

    // ---- Getters ----

    public AppConfig getConfig() {
        return config;
    }

    public DataPaths getDataPaths() {
        return dataPaths;
    }

    public TimeProvider getTimeProvider() {
        return timeProvider;
    }

    public CampaignService getCampaignService() {
        return campaignService;
    }

    public TurnService getTurnService() {
        return turnService;
    }

    public PlayerActionService getPlayerActionService() {
        return playerActionService;
    }

    public InteractionContext getInteractionContext() {
        return interactionContext;
    }

    public InteractionSession getInteractionSession() {
        return interactionSession;
    }

    public InteractionManager getInteractionManager() {
        return interactionManager;
    }
}
