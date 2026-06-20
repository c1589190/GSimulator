package com.gsim.agent;

import com.gsim.agent.tool.ConsolePrintTool;
import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolLoop 中 console_print 通过 CliAgentProgressSink 正确输出到 CLI。
 */
@DisplayName("ConsolePrint 在 ToolLoop 中可见")
class ConsolePrintVisibleInCliIntegrationTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private List<AgentProgressEvent> capturedEvents;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();

        capturedEvents = new ArrayList<>();
        AgentProgressSink sink = capturedEvents::add;

        toolRegistry.register(new FinishActionTool());
        toolRegistry.register(new ConsolePrintTool(sink));

        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model", sink);
    }

    @Test
    @DisplayName("LLM 调用 console_print → CLI sink 收到 AGENT_PUBLIC_MESSAGE")
    void consolePrintEmitsPublicMessageToSink() {
        // Round 1: console_print
        fakeLlm.addResponse("{\"tool\":\"console_print\",\"args\":"
                + "{\"message\":\"# 报名表\\n\\n| 字段 | 值 |\\n|------|-----|\\n| 姓名 | |\\n| 种族 | |\"}}");
        // Round 2: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":"
                + "{\"status\":\"success\",\"message\":\"报名表已展示，请填写。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "生成报名表");

        assertTrue(result.success(), "Should succeed: " + result.errorMessage());

        // 验证事件序列中出现了 AGENT_PUBLIC_MESSAGE
        var publicMessages = capturedEvents.stream()
                .filter(e -> AgentProgressEvent.AGENT_PUBLIC_MESSAGE.equals(e.phase()))
                .toList();

        assertFalse(publicMessages.isEmpty(),
                "Should have at least one AGENT_PUBLIC_MESSAGE event");
        assertEquals(1, publicMessages.size());

        var msg = publicMessages.get(0);
        assertTrue(msg.detail().contains("报名表"),
                "Public message should contain the template: " + msg.detail());
        assertTrue(msg.detail().contains("| 姓名 |"),
                "Public message should contain the table: " + msg.detail());
    }

    @Test
    @DisplayName("console_print 后 ToolLoop 继续，finish_action 正常结束")
    void consolePrintDoesNotEndToolLoop() {
        // Round 1: console_print
        fakeLlm.addResponse("{\"tool\":\"console_print\",\"args\":"
                + "{\"message\":\"阶段性草稿：当前世界观设定如下……\"}}");
        // Round 2: another tool
        fakeLlm.addResponse("{\"tool\":\"console_print\",\"args\":"
                + "{\"message\":\"补充说明：种族关系详见附件。\"}}");
        // Round 3: finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":"
                + "{\"status\":\"success\",\"message\":\"草稿已输出，需要进一步确认。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "展示草稿");

        assertTrue(result.success(), "Should succeed after 2 console_prints + finish");
        assertEquals(3, result.toolCalls().size(),
                "2 console_print + 1 finish_action = 3 tool calls");

        // 应有 2 个 AGENT_PUBLIC_MESSAGE
        var publicMessages = capturedEvents.stream()
                .filter(e -> AgentProgressEvent.AGENT_PUBLIC_MESSAGE.equals(e.phase()))
                .toList();
        assertEquals(2, publicMessages.size(),
                "Should have 2 AGENT_PUBLIC_MESSAGE events (one per console_print)");
    }

    @Test
    @DisplayName("console_print 失败时 CLI sink 收到 TOOL_FAILED 事件")
    void consolePrintFailureEmitsToolFailed() {
        // Round 1: console_print 空 message → 失败
        fakeLlm.addResponse("{\"tool\":\"console_print\",\"args\":{\"message\":\"\"}}");
        // Round 2: finish_action（收到错误后可继续）
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":"
                + "{\"status\":\"success\",\"message\":\"已收到错误提示。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试失败");

        // console_print 空 message 被工具层拒绝，但 finish_action 正常结束
        assertTrue(result.success(), "finish_action should still succeed");
        assertEquals(2, result.toolCalls().size());

        // 应收到 TOOL_FAILED 事件
        var failedEvents = capturedEvents.stream()
                .filter(e -> AgentProgressEvent.TOOL_FAILED.equals(e.phase()))
                .toList();
        assertFalse(failedEvents.isEmpty(),
                "Should have TOOL_FAILED event for console_print with empty message");
        var failed = failedEvents.get(0);
        assertEquals("console_print", failed.meta().get("tool"));
        assertTrue(failed.meta().getOrDefault("error", "").contains("不能为空"),
                "Error should mention empty message: " + failed.meta().get("error"));
    }

    @Test
    @DisplayName("console_print 正文经 CliAgentProgressSink.format 渲染后不含 [Agent]")
    void publicMessageRenderedWithoutAgentPrefix() {
        String content = "# 长模板\n\n正文内容很长……";
        var event = AgentProgressEvent.publicMessage(content);
        String formatted = CliAgentProgressSink.format(event);

        assertFalse(formatted.contains("[Agent]"),
                "publicMessage should NOT have [Agent] prefix");
        assertEquals(content, formatted,
                "Content should be rendered as-is without modification");
    }

    @Test
    @DisplayName("正常执行的完整事件序列：TOOL_EXECUTING → AGENT_PUBLIC_MESSAGE → TOOL_SUCCESS")
    void fullEventSequenceForConsolePrint() {
        // Round 1: console_print → finish
        fakeLlm.addResponse("{\"tool\":\"console_print\",\"args\":"
                + "{\"message\":\"测试正文\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":"
                + "{\"status\":\"success\",\"message\":\"完成。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "测试事件序列");

        assertTrue(result.success());

        // 提取事件序列（按顺序）
        var phases = capturedEvents.stream()
                .map(AgentProgressEvent::phase)
                .toList();

        // 验证 TOOL_EXECUTING 在 AGENT_PUBLIC_MESSAGE 之前
        int execIdx = phases.indexOf(AgentProgressEvent.TOOL_EXECUTING);
        int publicIdx = phases.indexOf(AgentProgressEvent.AGENT_PUBLIC_MESSAGE);
        int successIdx = phases.indexOf(AgentProgressEvent.TOOL_SUCCESS);

        assertTrue(execIdx >= 0, "Should have TOOL_EXECUTING");
        assertTrue(publicIdx >= 0, "Should have AGENT_PUBLIC_MESSAGE");
        assertTrue(successIdx >= 0, "Should have TOOL_SUCCESS");
        assertTrue(execIdx < publicIdx,
                "TOOL_EXECUTING should come before AGENT_PUBLIC_MESSAGE");
        assertTrue(publicIdx < successIdx,
                "AGENT_PUBLIC_MESSAGE should come before TOOL_SUCCESS");
    }
}
