package com.gsim.interaction.commands;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.branch.BranchAnalysis;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.SessionMessage;
import com.gsim.context.summary.NodeSummaryManager;
import com.gsim.data.BranchUpdate;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * /sim — 对当前节点执行 LLM 推演。
 *
 * <p>新路径（ContextSession 模式）：
 * <ol>
 *   <li>创建/复用 ContextSession</li>
 *   <li>写入 sim_user 到 SessionMessageStore + BranchMessageStore</li>
 *   <li>renderForLlm() 获取 LLM 输入</li>
 *   <li>调用 OrchestratorAgent.runWithContextSession()</li>
 *   <li>写入结果到两个 store，更新 branch 章节，更新 NodeSummary</li>
 * </ol>
 */
public class SimCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(SimCommand.class);
    private final DataManager dm;
    private final BranchContextRenderer renderer;
    private final OrchestratorAgent orchestrator;
    private final BranchMessageStore messageStore;
    private final BranchAnalyzer branchAnalyzer;
    private final ContextSessionManager ctxSessionManager;
    private final NodeSummaryManager summaryManager;

    public SimCommand(DataManager dm, BranchContextRenderer renderer, OrchestratorAgent orchestrator,
                      BranchMessageStore messageStore, BranchAnalyzer branchAnalyzer,
                      ContextSessionManager ctxSessionManager,
                      NodeSummaryManager summaryManager) {
        this.dm = dm;
        this.renderer = renderer;
        this.orchestrator = orchestrator;
        this.messageStore = messageStore;
        this.branchAnalyzer = branchAnalyzer;
        this.ctxSessionManager = ctxSessionManager;
        this.summaryManager = summaryManager;
    }

    @Override public String name() { return "sim"; }
    @Override public String description() { return "对当前节点执行 LLM 推演，覆盖 branch 推演章节"; }
    @Override public String usage() { return "/sim <推演备注>"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (session.getLlmClient() == null || !session.getLlmClient().isAvailable()) {
            return InteractionResult.fail(
                    "LLM is not configured.\n"
                            + "Run /config init to set up your LLM, or /config status to see current config.");
        }

        String full = String.join(" ", args).trim();
        String simNote = full.isBlank() ? "" : full;
        String apiSessionId = "default";

        try {
            String branchId = dm.getActiveBranch();
            String inputText = dm.getInputBody();
            if (!simNote.isBlank()) inputText += "\n\n/sim 备注: " + simNote;

            // 0. 老节点检查
            StringBuilder warning = new StringBuilder();
            BranchAnalysis analysis = branchAnalyzer.analyze(branchId, "compact");
            if (analysis.oldNode()) {
                warning.append("⚠️  警告：当前节点是旧节点 (状态: ")
                        .append(analysis.nodeAgeStatus()).append(")\n");
                if (analysis.childBranchCount() > 0)
                    warning.append("  - 已有 ").append(analysis.childBranchCount()).append(" 个子分支\n");
                warning.append("\n/sim 将覆盖当前节点推演结果，但不会删除已有子分支。\n\n");
            }

            String compactSummary = BranchAnalyzer.renderCompactMarkdown(analysis);
            String enrichedInput = (inputText.isBlank() ? "" : inputText)
                    + "\n\n<!-- 本次推演发生时的节点态势 -->\n"
                    + compactSummary;

            // 1. 获取或创建 ContextSession
            ContextSession cs = ctxSessionManager.getOrCreateActiveSession(apiSessionId, branchId);
            String csId = cs.sessionId();

            // 2. 写入 sim_user 到 BranchMessageStore（长期档案）
            String sid = messageStore.nextMessageId(branchId);
            messageStore.appendMessage(branchId,
                    BranchMessage.create(sid, "user", "sim_user",
                            enrichedInput.isBlank() ? "无。" : enrichedInput));

            // 3. 写入 sim_user 到 SessionMessageStore
            SessionMessage smUser = new SessionMessage(
                    "msg-" + System.nanoTime(), csId, branchId,
                    "user", "sim_user", enrichedInput, Instant.now(),
                    Map.of("branchId", branchId, "nodeId", branchId,
                            "baseContextId", cs.baseContextId(), "source", "sim")
            );
            ctxSessionManager.appendMessage(csId, smUser);

            // 4. 渲染 LLM 输入（base + session messages + sim prompt）
            String llmInput = ctxSessionManager.renderForLlm(apiSessionId, simNote);

            // 5. 调用 LLM（ContextSession 路径）
            String baseMarkdown = ctxSessionManager.getBaseContextMarkdown(apiSessionId);
            if (baseMarkdown == null) baseMarkdown = "";
            List<SessionMessage> sessionMsgs = ctxSessionManager.getSessionMessages(csId);
            List<SessionMessage> historyMsgs = sessionMsgs.stream()
                    .filter(m -> !m.id().equals(smUser.id()))
                    .toList();

            OrchestratorAgent.SimResult sr = orchestrator.runWithContextSession(
                    baseMarkdown, historyMsgs, simNote);

            if (!sr.finalText().contains("推演结果") && sr.finalText().startsWith("LLM")) {
                String errId = messageStore.nextMessageId(branchId);
                messageStore.appendMessage(branchId,
                        BranchMessage.create(errId, "system", "error", "LLM error: " + sr.finalText()));
                return InteractionResult.fail(sr.finalText());
            }

            // 6. 清理和解析结果
            String cleanResult = ToolPollutionFilter.deduplicateToolDefinitions(sr.finalText());
            Map<String, String> sections = parseSections(cleanResult);
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

            // 7. 覆盖 branch 标准章节
            dm.overwriteBranchSections(branchId, update);

            // 8. 写入 tool_call / tool_result / sim_response 到两个 store
            writeMessages(branchId, csId, sr, enrichedInput);

            // 9. 更新 NodeSummary
            if (summaryManager != null) {
                try {
                    summaryManager.updateFromSimulation(branchId, sr.finalText(), sr.trace());
                } catch (Exception e) {
                    log.warn("Failed to update NodeSummary for {}: {}", branchId, e.getMessage());
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!warning.isEmpty()) sb.append(warning).append("\n");
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
            } catch (Exception ignored) {}
            return InteractionResult.fail("/sim failed: " + e.getMessage());
        }
    }

    /** 写入 tool/sim 消息到 BranchMessageStore + SessionMessageStore。 */
    private void writeMessages(String branchId, String csId,
                                OrchestratorAgent.SimResult sr, String inputText)
            throws java.io.IOException {
        // sim_response 写入 BranchMessageStore
        String rid = messageStore.nextMessageId(branchId);
        messageStore.appendMessage(branchId,
                BranchMessage.create(rid, "assistant", "sim_response", sr.finalText()));

        // sim_response 写入 SessionMessageStore
        SessionMessage smResp = new SessionMessage(
                "msg-" + System.nanoTime(), csId, branchId,
                "assistant", "sim_response", sr.finalText(), Instant.now(),
                Map.of("branchId", branchId, "nodeId", branchId,
                        "baseContextId", csId, "source", "sim")
        );
        ctxSessionManager.appendMessage(csId, smResp);

        // tool messages
        for (OrchestratorAgent.ToolCallRecord tc : sr.toolCalls()) {
            String tid = messageStore.nextMessageId(branchId);
            messageStore.appendMessage(branchId,
                    BranchMessage.tool(tid, "tool_call", tc.tool(), tc.args().toString()));
            String trId = messageStore.nextMessageId(branchId);
            messageStore.appendMessage(branchId,
                    BranchMessage.tool(trId, "tool_result", tc.tool(),
                            tc.result().success()
                                    ? tc.result().items().size() + " results"
                                    : "error: " + tc.result().error()));

            SessionMessage smTc = SessionMessage.toolCall(csId, branchId, tc.tool(),
                    tc.args().toString());
            ctxSessionManager.appendMessage(csId, smTc);

            String trContent = tc.result().success()
                    ? tc.result().items().size() + " results"
                    : "error: " + tc.result().error();
            SessionMessage smTr = SessionMessage.toolResult(csId, branchId, tc.tool(), trContent);
            ctxSessionManager.appendMessage(csId, smTr);
        }
    }

    /** 从 LLM 输出中按 ## 标题解析九个标准章节。 */
    static Map<String, String> parseSections(String rawOutput) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isBlank()) return sections;

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "##\\s*(三|四|五|六|七|八|九)、(.+?)\\n(.*?)(?=\\n##\\s*(?:三|四|五|六|七|八|九)、|$)",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(rawOutput);

        Map<String, String> keyMap = Map.of(
                "三", "result", "四", "world", "五", "entity",
                "六", "rule", "七", "interaction", "八", "skill", "九", "risks");

        while (m.find()) {
            String num = m.group(1);
            String content = (m.group(2) + "\n" + m.group(3)).trim();
            String key = keyMap.getOrDefault(num, num);
            sections.put(key, content.isBlank() ? "无。" : content);
        }

        if (sections.isEmpty()) sections.put("result", rawOutput);
        for (String k : List.of("result", "world", "entity", "rule", "interaction", "skill", "risks")) {
            sections.putIfAbsent(k, "无。");
        }

        return sections;
    }
}
