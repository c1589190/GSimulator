package com.gsim.branch.tool;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Branch 文件中推演内容记录、玩家行动记录、回合结算、节点概览的读写操作。
 *
 * Marker block 格式：
 *   <!-- SIM_CONTENT:sim0001 START -->
 *   #### [sim0001] 标题
 *   - 类型：xxx
 *   ...
 *   <!-- SIM_CONTENT:sim0001 END -->
 *
 *   <!-- PLAYER_ACTION:act0001 START -->
 *   #### [act0001] 玩家A：前往龙门
 *   ...
 *   <!-- PLAYER_ACTION:act0001 END -->
 *
 *   <!-- TURN_SETTLEMENT:stl0001 START -->
 *   - settlementId：stl0001
 *   ...
 *   <!-- NODE_OVERVIEW START -->
 *   ...
 *   <!-- NODE_OVERVIEW END -->
 *   <!-- TURN_SETTLEMENT:stl0001 END -->
 */
public final class BranchFileSimContent {

    private static final Pattern SIM_MARKER_PATTERN =
            Pattern.compile("<!--\\s*SIM_CONTENT:(sim\\d+)\\s+START\\s*-->(.*?)<!--\\s*SIM_CONTENT:\\1\\s+END\\s*-->",
                    Pattern.DOTALL);
    private static final Pattern PLAYER_ACTION_MARKER_PATTERN =
            Pattern.compile("<!--\\s*PLAYER_ACTION:(act\\d+)\\s+START\\s*-->(.*?)<!--\\s*PLAYER_ACTION:\\1\\s+END\\s*-->",
                    Pattern.DOTALL);
    private static final Pattern TURN_SETTLEMENT_PATTERN =
            Pattern.compile("<!--\\s*TURN_SETTLEMENT\\s+START\\s*-->(.*?)<!--\\s*TURN_SETTLEMENT\\s+END\\s*-->",
                    Pattern.DOTALL);
    private static final Pattern TURN_SETTLEMENT_VERSIONED_PATTERN =
            Pattern.compile("<!--\\s*TURN_SETTLEMENT:(stl\\d+)\\s+START\\s*-->(.*?)<!--\\s*TURN_SETTLEMENT:\\1\\s+END\\s*-->",
                    Pattern.DOTALL);
    private static final Pattern NODE_OVERVIEW_PATTERN =
            Pattern.compile("<!--\\s*NODE_OVERVIEW\\s+START\\s*-->(.*?)<!--\\s*NODE_OVERVIEW\\s+END\\s*-->",
                    Pattern.DOTALL);

    private static final String SIM_CONTENT_SECTION_HEADER = "### 推演内容记录";
    private static final String PLAYER_ACTION_SECTION_HEADER = "### 玩家行动记录";
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
        String revisionOf = "";

