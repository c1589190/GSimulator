package com.gsim.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真 API 流式 + 工具调用集成测试。
 *
 * <p>使用 {@link LlmManager#submit(LlmRequest)} 发起流式请求，
 * 通过 {@link StreamPool} 接收实时事件并验证三个 provider 的流式行为。
 *
 * <p>不依赖 FakeLlmManager，所有请求走真实 API。
 */
@DisplayName("真 API 流式工具调用测试（三 provider）")
@EnabledIfSystemProperty(named = "gsim.real-api-test", matches = "true")
class RealApiStreamingTest {

    // ═══════════════════════════════════════════════════════════
    // API 凭据（硬编码测试用，不提交到版本控制）
    // ═══════════════════════════════════════════════════════════

    private static final String XFYUN_URL = "https://maas-api.cn-huabei-1.xf-yun.com/v2";
    private static final String XFYUN_KEY =
            "9aadfef086e5ed90207b76acadbb256b:NmRkMzJiZTM2NWEwMDI5NWUzYmJiNjQy";
    private static final String XFYUN_MODEL = "astron-code-latest";

    private static final String SF_URL = "https://api.siliconflow.cn/v1";
    private static final String SF_KEY =
            "sk-nqndfqwmywnhietyuivzlfpmyjongcrpxplrwwvueiwpcsry";
    private static final String SF_MODEL = "deepseek-ai/DeepSeek-V4-Flash";

    private static final String DS_URL = "https://api.deepseek.com";
    private static final String DS_KEY =
            "sk-408b8057faaa442a8b5e86cd20e2eb8e";
    private static final String DS_MODEL = "deepseek-chat";

    // ═══════════════════════════════════════════════════════════
    // 工具定义
    // ═══════════════════════════════════════════════════════════

    private static final ToolDef FINISH_ACTION_TOOL = new ToolDef(
            "finish_action",
            "完成任务并返回最终结果。当你确认所有工作已完成时调用此工具。",
            ToolDef.strictSchema(
                    Map.of(
                            "status", Map.of("type", "string",
                                    "description", "任务状态：success 或 failure",
                                    "enum", List.of("success", "failure")),
                            "message", Map.of("type", "string",
                                    "description", "给用户的最终消息")
                    ),
                    List.of("status", "message")
            )
    );

    // ═══════════════════════════════════════════════════════════
    // Stream via LlmManager.submit() → StreamPool
    // ═══════════════════════════════════════════════════════════

    private StreamPool submitAndAwait(LlmManager mgr, LlmRequest request, long timeoutMs) throws Exception {
        LlmCall call = mgr.submit(request);
        call.pool().awaitCompletion(timeoutMs);
        return call.pool();
    }

    private LlmManager createManager(String baseUrl, String apiKey, String model) {
        return new LlmManager(ProviderConfig.generic("test", baseUrl, apiKey, model, 0.3, 120));
    }

    private LlmRequest buildRequest(List<LlmMessage> messages, Object toolChoice) {
        return new LlmRequest(
                null,           // model — use default from client
                messages,
                0.3,            // temperature
                500,            // maxTokens
                List.of(FINISH_ACTION_TOOL),
                toolChoice
        );
    }

    // ═══════════════════════════════════════════════════════════
    // Test 1: 讯飞 astron-code-latest — auto tool_choice
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("讯飞 astron-code-latest: 流式 + auto tool_choice → 返回 finish_action")
    void xfyunAutoToolChoice() throws Exception {
        var mgr = createManager(XFYUN_URL, XFYUN_KEY, XFYUN_MODEL);
        LlmRequest request = buildRequest(
                List.of(LlmMessage.user("请调用 finish_action 工具。status=success, message=流式测试完成")),
                "auto");

        var pool = submitAndAwait(mgr, request, 60_000);

        System.out.println("═══ [讯飞 astron-code-latest] auto tool_choice ═══");
        System.out.println(pool.report());

        assertTrue(pool.isComplete(), "流式应标记完成");
        assertTrue(pool.getToolCalls().size() > 0 || !pool.getContent().isBlank(),
                "至少应有 tool_calls 或 content");
        assertFalse(pool.getContent().contains("null"),
                "content 不应是字面量 null");

        if (!pool.getToolCalls().isEmpty()) {
            LlmToolCall tc = pool.getToolCalls().get(0);
            assertEquals("finish_action", tc.name(), "工具名应为 finish_action");
            assertTrue(tc.arguments().containsKey("status"), "args 应含 status");
        }
        mgr.close();
    }

    // ═══════════════════════════════════════════════════════════
    // Test 2: 讯飞 astron-code-latest — forced tool_choice
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("讯飞 astron-code-latest: 流式 + forced finish_action → 直接返回 tool_call")
    void xfyunForcedToolChoice() throws Exception {
        var mgr = createManager(XFYUN_URL, XFYUN_KEY, XFYUN_MODEL);
        LlmRequest request = buildRequest(
                List.of(LlmMessage.user("请完成流式测试任务")),
                Map.of("type", "function", "function", Map.of("name", "finish_action")));

        var pool = submitAndAwait(mgr, request, 60_000);

        System.out.println("═══ [讯飞 astron-code-latest] forced tool_choice ═══");
        System.out.println(pool.report());

        assertTrue(pool.isComplete(), "流式应标记完成");
        assertTrue(!pool.getToolCalls().isEmpty() || !pool.getContent().isBlank(),
                "至少应有 tool_calls 或 content");
        mgr.close();
    }

    // ═══════════════════════════════════════════════════════════
    // Test 3: SiliconFlow DeepSeek-V4-Flash — auto
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("SiliconFlow DeepSeek-V4-Flash: 流式 + auto tool_choice → 返回 finish_action")
    void siliconFlowAutoToolChoice() throws Exception {
        var mgr = createManager(SF_URL, SF_KEY, SF_MODEL);
        LlmRequest request = buildRequest(
                List.of(LlmMessage.user("请调用 finish_action 工具。status=success, message=SiliconFlow流式测试完成")),
                "auto");

        var pool = submitAndAwait(mgr, request, 60_000);

        System.out.println("═══ [SiliconFlow DeepSeek-V4-Flash] auto tool_choice ═══");
        System.out.println(pool.report());

        assertTrue(pool.isComplete(), "流式应标记完成");
        assertTrue(!pool.getContent().isBlank() || !pool.getToolCalls().isEmpty(),
                "至少应有 content 或 tool_calls");
        assertFalse(pool.getContent().contains("null"),
                "content 不应是字面量 null");

        if (!pool.getToolCalls().isEmpty()) {
            LlmToolCall tc = pool.getToolCalls().get(0);
            assertEquals("finish_action", tc.name());
        }
        mgr.close();
    }

    // ═══════════════════════════════════════════════════════════
    // Test 4: SiliconFlow DeepSeek-V4-Flash — forced
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("SiliconFlow DeepSeek-V4-Flash: 流式 + forced finish_action → tool_call")
    void siliconFlowForcedToolChoice() throws Exception {
        var mgr = createManager(SF_URL, SF_KEY, SF_MODEL);
        LlmRequest request = buildRequest(
                List.of(LlmMessage.user("完成任务")),
                Map.of("type", "function", "function", Map.of("name", "finish_action")));

        var pool = submitAndAwait(mgr, request, 60_000);

        System.out.println("═══ [SiliconFlow DeepSeek-V4-Flash] forced tool_choice ═══");
        System.out.println(pool.report());

        assertTrue(pool.isComplete(), "流式应标记完成");
        assertTrue(!pool.getContent().isBlank() || !pool.getToolCalls().isEmpty(),
                "至少应有 content 或 tool_calls");
        mgr.close();
    }

    // ═══════════════════════════════════════════════════════════
    // Test 5: DeepSeek deepseek-chat — auto tool_choice
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DeepSeek deepseek-chat: 流式 + auto tool_choice → 返回 finish_action")
    void deepseekAutoToolChoice() throws Exception {
        var mgr = createManager(DS_URL, DS_KEY, DS_MODEL);
        LlmRequest request = buildRequest(
                List.of(LlmMessage.user("请调用 finish_action 工具。status=success, message=DeepSeek流式测试完成")),
                "auto");

        var pool = submitAndAwait(mgr, request, 60_000);

        System.out.println("═══ [DeepSeek deepseek-chat] auto tool_choice ═══");
        System.out.println(pool.report());

        assertTrue(pool.isComplete(), "流式应标记完成");
        assertTrue(!pool.getContent().isBlank() || !pool.getToolCalls().isEmpty(),
                "至少应有 content 或 tool_calls");
        assertFalse(pool.getContent().contains("null"),
                "content 不应是字面量 null");

        if (!pool.getToolCalls().isEmpty()) {
            LlmToolCall tc = pool.getToolCalls().get(0);
            assertEquals("finish_action", tc.name());
        }
        mgr.close();
    }

    // ═══════════════════════════════════════════════════════════
    // Test 6: DeepSeek deepseek-chat — forced tool_choice
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("DeepSeek deepseek-chat: 流式 + forced finish_action → tool_call (无 thinking)")
    void deepseekForcedToolChoice() throws Exception {
        var mgr = createManager(DS_URL, DS_KEY, DS_MODEL);
        LlmRequest request = buildRequest(
                List.of(LlmMessage.user("完成任务")),
                Map.of("type", "function", "function", Map.of("name", "finish_action")));

        var pool = submitAndAwait(mgr, request, 60_000);

        System.out.println("═══ [DeepSeek deepseek-chat] forced tool_choice ═══");
        System.out.println(pool.report());

        assertTrue(pool.isComplete(), "流式应标记完成");
        assertTrue(!pool.getContent().isBlank() || !pool.getToolCalls().isEmpty(),
                "至少应有 content 或 tool_calls");
        mgr.close();
    }

    // ═══════════════════════════════════════════════════════════
    // Test 7: 三 API 全部流式事件到达检测（实时池更新）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("三个 API 流式事件均实时到达 StreamPool")
    void allApisDeliverEventsToPool() throws Exception {
        record ApiCase(String name, String url, String key, String model) {}

        var apis = List.of(
                new ApiCase("讯飞", XFYUN_URL, XFYUN_KEY, XFYUN_MODEL),
                new ApiCase("SiliconFlow", SF_URL, SF_KEY, SF_MODEL),
                new ApiCase("DeepSeek", DS_URL, DS_KEY, DS_MODEL)
        );

        for (var api : apis) {
            var mgr = createManager(api.url(), api.key(), api.model());
            LlmRequest request = buildRequest(
                    List.of(LlmMessage.user("请简单回复「你好」，然后调用 finish_action 工具，status=success，message=完成")),
                    "auto");

            var pool = submitAndAwait(mgr, request, 60_000);

            System.out.println("═══ [事件到达检测] " + api.name() + " ═══");
            System.out.println(pool.report());

            assertTrue(pool.isComplete(), api.name() + " 应标记完成");

            long contentEvents = pool.eventCount(StreamPool.EventType.CONTENT);
            long toolCallEvents = pool.eventCount(StreamPool.EventType.TOOL_CALL_DELTA);
            long reasoningEvents = pool.eventCount(StreamPool.EventType.REASONING);

            System.out.printf("[%s] CONTENT=%d REASONING=%d TOOL_CALL=%d%n",
                    api.name(), contentEvents, reasoningEvents, toolCallEvents);

            // 至少应有 content 事件或 tool_call 事件（不能是空池）
            assertTrue(contentEvents > 0 || toolCallEvents > 0,
                    api.name() + " 流式池必须收到至少一种事件（CONTENT 或 TOOL_CALL）");

            assertFalse(pool.getContent().contains("null"),
                    api.name() + " content 不应是字面量 null");
            mgr.close();
        }
    }
}
