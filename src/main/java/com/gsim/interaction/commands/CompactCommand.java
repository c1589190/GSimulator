package com.gsim.interaction.commands;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.compact.ContextCompactor;
import com.gsim.context.session.ContextSession;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.context.session.SessionMessage;
import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * /compact — 压缩上下文窗口，将对话历史总结为摘要以释放 token 空间。
 */
public class CompactCommand implements InteractionCommand {

    private static final Logger log = LoggerFactory.getLogger(CompactCommand.class);
    private static final String API_SESSION_ID = "default";

    private final ContextSessionManager ctxSessionManager;
    private final ContextCompactor compactor;
    private final OrchestratorAgent orchestrator;
    private final DataManager dataManager;

    public CompactCommand(ContextSessionManager ctxSessionManager, ContextCompactor compactor,
                          OrchestratorAgent orchestrator, DataManager dataManager) {
        this.ctxSessionManager = ctxSessionManager;
        this.compactor = compactor;
        this.orchestrator = orchestrator;
        this.dataManager = dataManager;
    }

    @Override
    public String name() { return "compact"; }

    @Override
    public String description() {
        return "压缩上下文窗口 — 将对话历史总结为摘要，以释放 token 空间";
    }

    @Override
    public String usage() {
        return "/compact";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        try {
            // 1. 获取当前 ContextSession
            Optional<ContextSession> active = ctxSessionManager.getActiveSession(API_SESSION_ID);
            if (active.isEmpty()) {
                return ok("当前没有活跃的对话上下文，无需压缩。\n"
                        + "对话上下文会在首次聊天/推演时自动创建。");
            }

            ContextSession ctx = active.get();
            String sessionId = ctx.sessionId();

            // 2. 获取所有 session 消息
            List<SessionMessage> messages = ctxSessionManager.getSessionMessages(sessionId);
            if (messages.size() < 5) {
                return ok("对话尚短（仅 " + messages.size() + " 条消息），无需压缩。\n"
                        + "建议对话达到一定长度后再使用 /compact。");
            }

            // 3. 取消当前正在进行的 ToolLoop（如果有）
            orchestrator.cancel();

            // 4. 执行压缩
            String summary = compactor.compact(messages);
            if (summary == null || summary.isBlank()) {
                return fail("压缩失败：LLM 未返回有效摘要。请重试。");
            }

            int originalCount = messages.size();
            int summaryLength = summary.length();

            // 5. 创建 Compact 检查点节点（持久化到新 branch，切换 activeBranch）
            String parentBranch = dataManager.getActiveBranch();
            var compactDoc = dataManager.createCompactCheckpoint(parentBranch, summary);
            String newBranchId = compactDoc.id();
            log.info("Compact: {} messages → {} char summary, new branch={} compactOf={}",
                    originalCount, summaryLength, newBranchId, parentBranch);

            // 6. 重置 ContextSession（branch 已切换，会自动创建新 session）
            ContextSession newSession = ctxSessionManager.resetSession(API_SESSION_ID,
                    "compact: " + originalCount + " messages → " + summaryLength + " chars, branch=" + newBranchId);
            String newSessionId = newSession.sessionId();

            // 7. 将压缩摘要写入新的 session（作为 system_note）
            String noteContent = "[上下文摘要]\n\n" + summary;
            SessionMessage note = SessionMessage.systemNote(newSessionId, newSession.branchId(), noteContent);
            ctxSessionManager.appendMessage(newSessionId, note);

            return ok("✅ 上下文已压缩。\n"
                    + "原 " + originalCount + " 条消息 → " + summaryLength + " 字符摘要。\n"
                    + "新节点: " + newBranchId + "\n"
                    + "新 Session: " + newSessionId + "\n\n"
                    + "旧节点已冻结，后续消息写入新节点。可以继续对话。");

        } catch (Exception e) {
            log.error("/compact failed: {}", e.getMessage(), e);
            return fail("压缩失败: " + e.getMessage() + "\n请重试 /compact。");
        }
    }

    private static InteractionResult ok(String msg) {
        return InteractionResult.ok(msg);
    }

    private static InteractionResult fail(String msg) {
        return InteractionResult.fail(msg);
    }
}
