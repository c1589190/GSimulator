package com.gsim.interaction;

import com.gsim.app.AppConfig;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.TurnService;
import com.gsim.campaign.PlayerActionService;

/**
 * 交互会话 — 持有所有服务引用和交互上下文。
 */
public class InteractionSession {

    private final InteractionContext context;
    private final AppConfig config;
    private final CampaignService campaignService;
    private final TurnService turnService;
    private final PlayerActionService playerActionService;

    public InteractionSession(
            InteractionContext context,
            AppConfig config,
            CampaignService campaignService,
            TurnService turnService,
            PlayerActionService playerActionService) {
        this.context = context;
        this.config = config;
        this.campaignService = campaignService;
        this.turnService = turnService;
        this.playerActionService = playerActionService;
    }

    public InteractionContext getContext() {
        return context;
    }

    public AppConfig getConfig() {
        return config;
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
}
