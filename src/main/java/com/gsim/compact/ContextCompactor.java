package com.gsim.compact;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.app.AppConfig;
import com.gsim.context.session.SessionMessage;
import com.gsim.llm.LlmCall;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.llm.StreamPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史压缩引擎 — 将长对话分段交给 LLM 总结为简洁摘要。
 *
 * <p>复用现有 {@link LlmManager}（不新建实例），每次 LLM 调用携带独立 messages 列表，
 * 与主对话 Cache 完全隔离。
 *
 * <p>使用流式调用（{@link LlmManager#submit(LlmRequest)}），
 * 实时将 delta 转发到 {@link AgentProgressSink}，让用户看到压缩 LLM 的思考过程。
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 每段最多包含的消息数。 */
    private static final int SEGMENT_MAX_MESSAGES = 20;
    /** 每段最大字符数。 */
    private static final int SEGMENT_MAX_CHARS = 8000;
    /** 需要二次 LLM 合并的段数阈值。 */
    private static final int MERGE_THRESHOLD = 3;

    private static final String COMPACT_SYSTEM_PROMPT = """
            你是一个对话摘要专家。你的任务是将用户和 AI 助手之间的对话历史总结为简洁的结构化摘要。

            摘要要求：
            1. 使用中文
            2. 按时间顺序列出关键节点：用户请求了什么 → AI 调用了什么工具 → 得到了什么结果 → 做出了什么决定
            3. 忽略闲聊和重复内容
            4. 保留所有重要的决策、数据、和输出
            5. 总长度控制在 300 字以内

            输出格式：
            ## 对话摘要 (第 N 段)
            - 用户意图: ...
            - 关键操作: ...
            - 主要结果: ...
            """;

    private static final String MERGE_SYSTEM_PROMPT = """
            你是一个对话摘要合并专家。你的任务是将多段对话摘要合并为一段连贯的总体摘要。

            合并要求：
            1. 使用中文
            2. 按时间顺序排列
            3. 去重：如果同一事件在多个分段中出现，只保留一次
            4. 保留所有关键决策和数据
            5. 总长度控制在 500 字以内
            """;

    private final LlmManager llmManager;
    private final String compactModel;
    private final double compactTemperature;
    private final int summaryMaxChars;
    private final AgentProgressSink progressSink;

    public ContextCompactor(LlmManager llmManager, AppConfig config, AgentProgressSink progressSink) {
        this.llmManager = llmManager;
        this.compactModel = (config.getCompactLlmModel() != null && !config.getCompactLlmModel().isBlank())
                ? config.getCompactLlmModel()
                : config.getLlmModel();
        this.compactTemperature = config.getCompactLlmTemperature();
        this.summaryMaxChars = config.getCompactSummaryMaxChars();
        this.progressSink = progressSink;
    }

    /**
     * 压缩对话历史为上下文摘要。
     *
     * @param historyMessages 所有待压缩的 session 消息
     * @return 压缩后的中文摘要文本，可直接注入新 session 上下文
     */
    public String compact(List<SessionMessage> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return "";
        }

        int totalMessages = historyMessages.size();
        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "\n📦 正在压缩上下文（共 " + totalMessages + " 条消息）...\n"));

        // 1. 分段
        List<List<SessionMessage>> segments = splitIntoSegments(historyMessages);
        log.info("Compact: {} messages split into {} segments", totalMessages, segments.size());

        // 2. 逐段总结
        List<String> segmentSummaries = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String summary = summarizeSegment(segments.get(i), i + 1, segments.size());
            if (summary != null && !summary.isBlank()) {
                segmentSummaries.add(summary);
            }
        }

        if (segmentSummaries.isEmpty()) {
            return "";
        }

        // 3. 合并
        String finalSummary;
        if (segmentSummaries.size() <= MERGE_THRESHOLD) {
            finalSummary = segmentSummaries.size() == 1
                    ? segmentSummaries.get(0)
                    : mergeSimple(segmentSummaries);
        } else {
            finalSummary = mergeWithLlm(segmentSummaries);
        }

        // 4. 截断
        if (finalSummary.length() > summaryMaxChars) {
            finalSummary = finalSummary.substring(0, summaryMaxChars - 3) + "...";
        }

        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "✅ 压缩完成：" + totalMessages + " 条消息 → " + finalSummary.length() + " 字符摘要\n"));

        return finalSummary;
    }

    // ---- 内部分段 ----

    /**
     * 按轮边界切分消息列表。
     * 一轮对话定义为从一个 user 消息到下一个 user 消息之前的所有内容。
     */
    private List<List<SessionMessage>> splitIntoSegments(List<SessionMessage> messages) {
        List<List<SessionMessage>> segments = new ArrayList<>();
        List<SessionMessage> current = new ArrayList<>();
        int currentChars = 0;

        for (SessionMessage msg : messages) {
            String content = msg.content() != null ? msg.content() : "";
            int msgChars = content.length();

            // 在 user 消息边界且当前段已满 → 开启新段
            if ("user".equals(msg.role())
                    && (!current.isEmpty())
                    && (current.size() >= SEGMENT_MAX_MESSAGES || currentChars + msgChars > SEGMENT_MAX_CHARS)) {
                segments.add(List.copyOf(current));
                current = new ArrayList<>();
                currentChars = 0;
            }

            current.add(msg);
            currentChars += msgChars;
        }

        if (!current.isEmpty()) {
            segments.add(List.copyOf(current));
        }

        return segments;
    }

    // ---- 逐段总结 ----

    private String summarizeSegment(List<SessionMessage> segment, int index, int total) {
        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "  🔄 压缩第 " + index + "/" + total + " 段（" + segment.size() + " 条消息）..."));

        List<LlmMessage> llmMessages = new ArrayList<>();
        llmMessages.add(LlmMessage.system(COMPACT_SYSTEM_PROMPT));

        StringBuilder userContent = new StringBuilder();
        for (SessionMessage msg : segment) {
            String roleLabel = switch (msg.role()) {
                case "user" -> "用户";
                case "assistant" -> "AI";
                case "tool" -> "工具" + (msg.metadata() != null && msg.metadata().containsKey("toolName")
                        ? "[" + msg.metadata().get("toolName") + "]" : "");
                case "system" -> "系统";
                default -> msg.role();
            };
            userContent.append("[").append(roleLabel).append("] ")
                    .append(msg.content())
                    .append("\n");
        }
        llmMessages.add(LlmMessage.user(userContent.toString()));

        String segmentId = "compact-seg-" + index;
        return callCompactLlm(segmentId, llmMessages);
    }

    // ---- 简单拼接 ----

    private String mergeSimple(List<String> summaries) {
        StringBuilder sb = new StringBuilder();
        for (String s : summaries) {
            if (s != null && !s.isBlank()) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ---- LLM 合并 ----

    private String mergeWithLlm(List<String> segmentSummaries) {
        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "  🔄 合并 " + segmentSummaries.size() + " 段摘要..."));

        List<LlmMessage> llmMessages = new ArrayList<>();
        llmMessages.add(LlmMessage.system(MERGE_SYSTEM_PROMPT));

        StringBuilder userContent = new StringBuilder();
        for (int i = 0; i < segmentSummaries.size(); i++) {
            userContent.append("--- 第 ").append(i + 1).append(" 段 ---\n");
            userContent.append(segmentSummaries.get(i)).append("\n\n");
        }
        llmMessages.add(LlmMessage.user(userContent.toString()));

        return callCompactLlm("compact-merge", llmMessages);
    }

    // ---- 流式 LLM 调用 ----

    /**
     * 发送独立的 LLM 请求（无工具，流式），收集结果并转发 delta 到 progressSink。
     */
    private String callCompactLlm(String callId, List<LlmMessage> messages) {
        try {
            LlmRequest request = new LlmRequest(
                    compactModel, messages, compactTemperature, 1024,
                    List.of(), null, null, null);

            LlmCall call = llmManager.submit(request);
            StreamPool pool = call.pool();

            // 轮询流式事件，转发 delta 给用户
            int lastEventCount = 0;
            boolean firstDelta = true;
            while (!pool.isComplete()) {
                List<StreamPool.PoolEvent> events = pool.getEvents();
                if (events.size() > lastEventCount) {
                    for (int i = lastEventCount; i < events.size(); i++) {
                        StreamPool.PoolEvent e = events.get(i);
                        switch (e.type()) {
                            case CONTENT -> {
                                if (firstDelta) {
                                    progressSink.onProgress(AgentProgressEvent.publicMessage("  "));
                                    firstDelta = false;
                                }
                                progressSink.onProgress(
                                        AgentProgressEvent.llmContentDelta(callId, e.data()));
                            }
                            case REASONING -> progressSink.onProgress(
                                    AgentProgressEvent.llmReasoningDelta(callId, e.data()));
                        }
                    }
                    lastEventCount = events.size();
                }
                Thread.sleep(30);
            }

            // 收集最终剩余事件
            List<StreamPool.PoolEvent> allEvents = pool.getEvents();
            for (int i = lastEventCount; i < allEvents.size(); i++) {
                StreamPool.PoolEvent e = allEvents.get(i);
                if (e.type() == StreamPool.EventType.CONTENT) {
                    progressSink.onProgress(
                            AgentProgressEvent.llmContentDelta(callId, e.data()));
                } else if (e.type() == StreamPool.EventType.REASONING) {
                    progressSink.onProgress(
                            AgentProgressEvent.llmReasoningDelta(callId, e.data()));
                }
            }

            LlmResult result = call.await(100);
            if (result.success() && result.hasContent()) {
                return result.content();
            }
            log.warn("Compact LLM call {} failed: {}", callId, result.errorMessage());
            return "";
        } catch (Exception e) {
            log.error("Compact LLM call {} error: {}", callId, e.getMessage(), e);
            progressSink.onProgress(AgentProgressEvent.publicMessage(
                    "  ⚠️ 压缩 LLM 调用失败: " + e.getMessage()));
            return "";
        }
    }
}
