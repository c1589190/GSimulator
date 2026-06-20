package com.gsim.agent;

import com.gsim.llm.FakeLlmManager;
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
 * 验证：第一轮普通答复内容被吞后，第二轮 finish_action.message
 * 必须包含完整正文，不能只是"以上是模板"式的空引用。
 */
@DisplayName("finish_action 不能引用不可见的上轮答复")
class FinishActionCannotReferToInvisiblePreviousAnswerTest {

    private FakeLlmManager fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent agent;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmManager();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());
        agent = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("第一轮完整答复被吞 → reprompt 要求完整重写 → 第二轮必须包含完整正文")
    void fullContentMustBeInFinishActionAfterReprompt() {
        // 第一轮：生成了完整报名表，但没有 finish_action
        String fullTemplate = """
                # 角色报名卡

                **姓名**：
                **种族**：
                **职业**：
                **背景故事**：
                """;
        fakeLlm.addResponse(fullTemplate);

        // 第二轮：模型收到提醒，在 finish_action.message 中完整重写了报名表
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"# 角色报名卡\\n\\n**姓名**：\\n**种族**：\\n**职业**："
                + "\\n**背景故事**：\\n\\n请复制填写后提交。\"}}");

        var result = agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "给我做一个报名表");

        assertTrue(result.success(),
                "Should succeed when finish_action has full content");
        // 最终输出必须包含完整报名表正文
        String finalText = result.finalText();
        assertTrue(finalText.contains("角色报名卡"),
                "Final text should contain the full template: " + finalText);
        assertTrue(finalText.contains("姓名"),
                "Final text should contain template fields: " + finalText);
    }

    @Test
    @DisplayName("reprompt 确保模型收到 '用户看不到上一轮' 的警告")
    void repromptWarnsModelAboutInvisiblePreviousAnswer() {
        // 第一轮：完整长答复
        fakeLlm.addResponse("这是完整的模板和表格内容……（长文本）");

        // 第二轮：finish_action
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"完成\"}}");

        agent.chatWithContextSession(
                "# Base\nbranch: branch.b0000-start\n",
                List.of(), "生成一个模板");

        // 检查第二轮 LLM request 中包含了 "没有展示给用户" 的提醒
        var requests = fakeLlm.getCapturedRequests();
        assertTrue(requests.size() >= 2,
                "Should have at least 2 LLM requests");

        // 第二轮 request 的 messages 应包含纠错提醒
        String secondRequestMessages = requests.get(1).messages().stream()
                .map(Object::toString)
                .reduce("", (a, b) -> a + b);
        assertTrue(
                secondRequestMessages.contains("没有展示给用户")
                        || secondRequestMessages.contains("用户看不到"),
                "Second request should warn model about invisible content: "
                        + secondRequestMessages);
    }

    // ===== Stub =====

    static class EchoTool implements AgentTool {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "Echo tool."; }
        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("echo", "test", "ok", 1.0)));
        }
    }
}
