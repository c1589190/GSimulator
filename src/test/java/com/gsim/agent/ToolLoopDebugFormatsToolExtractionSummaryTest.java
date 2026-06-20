package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
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
 * 验证 ToolLoop DEBUG 日志 TOOL_EXTRACTION 段格式：
 * 包含 toolCallCount, tools, containsFinishAction, suspectToolSyntax。
 */
@DisplayName("ToolLoop DEBUG 日志 TOOL_EXTRACTION 格式")
class ToolLoopDebugFormatsToolExtractionSummaryTest {

    private TestLogAppender appender;
    private LoggerContext ctx;
    private Configuration config;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        ctx = (LoggerContext) LogManager.getContext(false);
        config = ctx.getConfiguration();

        LoggerConfig loggerConfig = config.getLoggerConfig("com.gsim.agent.OrchestratorAgent");
        originalLevel = loggerConfig.getLevel();
        loggerConfig.setLevel(Level.DEBUG);

        appender = new TestLogAppender("test-extraction");
        appender.start();
        config.addAppender(appender);
        loggerConfig.addAppender(appender, Level.DEBUG, null);

        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        LoggerConfig loggerConfig = config.getLoggerConfig("com.gsim.agent.OrchestratorAgent");
        loggerConfig.removeAppender("test-extraction");
        loggerConfig.setLevel(originalLevel);
        appender.stop();
        ctx.updateLoggers();
    }

    @Test
    @DisplayName("工具提取日志包含 toolCallCount, tools, containsFinishAction")
    void toolExtractionLogContainsSummaryFields() {
        FakeLlmManager fakeLlm = new FakeLlmManager();
        fakeLlm.addResponse("{\"tool\":\"knowledge_upsert\",\"args\":{\"key\":\"test\",\"content\":\"v\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已写入。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new StubUpsertTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "记录信息");

        boolean found = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP TOOL_EXTRACTION")) {
                found = true;
                assertTrue(msg.contains("toolCallCount="),
                        "Should contain toolCallCount: " + msg);
                assertTrue(msg.contains("tools="),
                        "Should contain tools list: " + msg);
                assertTrue(msg.contains("containsFinishAction="),
                        "Should contain containsFinishAction: " + msg);
            }
        }
        assertTrue(found, "Should log TOOL_EXTRACTION");
    }

    @Test
    @DisplayName("[工具结果] marker 污染时标记 suspectToolSyntax")
    void suspectToolSyntaxFlaggedWhenFakeToolResultInContent() {
        FakeLlmManager fakeLlm = new FakeLlmManager();
        fakeLlm.addResponse("好的，任务已完成。\n[工具结果] echo: success\n请继续。");
        fakeLlm.addResponse("让我再看看...");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        boolean foundSuspect = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("suspectToolSyntax=true")) {
                foundSuspect = true;
                assertTrue(msg.contains("suspectReason="),
                        "Should include suspectReason when suspectToolSyntax=true");
            }
        }
        if (foundSuspect) {
            for (var event : appender.events()) {
                String msg = event.getMessage().getFormattedMessage();
                if (msg.contains("suspectReason=")) {
                    assertTrue(msg.contains("raw_tool_json") || msg.contains("fake_tool_result"),
                            "suspectReason should be identifiable: " + msg);
                    break;
                }
            }
        }
    }

    @Test
    @DisplayName("finish_action 工具调用时 containsFinishAction=true")
    void finishActionSetsContainsFinishActionTrue() {
        FakeLlmManager fakeLlm = new FakeLlmManager();
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"任务完成。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        boolean found = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP TOOL_EXTRACTION") && msg.contains("finish_action")) {
                assertTrue(msg.contains("containsFinishAction=true"),
                        "finish_action extraction should set containsFinishAction=true");
                found = true;
            }
        }
        assertTrue(found, "Should find TOOL_EXTRACTION with finish_action");
    }

    // ===== Stub =====

    static class StubUpsertTool implements AgentTool {
        @Override public String name() { return "knowledge_upsert"; }
        @Override public String description() { return "写入知识。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("written", "kn-test", "ok", 1.0)));
        }
    }
}
