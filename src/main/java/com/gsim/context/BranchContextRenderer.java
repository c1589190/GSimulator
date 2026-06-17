package com.gsim.context;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
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

    public BranchContextRenderer(DataManager dm, Path dataRoot) {
        this.dm = dm;
        this.dataRoot = dataRoot;
    }

    /** 渲染当前 active branch 完整上下文。 */
    public RenderedContext render() {
        List<RenderedMessage> messages = new ArrayList<>();
        boolean sysExists = ensureSystemPrompt();

        // 1. System prompt
        String systemContent = getSystemPrompt();
        messages.add(new RenderedMessage("system", "system_prompt", "", systemContent));

        // 2. History: active branch 父链中每个 branch 的 LLM 上下文记录
        List<DataDocument> chain = dm.getBranchChain(dm.getActiveBranch());
        // 从根到叶（时间顺序）
        for (int i = chain.size() - 1; i >= 0; i--) {
            DataDocument b = chain.get(i);
            messages.addAll(extractBranchMessages(b));
        }

        // 3. Data: 世界上下文摘要
        String worldCtx = dm.getEffectiveWorldContext();
        if (!worldCtx.isBlank()) {
            messages.add(new RenderedMessage("system", "effective_data", "", worldCtx));
        }

        // 4. User: input.md
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

    /** 从 branch 文件中提取 LLM 上下文记录消息。 */
    public List<RenderedMessage> extractBranchMessages(DataDocument branch) {
        List<RenderedMessage> msgs = new ArrayList<>();
        String body = branch.body();
        String bid = branch.id();

        // 本节点输入
        String input = extractSubSection(body, "一、本节点输入");
        if (!input.isBlank()) {
            msgs.add(new RenderedMessage("user", "branch_input", bid, input));
        }

        // LLM 上下文记录
        String llmSection = extractSection(body, "二、LLM 上下文记录");
        if (!llmSection.isBlank()) {
            String user = extractSubSection(llmSection, "### user");
            String assistant = extractSubSection(llmSection, "### assistant");
            String toolCall = extractSubSection(llmSection, "### tool_call");
            String toolResult = extractSubSection(llmSection, "### tool_result");

            if (!user.isBlank() && !"无。".equals(user.trim()))
                msgs.add(new RenderedMessage("user", "branch_user", bid, user));
            if (!assistant.isBlank() && !"无。".equals(assistant.trim()))
                msgs.add(new RenderedMessage("assistant", "branch_assistant", bid, assistant));
            if (!toolCall.isBlank() && !"无。".equals(toolCall.trim()))
                msgs.add(new RenderedMessage("tool", "tool_call", bid, toolCall));
            if (!toolResult.isBlank() && !"无。".equals(toolResult.trim()))
                msgs.add(new RenderedMessage("tool", "tool_result", bid, toolResult));
        }
        return msgs;
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
            Files.writeString(f, """
                    id: skill.system
                    type: skill
                    name: System Prompt
                    scope: global
                    tags: ["system", "prompt"]
                    updated: 2026-06-18
                    -------------------

                    你是 GSimulator 的推演 Agent。
                    你必须依据当前世界数据、时间线分支、玩家输入、工具结果进行推演。
                    你不能直接修改世界基础文件。
                    你只能生成推演结果、branch 增量、工具调用请求和待审核修改。
                    """, StandardCharsets.UTF_8);
            log.info("Created System.md");
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

    /** 保存渲染结果到 rendered-context.md。 */
    public void saveRendered() throws IOException {
        Path f = dataRoot.resolve("worlds").resolve(dm.getActiveWorld()).resolve("rendered-context.md");
        String md = renderAsMarkdown();
        Files.createDirectories(f.getParent());
        Files.writeString(f, md, StandardCharsets.UTF_8);
        log.info("Saved rendered context to {}", f);
    }
}
