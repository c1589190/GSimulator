package com.gsim.interaction.commands;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.chat.ToolPollutionFilter;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.RenderedContext;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.SessionMessage;
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
import java.util.Optional;

/**
 * /context — 上下文管理和诊断。
 *
 * <p>新命令（ContextSession 模式）：
 * <ul>
 *   <li>/context show     — 显示当前 ContextSession 状态</li>
 *   <li>/context reset    — 重置 ContextSession</li>
 *   <li>/context base     — 显示 BaseContextSnapshot markdown</li>
 *   <li>/context messages — 显示当前 session 消息</li>
 * </ul>
 *
 * <p>旧命令（debug 保留）：
 * <ul>
 *   <li>/context render   — 渲染完整 debug 上下文</li>
 *   <li>/context save     — 保存完整上下文到文件</li>
 *   <li>/context diagnose — 诊断工具定义污染</li>
 * </ul>
 */
public class ContextCommand implements InteractionCommand {
    private static final Logger log = LoggerFactory.getLogger(ContextCommand.class);
    private final BranchContextRenderer renderer;
    private final DataManager dm;
    private final Path dataRoot;
    private final ContextSessionManager ctxSessionManager;

    public ContextCommand(BranchContextRenderer renderer, DataManager dm, Path dataRoot,
                           ContextSessionManager ctxSessionManager) {
        this.renderer = renderer;
        this.dm = dm;
        this.dataRoot = dataRoot;
        this.ctxSessionManager = ctxSessionManager;
    }

    @Override public String name() { return "context"; }
    @Override public String description() {
        return "管理上下文会话。子命令: show, reset, base, messages, render, save, diagnose";
    }
    @Override public String usage() {
        return "/context <show|reset|base|messages|render|save|diagnose>";
    }

    @Override public InteractionResult execute(String[] args, InteractionSession session) {
        if (args == null || args.length == 0) {
            return fail("Usage: " + usage());
        }
        String full = String.join(" ", args).trim();
        String[] t = full.split("\\s+");
        try {
            return switch (t[0]) {
                case "show" -> handleShow();
                case "reset" -> handleReset();
                case "base" -> handleBase();
                case "messages" -> handleMessages();
                case "render" -> render();
                case "save" -> save();
                case "diagnose" -> diagnose();
                default -> fail("Unknown: " + t[0] + ". Available: show, reset, base, messages, render, save, diagnose");
            };
        } catch (Exception e) {
            log.error("/context {}: {}", t[0], e.getMessage());
            return fail(e.getMessage());
        }
    }

    // ====== 新命令（ContextSession 模式） ======

    private InteractionResult handleShow() {
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        if (active.isEmpty()) {
            return ok("No active ContextSession.\n"
                    + "A ContextSession will be created automatically on first chat/sim/run.\n"
                    + "Use /context reset to manually create one.");
        }

        ContextSession s = active.get();
        StringBuilder sb = new StringBuilder();
        sb.append("=== ContextSession ===\n");
        sb.append("Session ID:    ").append(s.sessionId()).append("\n");
        sb.append("API Session:   ").append(s.apiSessionId()).append("\n");
        sb.append("Branch:        ").append(s.branchId()).append("\n");
        sb.append("Start Node:    ").append(s.startNodeId()).append("\n");
        sb.append("BaseContextID: ").append(s.baseContextId()).append("\n");
        sb.append("Status:        ").append(s.status()).append("\n");
        sb.append("Messages:      ").append(ctxSessionManager.getSessionMessages(s.sessionId()).size()).append("\n");
        sb.append("Created:       ").append(s.createdAt()).append("\n");

        return ok(sb.toString().trim());
    }

    private InteractionResult handleReset() {
        ContextSession newSession = ctxSessionManager.resetSession("default", "cli reset");
        StringBuilder sb = new StringBuilder();
        sb.append("ContextSession reset.\n");
        sb.append("New Session ID:    ").append(newSession.sessionId()).append("\n");
        sb.append("New BaseContextID: ").append(newSession.baseContextId()).append("\n");

        return ok(sb.toString().trim());
    }

    private InteractionResult handleBase() {
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        String markdown;
        if (active.isPresent()) {
            markdown = ctxSessionManager.getBaseContextMarkdown("default");
        } else {
            // 无 session 时直接渲染（不创建 session）
            var contextDir = dm.getDataRoot().resolve("worlds")
                    .resolve(dm.getActiveWorld()).resolve("context");
            markdown = renderer.renderBaseContext(contextDir).markdown();
        }

        if (markdown == null || markdown.isBlank()) {
            return fail("No BaseContext available. Create a ContextSession first.");
        }

        // 截断显示（base context 可能很长）
        String display = markdown.length() > 3000
                ? markdown.substring(0, 3000) + "\n\n... (truncated, " + markdown.length() + " chars total)"
                : markdown;
        return ok(display);
    }

    private InteractionResult handleMessages() {
        Optional<ContextSession> active = ctxSessionManager.getActiveSession("default");
        if (active.isEmpty()) {
            return fail("No active ContextSession. Create one with /context reset.");
        }

        List<SessionMessage> msgs = ctxSessionManager.getSessionMessages(active.get().sessionId());
        if (msgs.isEmpty()) {
            return ok("No messages in this ContextSession yet.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Session Messages (").append(msgs.size()).append(") ===\n\n");
        for (int i = 0; i < Math.min(msgs.size(), 20); i++) {
            SessionMessage m = msgs.get(i);
            sb.append("[").append(i + 1).append("] ")
                    .append(m.role()).append("/").append(m.type())
                    .append(" (").append(m.createdAt()).append(")\n");
            String c = m.content();
            if (c.length() > 200) c = c.substring(0, 197) + "...";
            sb.append(c).append("\n\n");
        }
        if (msgs.size() > 20) {
            sb.append("... and ").append(msgs.size() - 20).append(" more messages.\n");
        }

        return ok(sb.toString().trim());
    }

    // ====== 旧命令（debug 保留） ======

    private InteractionResult render() {
        String md = renderer.renderFullDebugContextAsMarkdown();
        String display = md.length() > 3000
                ? md.substring(0, 3000) + "\n\n... (truncated, use /context save for full)"
                : md;
        return ok("=== DEBUG FULL Context ===\n" + display);
    }

    private InteractionResult save() throws Exception {
        renderer.saveRendered();
        return ok("Saved to data/worlds/" + renderer.renderFullDebugContext().activeWorld() + "/rendered-context.md");
    }

    /** /context diagnose — 扫描当前 active branch 父链中的工具定义污染。 */
    private InteractionResult diagnose() throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("=== 工具定义污染诊断 ===\n\n");

        int totalPolluted = 0;
        int totalMessages = 0;

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

        report.append("---\n");
        report.append("总消息数: ").append(totalMessages).append("\n");
        report.append("总污染数: ").append(totalPolluted).append("\n");
        report.append(totalPolluted == 0 ? "状态: 干净 ✓\n" : "状态: 存在污染 ✗\n");

        return ok(report.toString().trim());
    }

    private int countPollutionOccurrences(String content) {
        if (content == null || content.isBlank()) return 0;
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
