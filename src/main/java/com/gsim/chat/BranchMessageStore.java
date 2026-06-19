package com.gsim.chat;

import com.gsim.data.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BranchMessageStore — 从 branch 文件读取/追加 message blocks。
 */
public class BranchMessageStore {
    private static final Logger log = LoggerFactory.getLogger(BranchMessageStore.class);
    private static final Pattern MSG_START = Pattern.compile(
            "<!-- message:start id=(\\S+) role=(\\S+) type=(\\S+)(?: tool=(\\S+))? created=([^>]+) -->");
    private static final Pattern MSG_END = Pattern.compile("<!-- message:end -->");
    private static final String MSG_BLOCK_START = "<!-- BRANCH_MESSAGES START -->";
    private static final String MSG_BLOCK_END = "<!-- BRANCH_MESSAGES END -->";
    private static final String LLM_SECTION_HEADING = "## 二、LLM 上下文记录";
    /** 匹配单个 message block（用于迁移时从正文中清除）。 */
    private static final Pattern MSG_BLOCK_PATTERN = Pattern.compile(
            "<!-- message:start [^>]* -->[\\s\\S]*?<!-- message:end -->\\s*");

    private final DataManager dm;
    private final Path dataRoot;

    public BranchMessageStore(DataManager dm, Path dataRoot) {
        this.dm = dm; this.dataRoot = dataRoot;
    }

    /** 从 branch 文件读取所有 message blocks。合并 marker 内、marker 外、legacy 格式，按 id 去重（marker 优先）。 */
    public List<BranchMessage> listMessages(String branchId) throws IOException {
        List<BranchMessage> result = new ArrayList<>();
        Path f = branchFile(branchId);
        if (!Files.exists(f)) return result;

        String raw = Files.readString(f, StandardCharsets.UTF_8);

        // 1. marker block 内消息
        List<BranchMessage> markerMsgs = new ArrayList<>();
        int blockStart = raw.indexOf(MSG_BLOCK_START);
        int blockEnd = raw.indexOf(MSG_BLOCK_END);
        if (blockStart >= 0 && blockEnd > blockStart) {
            String blockContent = raw.substring(blockStart + MSG_BLOCK_START.length(), blockEnd);
            parseMessageBlocks(blockContent, markerMsgs);
        }

        // 2. 全文 message blocks（marker 外旧消息）
        List<BranchMessage> fullMsgs = new ArrayList<>();
        parseMessageBlocks(raw, fullMsgs);

        // 3. legacy ### user / ### assistant / ### tool_call / ### tool_result
        List<BranchMessage> legacyMsgs = new ArrayList<>();
        parseLegacyFormat(raw, legacyMsgs);

        // 合并：marker 内版本优先，按 id 去重
        Map<String, BranchMessage> merged = new LinkedHashMap<>();
        for (BranchMessage m : fullMsgs) merged.put(m.id(), m);
        for (BranchMessage m : legacyMsgs) merged.putIfAbsent(m.id(), m);
        for (BranchMessage m : markerMsgs) merged.put(m.id(), m); // marker 覆盖

        return new ArrayList<>(merged.values());
    }

    /** 向当前 branch 文件的消息区插入一条 message block。写入前进行污染过滤。如果尚无 marker block 但已有旧 message blocks，自动迁移进 marker。 */
    public void appendMessage(String branchId, BranchMessage msg) throws IOException {
        Path f = branchFile(branchId);
        if (!Files.exists(f)) throw new IOException("Branch file not found: " + f);

        // 写入前过滤：检查内容是否包含工具定义污染
        BranchMessage writable = msg;
        if (ToolPollutionFilter.isPolluted(msg.content())) {
            log.warn("Filtered polluted message {} (type={}, len={}) in branch {}",
                    msg.id(), msg.type(), msg.content().length(), branchId);
            writable = BranchMessage.create(msg.id(), "system", "system_note",
                    String.format("工具定义污染已被过滤，原消息类型: %s, 原消息长度: %d",
                            msg.type(), msg.content().length()));
        }

        String block = writable.toBlock() + "\n";
        String raw = Files.readString(f, StandardCharsets.UTF_8);

        int blockStart = raw.indexOf(MSG_BLOCK_START);
        int blockEnd = raw.indexOf(MSG_BLOCK_END);

        if (blockStart >= 0 && blockEnd > blockStart) {
            // 插入到 marker block 内（在 MSG_BLOCK_END 之前）
            String before = raw.substring(0, blockEnd);
            String after = raw.substring(blockEnd);
            String updated = before + block + after;
            Files.writeString(f, updated, StandardCharsets.UTF_8);
            log.debug("Inserted message {} into marker block in {}", writable.id(), branchId);
        } else {
            // 没有 marker block — 检查是否有旧 message blocks 需要迁移
            List<BranchMessage> legacyMsgs = new ArrayList<>();
            parseMessageBlocks(raw, legacyMsgs);

            String cleanedRaw = raw;
            if (!legacyMsgs.isEmpty()) {
                // 迁移：把旧 message blocks 从正文中移除，稍后放入 marker
                cleanedRaw = MSG_BLOCK_PATTERN.matcher(raw).replaceAll("");
                log.info("Migrating {} legacy message blocks into marker in {}", legacyMsgs.size(), branchId);
            }

            // 构建 marker 内容：旧消息（如有）+ 新消息
            StringBuilder markerContent = new StringBuilder();
            for (BranchMessage m : legacyMsgs) {
                markerContent.append(m.toBlock()).append("\n");
            }
            markerContent.append(block);

            int llmSection = cleanedRaw.indexOf(LLM_SECTION_HEADING);
            if (llmSection >= 0) {
                int insertAt = llmSection + LLM_SECTION_HEADING.length();
                int nextNewline = cleanedRaw.indexOf('\n', insertAt);
                if (nextNewline < 0) nextNewline = insertAt;
                insertAt = nextNewline + 1;
                String before = cleanedRaw.substring(0, insertAt);
                String after = cleanedRaw.substring(insertAt);
                String updated = before + "\n" + MSG_BLOCK_START + "\n" + markerContent + MSG_BLOCK_END + "\n" + after;
                Files.writeString(f, updated, StandardCharsets.UTF_8);
                log.info("Created marker block{} under LLM section in {}", legacyMsgs.isEmpty() ? "" : " (with migration)", branchId);
            } else {
                String updated = cleanedRaw;
                if (!updated.endsWith("\n")) updated += "\n";
                updated += "\n" + LLM_SECTION_HEADING + "\n\n" + MSG_BLOCK_START + "\n" + markerContent + MSG_BLOCK_END + "\n";
                Files.writeString(f, updated, StandardCharsets.UTF_8);
                log.info("Created LLM section + marker block{} at EOF in {}", legacyMsgs.isEmpty() ? "" : " (with migration)", branchId);
            }
        }
    }

