package com.gsim.context;

import com.gsim.branch.BranchAnalysis;
import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.memory.PinnedConstraint;
import com.gsim.context.memory.PinnedConstraintStore;
import com.gsim.context.session.BaseContextSnapshot;
import com.gsim.context.session.SessionMessage;
import com.gsim.context.session.SessionMessageStore;
import com.gsim.context.summary.BranchPathSummaryRenderer;
import com.gsim.context.summary.NodeSummaryStore;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.resource.ResourceManager;
import com.gsim.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * BranchContextRenderer — 渲染上下文。
 *
 * <p>新架构（推荐）：
 * <ul>
 *   <li>{@link #renderBaseContext(Path)} — 只渲染概要链 + pins + 当前节点概要</li>
 *   <li>{@link #renderSessionContext(String, String)} — base snapshot + session messages</li>
 *   <li>{@link #renderBranchSummaryPath()} — 只渲染节点概要链</li>
 * </ul>
 *
 * <p>旧架构（调试保留）：
 * <ul>
 *   <li>{@link #renderFullDebugContext()} — 完整上下文（含父链 messages）</li>
 *   <li>{@link #renderFullDebugContextAsMarkdown()} — Markdown 格式</li>
 * </ul>
 */
public class BranchContextRenderer {

    private static final Logger log = LoggerFactory.getLogger(BranchContextRenderer.class);
    private static final String SYSTEM_SKILL_ID = "skill.system";
    static final String MEMORY_TOOLS_HELP = """
            ## Available Memory Tools
            - branch_path — 查看当前分支概要链
            - branch_node_get — 读取节点内容（summary/messages/output/tool_logs/full）
            - branch_node_search — 搜索节点概要和消息
            - branch_log_filter — 按字段读取节点日志
            - branch_pin_get — 读取硬约束
            - branch_pin_add — 添加硬约束

            ## Current Operating Rule
            默认只根据本上下文和当前会话消息工作。若需要旧节点完整内容，主动调用 memory tools 查询。""";

    private final DataManager dm;
    private final Path dataRoot;
    private final BranchMessageStore messageStore;
    private final BranchAnalyzer branchAnalyzer;
    private final BranchPathSummaryRenderer pathSummaryRenderer;
    private final NodeSummaryStore nodeSummaryStore;
    private final PinnedConstraintStore pinStore;
    private SessionMessageStore sessionMessageStore;

    public BranchContextRenderer(DataManager dm, Path dataRoot, BranchMessageStore messageStore,
                                  BranchAnalyzer branchAnalyzer) {
        this(dm, dataRoot, messageStore, branchAnalyzer, null, null, null);
    }

    public BranchContextRenderer(DataManager dm, Path dataRoot, BranchMessageStore messageStore,
                                  BranchAnalyzer branchAnalyzer,
                                  BranchPathSummaryRenderer pathSummaryRenderer,
                                  NodeSummaryStore nodeSummaryStore,
                                  PinnedConstraintStore pinStore) {
        this.dm = dm;
        this.dataRoot = dataRoot;
        this.messageStore = messageStore;
        this.branchAnalyzer = branchAnalyzer;
        this.pathSummaryRenderer = pathSummaryRenderer;
        this.nodeSummaryStore = nodeSummaryStore;
        this.pinStore = pinStore;
    }

    public void setSessionMessageStore(SessionMessageStore store) {
        this.sessionMessageStore = store;
    }

    /**
     * @deprecated 使用 {@link #renderFullDebugContext()} 替代。
     *             完整上下文不应再作为默认 Agent 输入。
     */
    @Deprecated
    public RenderedContext render() {
        return renderFullDebugContext();
    }

    /**
     * @deprecated 使用 {@link #renderFullDebugContextAsMarkdown()} 替代。
     */
    @Deprecated
    public String renderAsMarkdown() {
        return renderFullDebugContextAsMarkdown();
    }

    // ====== 新架构：BaseContext + Session ======

    /**
     * 渲染 BaseContextSnapshot markdown。
     * 只包含：概要链 + 硬约束 + 当前节点概要 + memory tools 说明。
     * 不包含完整父链 messages。
     */
    public BaseContextSnapshot renderBaseContext(Path contextDir) {
        String branchId = dm.getActiveBranch();
        String startNodeId = branchId;
        List<String> includedNodeIds = new ArrayList<>();
        List<String> includedPinIds = new ArrayList<>();

        StringBuilder sb = new StringBuilder();
        sb.append("# GSimulator Base Context\n\n");

        // 1. Active Branch 信息
        sb.append("## Active Branch\n");
        sb.append("branch: ").append(branchId).append("\n");
        sb.append("start node: ").append(startNodeId).append("\n\n");

        // 2. Pinned Constraints
        List<PinnedConstraint> pins = pinStore != null
                ? pinStore.findByBranch(branchId) : List.of();
        // 也查找父链的 pins
        if (pinStore != null) {
            List<DataDocument> chain = dm.getBranchChain(branchId);
            Set<String> branchIds = new HashSet<>();
            if (chain != null) {
                for (DataDocument doc : chain) {
                    branchIds.add(doc.id());
                }
            }
            branchIds.add(branchId);
            List<PinnedConstraint> allPins = pinStore.findByBranches(branchIds);
            // 去重
            Set<String> seen = new HashSet<>();
            pins = new ArrayList<>();
            for (PinnedConstraint p : allPins) {
                if (seen.add(p.id())) pins.add(p);
            }
        }

        sb.append("## Pinned Constraints\n");
        if (pins.isEmpty()) {
            sb.append("_（暂无硬约束）_\n");
        } else {
            for (PinnedConstraint pin : pins) {
                sb.append("- ").append(pin.text()).append("\n");
                includedPinIds.add(pin.id());
            }
        }
        sb.append("\n");

        // 3. Branch Evolution Summary（概要链）
        if (pathSummaryRenderer != null) {
            String pathSummary = pathSummaryRenderer.renderPath(branchId,
                    new BranchPathSummaryRenderer.BranchPathRenderOptions());
            sb.append(pathSummary).append("\n\n");

            // 收集节点 ID
            List<DataDocument> chain = dm.getBranchChain(branchId);
            if (chain != null) {
                for (DataDocument doc : chain) {
                    includedNodeIds.add(doc.id());
                }
            }
            includedNodeIds.add(branchId);
        } else {
            sb.append("## Branch Evolution Summary\n\n");
            sb.append("_（概要渲染器未初始化）_\n\n");
        }

        // 4. 当前节点态势摘要
        String situation = buildSituationSummary();
        if (!situation.isBlank()) {
            sb.append("## Current Node Situation\n\n");
            sb.append(situation).append("\n\n");
        }

        // 5. Memory Tools 说明
        sb.append(MEMORY_TOOLS_HELP).append("\n");

        String markdown = sb.toString().trim();
        String snapshotId = IdGenerator.generate("bc");

        // 持久化到文件
        if (contextDir != null) {
            try {
                Path baseContextsDir = contextDir.resolve("base_contexts");
                Files.createDirectories(baseContextsDir);
                Path file = baseContextsDir.resolve(snapshotId + ".md");
                Files.writeString(file, markdown, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to save BaseContextSnapshot: {}", e.getMessage());
            }
        }

        return new BaseContextSnapshot(
                snapshotId, branchId, startNodeId, Instant.now(),
                markdown, markdown.length(), includedNodeIds, includedPinIds
        );
    }

    /**
     * 渲染会话上下文：BaseContext + session messages + user input。
     */
    public String renderSessionContext(String baseContextMarkdown, String contextSessionId, String currentUserInput) {
        StringBuilder sb = new StringBuilder();
        sb.append(baseContextMarkdown).append("\n\n");

        // Session messages
        if (sessionMessageStore != null && contextSessionId != null) {
            List<SessionMessage> msgs = sessionMessageStore.getAll();
            if (!msgs.isEmpty()) {
                sb.append("## Session Messages\n\n");
                for (SessionMessage msg : msgs) {
                    sb.append("[").append(msg.role()).append("] ");
                    String content = msg.content();
                    // 截断过长消息
                    if (content.length() > 2000) {
                        content = content.substring(0, 1997) + "...";
                    }
                    sb.append(content).append("\n\n");
                }
            }
        }

        // Current user input
        if (currentUserInput != null && !currentUserInput.isBlank()) {
            sb.append("## Current Input\n\n");
            sb.append(currentUserInput).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 只渲染节点概要链（不含 messages / world data）。
     */
    public String renderBranchSummaryPath() {
        if (pathSummaryRenderer != null) {
            return pathSummaryRenderer.renderActivePath();
        }
        return "_（概要渲染器未初始化）_\n";
    }

    // ====== 旧架构：调试用完整上下文 ======

    /**
     * 渲染完整调试上下文（含父链 messages / world / entities / players / rules / input）。
     * 不应用于默认 Agent 输入。
     */
    public RenderedContext renderFullDebugContext() {
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

    /**
     * 渲染完整调试上下文为 Markdown。
     * 不应用于默认 Agent 输入。
     */
    public String renderFullDebugContextAsMarkdown() {
        RenderedContext ctx = renderFullDebugContext();
        StringBuilder sb = new StringBuilder();
        sb.append("# Rendered Context (DEBUG FULL)\n");
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
