package com.gsim.interaction.commands;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.context.BranchContextRenderer;
import com.gsim.data.BranchUpdate;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SimCommand.class);
    private final DataManager dm;
    private final BranchContextRenderer renderer;
    private final OrchestratorAgent orchestrator;

    public SimCommand(DataManager dm, BranchContextRenderer renderer, OrchestratorAgent orchestrator) {
        this.dm = dm; this.renderer = renderer; this.orchestrator = orchestrator;
    }

    @Override public String name() { return "sim"; }
    @Override public String description() { return "对当前节点执行推演，覆盖当前 branch 推演信息"; }
    @Override public String usage() { return "/sim <推演备注>"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        String simNote = full.isBlank() ? "" : full;

        try {
            String contextMd = renderer.renderAsMarkdown();
            OrchestratorAgent.SimResult sr = orchestrator.runWithRenderedContext(contextMd, simNote);

            // 构建 LLM 上下文日志
            StringBuilder llmLog = new StringBuilder();
            for (OrchestratorAgent.MessageTrace t : sr.trace()) {
                llmLog.append("### ").append(t.type().replace("sim_input", "user")
                        .replace("sim_output", "assistant")).append("\n\n");
                llmLog.append(t.content()).append("\n\n");
            }

            String inputText = dm.getInputBody();
            if (!simNote.isBlank()) inputText += "\n\n/sim 备注: " + simNote;

            BranchUpdate update = new BranchUpdate(
                    inputText.isBlank() ? "无。" : inputText,
                    llmLog.toString(),
                    sr.finalText(),
                    "无。", "无。", "无。", "无。", "无。", "待后续推演。");

            dm.overwriteBranchSections(dm.getActiveBranch(), update);

            StringBuilder sb = new StringBuilder();
            sb.append("=== 推演完成 ===\n");
            sb.append("Branch: ").append(dm.getActiveBranch()).append("\n");
            sb.append("工具调用: ").append(sr.toolCalls().size()).append(" 次\n\n");
            sb.append(sr.finalText());
            return InteractionResult.ok("sim done: " + dm.getActiveBranch(), sb.toString());

        } catch (Exception e) {
            log.error("/sim failed: {}", e.getMessage(), e);
            return InteractionResult.fail("/sim failed: " + e.getMessage());
        }
    }
}
