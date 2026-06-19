package com.gsim.agent;

import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmClient;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.ToolDef;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 CONTEXT_LOAD 功能：
 * 1. tools[] schema 有非零字符估算
 * 2. 请求中包含 system prompt 和用户输入
 */
@DisplayName("CONTEXT_LOAD debug 显示上下文拆分")
class ToolLoopContextLoadDebugShowsContextPartsTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("tools[] schema 字符估算 > 0")
    void toolsJsonCharsEstimatePositive() {
        List<ToolDef> oneTool = List.of(new ToolDef("finish_action", "结束本轮对话。"));
        int chars = ToolLoopDebug.estimateToolsJsonChars(oneTool);
        assertTrue(chars > 0,
                "Single tool schema estimate should be > 0, got " + chars);
    }

    @Test
    @DisplayName("空 toolDefs → 0")
    void emptyToolDefsReturnsZero() {
        int chars = ToolLoopDebug.estimateToolsJsonChars(List.of());
        assertEquals(0, chars);
    }

    @Test
    @DisplayName("多工具 schema 字符数 > 单个工具")
    void moreToolsMoreChars() {
        List<ToolDef> one = List.of(new ToolDef("t1", "d1"));
        List<ToolDef> many = List.of(
                new ToolDef("t1", "d1"),
                new ToolDef("t2", "d2"),
                new ToolDef("t3_long_name", "long description for many chars"));
        int oneChars = ToolLoopDebug.estimateToolsJsonChars(one);
        int manyChars = ToolLoopDebug.estimateToolsJsonChars(many);
        assertTrue(manyChars > oneChars,
                "Many tools (" + manyChars + ") should be > one tool (" + oneChars + ")");
    }

    @Test
    @DisplayName("系统 prompt 包含 orchestrator-system.md 内容")
    void systemPromptContainsOrchestratorContent() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        List<LlmRequest> requests = fakeLlm.getCapturedRequests();
        assertFalse(requests.isEmpty(), "Should have captured at least one request");

        // 系统 prompt 包含 tool catalog 标记
        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.contains("已注册工具") || systemPrompt.contains("Registered Tool"),
                "System prompt should contain tool catalog section");
    }

    @Test
    @DisplayName("请求消息数 > 2（至少 system + user）")
    void requestHasAtLeastSystemAndUserMessages() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        List<LlmRequest> requests = fakeLlm.getCapturedRequests();
        assertFalse(requests.isEmpty());
        LlmRequest lastReq = requests.get(requests.size() - 1);
        assertTrue(lastReq.messages().size() >= 2,
                "Should have at least system + user messages, got " + lastReq.messages().size());
    }
}
