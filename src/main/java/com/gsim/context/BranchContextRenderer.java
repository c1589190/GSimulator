package com.gsim.context;

import com.gsim.branch.BranchAnalysis;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.resource.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BranchContextRenderer — 渲染当前 active branch 的完整 LLM 上下文。
 *
 * 渲染顺序：
 * 1. system：System.md
 * 2. history：active branch 父链中每个 branch 的 LLM 上下文记录
 * 3. data：当前有效世界上下文摘要
 * 4. user：当前 input.md 内容
 */
public class BranchContextRenderer {

    private static final Logger log = LoggerFactory.getLogger(BranchContextRenderer.class);
    private static final String SYSTEM_SKILL_ID = "skill.system";

    private final DataManager dm;
    private final Path dataRoot;
    private final BranchMessageStore messageStore;
    private final BranchAnalyzer branchAnalyzer;

    public BranchContextRenderer(DataManager dm, Path dataRoot, BranchMessageStore messageStore,
                                  BranchAnalyzer branchAnalyzer) {
        this.dm = dm;
        this.dataRoot = dataRoot;
        this.messageStore = messageStore;
        this.branchAnalyzer = branchAnalyzer;
    }

    /** 渲染当前 active branch 完整上下文。
     *
     * 顺序：
     * 1. System.md
     * 2. 当前节点态势摘要
     * 3. branch 父链 message blocks
     * 4. world.md
     * 5. entities.md
     * 6. players.md
     * 7. rules.md
     * 8. input.md
     */
    public RenderedContext render() {
        List<RenderedMessage> messages = new ArrayList<>();
        boolean sysExists = ensureSystemPrompt();

        // 1. System prompt
        String systemContent = getSystemPrompt();
        messages.add(new RenderedMessage("system", "system_prompt", "", systemContent));

        // 2. 当前节点态势摘要
        String situationSummary = buildSituationSummary();
        if (!situationSummary.isBlank()) {
            messages.add(new RenderedMessage("system", "node_situation", "", situationSummary));
        }

        // 3. History: active branch 父链中每个 branch 的 LLM 上下文记录
        List<DataDocument> chain = dm.getBranchChain(dm.getActiveBranch());
        // 从根到叶（时间顺序）
        for (int i = chain.size() - 1; i >= 0; i--) {
            DataDocument b = chain.get(i);
            messages.addAll(extractBranchMessages(b));
        }

        // 4. Data: 世界观
        String worldCtx = dm.getEffectiveWorldContext();
        if (!worldCtx.isBlank()) {
            messages.add(new RenderedMessage("system", "effective_world", "", worldCtx));
        }

        // 5. 实体资料
        String entitiesCtx = dm.getEffectiveEntityContext();
        if (!entitiesCtx.isBlank()) {
            messages.add(new RenderedMessage("system", "effective_entities", "", entitiesCtx));
        }

        // 6. Players: 玩家档案
        dm.ensurePlayersFile();
        String playersCtx = dm.readPlayers();
        if (!playersCtx.isBlank()) {
            messages.add(new RenderedMessage("system", "players", "", playersCtx));
        }

        // 7. 推演规则
        String rulesCtx = dm.getEffectiveRuleContext();
        if (!rulesCtx.isBlank()) {
            messages.add(new RenderedMessage("system", "effective_rules", "", rulesCtx));
        }

        // 8. User: input.md
        String input = readInputContent();
        String inputText = input.isBlank() ? "暂无待结算内容。" : input;
        messages.add(new RenderedMessage("user", "current_input", "", inputText));

        return new RenderedContext(dm.getActiveWorld(), dm.getActiveBranch(),
                chain.size(), sysExists, messages);
    }

    /** 渲染为 Markdown 文本（用于 /context render 显示或 save）。 */
    public String renderAsMarkdown() {
        RenderedContext ctx = render();
        StringBuilder sb = new StringBuilder();
        sb.append("# Rendered Context\n");
        sb.append("World: ").append(ctx.activeWorld()).append("\n");
        sb.append("Branch: ").append(ctx.activeBranch()).append("\n");
        sb.append("Chain length: ").append(ctx.chainLength()).append("\n");
        sb.append("System.md: ").append(ctx.systemPromptExists() ? "yes" : "no").append("\n\n");

        for (int i = 0; i < ctx.messages().size(); i++) {
            RenderedMessage m = ctx.messages().get(i);
            sb.append("## [").append(i + 1).append("] ")
                    .append(m.role()).append(" / ").append(m.type());
            if (!m.branchId().isBlank()) sb.append(" / ").append(m.branchId());
            sb.append("\n\n").append(m.content()).append("\n\n");
        }
        return sb.toString();
    }

