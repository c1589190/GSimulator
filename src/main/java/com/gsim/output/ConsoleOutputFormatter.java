package com.gsim.output;

import com.gsim.chroma.EvidenceBundle;
import com.gsim.chroma.EvidenceItem;
import com.gsim.timeline.TimelineEvent;
import com.gsim.world.StateChange;

import java.util.List;

/**
 * 控制台输出格式化器 — 将 /run 结果格式化为 CLI 人类可读格式。
 *
 * <p>PlayerActionAnalysis 和 WriterOutput 已废弃，相关参数替换为通用类型。
 */
public class ConsoleOutputFormatter {

    /**
     * 格式化回合结算结果。
     */
    public String formatTurnResult(
            String campaignId, String turnId, String taskId,
            List<String> analyses,                  // was List<PlayerActionAnalysis>
            EvidenceBundle evidence,
            String researchSummary,
            List<TimelineEvent> timelineEvents,
            List<StateChange> stateChanges,
            String writerOutput,                    // was WriterOutput
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
                sb.append("  - ").append(a).append("\n");
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

        sb.append("\n【四、时间线变化】\n");
        if (timelineEvents != null) {
            for (var evt : timelineEvents) {
                sb.append("  - ").append(evt.date()).append(": ")
                        .append(evt.title()).append("\n");
            }
        }

        sb.append("\n【五、世界状态变化建议】\n");
        if (stateChanges != null) {
            for (var sc : stateChanges) {
                sb.append("  - ").append(sc.targetType()).append("/")
                        .append(sc.targetId()).append(".")
                        .append(sc.field()).append(": ")
                        .append(sc.newValue()).append("\n");
            }
        }

        sb.append("\n【六、公开战报 / 推文】\n\n");
        if (writerOutput != null) {
            sb.append(writerOutput).append("\n");
        }

        sb.append("\n【七、不确定性与后续待裁定点】\n\n");
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
