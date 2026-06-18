package com.gsim.interaction.commands;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.RenderedContext;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ContextCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(ContextCommand.class);
    private final BranchContextRenderer renderer;
    private final DataManager dm;
    private final Path dataRoot;

    public ContextCommand(BranchContextRenderer renderer, DataManager dm, Path dataRoot) {
        this.renderer = renderer;
        this.dm = dm;
        this.dataRoot = dataRoot;
    }

    @Override public String name() { return "context"; }
    @Override public String description() { return "渲染当前分支的 LLM 上下文"; }
    @Override public String usage() { return "/context status | render | save | diagnose"; }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0) return fail("Usage: /context <status|render|save|diagnose>");
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        try {
            return switch (t[0]) {
                case "status" -> status();
                case "render" -> render();
                case "save" -> save();
                case "diagnose" -> diagnose();
                default -> fail("Unknown: " + t[0]);
            };
        } catch (Exception e) { log.error("/context {}: {}", t[0], e.getMessage()); return fail(e.getMessage()); }
    }

    private InteractionResult status() {
        RenderedContext ctx = renderer.render();
        return ok("World: " + ctx.activeWorld() + "\nBranch: " + ctx.activeBranch() +
                "\nChain: " + ctx.chainLength() + "\nSystem.md: " + (ctx.systemPromptExists() ? "yes" : "no") +
                "\nMessages: " + ctx.messages().size());
    }

    private InteractionResult render() {
        String md = renderer.renderAsMarkdown();
        return ok(md.length() > 3000 ? md.substring(0, 3000) + "\n\n... (truncated, use /context save for full)" : md);
    }

    private InteractionResult save() throws Exception {
        renderer.saveRendered();
        return ok("Saved to data/worlds/" + renderer.render().activeWorld() + "/rendered-context.md");
    }

    /** /context diagnose — 扫描当前 active branch 父链中的工具定义污染。 */
    private InteractionResult diagnose() throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("=== 工具定义污染诊断 ===\n\n");

        int totalPolluted = 0;
        int totalMessages = 0;

        // 1. 扫描父链中每个 branch 的 message blocks
        List<DataDocument> chain = dm.getBranchChain(dm.getActiveBranch());
        BranchMessageStore store = new BranchMessageStore(dm, dataRoot);

        for (int i = chain.size() - 1; i >= 0; i--) {
            DataDocument b = chain.get(i);
            List<BranchMessage> msgs;
            try {
                msgs = store.listMessages(b.id());
            } catch (IOException e) {
                report.append("Branch ").append(b.id()).append(": 读取失败 — ").append(e.getMessage()).append("\n");
                continue;
            }

            if (msgs.isEmpty()) continue;

            int branchPolluted = 0;
            for (BranchMessage m : msgs) {
                totalMessages++;
                if (ToolPollutionFilter.isPolluted(m.content())) {
                    branchPolluted++;
                    totalPolluted++;
                    report.append("  [污染] messageId=").append(m.id())
                            .append(" type=").append(m.type())
                            .append(" role=").append(m.role());
                    if (m.toolName() != null) report.append(" tool=").append(m.toolName());
                    report.append("\n         片段: ")
                            .append(m.content().length() > 120
                                    ? m.content().substring(0, 120).replace("\n", "\\n") + "..."
                                    : m.content().replace("\n", "\\n"))
                            .append("\n");
                }
            }
            if (branchPolluted > 0) {
                report.append("Branch ").append(b.id()).append(": ").append(branchPolluted)
                        .append(" 条污染 / ").append(msgs.size()).append(" 条消息\n\n");
            }
        }

        // 2. 扫描 rendered-context.md
        Path renderedFile = dataRoot.resolve("worlds").resolve(dm.getActiveWorld())
                .resolve("rendered-context.md");
        if (Files.exists(renderedFile)) {
            String renderedContent = Files.readString(renderedFile, StandardCharsets.UTF_8);
            int renderedPolluted = countPollutionOccurrences(renderedContent);
            if (renderedPolluted > 0) {
                report.append("rendered-context.md: ").append(renderedPolluted).append(" 处污染\n\n");
                totalPolluted += renderedPolluted;
            }
        }

        // 3. 汇总
        report.append("---\n");
        report.append("总消息数: ").append(totalMessages).append("\n");
        report.append("总污染数: ").append(totalPolluted).append("\n");
        if (totalPolluted == 0) {
            report.append("状态: 干净 ✓\n");
        } else {
            report.append("状态: 存在污染 ✗\n");
            report.append("提示: 使用 /context render 查看渲染时污染已被自动跳过。\n");
        }

        return ok(report.toString().trim());
    }

    /** 统计文本中污染片段出现次数。 */
    private int countPollutionOccurrences(String content) {
        if (content == null || content.isBlank()) return 0;

        // 统计已知污染片段出现次数
        int count = 0;
        String lower = content.toLowerCase();
        String[] fragments = {
                "run tests with the given coverage strategy",
                "mvn test with optional coverage",
                "use when asked to run tests, check coverage"
        };
        for (String frag : fragments) {
            int idx = 0;
            while ((idx = lower.indexOf(frag, idx)) >= 0) {
                count++;
                idx += frag.length();
            }
        }
        return count;
    }

    private static InteractionResult ok(String s) { return InteractionResult.ok(s); }
    private static InteractionResult fail(String s) { return InteractionResult.fail(s); }
}
