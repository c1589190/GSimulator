package com.gsim.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolLoop DEBUG 日志 LLM_RESPONSE 段格式：
 * 包含 rawChars, rawPreview，截断超过 2000 字符的内容。
 */
@DisplayName("ToolLoop DEBUG 日志 LLM_RESPONSE 格式")
class ToolLoopDebugFormatsLlmResponsePreviewTest {

    private ListAppender<ILoggingEvent> listAppender;
    private Logger orchestratorLogger;

    @BeforeEach
    void setUp() {
        orchestratorLogger = (Logger) LoggerFactory.getLogger(OrchestratorAgent.class);
        orchestratorLogger.setLevel(Level.DEBUG);
        listAppender = new ListAppender<>();
        listAppender.start();
        orchestratorLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        orchestratorLogger.detachAppender(listAppender);
        listAppender.stop();
        orchestratorLogger.setLevel(null); // reset to default
    }

    @Test
    @DisplayName("LLM 响应日志包含 rawChars 和 rawPreview")
    void llmResponseLogContainsRawCharsAndPreview() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"任务完成。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        List<ILoggingEvent> logs = listAppender.list;
        boolean foundLlmResponse = false;
        for (ILoggingEvent event : logs) {
            if (event.getFormattedMessage().contains("TOOL_LOOP LLM_RESPONSE")) {
                foundLlmResponse = true;
                String msg = event.getFormattedMessage();
                assertTrue(msg.contains("rawChars="),
                        "LLM_RESPONSE should contain rawChars: " + msg);
                assertTrue(msg.contains("rawPreview:"),
                        "LLM_RESPONSE should contain rawPreview: " + msg);
                assertTrue(msg.contains("loop=runToolLoop"),
                        "Should identify loop name");
                assertTrue(msg.contains("round="),
                        "Should identify round number");
                break;
            }
        }
        assertTrue(foundLlmResponse, "Should log LLM_RESPONSE at DEBUG level");
    }

    @Test
    @DisplayName("LLM 响应超过 2000 字符时截断并标记 truncated")
    void longLlmResponseIsTruncated() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        // 构造超长 finish_action JSON (generic fill to exceed 2000 chars)
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"");
        // Fill with content to exceed 2000 chars
        while (sb.length() < 2500) {
            sb.append("任务完成。这是一段很长的回复内容用于测试截断功能。");
        }
        sb.append("\"}}");
        fakeLlm.addResponse(sb.toString());

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        List<ILoggingEvent> logs = listAppender.list;
        boolean found = false;
        for (ILoggingEvent event : logs) {
            String msg = event.getFormattedMessage();
            if (msg.contains("TOOL_LOOP LLM_RESPONSE") && msg.contains("truncated")) {
                found = true;
                assertTrue(msg.contains("truncated, rawChars="),
                        "Should mark truncated with char count");
                break;
            }
        }
        assertTrue(found, "Long response should be logged with truncation marker");
    }

    @Test
    @DisplayName("所有 ToolLoop 段都使用统一的 TOOL_LOOP 前缀")
    void allSectionsUseUnifiedPrefix() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        List<ILoggingEvent> logs = listAppender.list;
        assertFalse(logs.isEmpty(), "Should produce debug logs");

        for (ILoggingEvent event : logs) {
            String msg = event.getFormattedMessage();
            if (msg.contains("TOOL_LOOP")) {
                assertTrue(msg.startsWith("\n=== TOOL_LOOP "),
                        "TOOL_LOOP section should start with unified prefix: " + msg);
                assertTrue(msg.contains(" END ==="),
                        "TOOL_LOOP section should end with unified suffix");
            }
        }
    }
}
