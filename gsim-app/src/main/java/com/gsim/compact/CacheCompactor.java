package com.gsim.compact;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.cache.CacheSession;
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
import java.util.Map;

/**
 * Cache 压缩引擎 — 将完整对话历史压缩为摘要文本。
 *
 * <p>流程：
 * <ol>
 *   <li>读取 CacheSession 中所有 messages</li>
 *   <li>渲染为 {@code [role]: content} 格式的大文本</li>
 *   <li>按压缩 LLM 的 maxInputChars 分段（对齐消息边界）</li>
 *   <li>逐段发送给压缩 LLM，流式输出 delta 到 CLI</li>
 *   <li>合并各段摘要 → 最终压缩文本</li>
 * </ol>
 *
 * <p>压缩 LLM 从 LlmProviderRegistry 按 id="compact" 查找，未配置则 fallback 到默认 provider。
 */
public class CacheCompactor {

    private static final Logger log = LoggerFactory.getLogger(CacheCompactor.class);

    private static final String SUMMARIZE_SYSTEM = """
            你是一个对话历史压缩专家。请将以下对话片段总结为简洁的摘要。

            要求：
            1. 使用中文
            2. 保留所有关键信息：人物、事件、决策、数据、结论
            3. 保留重要的用户指令和系统反馈
            4. 去除冗余的格式标记、重复内容、工具调用的技术细节
            5. 按时间顺序组织摘要
            6. 输出纯文本摘要，不要用 markdown 格式""";

    private final LlmManager compactorLlm;
    private final int maxInputChars;
    private final String compactorId;

    /**
     * @param compactorLlm 压缩专用 LLM（id="compact" 的 provider）
     * @param maxTokens    压缩 LLM 的 maxTokens（用于估算单次输入上限 = maxTokens * 3 字符）
     */
    public CacheCompactor(LlmManager compactorLlm, int maxTokens) {
        this.compactorLlm = compactorLlm;
        this.maxInputChars = maxTokens * 3;
        this.compactorId = compactorLlm.providerId();
    }

    /**
     * 压缩一个 CacheSession 的完整消息历史。
     *
     * @param session      要压缩的 cache
     * @param progressSink 用于流式输出压缩进度和 LLM delta
     * @return 压缩后的摘要文本
     */
    public String compact(CacheSession session, AgentProgressSink progressSink) {
        List<Map<String, Object>> messages = session.messages();
        if (messages.isEmpty()) return "(空对话历史)";

        // 1. 渲染消息为文本
        String fullText = renderMessages(messages);
        log.info("[CacheCompactor] rendering {} messages → {} chars", messages.size(), fullText.length());

        // 2. 分段
        List<String> chunks = chunkByMessageBoundary(fullText, maxInputChars);
        log.info("[CacheCompactor] split into {} chunks (maxInputChars={})", chunks.size(), maxInputChars);

        progressSink.onProgress(AgentProgressEvent.publicMessage(
                "  📦 压缩 " + messages.size() + " 条消息 → " + chunks.size() + " 段"));

        // 3. 逐段压缩（流式）
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            progressSink.onProgress(AgentProgressEvent.publicMessage(
                    "  🔄 压缩第 " + (i + 1) + "/" + chunks.size() + " 段（" + chunks.get(i).length() + " 字符）…"));

            String summary = compactChunk(chunks.get(i), i, chunks.size(), progressSink);
            summaries.add(summary);
        }

        // 4. 合并
        if (summaries.size() == 1) {
            return summaries.get(0);
        }
        return mergeSummaries(summaries, progressSink);
    }

    // ── 渲染 ──

    @SuppressWarnings("unchecked")
    static String renderMessages(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        int seq = 0;
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.getOrDefault("role", "unknown");
            String content = (String) msg.get("content");

            // tool_calls
            Object tcObj = msg.get("tool_calls");
            if (tcObj instanceof List<?> tcList && !tcList.isEmpty()) {
                sb.append("[").append(role).append(" tool_calls]: ");
                for (Object tc : tcList) {
                    if (tc instanceof Map<?, ?> tcMap) {
                        Map<String, Object> fn = (Map<String, Object>) tcMap.get("function");
                        if (fn != null) {
                            sb.append(fn.get("name")).append(" ");
                        }
                    }
                }
                sb.append("\n");
            }

            if (content != null && !content.toString().isBlank()) {
                String text = content.toString();
                // 截断过长的单条消息
                if (text.length() > 2000) {
                    text = text.substring(0, 2000) + "…[truncated]";
                }
                sb.append("[").append(role).append("]: ").append(text).append("\n");
            }
            seq++;
        }
        return sb.toString();
    }

    // ── 分段 ──

    static List<String> chunkByMessageBoundary(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (current.length() + line.length() + 1 > maxChars && current.length() > 0) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    // ── 单段压缩 ──

    private String compactChunk(String chunk, int index, int total, AgentProgressSink sink) {
        String userPrompt = total == 1
                ? "请总结以下对话历史：\n\n" + chunk
                : "请总结以下对话历史的第 " + (index + 1) + "/" + total + " 部分：\n\n" + chunk;

        List<LlmMessage> messages = List.of(
                LlmMessage.system(SUMMARIZE_SYSTEM),
                LlmMessage.user(userPrompt));

        LlmRequest request = new LlmRequest(null, messages, 0.1, 1024);
        LlmCall call = compactorLlm.submit(request);
        StreamPool pool = call.pool();

        // 流式转发
        String lastContent = "";
        try {
            while (!pool.isComplete()) {
                String c = pool.getContent();
                if (!c.equals(lastContent)) {
                    String delta = c.substring(lastContent.length());
                    lastContent = c;
                    if (!delta.isEmpty()) {
                        sink.onProgress(AgentProgressEvent.llmContentDelta("compact-" + compactorId, delta));
                    }
                }
                // reasoning
                String r = pool.getReasoning();
                if (!r.isEmpty()) {
                    String lastR = "";
                    if (!r.equals(lastR)) {
                        sink.onProgress(AgentProgressEvent.llmReasoningDelta("compact-" + compactorId,
                                r.substring(lastR.length())));
                    }
                }
                Thread.sleep(50);
            }

            LlmResult result = call.await(100);
            if (result.success() && result.hasContent()) {
                return result.content();
            }
            log.warn("[CacheCompactor] chunk {} failed: {}", index, result.errorMessage());
            return "[压缩失败] " + chunk.substring(0, Math.min(200, chunk.length())) + "…";
        } catch (Exception e) {
            log.error("[CacheCompactor] chunk {} error: {}", index, e.getMessage());
            return "[压缩异常] " + e.getMessage();
        }
    }

    // ── 合并多段摘要 ──

    private String mergeSummaries(List<String> summaries, AgentProgressSink sink) {
        sink.onProgress(AgentProgressEvent.publicMessage(
                "  🔄 合并 " + summaries.size() + " 段摘要…"));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            sb.append("## 第 ").append(i + 1).append(" 部分\n");
            sb.append(summaries.get(i)).append("\n\n");
        }
        String combined = sb.toString();

        // 如果合并后仍然很长，再用一次压缩
        if (combined.length() > maxInputChars) {
            return compactChunk(combined, 0, 1, sink);
        }
        return combined;
    }
}
