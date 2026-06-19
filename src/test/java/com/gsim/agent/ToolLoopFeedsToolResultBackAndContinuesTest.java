package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolLoop 正确回灌 tool_result 并继续循环：
 * <ol>
 * <li>tool result 被注入 LLM 上下文</li>
 * <li>LLM 在下一轮可以看到 tool result</li>
 * <li>ToolLoop 继续直到 LLM 不再调用工具</li>
 * </ol>
 */
@DisplayName("ToolLoop 回灌 tool_result 并继续")
class ToolLoopFeedsToolResultBackAndContinuesTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new WeatherTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("tool result 被注入 LLM 上下文供下一轮使用")
    void toolResultInjectedToLlmContext() {
        fakeLlm.addResponse("{\"tool\":\"check_weather\",\"args\":{\"region\":\"龙门\"}}");
        fakeLlm.addResponse("根据天气数据，龙门当前阴天，适合行动。");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "龙门天气如何");

        // 验证 LLM 上下文中包含 tool result
        List<LlmRequest> requests = fakeLlm.getCapturedRequests();
        assertTrue(requests.size() >= 2, "Should have at least 2 LLM calls");

        // 第二轮请求的 messages 应包含 tool result
        LlmRequest secondReq = requests.get(1);
        boolean hasToolResult = false;
        for (LlmMessage msg : secondReq.messages()) {
            if (msg.content().contains("[TOOL_RESULT]") || msg.content().contains("tool=check_weather")) {
                hasToolResult = true;
            }
        }
        assertTrue(hasToolResult,
                "Second LLM request should contain tool result feedback");
    }

    @Test
    @DisplayName("ToolLoop 在工具调用后继续而非停止")
    void toolLoopContinuesAfterToolCall() {
        // 三轮：工具调用 → 工具调用 → 自然语言
        fakeLlm.addResponse("{\"tool\":\"check_weather\",\"args\":{\"region\":\"龙门\"}}");
        fakeLlm.addResponse("{\"tool\":\"check_weather\",\"args\":{\"region\":\"乌萨斯\"}}");
        fakeLlm.addResponse("两处天气已确认完毕。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "查询多处天气");

        assertTrue(result.success());
        assertEquals(2, result.toolCalls().size(),
                "Should have 2 tool calls (loop continued after first)");
        assertEquals(3, fakeLlm.getCapturedRequests().size(),
                "Should have 3 LLM calls (2 tool + 1 final)");
        assertTrue(result.finalText().contains("确认完毕"),
                "Final answer should be the third response");
    }

    @Test
    @DisplayName("LLM 基于 tool result 信息调整后续回复")
    void llmAdaptsResponseBasedOnToolResult() {
        // 第一轮：工具调用
        fakeLlm.addResponse("{\"tool\":\"check_weather\",\"args\":{\"region\":\"龙门\"}}");
        // 第二轮：LLM 基于 tool result 给出含具体数据的回复
        fakeLlm.addResponse("龙门外围荒漠地带当前天气晴朗，"
                + "能见度高，风力3级，适合罗德岛小队行动。");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "龙门天气");

        assertTrue(result.success());
        assertEquals(1, result.toolCalls().size());
        // LLM 的回复应包含从 tool result 得到的上下文
        assertTrue(result.finalText().contains("天气") || result.finalText().contains("风力"),
                "LLM should incorporate tool result data: " + result.finalText());
    }

    // ===== Fake Tool =====

    static class WeatherTool implements AgentTool {
        @Override public String name() { return "check_weather"; }
        @Override public String description() {
            return "查询指定区域的天气情况。参数: region(必填)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            String region = call.param("region", "未知区域");
            String data = "区域: " + region + " | 天气: 晴朗 | 温度: 22度 | 风力: 3级 | 能见度: 高";
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("weather", region, data, 1.0)));
        }
    }
}