    /** 批量追加多条 message blocks。 */
    public void appendMessages(String branchId, List<BranchMessage> msgs) throws IOException {
        for (BranchMessage msg : msgs) {
            appendMessage(branchId, msg);
        }
    }

    /** 渲染单条 message 为 block 文本（静态方法，供外部使用）。 */
    public static String renderMessageBlock(BranchMessage msg) {
        return msg.toBlock();
    }

    /** 生成下一个消息 id。 */
    public String nextMessageId(String branchId) throws IOException {
        List<BranchMessage> msgs = listMessages(branchId);
        int max = 0;
        for (BranchMessage m : msgs) {
            if (m.id().startsWith("m")) {
                try { int n = Integer.parseInt(m.id().substring(1)); if (n > max) max = n; }
                catch (NumberFormatException ignored) {}
            }
        }
        return String.format("m%04d", max + 1);
    }

    private Path branchFile(String branchId) {
        String fn = branchId.replace("branch.", "") + ".md";
        return dataRoot.resolve("worlds").resolve(dm.getActiveWorld()).resolve("branches").resolve(fn);
    }

    private void parseMessageBlocks(String raw, List<BranchMessage> out) {
        Matcher sm = MSG_START.matcher(raw);
        List<Integer> starts = new ArrayList<>();
        List<BranchMessage> temps = new ArrayList<>();
        while (sm.find()) {
            starts.add(sm.start());
            temps.add(new BranchMessage(
                    sm.group(1), sm.group(2), sm.group(3),
                    sm.group(4) != null ? sm.group(4) : null,
                    parseTime(sm.group(5)), ""));
        }
        for (int i = 0; i < temps.size(); i++) {
            int contentStart = raw.indexOf('\n', starts.get(i)) + 1;
            int contentEnd = raw.indexOf("<!-- message:end -->", contentStart);
            if (contentEnd < 0) contentEnd = raw.length();
            String content = raw.substring(contentStart, contentEnd).trim();
            BranchMessage orig = temps.get(i);
            out.add(new BranchMessage(orig.id(), orig.role(), orig.type(), orig.toolName(), orig.createdAt(), content));
        }
    }

    /** 兼容旧 ### user / ### assistant / ### tool_call / ### tool_result 格式。 */
    private void parseLegacyFormat(String raw, List<BranchMessage> out) {
        String llmSection = extractSection(raw, "二、LLM 上下文记录");
        if (llmSection.isBlank()) return;
        String[] subHeadings = {"### user", "### assistant", "### tool_call", "### tool_result"};
        String[] types = {"chat_user", "chat_response", "tool_call", "tool_result"};
        String[] roles = {"user", "assistant", "tool", "tool"};
        int idx = 0;
        for (int i = 0; i < subHeadings.length; i++) {
            String content = extractSubSection(llmSection, subHeadings[i]);
            if (!content.isBlank() && !"无。".equals(content.trim())) {
                out.add(new BranchMessage("m" + String.format("%04d", ++idx), roles[i], types[i], null, Instant.now(), content));
            }
        }
    }

    private Instant parseTime(String s) {
        try { return Instant.parse(s); } catch (Exception e) { return Instant.now(); }
    }

    private String extractSection(String body, String heading) {
        int s = body.indexOf("## " + heading); if (s < 0) return "";
        int e = body.indexOf("\n## ", s + heading.length() + 4); if (e < 0) e = body.length();
        return body.substring(s, e).trim();
    }

    private String extractSubSection(String section, String heading) {
        int s = section.indexOf(heading); if (s < 0) return "";
        int cs = section.indexOf('\n', s); if (cs < 0) return "";
        int e = section.indexOf("\n### ", cs + 1); if (e < 0) e = section.indexOf("\n## ", cs + 1); if (e < 0) e = section.length();
        return section.substring(cs, e).trim();
    }
}
