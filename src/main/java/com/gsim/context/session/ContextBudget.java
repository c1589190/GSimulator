package com.gsim.context.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算控制 — 规则压缩，不调用 LLM。
 *
 * <p>默认规则：
 * <ul>
 *   <li>SESSION_CONTEXT_MAX_CHARS = 20000</li>
 *   <li>SESSION_RECENT_MESSAGES = 12</li>
 *   <li>TOOL_RESULT_MAX_CHARS = 3000</li>
 *   <li>ASSISTANT_MESSAGE_MAX_CHARS = 6000</li>
 * </ul>
 */
public class ContextBudget {

    private static final Logger log = LoggerFactory.getLogger(ContextBudget.class);

    public static final int SESSION_CONTEXT_MAX_CHARS = 20000;
    public static final int SESSION_RECENT_MESSAGES = 12;
    public static final int TOOL_RESULT_MAX_CHARS = 3000;
    public static final int ASSISTANT_MESSAGE_MAX_CHARS = 6000;

    private final int maxChars;
    private final int recentMessages;
    private final int toolResultMaxChars;
    private final int assistantMaxChars;

    public ContextBudget() {
        this(SESSION_CONTEXT_MAX_CHARS, SESSION_RECENT_MESSAGES,
                TOOL_RESULT_MAX_CHARS, ASSISTANT_MESSAGE_MAX_CHARS);
    }

    public ContextBudget(int maxChars, int recentMessages,
                          int toolResultMaxChars, int assistantMaxChars) {
        this.maxChars = maxChars;
        this.recentMessages = recentMessages;
        this.toolResultMaxChars = toolResultMaxChars;
        this.assistantMaxChars = assistantMaxChars;
    }

    /**
     * 压缩 session messages 列表。返回处理后的列表。
     */
    public CompressedResult compress(List<SessionMessage> messages) {
        if (messages.isEmpty()) {
            return new CompressedResult(messages, List.of(), 0, 0);
        }

        List<SessionMessage> result = new ArrayList<>();
        int totalChars = 0;
        int truncatedCount = 0;
        int summaryCount = 0;

        int recentStart = Math.max(0, messages.size() - recentMessages);

        for (int i = 0; i < messages.size(); i++) {
            SessionMessage msg = messages.get(i);
            boolean isRecent = (i >= recentStart);

            if (isRecent) {
                // 最近 N 条原文保留
                SessionMessage processed = truncateIfNeeded(msg);
                result.add(processed);
                totalChars += processed.content().length();
                if (!processed.content().equals(msg.content())) truncatedCount++;
            } else {
                // 更早消息压缩为摘要
                if (i == recentStart - 1 && summaryCount == 0) {
                    // 在最近消息之前插入一条摘要
                    SessionMessage summary = SessionMessage.systemNote(
                            msg.contextSessionId(), msg.branchId(),
                            "[上下文摘要] 此前 " + recentStart + " 条消息已压缩。"
                                    + "关键主题请参考后续消息。"
                    );
                    result.add(summary);
                    summaryCount++;
                }
                // 跳过早期消息，不加入结果
                totalChars += Math.min(msg.content().length(), 100);
            }
        }

        log.debug("ContextBudget compression: {} messages → {} ({} chars, {} truncated, {} summary)",
                messages.size(), result.size(), totalChars, truncatedCount, summaryCount);

        return new CompressedResult(result, messages, truncatedCount, summaryCount);
    }

    private SessionMessage truncateIfNeeded(SessionMessage msg) {
        String content = msg.content();
        int maxLen = maxChars;

        if ("tool".equals(msg.role())) {
            if ("tool_result".equals(msg.type())) {
                maxLen = toolResultMaxChars;
            }
        } else if ("assistant".equals(msg.role())) {
            maxLen = assistantMaxChars;
        }

        if (content.length() <= maxLen) return msg;

        String truncated = content.substring(0, maxLen - 3) + "...";
        String fullRef = msg.metadata() != null
                ? msg.metadata().getOrDefault("fullRef", "")
                : "";

        return new SessionMessage(
                msg.id(), msg.contextSessionId(), msg.branchId(),
                msg.role(), msg.type(), truncated, msg.createdAt(),
                java.util.Map.of("fullRef", fullRef, "truncated", "true",
                        "originalLength", String.valueOf(content.length()))
        );
    }

    /**
     * 压缩结果。
     */
    public record CompressedResult(
            List<SessionMessage> compressedMessages,
            List<SessionMessage> originalMessages,
            int truncatedCount,
            int summaryCount
    ) {}
}