        // 解析标题: #### [sim0001] 标题
        Matcher titleMatcher = Pattern.compile("####\\s*\\[" + simId + "\\]\\s*(.*?)\\n").matcher(block);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).trim();
        }

        // 解析元数据行
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
            } else if (line.startsWith("- revisionOf：") || line.startsWith("- revisionOf:")) {
                revisionOf = extractMetaValue(line);
            }
        }

        // 提取正文
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
                    || line.startsWith("- 元数据") || line.startsWith("- metadata")
                    || line.startsWith("- revisionOf")) {
                inContent = true;
                continue;
            }
            if (inContent || !line.startsWith("-")) {
                contentBuf.append(line).append("\n");
            }
        }
        content = contentBuf.toString().trim();

        return new SimContentRecord(
                simId, branchId, type, title, content, summary, status, source, createdAt, metadata, revisionOf);
    }

    private static String extractMetaValue(String line) {
        int colon = line.indexOf("：");
        if (colon < 0) colon = line.indexOf(":");
        return colon >= 0 ? line.substring(colon + 1).trim() : "";
    }

    // ==================== Sim Content Write ====================

    /**
     * 向 branch markdown 追加一条 sim content。
     */
    public static String appendSimContent(String markdown, SimContentRecord record) {
        String block = buildSimBlock(record);

        int simSectionIdx = markdown.indexOf(SIM_CONTENT_SECTION_HEADER);

        if (simSectionIdx >= 0) {
            int afterHeader = simSectionIdx + SIM_CONTENT_SECTION_HEADER.length();
            int newlineAfter = markdown.indexOf('\n', afterHeader);
            if (newlineAfter < 0) newlineAfter = afterHeader;

            int insertPoint = findSectionInsertPoint(markdown, newlineAfter + 1,
                    "### 玩家行动记录", "<!-- PLAYER_ACTION:", "### 本回合结算", "<!-- TURN_SETTLEMENT");
            return markdown.substring(0, insertPoint) + "\n" + block + "\n" + markdown.substring(insertPoint);
        } else {
            return insertAfterSectionHeader(markdown, "三、推演结果",
                    SIM_CONTENT_SECTION_HEADER + "\n\n" + block);
        }
    }

    /**
     * 查找插入点：在最后一个当前类型的 block 之后、下一个 section 之前。
     */
    private static int findSectionInsertPoint(String markdown, int startFrom,
                                              String... nextSectionMarkers) {
        // 找到后续 section 中最早出现的位置
        int earliestNext = Integer.MAX_VALUE;
        for (String marker : nextSectionMarkers) {
            int idx = markdown.indexOf(marker, startFrom);
            if (idx >= 0 && idx < earliestNext) earliestNext = idx;
        }

        if (earliestNext < Integer.MAX_VALUE) {
            return earliestNext;
        }

        // 找到当前 section 中最后一个 block 的 END -->
        int lastEnd = startFrom;
        int idx = startFrom;
        while ((idx = markdown.indexOf("END -->", idx)) >= 0) {
            lastEnd = idx + "END -->".length();
            idx = lastEnd;
            // 如果后面跟的是下一个 section，回退
            String after = markdown.substring(Math.min(lastEnd, markdown.length()));
            for (String marker : nextSectionMarkers) {
                if (after.trim().startsWith(marker)) {
                    return lastEnd;
                }
            }
        }
        return lastEnd;
    }

    /**
     * 在指定 section heading 后插入内容。
     */
    private static String insertAfterSectionHeader(String markdown, String heading, String newContent) {
        String marker = "## " + heading;
        int idx = markdown.indexOf(marker);
        if (idx < 0) {
            return markdown.trim() + "\n\n" + newContent + "\n";
        }
        int newlineAfter = markdown.indexOf('\n', idx + marker.length());
        if (newlineAfter < 0) newlineAfter = idx + marker.length();

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
        if (record.revisionOf() != null && !record.revisionOf().isBlank()) {
            sb.append("- revisionOf：").append(record.revisionOf()).append("\n");
        }
        sb.append("\n").append(record.content()).append("\n");
        sb.append("<!-- SIM_CONTENT:").append(record.simId()).append(" END -->");
        return sb.toString();
    }

    /**
     * 更新指定 simId 的内容块（原地替换，供 metadata/title 修改使用）。
     * 重推请使用 appendSimContent 追加新版。
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
                old.metadata(),
                old.revisionOf()
        );

        String newBlock = buildSimBlock(updated);
        return markdown.substring(0, m.start()) + newBlock + markdown.substring(m.end());
    }

    // ==================== Player Action Read ====================

    /**
     * 从 branch markdown 中解析出所有 PlayerAction 记录。
     */
    public static List<PlayerActionRecord> parsePlayerActions(String markdown, String branchId) {
        List<PlayerActionRecord> records = new ArrayList<>();
        Matcher m = PLAYER_ACTION_MARKER_PATTERN.matcher(markdown);
        while (m.find()) {
            String actId = m.group(1);
            String block = m.group(2).trim();
            records.add(parseActionBlock(actId, branchId, block));
        }
        return records;
    }

    private static PlayerActionRecord parseActionBlock(String actId, String branchId, String block) {
        String playerName = "";
        String content = "";
        String summary = "";
        String status = "active";
        String source = "user";
        String createdAt = "";
        String revisionOf = "";

        Matcher titleMatcher = Pattern.compile("####\\s*\\[" + actId + "\\]\\s*(.*?)\\n").matcher(block);
        if (titleMatcher.find()) {
            String title = titleMatcher.group(1).trim();
            // title 格式："玩家A：前往龙门" 或 "玩家A：前往龙门（修订）"
            int cnColon = title.indexOf("：");
            if (cnColon < 0) {
                cnColon = title.indexOf(":");
            }
            if (cnColon > 0) {
                playerName = title.substring(0, cnColon).trim();
                // 内容从 title 中提取（去掉可能的"（修订）"后缀）
                String rawTitleContent = title.substring(cnColon + 1).trim();
                rawTitleContent = rawTitleContent.replaceAll("（修订）$", "").replaceAll("\\(revised\\)$", "").trim();
                if (content.isEmpty()) content = rawTitleContent;
            }
        }

        for (String line : block.split("\\n")) {
            line = line.trim();
            if (line.startsWith("- 玩家：") || line.startsWith("- player:")) {
                playerName = extractMetaValue(line);
            } else if (line.startsWith("- 状态：") || line.startsWith("- status:")) {
                status = extractMetaValue(line);
            } else if (line.startsWith("- 来源：") || line.startsWith("- source:")) {
                source = extractMetaValue(line);
            } else if (line.startsWith("- 时间：") || line.startsWith("- time:") || line.startsWith("- createdAt:")) {
                createdAt = extractMetaValue(line);
            } else if (line.startsWith("- 摘要：") || line.startsWith("- summary:")) {
                summary = extractMetaValue(line);
            } else if (line.startsWith("- revisionOf：") || line.startsWith("- revisionOf:")) {
                revisionOf = extractMetaValue(line);
            }
        }

        // 提取正文：元数据行之后的内容
        StringBuilder contentBuf = new StringBuilder();
        boolean inContent = false;
        for (String line : block.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("####") || line.startsWith("- 玩家") || line.startsWith("- player")
                    || line.startsWith("- 状态") || line.startsWith("- status")
                    || line.startsWith("- 来源") || line.startsWith("- source")
                    || line.startsWith("- 时间") || line.startsWith("- time") || line.startsWith("- createdAt")
                    || line.startsWith("- 摘要") || line.startsWith("- summary")
                    || line.startsWith("- revisionOf")) {
                inContent = true;
                continue;
            }
            if (inContent || !line.startsWith("-")) {
                contentBuf.append(line).append("\n");
            }
        }
        String bodyContent = contentBuf.toString().trim();
        if (!bodyContent.isEmpty()) content = bodyContent;

        return new PlayerActionRecord(
                actId, branchId, playerName, content, summary, status, source, createdAt, revisionOf);
    }

    // ==================== Player Action Write ====================

    /**
     * 向 branch markdown 追加一条玩家行动记录。
     */
    public static String appendPlayerAction(String markdown, PlayerActionRecord record) {
        String block = buildActionBlock(record);

        int sectionIdx = markdown.indexOf(PLAYER_ACTION_SECTION_HEADER);

        if (sectionIdx >= 0) {
            int afterHeader = sectionIdx + PLAYER_ACTION_SECTION_HEADER.length();
            int newlineAfter = markdown.indexOf('\n', afterHeader);
            if (newlineAfter < 0) newlineAfter = afterHeader;

            int insertPoint = findSectionInsertPoint(markdown, newlineAfter + 1,
                    "### 推演内容记录", "<!-- SIM_CONTENT:", "### 本回合结算", "<!-- TURN_SETTLEMENT");
            return markdown.substring(0, insertPoint) + "\n" + block + "\n" + markdown.substring(insertPoint);
        } else {
            // 在推演内容记录之前或之后插入
            int simSectionIdx = markdown.indexOf(SIM_CONTENT_SECTION_HEADER);
            if (simSectionIdx >= 0) {
                return markdown.substring(0, simSectionIdx)
                        + PLAYER_ACTION_SECTION_HEADER + "\n\n" + block + "\n\n"
                        + markdown.substring(simSectionIdx);
            }
            return insertAfterSectionHeader(markdown, "三、推演结果",
                    PLAYER_ACTION_SECTION_HEADER + "\n\n" + block);
        }
    }

    private static String buildActionBlock(PlayerActionRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- PLAYER_ACTION:").append(record.actId()).append(" START -->\n");
        String title = record.playerName() + "：" + record.content();
        if (title.length() > 80) title = title.substring(0, 77) + "...";
        if (record.revisionOf() != null && !record.revisionOf().isBlank()) {
            title += "（修订）";
        }
        sb.append("#### [").append(record.actId()).append("] ").append(title).append("\n\n");
        sb.append("- 玩家：").append(record.playerName()).append("\n");
        sb.append("- 状态：").append(record.status()).append("\n");
        sb.append("- 来源：").append(record.source()).append("\n");
        sb.append("- 时间：").append(record.createdAt()).append("\n");
        if (record.summary() != null && !record.summary().isBlank()) {
            sb.append("- 摘要：").append(record.summary()).append("\n");
        }
        if (record.revisionOf() != null && !record.revisionOf().isBlank()) {
            sb.append("- revisionOf：").append(record.revisionOf()).append("\n");
        }
        sb.append("\n").append(record.content()).append("\n");
        sb.append("<!-- PLAYER_ACTION:").append(record.actId()).append(" END -->");
        return sb.toString();
    }

    /**
     * 更新指定 actId 的元数据（不覆盖正文，只改状态等）。
     */
    public static String updatePlayerActionMeta(String markdown, String actId,
                                                 String newStatus, String newSummary) throws IOException {
        Pattern pattern = Pattern.compile(
                "<!--\\s*PLAYER_ACTION:" + actId + "\\s+START\\s*-->.*?<!--\\s*PLAYER_ACTION:" + actId + "\\s+END\\s*-->",
                Pattern.DOTALL);
        Matcher m = pattern.matcher(markdown);
        if (!m.find()) {
            throw new IOException("PLAYER_ACTION_NOT_FOUND: " + actId);
        }

        String oldBlock = m.group(0);
        PlayerActionRecord old = parseActionBlock(actId, "", oldBlock);

        PlayerActionRecord updated = new PlayerActionRecord(
                actId, old.branchId(),
                old.playerName(), old.content(),
                newSummary != null ? newSummary : old.summary(),
                newStatus != null ? newStatus : old.status(),
                old.source(), old.createdAt(), old.revisionOf()
        );

        String newBlock = buildActionBlock(updated);
        return markdown.substring(0, m.start()) + newBlock + markdown.substring(m.end());
    }

    // ==================== Turn Settlement Write (versioned) ====================

    /**
     * 保存回合结算到 branch markdown（追加新版本，不覆盖旧版）。
     */
    public static String saveTurnSettlement(String markdown,
                                            String settlementId,
                                            String revisionOf,
                                            String inputSummary,
                                            String settlement,
                                            String worldDelta,
                                            String entityDelta,
                                            String ruleDelta,
                                            String interactionDelta,
                                            String risk,
                                            String referencedSimIds,
                                            String referencedActionIds) {
        String block = buildTurnSettlementBlock(settlementId, revisionOf, inputSummary, settlement,
                referencedSimIds, referencedActionIds);
        String nodeOverview = buildNodeOverviewPlaceholder();

        String result = markdown;

        // 1. 插入 TURN_SETTLEMENT block（追加模式，不替换旧版）
        int tsHeaderIdx = result.indexOf(TURN_SETTLEMENT_SECTION_HEADER);
        if (tsHeaderIdx >= 0) {
            // 找到 "### 本回合结算" 后的内容末尾（下一个 ## 或文件末尾）
            int afterHeader = tsHeaderIdx + TURN_SETTLEMENT_SECTION_HEADER.length();
            // 查找下一个 ## heading
            int nextSection = result.indexOf("\n## ", afterHeader);
            if (nextSection < 0) nextSection = result.length();

            // 在下一 section 之前追加
            result = result.substring(0, nextSection) + "\n" + block + "\n" + result.substring(nextSection);
        } else {
            // 没有 TURN_SETTLEMENT 区 —— 在推演内容记录后创建
            int simSectionIdx = result.indexOf(SIM_CONTENT_SECTION_HEADER);
            if (simSectionIdx >= 0) {
                int afterSim = findSectionInsertPoint(result, simSectionIdx + SIM_CONTENT_SECTION_HEADER.length(),
                        "## 四、", "## 五、", "## 六、");
                result = result.substring(0, afterSim)
                        + "\n" + TURN_SETTLEMENT_SECTION_HEADER + "\n\n" + block + "\n"
                        + result.substring(afterSim);
            } else {
                result = insertAfterSectionHeader(result, "三、推演结果",
                        TURN_SETTLEMENT_SECTION_HEADER + "\n\n" + block);
            }
        }

        // 2. 更新 NODE_OVERVIEW（替换已有或追加到最后一个 settlement 后）
        result = upsertNodeOverview(result, settlementId, inputSummary, settlement,
                referencedSimIds, referencedActionIds);

        // 3. 更新各章节
        if (worldDelta != null) {
            result = replaceSectionIfPresent(result, "四、世界观/设定增量", worldDelta);
        }
        if (entityDelta != null) {
            result = replaceSectionIfPresent(result, "五、实体状态增量", entityDelta);
        }
        if (ruleDelta != null) {
            result = replaceSectionIfPresent(result, "六、推演规则增量", ruleDelta);
        }
        if (interactionDelta != null) {
            result = replaceSectionIfPresent(result, "七、交互逻辑增量", interactionDelta);
        }
        if (risk != null) {
            result = replaceSectionIfPresent(result, "九、下节点风险", risk);
        }

        return result;
    }

    private static String buildTurnSettlementBlock(String settlementId, String revisionOf,
                                                    String inputSummary, String settlement,
                                                    String referencedSimIds, String referencedActionIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- TURN_SETTLEMENT:").append(settlementId).append(" START -->\n\n");
        sb.append("- settlementId：").append(settlementId).append("\n");
        if (revisionOf != null && !revisionOf.isBlank()) {
            sb.append("- revisionOf：").append(revisionOf).append("\n");
        }
        if (inputSummary != null && !inputSummary.isBlank()) {
            sb.append("\n**本回合输入摘要：** ").append(inputSummary).append("\n");
        }
        sb.append("\n**完整回合结算：**\n\n").append(settlement).append("\n");

        if (referencedSimIds != null && !referencedSimIds.isBlank()) {
            sb.append("\n**关联推演内容：** ").append(referencedSimIds).append("\n");
        }
        if (referencedActionIds != null && !referencedActionIds.isBlank()) {
            sb.append("\n**关联玩家行动：** ").append(referencedActionIds).append("\n");
        }

        sb.append("\n<!-- TURN_SETTLEMENT:").append(settlementId).append(" END -->");
        return sb.toString();
    }

    // ==================== Node Overview ====================

    /**
     * 生成节点概览占位符（首次创建时使用）。
     */
    private static String buildNodeOverviewPlaceholder() {
        return "<!-- NODE_OVERVIEW START -->\n"
                + "- **最近结算**：stl0001\n"
                + "- **操作摘要**：待更新\n"
                + "- **行动数**：0\n"
                + "- **推演内容数**：0\n"
                + "- **结算数**：1\n"
                + "<!-- NODE_OVERVIEW END -->";
    }

    /**
     * 更新或创建 NODE_OVERVIEW 区块。
     */
    public static String upsertNodeOverview(String markdown, String lastSettlementId,
                                             String inputSummary, String settlementSnippet,
                                             String referencedSimIds, String referencedActionIds) {
        // 统计当前各种记录数
        int actionCount = countMarkerBlocks(markdown, "<!-- PLAYER_ACTION:act");
        int simCount = countMarkerBlocks(markdown, "<!-- SIM_CONTENT:sim");
        int settlementCount = countMarkerBlocks(markdown, "<!-- TURN_SETTLEMENT:stl");

        // 生成操作摘要
        String opSummary = inputSummary != null && !inputSummary.isBlank()
                ? (inputSummary.length() > 120 ? inputSummary.substring(0, 117) + "..." : inputSummary)
                : "回合结算完成";
        if (settlementSnippet != null && !settlementSnippet.isBlank()) {
            String snippet = settlementSnippet.replaceAll("\\*\\*", "").replaceAll("#+", "").trim();
            // 取第一段有意义的话
            String[] paras = snippet.split("\\n\\n");
            for (String p : paras) {
                p = p.trim();
                if (p.length() > 20) {
                    if (p.length() > 150) p = p.substring(0, 147) + "...";
                    opSummary = p;
                    break;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!-- NODE_OVERVIEW START -->\n");
        sb.append("- **最近结算**：").append(lastSettlementId).append("\n");
        sb.append("- **操作摘要**：").append(opSummary).append("\n");
        sb.append("- **行动数**：").append(actionCount).append("\n");
        sb.append("- **推演内容数**：").append(simCount).append("\n");
        sb.append("- **结算数**：").append(settlementCount).append("\n");
        if (referencedSimIds != null && !referencedSimIds.isBlank()) {
            sb.append("- **关联 simId**：").append(referencedSimIds).append("\n");
        }
        if (referencedActionIds != null && !referencedActionIds.isBlank()) {
            sb.append("- **关联 actId**：").append(referencedActionIds).append("\n");
        }
        sb.append("<!-- NODE_OVERVIEW END -->");

        String newOverview = sb.toString();

        Matcher m = NODE_OVERVIEW_PATTERN.matcher(markdown);
        if (m.find()) {
            return markdown.substring(0, m.start()) + newOverview + markdown.substring(m.end());
        } else {
            // 插入到最后一个 TURN_SETTLEMENT block 之后
            int lastSettlementEnd = findLastSettlementEnd(markdown);
            if (lastSettlementEnd > 0) {
                return markdown.substring(0, lastSettlementEnd) + "\n\n" + newOverview
                        + markdown.substring(lastSettlementEnd);
            }
            // fallback：插入到 "### 本回合结算" 小节
            int tsHeaderIdx = markdown.indexOf(TURN_SETTLEMENT_SECTION_HEADER);
            if (tsHeaderIdx >= 0) {
                int afterHeader = markdown.indexOf('\n', tsHeaderIdx + TURN_SETTLEMENT_SECTION_HEADER.length());
                if (afterHeader < 0) afterHeader = tsHeaderIdx + TURN_SETTLEMENT_SECTION_HEADER.length();
                return markdown.substring(0, afterHeader + 1) + "\n" + newOverview + "\n"
                        + markdown.substring(afterHeader + 1);
            }
            return markdown + "\n" + newOverview + "\n";
        }
    }

    private static int findLastSettlementEnd(String markdown) {
        int lastEnd = -1;
        int idx = 0;
        while ((idx = markdown.indexOf("<!-- TURN_SETTLEMENT:", idx)) >= 0) {
            int endIdx = markdown.indexOf("END -->", idx);
            if (endIdx >= 0) {
                lastEnd = endIdx + "END -->".length();
                idx = endIdx + 1;
            } else {
                idx++;
            }
        }
        return lastEnd;
    }

    private static int countMarkerBlocks(String markdown, String markerPrefix) {
        int count = 0;
        int idx = 0;
        while ((idx = markdown.indexOf(markerPrefix, idx)) >= 0) {
            count++;
            idx += markerPrefix.length();
        }
        return count;
    }

    // ==================== Turn Settlement Read ====================

    /**
     * 从 branch markdown 读取所有回合结算。
     */
    public static List<SettlementRecord> parseSettlements(String markdown) {
        List<SettlementRecord> records = new ArrayList<>();

        // 优先匹配版本化 settlement
        Matcher vMatcher = TURN_SETTLEMENT_VERSIONED_PATTERN.matcher(markdown);
        while (vMatcher.find()) {
            String stlId = vMatcher.group(1);
            String block = vMatcher.group(2).trim();
            records.add(parseSettlementBlock(stlId, block));
        }

        // 兼容旧格式（无 ID）
        if (records.isEmpty()) {
            Matcher oldMatcher = TURN_SETTLEMENT_PATTERN.matcher(markdown);
            while (oldMatcher.find()) {
                String block = oldMatcher.group(1).trim();
                // 跳过包含 NODE_OVERVIEW 的旧格式
                records.add(parseSettlementBlock("stl0001", block));
            }
        }

        return records;
    }

    private static SettlementRecord parseSettlementBlock(String stlId, String block) {
        String revisionOf = "";
        String inputSummary = "";
        String settlement = block;
        String referencedSimIds = "";
        String referencedActionIds = "";

        // 移除 NODE_OVERVIEW 区块
        Matcher noMatcher = NODE_OVERVIEW_PATTERN.matcher(block);
        if (noMatcher.find()) {
            block = block.substring(0, noMatcher.start()) + block.substring(noMatcher.end());
            block = block.trim();
            settlement = block;
        }

        for (String line : block.split("\\n")) {
            line = line.trim();
            if (line.startsWith("- settlementId：")) {
                // skip
            } else if (line.startsWith("- revisionOf：") || line.startsWith("- revisionOf:")) {
                revisionOf = extractMetaValue(line);
            } else if (line.startsWith("**本回合输入摘要：**")) {
                inputSummary = line.substring("**本回合输入摘要：**".length()).trim();
            } else if (line.startsWith("**关联推演内容：**")) {
                referencedSimIds = line.substring("**关联推演内容：**".length()).trim();
            } else if (line.startsWith("**关联玩家行动：**")) {
                referencedActionIds = line.substring("**关联玩家行动：**".length()).trim();
            }
        }

        // settlement 正文：去除元数据行
        if (settlement.contains("**完整回合结算：**")) {
            int startIdx = settlement.indexOf("**完整回合结算：**");
            settlement = settlement.substring(startIdx + "**完整回合结算：**".length()).trim();
            // 截断到下一个 ** 行
            int endIdx = settlement.indexOf("\n**关联");
            if (endIdx > 0) settlement = settlement.substring(0, endIdx).trim();
        }

        return new SettlementRecord(stlId, revisionOf, inputSummary, settlement,
                referencedSimIds, referencedActionIds);
    }

    /**
     * 读取最新回合结算的正文。
     */
    public static String readTurnSettlement(String markdown) {
        List<SettlementRecord> records = parseSettlements(markdown);
        if (records.isEmpty()) return "尚无回合结算。";
        return records.get(records.size() - 1).settlement();
    }

    /**
     * 读取 NODE_OVERVIEW 内容。
     */
    public static String readNodeOverview(String markdown) {
        Matcher m = NODE_OVERVIEW_PATTERN.matcher(markdown);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    /**
     * 获取节点轻量状态信息。
     */
    public static NodeLightStatus getNodeLightStatus(String markdown) {
        int actionCount = countMarkerBlocks(markdown, "<!-- PLAYER_ACTION:act");
        int simCount = countMarkerBlocks(markdown, "<!-- SIM_CONTENT:sim");
        int settlementCount = countMarkerBlocks(markdown, "<!-- TURN_SETTLEMENT:stl");
        String overview = readNodeOverview(markdown);
        String latestSettlement = readTurnSettlement(markdown);
        // 截取最新 settlement 的前 300 字符作为摘要
        String settlementSnippet = "";
        if (!latestSettlement.equals("尚无回合结算。")) {
            settlementSnippet = latestSettlement.length() > 300
                    ? latestSettlement.substring(0, 297) + "..."
                    : latestSettlement;
        }

        return new NodeLightStatus(actionCount, simCount, settlementCount,
                overview, settlementSnippet);
    }

    public record NodeLightStatus(int actionCount, int simContentCount, int settlementCount,
                                   String nodeOverview, String latestSettlementSnippet) {}

    // ==================== SettlementRecord ====================

    public record SettlementRecord(
            String settlementId,
            String revisionOf,
            String inputSummary,
            String settlement,
            String referencedSimIds,
            String referencedActionIds
    ) {}

    // ==================== Section Helpers ====================

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
}
