package com.gsim.compact;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.llm.FakeLlmManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolResultCompactor 的工具结果溢出保护逻辑。
 *
 * <p>注意：ToolResultCompactor 内部将 threshold 钳制在 Math.max(500, threshold)，
 * 因此任何低于 500 的配置值实际上以 500 为有效阈值。
 */
class ToolResultCompactorTest {

    private FakeLlmManager fakeLlm;
    private List<AgentProgressEvent> capturedEvents;
    private AgentProgressSink sink;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager("compact-model");
        capturedEvents = new ArrayList<>();
        sink = capturedEvents::add;
    }

    // ---- 未超阈值 ----

    @Test
    void shortResult_passedThrough() {
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 3000, null, 0.1, sink);

        String input = "Short tool result";
        String result = compactor.compactIfNeeded(input);

        assertSame(input, result, "Short result should be returned as-is");
        assertTrue(capturedEvents.isEmpty(), "No events for short results");
    }

    @Test
    void nullResult_passedThrough() {
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 500, null, 0.1, sink);

        assertNull(compactor.compactIfNeeded(null));
        assertTrue(capturedEvents.isEmpty());
    }

    @Test
    void blankResult_passedThrough() {
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 500, null, 0.1, sink);

        assertEquals("   ", compactor.compactIfNeeded("   "));
        assertTrue(capturedEvents.isEmpty());
    }

    // ---- 超阈值压缩 ----

    @Test
    void longResult_compacted() {
        fakeLlm.addResponse("压缩后的工具结果摘要，包含关键数据。");

        // threshold=100 被钳制到 500，使用 600 字符触发压缩
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 100, "compact-model", 0.1, sink);

        String input = "A".repeat(600);
        String result = compactor.compactIfNeeded(input);

        assertNotNull(result);
        assertTrue(result.contains("[COMPACTED_TOOL_RESULT]"),
                "Should wrap result in COMPACTED_TOOL_RESULT header");
        assertTrue(result.contains("原始长度 600 字符"),
                "Should mention original length");
        assertTrue(result.contains("压缩后的工具结果摘要"),
                "Should contain LLM-compacted content");
        assertFalse(capturedEvents.isEmpty(), "Should emit progress events");
    }

    @Test
    void justOverEffectiveThreshold_compacted() {
        fakeLlm.addResponse("Summary of just-over-threshold result.");

        // 钳制阈值 = 500，501 字符应触发压缩
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 500, null, 0.1, sink);

        String input = "A".repeat(501);
        String result = compactor.compactIfNeeded(input);

        assertTrue(result.contains("[COMPACTED_TOOL_RESULT]"),
                "Should compact when just over effective 500-char threshold");
    }

    @Test
    void atEffectiveThreshold_notCompacted() {
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 500, null, 0.1, sink);

        String input = "A".repeat(500);  // exactly at effective threshold
        String result = compactor.compactIfNeeded(input);

        assertEquals(input, result, "Exactly at threshold should not be compacted");
    }

    // ---- 降级方案 ----

    @Test
    void llmFailure_fallsBackToTruncation() {
        // 不预填任何响应，FakeLlmManager.submit() 返回 failure
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 100, null, 0.1, sink);

        // 需要超过 maxLen = threshold*2 = 1000 字符才能触发截断
        String input = "A".repeat(1200);
        String result = compactor.compactIfNeeded(input);

        assertNotNull(result);
        assertTrue(result.contains("结果已截断"),
                "Should fall back to truncation on LLM failure: " + result);
        assertTrue(result.contains("原始长度 1200 字符"),
                "Truncation fallback should mention original length");
    }

    // ---- 阈值最小值 ----

    @Test
    void thresholdClampedToMinimum_500() {
        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 10, null, 0.1, sink);  // 低于最小值

        String input = "A".repeat(400);  // 低于实际钳制阈值 500
        String result = compactor.compactIfNeeded(input);

        assertEquals(input, result,
                "Input below effective 500-char clamp should pass through unchanged");
    }

    // ---- 独立 Cache ----

    @Test
    void compactionUsesSeparateCache() {
        fakeLlm.addResponse("Compacted result.");
        fakeLlm.addResponse("Main conversation response.");

        ToolResultCompactor compactor = new ToolResultCompactor(
                fakeLlm, 100, null, 0.1, sink);

        compactor.compactIfNeeded("A".repeat(600));

        // compaction 消耗了第一个响应
        assertEquals(1, fakeLlm.getRequestCount(),
                "Should have made 1 LLM request for compaction");

        // 验证第二个响应仍然可用（未被 compaction 消耗）
        var mainResult = fakeLlm.chat(null);
        assertTrue(mainResult.success());
        assertEquals("Main conversation response.", mainResult.content());
    }
}
