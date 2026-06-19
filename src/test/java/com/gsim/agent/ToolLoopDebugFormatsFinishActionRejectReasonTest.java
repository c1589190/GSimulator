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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolLoop DEBUG 日志 FINISH_ACTION VALIDATION 段格式：
 * 拒绝原因、claim、requiredTool 清晰可读。
 */
@DisplayName("ToolLoop DEBUG 日志 finish_action 拒绝原因格式")
class ToolLoopDebugFormatsFinishActionRejectReasonTest {

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

        appender = new TestLogAppender("test-rejection");
        appender.start();
        config.addAppender(appender);
        loggerConfig.addAppender(appender, Level.DEBUG, null);

        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        LoggerConfig loggerConfig = config.getLoggerConfig("com.gsim.agent.OrchestratorAgent");
        loggerConfig.removeAppender("test-rejection");
        loggerConfig.setLevel(originalLevel);
        appender.stop();
        ctx.updateLoggers();
    }

    @Test
    @DisplayName("finish_action 被接受时日志包含 finishAccepted=true")
    void acceptedFinishActionLogsFinishAcceptedTrue() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
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
            if (msg.contains("TOOL_LOOP FINISH_ACTION VALIDATION")) {
                assertTrue(msg.contains("finishAccepted=true"),
                        "Accepted finish_action should log finishAccepted=true: " + msg);
                found = true;
            }
        }
        assertTrue(found, "Should log FINISH_ACTION VALIDATION for accepted case");
    }

    @Test
    @DisplayName("finish_action 被拒绝时日志包含 rejectReason")
    void rejectedFinishActionLogsRejectReason() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        // Round 1: finish_action with "[工具调用已执行]" banned marker → rejected
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"[工具调用已执行] 任务完成。\"}}");
        // Round 2: recover
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"任务已完成。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        boolean foundRejection = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP FINISH_ACTION VALIDATION")
                    && msg.contains("finishAccepted=false")) {
                foundRejection = true;
                assertTrue(msg.contains("rejectReason="),
                        "Rejection should include rejectReason: " + msg);
                String rejectReason = null;
                for (String line : msg.split("\n")) {
                    if (line.contains("rejectReason=")) {
                        rejectReason = line;
                        break;
                    }
                }
                assertNotNull(rejectReason, "Must have rejectReason line");
            }
        }
        assertTrue(foundRejection, "Should log rejection");
    }

    @Test
    @DisplayName("claims 拒绝时日志包含 claim 和 requiredTool")
    void claimsRejectionLogsClaimAndRequiredTool() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        // Round 1: finish_action claiming "已保存" but no save tool was called → rejected
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已保存 settlement。\"}}");
        // Round 2: 正确的 finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"任务完成。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        boolean foundClaimsRejection = false;
        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP FINISH_ACTION VALIDATION")
                    && msg.contains("CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT")) {
                foundClaimsRejection = true;
                assertTrue(msg.contains("claim="),
                        "Claims rejection should include claim: " + msg);
                assertTrue(msg.contains("successTools="),
                        "Claims rejection should include successTools: " + msg);
            }
        }
        assertTrue(foundClaimsRejection, "Should log claims rejection with claim and successTools");
    }

    @Test
    @DisplayName("所有 FINISH_ACTION VALIDATION 日志包含 loop 和 round")
    void validationLogContainsLoopAndRound() {
        FakeLlmClient fakeLlm = new FakeLlmClient();
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成。\"}}");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        OrchestratorAgent agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");

        agent.chatWithContextSession("# Base\nbranch: branch.b0000-start\n",
                java.util.List.of(), "测试");

        for (var event : appender.events()) {
            String msg = event.getMessage().getFormattedMessage();
            if (msg.contains("TOOL_LOOP FINISH_ACTION VALIDATION")) {
                assertTrue(msg.contains("loop=runToolLoop"),
                        "Validation log should identify loop: " + msg);
                assertTrue(msg.contains("round="),
                        "Validation log should identify round: " + msg);
            }
        }
    }
}
