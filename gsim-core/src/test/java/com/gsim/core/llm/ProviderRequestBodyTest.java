package com.gsim.agentsmanager.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 Provider.buildRequestBody 对 reasoning_content 字段的处理。
 *
 * <p>回归场景：SiliconFlow DeepSeek-V4-Flash（thinking 模型）多轮工具循环中，
 * 某轮 assistant 响应无 reasoning 时，历史消息缺少 reasoning_content 字段，
 * 下一轮请求报 HTTP 400: "The reasoning_content in the thinking mode must be passed back to the API"。
 * 修复后：hasNativeReasoning 的 provider 对每条 assistant 消息都输出 reasoning_content（无则空串）。
 */
class ProviderRequestBodyTest {

    private static List<LlmMessage> toolLoopMessages() {
        return List.of(
                LlmMessage.system("sys"),
                LlmMessage.user("do it"),
                // 第 1 轮 assistant：带 reasoning + tool_calls
                LlmMessage.assistantWithToolCalls(
                        "", List.of(new LlmToolCall("call_1", "query_node", Map.of())), "thinking tokens..."),
                LlmMessage.toolWithId("call_1", "query_node", "ok"),
                // 第 2 轮 assistant：无 reasoning（本轮模型未输出思考内容）
                LlmMessage.assistantWithToolCalls(
                        "继续", List.of(new LlmToolCall("call_2", "finish_action", Map.of())), null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> assistantMsg(Map<String, Object> body, int index) {
        return ((List<Map<String, Object>>) body.get("messages")).get(index);
    }

    @Test
    @DisplayName("hasNativeReasoning provider：每条 assistant 消息都带 reasoning_content（无 reasoning 时为空串）")
    void nativeReasoningProvider_alwaysEmitsReasoningContentOnAssistantMessages() {
        Provider provider = new Provider(ProviderConfig.forSiliconFlow("key", "deepseek-ai/DeepSeek-V4-Flash"));
        LlmRequest request = new LlmRequest("m", toolLoopMessages(), 0.3, 2048, List.of());

        Map<String, Object> body = provider.buildRequestBody(request, true);

        // 第 1 轮 assistant：原样回传 reasoning_content
        assertEquals("thinking tokens...", assistantMsg(body, 2).get("reasoning_content"));
        // 第 2 轮 assistant（reasoning=null）：仍输出空串字段
        assertEquals("", assistantMsg(body, 4).get("reasoning_content"));
        // 非 assistant 消息（system/user/tool）不受影响
        assertFalse(assistantMsg(body, 0).containsKey("reasoning_content"));
        assertFalse(assistantMsg(body, 1).containsKey("reasoning_content"));
        assertFalse(assistantMsg(body, 3).containsKey("reasoning_content"));
    }

    @Test
    @DisplayName("非 thinking provider：无 reasoning 的 assistant 消息不输出 reasoning_content（保持原行为）")
    void nonNativeReasoningProvider_keepsOriginalBehavior() {
        Provider provider = new Provider(ProviderConfig.generic("plain", "http://localhost", "key", "m", 0.3, 30));
        LlmRequest request = new LlmRequest("m", toolLoopMessages(), 0.3, 2048, List.of());

        Map<String, Object> body = provider.buildRequestBody(request, true);

        // 有 reasoning 仍回传
        assertEquals("thinking tokens...", assistantMsg(body, 2).get("reasoning_content"));
        // 无 reasoning 则完全省略字段
        assertFalse(assistantMsg(body, 4).containsKey("reasoning_content"));
    }
}
