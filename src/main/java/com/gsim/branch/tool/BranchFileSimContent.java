package com.gsim.branch.tool;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Branch 文件中推演内容记录 和 回合结算 的读写操作。
 *
 * Marker block 格式：
 *   <!-- SIM_CONTENT:sim0001 START -->
 *   #### [sim0001] 标题
 *   - 类型：xxx
 *   - 状态：xxx
 *   - 来源：xxx
 *   - 时间：xxx
 *   正文...
 *   <!-- SIM_CONTENT:sim0001 END -->
 *
 * Turn settlement marker:
 *   <!-- TURN_SETTLEMENT START -->
 *   结算正文...
 *   <!-- TURN_SETTLEMENT END -->
 */
public final class BranchFileSimContent {

    private static final Pattern SIM_MARKER_PATTERN =
            Pattern.compile("<!--\\s*SIM_CONTENT:(sim\\d+)\\s+START\\s*-->(.*?)<!--\\s*SIM_CONTENT:\\1\\s+END\\s*-->",
                    Pattern.DOTALL);
    private static final Pattern TURN_SETTLEMENT_PATTERN =
            Pattern.compile("<!--\\s*TURN_SETTLEMENT\\s+START\\s*-->(.*?)<!--\\s*TURN_SETTLEMENT\\s+END\\s*-->",
                    Pattern.DOTALL);

    private static final String SIM_CONTENT_SECTION_HEADER = "### 推演内容记录";
    private static final String TURN_SETTLEMENT_SECTION_HEADER = "### 本回合结算";

    private BranchFileSimContent() {}

    // ==================== Sim Content Read ====================

    /**
     * 从 branch markdown 中解析出所有 sim content 记录。
     */
    public static List<SimContentRecord> parseSimContents(String markdown, String branchId) {
        List<SimContentRecord> records = new ArrayList<>();
        Matcher m = SIM_MARKER_PATTERN.matcher(markdown);
        while (m.find()) {
            String simId = m.group(1);
            String block = m.group(2).trim();
            records.add(parseSimBlock(simId, branchId, block));
        }
        return records;
    }

    /**
     * 解析单条 sim content block 的元数据。
     */
    private static SimContentRecord parseSimBlock(String simId, String branchId, String block) {
        String type = "other";
        String title = "";
        String status = "draft";
        String source = "agent";
        String createdAt = "";
        String summary = "";
        String content = "";
        String metadata = "";

        // 解析标题: #### [sim0001] 标题
        Matcher titleMatcher = Pattern.compile("####\\s*\\[" + simId + "\\]\\s*(.*?)\\n").matcher(block);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        // 解析元数据行: - 类型：xxx
        for (String line : block.split("\\n")) {
            line = line.trim();
            if (line.startsWith("- 类型：") || line.startsWith("- type:")) {
                type = extractMetaValue(line);
            } else if (line.startsWith("- 状态：") || line.startsWith("- status:")) {
                status = extractMetaValue(line);
            } else if (line.startsWith("- 来源：") || line.startsWith("- source:")) {
                source = extractMetaValue(line);
            } else if (line.startsWith("- 时间：") || line.startsWith("- time:") || line.startsWith("- createdAt:")) {
                createdAt = extractMetaValue(line);
            } else if (line.startsWith("- 摘要：") || line.startsWith("- summary:")) {
                summary = extractMetaValue(line);
            } else if (line.startsWith("- 元数据：") || line.startsWith("- metadata:")) {
                metadata = extractMetaValue(line);
            }
        }

        // 提取正文：元数据行之后的内容（跳过标题和元数据行）
        StringBuilder contentBuf = new StringBuilder();
        boolean inContent = false;
        for (String line : block.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("####") || line.startsWith("- 类型") || line.startsWith("- type")
                    || line.startsWith("- 状态") || line.startsWith("- status")
                    || line.startsWith("- 来源") || line.startsWith("- source")
                    || line.startsWith("- 时间") || line.startsWith("- time") || line.startsWith("- createdAt")
                    || line.startsWith("- 摘要") || line.startsWith("- summary")
                    || line.startsWith("- 元数据") || line.startsWith("- metadata")) {
                inContent = true;
                continue;
            }
            if (inContent || !line.startsWith("-")) {
                contentBuf.append(line).append("\n");
            }
        }
        content = contentBuf.toString().trim();

