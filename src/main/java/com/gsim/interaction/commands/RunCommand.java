package com.gsim.interaction.commands;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.campaign.PlayerAction;
import com.gsim.campaign.PlayerActionService;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * /run 命令 — 结算当前回合，驱动 OrchestratorAgent 进行推演。
 *
 * 用法：
 *   /run                                   — 使用默认配置结算
 *   /run 本回合允许使用 wiki_search 工具    — 附带主持人指令
 */
public class RunCommand implements InteractionCommand {

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    private final OrchestratorAgent orchestrator;

    public RunCommand(OrchestratorAgent orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "run";
    }

    @Override
    public String description() {
        return "结算当前回合，驱动 LLM 推演引擎";
    }

    @Override
    public String usage() {
        return "/run [指令]   — 结算当前回合，可附带主持人指令";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        String instruction = extractInstruction(args);
        List<PlayerAction> actions = session.getPlayerActionService().getActions();

        if (actions.isEmpty()) {
            log.info("No player actions for this turn");
        }

        String campaignId = session.getContext().getCurrentCampaignId();
        String turnId = session.getContext().getCurrentTurnId();
        String turnInfo = "Campaign: " + campaignId + "\nTurn: " + turnId;

        log.info("Running orchestration for turn {} with {} actions", turnId, actions.size());

        OrchestratorAgent.RunResult result = orchestrator.run(actions, instruction, turnInfo);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 推演结果 ===\n");
        sb.append("Turn: ").append(turnId).append("\n");
        sb.append("玩家行动数: ").append(actions.size()).append("\n");
        sb.append("工具调用次数: ").append(result.toolCalls().size()).append("\n\n");

        // 工具调用记录
        if (!result.toolCalls().isEmpty()) {
            sb.append("--- 工具调用记录 ---\n");
            for (int i = 0; i < result.toolCalls().size(); i++) {
                OrchestratorAgent.ToolCallRecord tc = result.toolCalls().get(i);
                sb.append("[").append(i + 1).append("] ").append(tc.tool())
                        .append(": ").append(tc.args()).append("\n");
                ToolResult tr = tc.result();
                if (tr.success()) {
                    sb.append("    ✓ ").append(tr.items().size()).append(" 条结果\n");
                    for (ToolResult.Item item : tr.items()) {
                        sb.append("      - ").append(item.path()).append("\n");
                    }
                } else {
                    sb.append("    ✗ ").append(tr.error()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("--- 推演正文 ---\n");
        sb.append(result.finalText());

        return InteractionResult.ok("run completed for turn " + turnId, sb.toString());
    }

    private String extractInstruction(String[] args) {
        if (args == null || args.length == 0) return "";
        // args[0] 是 CommandParser 传入的完整参数字符串
        String full = args[0];
        if (full == null || full.isBlank()) return "";
        return full.trim();
    }
}
