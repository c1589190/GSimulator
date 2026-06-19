package com.gsim.agent;

import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.ToolRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolLoop DEBUG 日志 LLM_RESPONSE 段格式：
 * 包含 rawChars, rawPreview，截断超过 2000 字符的内容。
 */
@DisplayName("ToolLoop DEBUG 日志 LLM_RESPONSE 格式")
class ToolLoopDebugFormatsLlmResponsePreviewTest {

    private TestLogAppender appender;
    private LoggerContext ctx;
    private Configuration config;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        ctx = (LoggerContext) LogManager.getContext(false);
        config = ctx.getConfiguration();

        // 保存原始级别
        LoggerConfig loggerConfig = config.getLoggerConfig("com.gsim.agent.OrchestratorAgent");
        originalLevel = loggerConfig.getLevel();

        // 设置 DEBUG 级别
        loggerConfig.setLevel(Level.DEBUG);

        // 添加测试 appender
        appender = new TestLogAppender("test-preview");
        appender.start();
        config.addAppender(appender);
        loggerConfig.addAppender(appender, Level.DEBUG, null);

        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        LoggerConfig loggerConfig = config.getLoggerConfig("com.gsim.agent.OrchestratorAgent");
        loggerConfig.removeAppender("test-preview");
        loggerConfig.setLevel(originalLevel);
        appender.stop();
        ctx.updateLoggers();
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

        boolean foundLlmResponse = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP LLM_RESPONSE")) {
                foundLlmResponse = true;
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
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"");
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

        boolean found = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
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

        assertFalse(appender.events().isEmpty(), "Should produce debug logs");

        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP")) {
                assertTrue(msg.startsWith("\n=== TOOL_LOOP "),
                        "TOOL_LOOP section should start with unified prefix: " + msg);
                assertTrue(msg.contains(" END ==="),
                        "TOOL_LOOP section should end with unified suffix");
            }
        }
    }
}