        return new SimContentRecord(
                simId, branchId, type, title, content, summary, status, source, createdAt, metadata);
    }

    private static String extractMetaValue(String line) {
        int colon = line.indexOf("：");
        if (colon < 0) colon = line.indexOf(":");
        return colon >= 0 ? line.substring(colon + 1).trim() : "";
    }

    // ==================== Sim Content Write ====================

    /**
     * 向 branch markdown 追加一条 sim content。
     * 返回修改后的 markdown 字符串。
     */
    public static String appendSimContent(String markdown, SimContentRecord record) {
        String block = buildSimBlock(record);

        // 查找 "### 推演内容记录" 小节位置
        int simSectionIdx = markdown.indexOf(SIM_CONTENT_SECTION_HEADER);

        if (simSectionIdx >= 0) {
            // 已有推演内容记录区：追加到最后一条记录之后
            // 找到推演内容记录区后面的第一个小节标题（### ）或 TURN_SETTLEMENT
            int afterHeader = simSectionIdx + SIM_CONTENT_SECTION_HEADER.length();
            // 跳到行末
            int newlineAfter = markdown.indexOf('\n', afterHeader);
            if (newlineAfter < 0) newlineAfter = afterHeader;

            int insertPoint = findSimContentInsertPoint(markdown, newlineAfter + 1);
            return markdown.substring(0, insertPoint) + "\n" + block + "\n" + markdown.substring(insertPoint);
        } else {
            // 没有推演内容记录区：在"三、推演结果"下创建
            return insertAfterSectionHeader(markdown, "三、推演结果",
                    SIM_CONTENT_SECTION_HEADER + "\n\n" + block);
        }
    }

    /**
     * 查找推演内容记录区中最后一条 SIM_CONTENT 之后、TURN_SETTLEMENT 之前的位置。
     */
    private static int findSimContentInsertPoint(String markdown, int startFrom) {
        int turnSettlementIdx = markdown.indexOf("<!-- TURN_SETTLEMENT");
        if (turnSettlementIdx < 0) turnSettlementIdx = markdown.indexOf(TURN_SETTLEMENT_SECTION_HEADER);

        if (turnSettlementIdx > startFrom) {
            // 在 TURN_SETTLEMENT 之前追加
            return turnSettlementIdx;
        }

        // 找到最后一个 SIM_CONTENT END
        int lastSimEnd = startFrom;
        int idx = startFrom;
        while ((idx = markdown.indexOf("<!-- SIM_CONTENT:", idx)) >= 0) {
            int endIdx = markdown.indexOf("END -->", idx);
            if (endIdx >= 0) {
                lastSimEnd = endIdx + "END -->".length();
                idx = endIdx + 1;
            } else {
                idx++;
            }
        }
        return lastSimEnd;
    }

    /**
     * 在指定 section heading 后插入内容。
     */
    private static String insertAfterSectionHeader(String markdown, String heading, String newContent) {
        String marker = "## " + heading;
        int idx = markdown.indexOf(marker);
        if (idx < 0) {
            // 找不到 heading，追加到文件末尾
            return markdown.trim() + "\n\n" + newContent + "\n";
        }
        int newlineAfter = markdown.indexOf('\n', idx + marker.length());
        if (newlineAfter < 0) newlineAfter = idx + marker.length();

        // 跳过 empty lines
        int insertAt = newlineAfter + 1;
        while (insertAt < markdown.length() && markdown.charAt(insertAt) == '\n') {
            insertAt++;
        }

        return markdown.substring(0, insertAt) + newContent + "\n\n" + markdown.substring(insertAt);
    }

    /**
     * 构建单条 sim content marker block。
     */
    private static String buildSimBlock(SimContentRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- SIM_CONTENT:").append(record.simId()).append(" START -->\n");
        sb.append("#### [").append(record.simId()).append("] ").append(record.title()).append("\n\n");
        sb.append("- 类型：").append(record.type()).append("\n");
        sb.append("- 状态：").append(record.status()).append("\n");
        sb.append("- 来源：").append(record.source()).append("\n");
        sb.append("- 时间：").append(record.createdAt()).append("\n");
        if (record.summary() != null && !record.summary().isBlank()) {
            sb.append("- 摘要：").append(record.summary()).append("\n");
        }
        if (record.metadata() != null && !record.metadata().isBlank()) {
            sb.append("- 元数据：").append(record.metadata()).append("\n");
        }
        sb.append("\n").append(record.content()).append("\n");
        sb.append("<!-- SIM_CONTENT:").append(record.simId()).append(" END -->");
        return sb.toString();
    }

    /**
     * 更新指定 simId 的内容块。
     */
    public static String updateSimContent(String markdown, String simId,
                                          String newTitle, String newContent,
                                          String newSummary, String newStatus) throws IOException {
        Pattern pattern = Pattern.compile(
                "<!--\\s*SIM_CONTENT:" + simId + "\\s+START\\s*-->.*?<!--\\s*SIM_CONTENT:" + simId + "\\s+END\\s*-->",
                Pattern.DOTALL);
        Matcher m = pattern.matcher(markdown);
        if (!m.find()) {
            throw new IOException("SIM_CONTENT_NOT_FOUND: " + simId);
        }

        String oldBlock = m.group(0);
        SimContentRecord old = parseSimBlock(simId, "", oldBlock);

        SimContentRecord updated = new SimContentRecord(
                simId, old.branchId(),
                old.type(),
                newTitle != null ? newTitle : old.title(),
                newContent != null ? newContent : old.content(),
                newSummary != null ? newSummary : old.summary(),
                newStatus != null ? newStatus : old.status(),
                old.source(),
                old.createdAt(),
                old.metadata()
        );

        String newBlock = buildSimBlock(updated);
        return markdown.substring(0, m.start()) + newBlock + markdown.substring(m.end());
    }

    // ==================== Turn Settlement ====================

    /**
     * 保存回合结算到 branch markdown。
     * settlement 参数包含: inputSummary, settlement, worldDelta, entityDelta, ruleDelta, interactionDelta, risk, referencedSimIds
     */
    public static String saveTurnSettlement(String markdown,
                                            String inputSummary,
                                            String settlement,
                                            String worldDelta,
                                            String entityDelta,
                                            String ruleDelta,
                                            String interactionDelta,
                                            String risk,
                                            String referencedSimIds) {
        String block = buildTurnSettlementBlock(inputSummary, settlement, worldDelta, entityDelta,
                ruleDelta, interactionDelta, risk, referencedSimIds);

        String result = markdown;

        // 1. 替换或插入 TURN_SETTLEMENT 区块
        Matcher tsMatcher = TURN_SETTLEMENT_PATTERN.matcher(result);
        if (tsMatcher.find()) {
            result = result.substring(0, tsMatcher.start()) + block + result.substring(tsMatcher.end());
        } else {
            // 在 "### 本回合结算" 标题下插入
            int tsHeaderIdx = result.indexOf(TURN_SETTLEMENT_SECTION_HEADER);
            if (tsHeaderIdx >= 0) {
                int afterHeader = result.indexOf('\n', tsHeaderIdx + TURN_SETTLEMENT_SECTION_HEADER.length());
                if (afterHeader < 0) afterHeader = tsHeaderIdx + TURN_SETTLEMENT_SECTION_HEADER.length();
                result = result.substring(0, afterHeader + 1) + "\n" + block + "\n" + result.substring(afterHeader + 1);
            } else {
                // 没有 TURN_SETTLEMENT 区块 —— 在推演内容记录后插入
                int simSectionIdx = result.indexOf(SIM_CONTENT_SECTION_HEADER);
                if (simSectionIdx >= 0) {
                    int insertAt = findSimContentInsertPoint(result, simSectionIdx + SIM_CONTENT_SECTION_HEADER.length());
                    result = result.substring(0, insertAt) + "\n" + TURN_SETTLEMENT_SECTION_HEADER + "\n\n" + block + "\n" + result.substring(insertAt);
                } else {
                    result = insertAfterSectionHeader(result, "三、推演结果",
                            SIM_CONTENT_SECTION_HEADER + "\n\n" + block + "\n\n" + TURN_SETTLEMENT_SECTION_HEADER + "\n\n" + block + "\n");
                }
            }
        }

        // 2. 更新 四、世界观/设定增量
        if (worldDelta != null) {
            result = replaceSectionIfPresent(result, "四、世界观/设定增量", worldDelta);
        }

        // 3. 更新 五、实体状态增量
        if (entityDelta != null) {
            result = replaceSectionIfPresent(result, "五、实体状态增量", entityDelta);
        }

        // 4. 更新 六、推演规则增量
        if (ruleDelta != null) {
            result = replaceSectionIfPresent(result, "六、推演规则增量", ruleDelta);
        }

        // 5. 更新 七、交互逻辑增量
        if (interactionDelta != null) {
            result = replaceSectionIfPresent(result, "七、交互逻辑增量", interactionDelta);
        }

        // 6. 更新 九、下节点风险
        if (risk != null) {
            result = replaceSectionIfPresent(result, "九、下节点风险", risk);
        }

        return result;
    }

    /**
     * 构建回合结算 marker block。
     */
    private static String buildTurnSettlementBlock(String inputSummary, String settlement,
                                                    String worldDelta, String entityDelta,
                                                    String ruleDelta, String interactionDelta,
                                                    String risk, String referencedSimIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- TURN_SETTLEMENT START -->\n\n");

        if (inputSummary != null && !inputSummary.isBlank()) {
            sb.append("**本回合输入摘要：** ").append(inputSummary).append("\n\n");
        }

        sb.append("**完整回合结算：**\n\n").append(settlement).append("\n");

        if (referencedSimIds != null && !referencedSimIds.isBlank()) {
            sb.append("\n**关联推演内容：** ").append(referencedSimIds).append("\n");
        }

        sb.append("\n<!-- TURN_SETTLEMENT END -->");
        return sb.toString();
    }

    /**
     * 替换 markdown 中 ## sectionHeading 下的内容。
     */
    private static String replaceSectionIfPresent(String markdown, String heading, String newContent) {
        String marker = "## " + heading;
        int start = markdown.indexOf(marker);
        if (start < 0) return markdown;

        int contentStart = markdown.indexOf('\n', start + marker.length());
        if (contentStart < 0) contentStart = start + marker.length();

        int end = markdown.indexOf("\n## ", contentStart);
        if (end < 0) end = markdown.length();

        return markdown.substring(0, contentStart + 1) + "\n" + newContent + "\n"
                + markdown.substring(end);
    }

    /**
     * 从 branch markdown 读取回合结算。
     */
    public static String readTurnSettlement(String markdown) {
        Matcher m = TURN_SETTLEMENT_PATTERN.matcher(markdown);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "尚无回合结算。";
    }
}
