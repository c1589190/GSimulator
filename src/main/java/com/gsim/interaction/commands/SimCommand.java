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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SimCommand.class);
    private final DataManager dm;
    private final BranchContextRenderer renderer;
    private final OrchestratorAgent orchestrator;

    public SimCommand(DataManager dm, BranchContextRenderer renderer, OrchestratorAgent orchestrator) {
        this.dm = dm; this.renderer = renderer; this.orchestrator = orchestrator;
    }

    @Override public String name() { return "sim"; }
    @Override public String description() { return "对当前节点执行 LLM 推演，覆盖 branch 推演章节"; }
    @Override public String usage() { return "/sim <推演备注>"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        String simNote = full.isBlank() ? "" : full;

        try {
            String contextMd = renderer.renderAsMarkdown();
            OrchestratorAgent.SimResult sr = orchestrator.runWithRenderedContext(contextMd, simNote);

            if (!sr.finalText().contains("推演结果") && sr.finalText().startsWith("LLM")) {
                return InteractionResult.fail(sr.finalText());
            }

            // 解析 LLM 输出为章节
            Map<String, String> sections = parseSections(sr.finalText());

            // 构建 LLM 上下文日志
            StringBuilder llmLog = new StringBuilder();
            for (OrchestratorAgent.MessageTrace t : sr.trace()) {
                String role = t.type().replace("sim_input", "user").replace("sim_output", "assistant");
                llmLog.append("### ").append(role).append("\n\n").append(t.content()).append("\n\n");
            }

            String inputText = dm.getInputBody();
            if (!simNote.isBlank()) inputText += "\n\n/sim 备注: " + simNote;

            BranchUpdate update = new BranchUpdate(
                    inputText.isBlank() ? "无。" : inputText,
                    llmLog.toString(),
                    sections.getOrDefault("result", sr.finalText()),
                    sections.getOrDefault("world", "无。"),
                    sections.getOrDefault("entity", "无。"),
                    sections.getOrDefault("rule", "无。"),
                    sections.getOrDefault("interaction", "无。"),
                    sections.getOrDefault("skill", "无。"),
                    sections.getOrDefault("risks", "无。"));

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

    /** 从 LLM 输出中按 ## 标题解析九个标准章节。 */
    static Map<String, String> parseSections(String rawOutput) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isBlank()) return sections;

        Pattern p = Pattern.compile("##\\s*(三|四|五|六|七|八|九)、(.+?)\\n(.*?)(?=\\n##\\s*(?:三|四|五|六|七|八|九)、|$)", Pattern.DOTALL);
        Matcher m = p.matcher(rawOutput);

        Map<String, String> keyMap = Map.of(
                "三", "result", "四", "world", "五", "entity",
                "六", "rule", "七", "interaction", "八", "skill", "九", "risks");

        while (m.find()) {
            String num = m.group(1);
            String content = (m.group(2) + "\n" + m.group(3)).trim();
            String key = keyMap.getOrDefault(num, num);
            sections.put(key, content.isBlank() ? "无。" : content);
        }

        // 如果没有匹配到任何章节，全部放入 result
        if (sections.isEmpty()) {
            sections.put("result", rawOutput);
        }

        // 补充缺失章节
        for (String k : List.of("result", "world", "entity", "rule", "interaction", "skill", "risks")) {
            sections.putIfAbsent(k, "无。");
        }

        return sections;
    }
}
