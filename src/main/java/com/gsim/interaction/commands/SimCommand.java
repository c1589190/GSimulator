package com.gsim.interaction.commands;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.BranchContextRenderer;
import com.gsim.data.BranchUpdate;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    private final BranchMessageStore messageStore;

    public SimCommand(DataManager dm, BranchContextRenderer renderer, OrchestratorAgent orchestrator,
                      BranchMessageStore messageStore) {
        this.dm = dm;
        this.renderer = renderer;
        this.orchestrator = orchestrator;
        this.messageStore = messageStore;
    }

    @Override public String name() { return "sim"; }
    @Override public String description() { return "对当前节点执行 LLM 推演，覆盖 branch 推演章节"; }
    @Override public String usage() { return "/sim <推演备注>"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        String full = String.join(" ", args).trim();
        String simNote = full.isBlank() ? "" : full;

        try {
            String branchId = dm.getActiveBranch();
            String inputText = dm.getInputBody();
            if (!simNote.isBlank()) inputText += "\n\n/sim 备注: " + simNote;

            // 1. 渲染上下文并调用 LLM
            String contextMd = renderer.renderAsMarkdown();
            OrchestratorAgent.SimResult sr = orchestrator.runWithRenderedContext(contextMd, simNote);

            if (!sr.finalText().contains("推演结果") && sr.finalText().startsWith("LLM")) {
                // 写入 error message block
                String errId = messageStore.nextMessageId(branchId);
                messageStore.appendMessage(branchId,
                        BranchMessage.create(errId, "system", "error", "LLM error: " + sr.finalText()));
                return InteractionResult.fail(sr.finalText());
            }

            // 2. 对推演结果文本进行去重和污染过滤
            String cleanResult = ToolPollutionFilter.deduplicateToolDefinitions(sr.finalText());

            // 3. 解析 LLM 输出为章节
            Map<String, String> sections = parseSections(cleanResult);

            // 4. llmContextLog 最小占位（message blocks 为正式记录）
            String llmContextLog = "### message_blocks\n\n已通过 message block 格式写入。\n\n使用 /messages 查看。\n";

            BranchUpdate update = new BranchUpdate(
                    inputText.isBlank() ? "无。" : inputText,
                    llmContextLog,
                    sections.getOrDefault("result", cleanResult),
                    sections.getOrDefault("world", "无。"),
                    sections.getOrDefault("entity", "无。"),
                    sections.getOrDefault("rule", "无。"),
                    sections.getOrDefault("interaction", "无。"),
                    sections.getOrDefault("skill", "无。"),
                    sections.getOrDefault("risks", "无。"));

            // 5. 覆盖九个标准章节（会重写整个 branch 文件）
            dm.overwriteBranchSections(branchId, update);

            // 6. 在章节覆盖后追加 message blocks（sim_user / tool_call / tool_result / sim_response）
            writeSimMessageBlocks(branchId, sr, inputText);

            StringBuilder sb = new StringBuilder();
            sb.append("=== 推演完成 ===\n");
            sb.append("Branch: ").append(branchId).append("\n");
            sb.append("工具调用: ").append(sr.toolCalls().size()).append(" 次\n\n");
            sb.append(sr.finalText());
            return InteractionResult.ok("sim done: " + branchId, sb.toString());

        } catch (Exception e) {
            log.error("/sim failed: {}", e.getMessage(), e);
            try {
                String errId = messageStore.nextMessageId(dm.getActiveBranch());
                messageStore.appendMessage(dm.getActiveBranch(),
                        BranchMessage.create(errId, "system", "error", "/sim failed: " + e.getMessage()));
            } catch (Exception ignored) { /* best effort */ }
            return InteractionResult.fail("/sim failed: " + e.getMessage());
        }
    }

    /** 在 overwriteBranchSections 之后追加 message blocks 到 branch 文件。 */
    private void writeSimMessageBlocks(String branchId, OrchestratorAgent.SimResult sr, String inputText)
            throws java.io.IOException {
        // sim_user
        String sid = messageStore.nextMessageId(branchId);
        messageStore.appendMessage(branchId,
                BranchMessage.create(sid, "user", "sim_user", inputText.isBlank() ? "无。" : inputText));
        // tool_call / tool_result
        for (OrchestratorAgent.ToolCallRecord tc : sr.toolCalls()) {
            String tid = messageStore.nextMessageId(branchId);
            messageStore.appendMessage(branchId,
                    BranchMessage.tool(tid, "tool_call", tc.tool(), tc.args().toString()));
            String rid = messageStore.nextMessageId(branchId);
            messageStore.appendMessage(branchId,
                    BranchMessage.tool(rid, "tool_result", tc.tool(),
                            tc.result().success()
                                    ? tc.result().items().size() + " results: "
                                            + tc.result().items().stream()
                                                    .map(i -> i.title() + " (" + i.path() + ")")
                                                    .reduce((a, b) -> a + ", " + b).orElse("")
                                    : "error: " + tc.result().error()));
        }
        // sim_response
        String rid = messageStore.nextMessageId(branchId);
        messageStore.appendMessage(branchId,
                BranchMessage.create(rid, "assistant", "sim_response", sr.finalText()));
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
