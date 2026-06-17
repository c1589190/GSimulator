package com.gsim.output;

import com.gsim.agent.PlayerActionAnalysis;
import com.gsim.agent.WriterOutput;
import com.gsim.chroma.EvidenceBundle;
import com.gsim.chroma.EvidenceItem;
import com.gsim.timeline.TimelineEvent;
import com.gsim.world.StateChange;

import java.util.List;

/**
 * 控制台输出格式化器 — 将 /run 结果格式化为 CLI 人类可读格式。
 */
public class ConsoleOutputFormatter {

    /**
     * 格式化回合结算结果。
     */
    public String formatTurnResult(
            String campaignId, String turnId, String taskId,
            List<PlayerActionAnalysis> analyses,
            EvidenceBundle evidence,
            String researchSummary,
            List<TimelineEvent> timelineEvents,
            List<StateChange> stateChanges,
            WriterOutput writerOutput,
            List<String> outputFiles,
            List<String> warnings
    ) {
        var sb = new StringBuilder();
        sb.append("\n========== GSimulator 回合结算 ==========\n\n");
        sb.append("Campaign: ").append(campaignId).append("\n");
        sb.append("Turn: ").append(turnId).append("\n");
        sb.append("TaskId: ").append(taskId).append("\n\n");

        sb.append("【一、本回合玩家行动摘要】\n\n");
        if (analyses != null) {
            for (var a : analyses) {
                sb.append("  ").append(a.playerName()).append(": ").append(a.summary()).append("\n");
            }
        }

        sb.append("\n【二、知识库检索摘要】\n\n");
        if (evidence != null) {
            sb.append(evidence.summary()).append("\n");
            for (EvidenceItem item : evidence.items()) {
                sb.append("  - [").append(item.collection()).append("] ")
                        .append(item.title()).append(" (score=")
                        .append(String.format("%.2f", item.score())).append(")\n");
            }
        }

        sb.append("\n【三、联网研究摘要】\n\n");
        sb.append(researchSummary != null ? researchSummary : "  未启用联网研究\n");

        sb.append("\n【四、玩家行动分析】\n\n");
        if (analyses != null) {
            for (var a : analyses) {
                sb.append("  === ").append(a.playerName()).append(" ===\n");
                sb.append("  军事意图: ").append(a.militaryIntent()).append("\n");
                sb.append("  政治意图: ").append(a.politicalIntent()).append("\n");
                sb.append("  经济意图: ").append(a.economicIntent()).append("\n");
                sb.append("  外交意图: ").append(a.diplomaticIntent()).append("\n");
                if (!a.contradictions().isEmpty()) {
                    sb.append("  矛盾点: ").append(String.join(", ", a.contradictions())).append("\n");
                }
            }
        }

        sb.append("\n【五、主要矛盾与冲突点】\n");
        sb.append("  (Phase 8 will expand)\n");

        sb.append("\n【六、时间线变化】\n");
        if (timelineEvents != null) {
            for (var evt : timelineEvents) {
                sb.append("  - ").append(evt.date()).append(": ")
                        .append(evt.title()).append("\n");
            }
        }

        sb.append("\n【七、世界状态变化建议】\n");
        if (stateChanges != null) {
            for (var sc : stateChanges) {
                sb.append("  - ").append(sc.targetType()).append("/")
                        .append(sc.targetId()).append(".")
                        .append(sc.field()).append(": ")
                        .append(sc.newValue()).append("\n");
            }
        }

        sb.append("\n【八、公开战报 / 推文】\n\n");
        if (writerOutput != null && writerOutput.publicText() != null) {
            sb.append(writerOutput.publicText()).append("\n");
        }

        sb.append("\n【九、主持人私密裁定说明】\n\n");
        if (writerOutput != null && writerOutput.privateNotes() != null) {
            sb.append(writerOutput.privateNotes()).append("\n");
        }

        sb.append("\n【十、不确定性与后续待裁定点】\n\n");
        if (warnings != null && !warnings.isEmpty()) {
            for (String w : warnings) {
                sb.append("  ⚠️ ").append(w).append("\n");
            }
        }

        sb.append("\n输出文件：\n");
        if (outputFiles != null) {
            for (String f : outputFiles) {
                sb.append("  - ").append(f).append("\n");
            }
        }

        sb.append("\n=========================================\n");
        return sb.toString();
    }
}
