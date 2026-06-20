package com.gsim.compact;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.llm.LlmCall;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.llm.StreamPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 工具结果溢出保护 — 当工具返回的文本超过阈值时，用 LLM 总结为简洁版本。
 *
 * <p>复用现有 {@link LlmManager}（不新建实例），每次 LLM 调用携带独立 messages 列表，
 * 与主对话 Cache 完全隔离。使用流式调用，实时转发 delta 给用户。
 */
public class ToolResultCompactor {

    private static final Logger log = LoggerFactory.getLogger(ToolResultCompactor.class);

    private static final String SUMMARIZE_PROMPT = """
            你是一个文本摘要专家。请将以下工具执行结果总结为简洁的关键信息摘要。

            要求：
            1. 使用中文
            2. 保留所有关键数据（数字、名称、状态、结果）
            3. 保留所有重要发现和结论
            4. 去除冗余的格式标记和重复内容
            5. 总长度控制在原文的 1/3 以内

            原始工具结果：
            """;

    private final LlmManager llmManager;
    private final int threshold;
    private final String compactModel;
    private final double compactTemperature;
    private final AgentProgressSink progressSink;

    public ToolResultCompactor(LlmManager llmManager, int threshold, String compactModel,
                               double compactTemperature, AgentProgressSink progressSink) {
        this.llmManager = llmManager;
        this.threshold = Math.max(500, threshold);
        this.compactModel = (compactModel != null && !compactModel.isBlank())
                ? compactModel : null;  // null = 复用 LlmRequest 的默认 model
        this.compactTemperature = compactTemperature;
        this.progressSink = progressSink;
    }

    /**
     * 如果文本超过阈值则压缩，否则原样返回。
     *
     * @param formattedToolResult 格式化的工具结果文本（如 buildToolResultFeedback 的输出）
     * @return 压缩后或原样的文本
     */
    public String compactIfNeeded(String formattedToolResult) {
        if (formattedToolResult == null || formattedToolResult.isBlank()) {
            return formattedToolResult;
        }
        if (formattedToolResult.length() <= threshold) {
            return formattedToolResult;
        }

        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "  🔄 工具结果过大（" + formattedToolResult.length()
                + " 字符），正在压缩..."));

        try {
            List<LlmMessage> messages = List.of(
                    LlmMessage.system(SUMMARIZE_PROMPT),
                    LlmMessage.user(formattedToolResult)
            );

            LlmRequest request = new LlmRequest(
                    compactModel, messages, compactTemperature, 1024,
                    List.of(), null, null, null);

            LlmCall call = llmManager.submit(request);
            StreamPool pool = call.pool();

            // 轮询流式事件，转发 delta 给用户
            int lastEventCount = 0;
            while (!pool.isComplete()) {
                List<StreamPool.PoolEvent> events = pool.getEvents();
                if (events.size() > lastEventCount) {
                    for (int i = lastEventCount; i < events.size(); i++) {
                        StreamPool.PoolEvent e = events.get(i);
                        switch (e.type()) {
                            case CONTENT -> progressSink.onProgress(
                                    AgentProgressEvent.llmContentDelta("tool-compact", e.data()));
                            case REASONING -> progressSink.onProgress(
                                    AgentProgressEvent.llmReasoningDelta("tool-compact", e.data()));
                        }
                    }
                    lastEventCount = events.size();
                }
                Thread.sleep(30);
            }

            LlmResult result = call.await(100);
            if (result.success() && result.hasContent()) {
                String compacted = result.content();
                progressSink.onProgress(AgentProgressEvent.publicMessage(
                        "  ✅ 压缩完成：" + formattedToolResult.length()
                        + " → " + compacted.length() + " 字符"));
                // 包装为 [COMPACTED_TOOL_RESULT] 标记，让 LLM 知道这是压缩后的结果
                return "[COMPACTED_TOOL_RESULT] (原始长度 " + formattedToolResult.length()
                        + " 字符，已压缩为 " + compacted.length() + " 字符)\n\n" + compacted;
            }
            log.warn("Tool result compaction LLM call failed: {}", result.errorMessage());
            progressSink.onProgress(AgentProgressEvent.publicMessage(
                    "  ⚠️ 压缩失败，使用截断版本"));
            return truncateFallback(formattedToolResult);
        } catch (Exception e) {
            log.error("Tool result compaction error: {}", e.getMessage(), e);
            progressSink.onProgress(AgentProgressEvent.publicMessage(
                    "  ⚠️ 压缩失败，使用截断版本"));
            return truncateFallback(formattedToolResult);
        }
    }

    /**
     * 降级方案：硬截断到 threshold * 2 字符。
     */
    private String truncateFallback(String text) {
        int maxLen = threshold * 2;
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...\n[结果已截断: 原始长度 " + text.length() + " 字符]";
    }
}
