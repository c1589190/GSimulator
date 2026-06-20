package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.llm.LlmToolCall;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 finish_action 被拒绝后重新生成合法 finish_action 正常结束。
 */
@DisplayName("finish_action 被拒后重写并通过")
class FinishActionRejectedThenValidFinishEndsLoopTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("R1 [工具调用已执行] → 被拒 → R2 合法 → 结束")
    void rejectedThenValidFinishes() {
        // R1: finish_action message 含 [工具调用已执行] → 应被拒
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success",
                                "message", "[工具调用已执行] 结果如上，已完成。"))
        ));
        // R2: 合法 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "success", "message", "重试完成，结果已准备好。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        assertTrue(result.success(),
                "Should succeed after retry: " + result.errorMessage());

        // 最终文本来自 R2
        assertTrue(result.finalText().contains("重试完成"),
                "finalText should come from the retry (R2): " + result.finalText());
        assertFalse(result.finalText().contains("[工具调用已执行]"),
                "finalText should be free of banned markers");

        // 两个 finish_action 都被执行和记录（R1 虽被拒但工具已执行）
        assertEquals(2, result.toolCalls().size(),
                "Both finish_action calls (rejected + accepted) are recorded");
        assertEquals("finish_action", result.toolCalls().get(0).tool());
        assertEquals("finish_action", result.toolCalls().get(1).tool());

        // 2 轮 LLM: R1 rejected + R2 accepted
        assertEquals(2, fakeLlm.getRequestCount(),
                "Should have exactly 2 LLM rounds");
    }

    @Test
    @DisplayName("R1 声称'已保存'无支撑 → 被拒 → R2 合法 → 结束")
    void falseClaimRejectedThenValidFinishes() {
        // R1: 声称"已保存"但没有 save 工具支撑
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_001", "finish_action",
                        Map.of("status", "success", "message", "结算已保存，回合已推进。"))
        ));
        // R2: 诚实的 finish_action
        fakeLlm.addToolCallsResponse(List.of(
                new LlmToolCall("call_002", "finish_action",
                        Map.of("status", "partial", "message", "无法完成保存操作，需要先执行保存工具。"))
        ));

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "结算");

        assertTrue(result.success(),
                "Should succeed after false claim rejection: " + result.errorMessage());
        assertFalse(result.finalText().contains("已保存"),
                "finalText should not contain uncorroborated success claims");
        assertEquals(2, fakeLlm.getRequestCount());
    }
}
