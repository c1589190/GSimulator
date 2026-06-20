package com.gsim.compact;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.app.AppConfig;
import com.gsim.context.session.SessionMessage;
import com.gsim.llm.FakeLlmManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ContextCompactor 的对话历史分段总结逻辑。
 * 使用 FakeLlmManager 模拟 LLM 响应，不依赖外部服务。
 */
class ContextCompactorTest {

    private FakeLlmManager fakeLlm;
    private List<AgentProgressEvent> capturedEvents;
    private AgentProgressSink sink;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager("compact-model");
        capturedEvents = new ArrayList<>();
        sink = capturedEvents::add;
    }

    // ---- 空输入 ----

    @Test
    void nullMessages_returnsEmpty() {
        ContextCompactor compactor = createCompactor();
        assertEquals("", compactor.compact(null));
    }

    @Test
    void emptyMessages_returnsEmpty() {
        ContextCompactor compactor = createCompactor();
        assertEquals("", compactor.compact(List.of()));
    }

    // ---- 单段压缩 ----

    @Test
    void singleSegment_compactedAndReturned() {
        fakeLlm.addResponse("## 对话摘要 (第 1 段)\n- 用户意图: 测试对话\n- 关键操作: 无\n- 主要结果: 完成");

        ContextCompactor compactor = createCompactor();
        List<SessionMessage> messages = createMessages(5);

        String result = compactor.compact(messages);

        assertNotNull(result);
        assertFalse(result.isBlank(), "Should return non-empty summary");
        assertTrue(result.contains("对话摘要"), "Should contain summary header");
        assertFalse(capturedEvents.isEmpty(), "Should emit progress events");
    }

    // ---- 多段压缩（≤3段直接拼接） ----

    @Test
    void twoSegments_directConcat() {
        // 25 条消息会分成 2 段（每段 ≤ 20 条）
        fakeLlm.addResponse("## 对话摘要 (第 1 段)\n- 用户意图: A\n- 关键操作: 无\n- 主要结果: 完成");
        fakeLlm.addResponse("## 对话摘要 (第 2 段)\n- 用户意图: B\n- 关键操作: 无\n- 主要结果: 完成");

        ContextCompactor compactor = createCompactor();
        List<SessionMessage> messages = createMessages(25);

        String result = compactor.compact(messages);

        assertNotNull(result);
        assertTrue(result.contains("第 1 段"), "Should contain segment 1");
        assertTrue(result.contains("第 2 段"), "Should contain segment 2");
        assertEquals(2, fakeLlm.getRequestCount(), "Should make 2 LLM calls for 2 segments");
    }

    // ---- 多段合并（>3段需要 LLM 二次合并） ----

    @Test
    void fourSegments_llmMerge() {
        // 65 条消息会分成 4 段
        // 需要 4 个段总结 + 1 个合并 = 5 个 LLM 调用
        for (int i = 0; i < 5; i++) {
            fakeLlm.addResponse("## 对话摘要 (第 " + (i + 1) + " 段)\n- 关键操作: 测试");
        }

        ContextCompactor compactor = createCompactor();
        List<SessionMessage> messages = createMessages(65);

        String result = compactor.compact(messages);

        assertNotNull(result);
        assertFalse(result.isBlank());
        assertEquals(5, fakeLlm.getRequestCount(),
                "Should make 4 segment + 1 merge = 5 LLM calls");
    }

    // ---- 截断超长结果 ----

    @Test
    void longSummary_truncated() {
        // 生成超过 summaryMaxChars 的摘要
        String longSummary = "X".repeat(2500);  // 默认 maxChars=2000
        fakeLlm.addResponse(longSummary);

        ContextCompactor compactor = createCompactor();
        List<SessionMessage> messages = createMessages(5);

        String result = compactor.compact(messages);

        assertNotNull(result);
        assertTrue(result.length() <= 2000 + 3,  // maxChars + "..."
                "Result should be truncated to maxChars: " + result.length());
    }

    // ---- 独立 Cache ----

    @Test
    void compactionUsesIndependentCache() {
        fakeLlm.addResponse("## 对话摘要\n- 用户意图: 独立压缩\n- 关键操作: 无\n- 主要结果: 完成");
        fakeLlm.addResponse("Main conversation response.");

        ContextCompactor compactor = createCompactor();
        List<SessionMessage> messages = createMessages(5);

        compactor.compact(messages);

        // compaction 消耗了一个响应
        assertEquals(1, fakeLlm.getRequestCount());

        // 主会话的响应还在
        var mainResult = fakeLlm.chat(null);
        assertTrue(mainResult.success());
        assertEquals("Main conversation response.", mainResult.content());
    }

    // ---- LLM 失败 ----

    @Test
    void llmFailure_returnsEmpty() {
        // 不预填响应，FakeLlmManager 返回 failure
        ContextCompactor compactor = createCompactor();
        List<SessionMessage> messages = createMessages(5);

        String result = compactor.compact(messages);

        assertEquals("", result, "Should return empty on LLM failure");
    }

    // ---- helpers ----

    private ContextCompactor createCompactor() {
        AppConfig config = AppConfig.forTesting();
        return new ContextCompactor(fakeLlm, config, sink);
    }

    private List<SessionMessage> createMessages(int count) {
        List<SessionMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            String type = i % 2 == 0 ? "chat_user" : "chat_response";
            messages.add(new SessionMessage(
                    "msg-" + i,        // id
                    "session-test",    // contextSessionId
                    "branch.b0000",    // branchId
                    role,
                    type,
                    "Message " + i + " content: " + "data ".repeat(10),
                    java.time.Instant.now(),
                    java.util.Map.of()
            ));
        }
        return messages;
    }
}
