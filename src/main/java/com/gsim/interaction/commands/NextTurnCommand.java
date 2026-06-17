package com.gsim.interaction.commands;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NextTurnCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(NextTurnCommand.class);
    private final DataManager dm;

    public NextTurnCommand(DataManager dm) { this.dm = dm; }

    @Override public String name() { return "nextturn"; }
    @Override public String description() { return "创建下一回合时间节点，不调用 LLM"; }
    @Override public String usage() { return "/nextturn <世界时间> [备注]"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        if (full.isBlank()) return InteractionResult.fail("Usage: /nextturn <世界时间> [备注]");

        String[] t = full.split("\\s+", 2);
        String worldTime = t[0];
        String note = t.length > 1 ? t[1] : "";

        try {
            boolean hasResult = dm.hasSimulationResult(dm.getActiveBranch());
            String warning = "";
            if (!hasResult) {
                warning = "⚠ 当前节点尚未推演（三、推演结果为空/待推演），仍继续创建下一节点。\n";
                log.warn("Creating next turn without simulation result for {}", dm.getActiveBranch());
            }

            DataDocument newDoc = dm.createNextTurnBranch(worldTime, note);

            StringBuilder sb = new StringBuilder();
            sb.append(warning);
            sb.append("=== 下一回合 ===\n");
            sb.append("新节点: ").append(newDoc.id()).append("\n");
            sb.append("Parent: ").append(newDoc.frontMatter().get("parent")).append("\n");
            sb.append("Turn: ").append(newDoc.frontMatter().get("turn")).append("\n");
            sb.append("世界时间: ").append(worldTime).append("\n");
            sb.append("Input 已清空，准备接收新玩家命令。\n");
            return InteractionResult.ok("nextturn: " + newDoc.id(), sb.toString());

        } catch (Exception e) {
            log.error("/nextturn failed: {}", e.getMessage(), e);
            return InteractionResult.fail(e.getMessage());
        }
    }
}
