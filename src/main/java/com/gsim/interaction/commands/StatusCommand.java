package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.campaign.Campaign;
import com.gsim.campaign.Turn;
import com.gsim.app.AppConfig;

import java.util.Optional;

/**
 * /status — 显示当前系统状态。
 */
public class StatusCommand implements InteractionCommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "显示当前状态（campaign、turn、玩家行动数等）";
    }

    @Override
    public String usage() {
        return "/status";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        var ctx = session.getContext();
        var config = session.getConfig();
        var campaignService = session.getCampaignService();
        var turnService = session.getTurnService();
        var actionService = session.getPlayerActionService();

        var sb = new StringBuilder();
        sb.append("========== GSimulator 状态 ==========\n\n");

        // Campaign
        sb.append("当前 Campaign: ");
        Optional<Campaign> camp = campaignService.getCurrentCampaign();
        sb.append(camp.map(c -> c.campaignId() + " (" + c.name() + ")").orElse("无"));
        sb.append("\n");

        // Turn
        sb.append("当前 Turn:     ");
        Optional<Turn> turn = turnService.getCurrentTurn();
        if (turn.isPresent()) {
            Turn t = turn.get();
            sb.append(t.turnId())
                    .append(" (index=").append(t.index())
                    .append(", status=").append(t.status())
                    .append(")");
        } else {
            sb.append("无");
        }
        sb.append("\n");

        // Actions
        sb.append("玩家行动数:    ").append(actionService.getActionCount()).append("\n\n");

        // Paths
        sb.append("--- 路径 ---\n");
        sb.append("数据目录:      ").append(config.getDataDir()).append("\n");
        sb.append("Import 目录:   ").append(config.getImportDir()).append("\n");
        sb.append("Output 目录:   ").append(config.getOutputDir()).append("\n");
        sb.append("Log 目录:      ").append(config.getLogDir()).append("\n\n");

        // Services
        sb.append("--- 服务状态 ---\n");
        sb.append("ChromaDB:      ")
                .append(config.isChromaEnabled()
                        ? "已启用 (" + config.getChromaBaseUrl() + ")"
                        : "未启用")
                .append("\n");
        sb.append("LLM:           ");
        if (config.isLlmConfigured()) {
            sb.append("已配置 (").append(config.getLlmModel()).append(")");
        } else {
            sb.append("未配置 (执行 /config init)");
        }
        sb.append("\n");
        sb.append("WebResearch:   ").append(config.isWebResearchEnabled() ? "已启用" : "未启用").append("\n\n");

        // Session
        sb.append("--- 会话 ---\n");
        sb.append("会话开始时间:  ").append(ctx.getSessionStartedAt()).append("\n");
        sb.append("Campaign ID:   ")
                .append(ctx.getCurrentCampaignId() != null
                        ? ctx.getCurrentCampaignId()
                        : "无")
                .append("\n");
        sb.append("Turn ID:       ").append(ctx.getCurrentTurnId() != null ? ctx.getCurrentTurnId() : "无").append("\n");

        sb.append("\n======================================\n");

        return InteractionResult.ok(sb.toString());
    }
}
