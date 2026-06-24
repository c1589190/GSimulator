package com.gsim.output;

import com.gsim.chroma.EvidenceBundle;
import com.gsim.chroma.EvidenceItem;
import com.gsim.timeline.TimelineEvent;
import com.gsim.world.StateChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Markdown 输出写入器 — 将 /run 结果保存为人类可读的 Markdown 文件。
 *
 * <p>PlayerActionAnalysis 和 WriterOutput 已废弃，相关参数替换为通用类型。
 */
public class MarkdownOutputWriter {

    /**
     * 写入完整的回合结算 Markdown 文件。
     */
    public Path write(
            Path outputFile,
            String campaignId,
            String turnId,
            String taskId,
            List<String> analyses,                  // was List<PlayerActionAnalysis>
            EvidenceBundle evidence,
            List<TimelineEvent> timelineEvents,
            List<StateChange> stateChanges,
            String writerOutput,                    // was WriterOutput
            List<String> warnings
    ) throws IOException {

        var sb = new StringBuilder();
        sb.append("# GSimulator 回合结算报告\n\n");
        sb.append("- **Campaign**: ").append(campaignId).append("\n");
        sb.append("- **Turn**: ").append(turnId).append("\n");
        sb.append("- **TaskId**: ").append(taskId).append("\n\n");
        sb.append("---\n\n");

        // 一、玩家行动分析
        sb.append("## 一、玩家行动分析\n\n");
        if (analyses != null) {
            for (var a : analyses) {
                sb.append("- ").append(a).append("\n");
            }
            sb.append("\n");
        }

        // 二、知识库检索
        sb.append("## 二、知识库检索摘要\n\n");
        if (evidence != null && evidence.items() != null) {
            for (EvidenceItem item : evidence.items()) {
                sb.append("- **").append(item.title()).append("** [")
                        .append(item.collection()).append("] score=")
                        .append(String.format("%.2f", item.score())).append("\n");
                if (item.text() != null) {
                    String snippet = item.text().length() > 200 ?
                            item.text().substring(0, 200) + "..." : item.text();
                    sb.append("  > ").append(snippet).append("\n");
                }
            }
        }
        sb.append("\n");

        // 三、时间线变化
        sb.append("## 三、时间线变化\n\n");
        if (timelineEvents != null) {
            for (var evt : timelineEvents) {
                sb.append("- **").append(evt.date()).append("**: ").append(evt.title())
                        .append(" (confidence=").append(String.format("%.0f%%", evt.confidence() * 100)).append(")\n");
                if (evt.description() != null) {
                    sb.append("  > ").append(evt.description()).append("\n");
                }
            }
        }
        sb.append("\n");

        // 四、世界状态变化
        sb.append("## 四、世界状态变化建议\n\n");
        if (stateChanges != null) {
            for (var sc : stateChanges) {
                sb.append("- **").append(sc.targetType()).append("/").append(sc.targetId())
                        .append("**.").append(sc.field()).append(": ")
                        .append(sc.oldValue()).append(" → ").append(sc.newValue()).append("\n");
                sb.append("  > Reason: ").append(sc.reason()).append("\n");
            }
        }
        sb.append("\n");

        // 五、公开战报
        sb.append("## 五、公开战报\n\n");
        if (writerOutput != null) {
            sb.append(writerOutput).append("\n\n");
        }

        // 六、警告
        if (warnings != null && !warnings.isEmpty()) {
            sb.append("## 六、警告与不确定性\n\n");
            for (String w : warnings) {
                sb.append("- ⚠️ ").append(w).append("\n");
            }
            sb.append("\n");
        }

        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, sb.toString());
        return outputFile;
    }
}
