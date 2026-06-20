package com.gsim.llm;

import com.gsim.agent.OrchestratorAgent;
import com.gsim.agent.tool.FinishActionTool;
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
 * 验证 OpenAiLlmClient 发送的请求中包含 tools。
 */
@DisplayName("OpenAiLlmClient 请求序列化 tools")
class OpenAiLlmClientSerializesToolsTest {

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
    @DisplayName("请求包含 tools 数组且含有 finish_action")
    void requestContainsToolDefinitions() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        List<LlmRequest> requests = fakeLlm.getCapturedRequests();
        assertFalse(requests.isEmpty(), "Should have captured at least one request");
        LlmRequest lastReq = requests.get(requests.size() - 1);
        assertNotNull(lastReq.tools(), "Request should have tools");
        assertFalse(lastReq.tools().isEmpty(), "Tool definitions should not be empty");

        boolean hasFinishAction = lastReq.tools().stream()
                .anyMatch(t -> "finish_action".equals(t.name()));
        assertTrue(hasFinishAction, "Tools should include finish_action: "
                + lastReq.tools().stream().map(ToolDef::name).toList());
    }

    @Test
    @DisplayName("请求设置 tool_choice=auto")
    void toolChoiceIsAuto() {
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        List<LlmRequest> requests = fakeLlm.getCapturedRequests();
        assertFalse(requests.isEmpty());
        LlmRequest lastReq = requests.get(requests.size() - 1);
        assertEquals("auto", lastReq.toolChoice(),
                "Default tool_choice should be auto");
    }

    @Test
    @DisplayName("注册多个工具时全部出现在 tools 中")
    void allRegisteredToolsAppear() {
        toolRegistry.register(new EchoTool());
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"done\"}}");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试");

        LlmRequest lastReq = fakeLlm.getCapturedRequests().get(
                fakeLlm.getCapturedRequests().size() - 1);
        List<String> toolNames = lastReq.tools().stream().map(ToolDef::name).toList();
        assertTrue(toolNames.contains("finish_action"));
        assertTrue(toolNames.contains("echo"));
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo test tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