    /** 从 branch 文件中提取 LLM 上下文记录消息。
     *  优先使用 BranchMessageStore 读取 message blocks，
     *  旧 ### user / ### assistant 格式仅作为 fallback。
     *  自动过滤工具定义污染消息。 */
    public List<RenderedMessage> extractBranchMessages(DataDocument branch) {
        List<RenderedMessage> msgs = new ArrayList<>();
        String bid = branch.id();

        // 本节点输入
        String input = extractSection(branch.body(), "一、本节点输入");
        if (!input.isBlank()) {
            String content = stripHeading(input, "一、本节点输入");
            if (!ToolPollutionFilter.isPolluted(content)) {
                msgs.add(new RenderedMessage("user", "branch_input", bid, content));
            } else {
                msgs.add(new RenderedMessage("system", "system_note", bid,
                        "[skipped polluted tool definition message — branch_input]"));
            }
        }

        // 优先使用 BranchMessageStore 读取 message blocks
        List<BranchMessage> blocks;
        try {
            blocks = messageStore.listMessages(bid);
        } catch (IOException e) {
            log.warn("Failed to read message blocks for {}: {}", bid, e.getMessage());
            blocks = List.of();
        }

        if (!blocks.isEmpty()) {
            // 使用 message blocks（新格式）
            java.util.Set<Integer> seenHashes = new java.util.HashSet<>();
            for (BranchMessage m : blocks) {
                String content = m.content();
                if (content.isEmpty() || "无。".equals(content)) continue;

                // 污染检测
                if (ToolPollutionFilter.isPolluted(content)) {
                    msgs.add(new RenderedMessage("system", "system_note", bid,
                            "[skipped polluted tool definition message — " + m.type() + "]"));
                    continue;
                }

                // 去重
                int hash = content.hashCode();
                if (!seenHashes.add(hash)) {
                    msgs.add(new RenderedMessage("system", "system_note", bid,
                            "[deduplicated — same content as earlier message]"));
                    continue;
                }

                // 映射 role/type 到 RenderedMessage
                String role = m.role();
                String type = m.type();
                String toolName = m.toolName();
                msgs.add(new RenderedMessage(role, type, bid, content));
            }
        } else {
            // Fallback: 旧 ### user / ### assistant 格式
            extractLegacyBranchMessages(branch, msgs);
        }

        return msgs;
    }

    /** Fallback: 从旧 ### user / ### assistant 格式解析消息。 */
    private void extractLegacyBranchMessages(DataDocument branch, List<RenderedMessage> msgs) {
        String body = branch.body();
        String bid = branch.id();
        String llmSection = extractSection(body, "二、LLM 上下文记录");
        if (llmSection.isBlank()) return;

        List<SubSection> subs = extractAllSubSections(llmSection);
        java.util.Set<Integer> seenHashes = new java.util.HashSet<>();
        for (SubSection sub : subs) {
            String content = sub.content.trim();
            if (content.isEmpty() || "无。".equals(content)) continue;

            if (ToolPollutionFilter.isPolluted(content)) {
                msgs.add(new RenderedMessage("system", "system_note", bid,
                        "[skipped polluted tool definition message — " + sub.heading + "]"));
                continue;
            }

            int hash = content.hashCode();
            if (!seenHashes.add(hash)) {
                msgs.add(new RenderedMessage("system", "system_note", bid,
                        "[deduplicated — same content as earlier message]"));
                continue;
            }

            switch (sub.heading) {
                case "### user" -> msgs.add(new RenderedMessage("user", "branch_user", bid, content));
                case "### assistant" -> msgs.add(new RenderedMessage("assistant", "branch_assistant", bid, content));
                case "### tool_call" -> msgs.add(new RenderedMessage("tool", "tool_call", bid, content));
                case "### tool_result" -> msgs.add(new RenderedMessage("tool", "tool_result", bid, content));
                case "### system_note" -> msgs.add(new RenderedMessage("system", "system_note", bid, content));
            }
        }
    }

    /** 解析 body 中所有 ### 子节（顺序保留）。 */
    private List<SubSection> extractAllSubSections(String body) {
        List<SubSection> result = new ArrayList<>();
        String[] lines = body.split("\n");
        String currentHeading = null;
        StringBuilder currentContent = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("### ")) {
                if (currentHeading != null) {
                    result.add(new SubSection(currentHeading, currentContent.toString()));
                }
                currentHeading = line.trim();
                currentContent = new StringBuilder();
            } else if (currentHeading != null) {
                currentContent.append(line).append("\n");
            }
        }
        if (currentHeading != null) {
            result.add(new SubSection(currentHeading, currentContent.toString()));
        }
        return result;
    }

    private record SubSection(String heading, String content) {}

    private String stripHeading(String section, String heading) {
        String prefix = "## " + heading;
        int start = section.indexOf('\n');
        return start >= 0 ? section.substring(start).trim() : section.replace(prefix, "").trim();
    }

    // ---- System.md ----

    public String getSystemPrompt() {
        Path f = dataRoot.resolve("skills").resolve("System.md");
        try {
            if (Files.exists(f)) {
                String raw = Files.readString(f, StandardCharsets.UTF_8);
                return DataManager.extractBody(raw);
            }
        } catch (IOException e) { log.warn("Failed to read System.md: {}", e.getMessage()); }
        return "";
    }

    public boolean ensureSystemPrompt() {
        Path f = dataRoot.resolve("skills").resolve("System.md");
        if (Files.exists(f)) return true;
        try {
            Files.createDirectories(f.getParent());
            String content = ResourceManager.readText("gsim/templates/skill-system-template.md");
            Files.writeString(f, content, StandardCharsets.UTF_8);
            log.info("Created System.md from classpath template");
            return true;
        } catch (IOException e) {
            log.error("Failed to create System.md: {}", e.getMessage());
            return false;
        }
    }

    // ---- helpers ----

    private String readInputContent() {
        Path f = dataRoot.resolve("worlds").resolve(dm.getActiveWorld()).resolve("input.md");
        try {
            if (Files.exists(f)) {
                String raw = Files.readString(f, StandardCharsets.UTF_8);
                return DataManager.extractBody(raw);
            }
        } catch (IOException e) { /* ignore */ }
        return "";
    }

    /** 从 body 中提取顶级 ## heading 段落。 */
    private String extractSection(String body, String heading) {
        int start = body.indexOf("## " + heading);
        if (start < 0) return "";
        int end = body.indexOf("\n## ", start + heading.length() + 4);
        if (end < 0) end = body.length();
        return body.substring(start, end).trim();
    }

    /** 从 section body 中提取 ### sub-heading 内容。 */
    private String extractSubSection(String section, String heading) {
        int start = section.indexOf(heading);
        if (start < 0) return "";
        int contentStart = section.indexOf('\n', start);
        if (contentStart < 0) return "";
        int end = section.indexOf("\n### ", contentStart + 1);
        if (end < 0) end = section.indexOf("\n## ", contentStart + 1);
        if (end < 0) end = section.length();
        return section.substring(contentStart, end).trim();
    }

    /** 构建当前节点态势摘要。分析失败时不中断渲染，返回 warning。 */
    private String buildSituationSummary() {
        try {
            BranchAnalysis analysis = branchAnalyzer.analyze(null, "compact");
            return BranchAnalyzer.renderCompactMarkdown(analysis);
        } catch (Exception e) {
            log.warn("Failed to build node situation summary: {}", e.getMessage());
            return "## 当前节点态势分析失败\n\n分析异常: " + e.getMessage() + "\n";
        }
    }

    /** 保存渲染结果到 rendered-context.md。 */
    public void saveRendered() throws IOException {
        Path f = dataRoot.resolve("worlds").resolve(dm.getActiveWorld()).resolve("rendered-context.md");
        String md = renderAsMarkdown();
        Files.createDirectories(f.getParent());
        Files.writeString(f, md, StandardCharsets.UTF_8);
        log.info("Saved rendered context to {}", f);
    }
}
