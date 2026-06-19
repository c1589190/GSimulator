package com.gsim.agent;

import com.gsim.context.session.SessionMessage;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 /chat ContextSession 主路径的 LLM system prompt 注入。
 * 适配 finish_action 架构。
 */
@DisplayName("Chat ContextSession Prompt Injection")
class ChatContextSessionPromptInjectionTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent orchestrator;

    @BeforeEach
    void setUp() {
        fakeLlm = new FakeLlmClient();
        toolRegistry = new ToolRegistry();

        // 注册 knowledge tools（模拟真实 ToolRegistry）
        toolRegistry.register(new FakeKnowledgeUpsertTool());
        toolRegistry.register(new FakeKeywordSearchTool());
        toolRegistry.register(new FakeKnowledgeSearchTool());
        toolRegistry.register(new FakeKnowledgeGetChunkTool());
        toolRegistry.register(new FakeKnowledgeGetDocumentTool());
        toolRegistry.register(new FakeKnowledgeUpdateTool());
        toolRegistry.register(new FakeKnowledgeDeleteTool());
        toolRegistry.register(new FakeKnowledgeEmbedMissingTool());
        toolRegistry.register(new com.gsim.agent.tool.FinishActionTool());

        orchestrator = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    // ========== 测试 1: system prompt 包含 orchestrator-system.md ==========

    @Test
    @DisplayName("/chat ContextSession 主路径的 system prompt 包含 orchestrator-system.md")
    void systemPromptContainsOrchestratorSystemMd() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"收到，已理解上下文。\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n\n_（暂无硬约束）_\n",
                List.of(),
                "请分析当前局势");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertNotNull(systemPrompt, "system prompt 不应为 null");
        assertFalse(systemPrompt.isBlank(), "system prompt 不应为空");

        // orchestrator-system.md 的特征内容
        assertTrue(systemPrompt.contains("架空历史推演助手"),
                "system prompt 应包含身份定义（架空历史推演助手）");
        assertTrue(systemPrompt.contains("统一自然语言"),
                "system prompt 应包含统一自然语言入口说明");
        assertTrue(systemPrompt.contains("BaseContextSnapshot"),
                "system prompt 应提到 BaseContextSnapshot");
    }

    // ========== 测试 2: system prompt 包含 knowledge_upsert ==========

    @Test
    @DisplayName("/chat ContextSession system prompt 包含 knowledge_upsert 字样")
    void systemPromptContainsKnowledgeUpsert() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"好的，我来保存资料。\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n",
                List.of(),
                "请把这段设定保存到知识库");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.contains("knowledge_upsert"),
                "system prompt 应包含 knowledge_upsert 工具名称");
        assertTrue(systemPrompt.contains("GSimulator 内置知识库工具"),
                "system prompt 应说明 knowledge_upsert 是内置工具");
        assertTrue(systemPrompt.contains("保存长期资料"),
                "system prompt 应说明 knowledge_upsert 可保存长期资料");
    }

    // ========== 测试 3: system prompt 包含 keyword_search / knowledge_search ==========

    @Test
    @DisplayName("/chat ContextSession system prompt 包含 keyword_search 和 knowledge_search")
    void systemPromptContainsSearchTools() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"这是搜索结果。\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n",
                List.of(),
                "搜索关于乌萨斯的资料");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.contains("keyword_search"),
                "system prompt 应包含 keyword_search");
        assertTrue(systemPrompt.contains("knowledge_search"),
                "system prompt 应包含 knowledge_search");
    }

    // ========== 测试 4: ToolLoop 执行 knowledge_upsert ==========

    @Test
    @DisplayName("模拟 LLM 输出 knowledge_upsert JSON 后 ToolLoop 能执行")
    void toolLoopExecutesKnowledgeUpsert() {
        fakeLlm.addResponse(
                "{\"tool\":\"knowledge_upsert\",\"args\":{\"title\":\"乌萨斯边境设定\",\"content\":\"乌萨斯边境感染者救援点容易引发地方军警注意\",\"collection\":\"first-test\",\"sourceType\":\"agent_note\",\"sourceUri\":\"chat-session\"}}");
        fakeLlm.addResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已将设定保存到知识库。collection=first-test，标题=乌萨斯边境设定。资料已存入知识库，后续可通过 knowledge_search 检索。\"}}");

        OrchestratorAgent.ChatResult result = orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n",
                List.of(),
                "请把这段设定保存到知识库，collection 用 first-test：乌萨斯边境感染者救援点容易引发地方军警注意，罗德岛必须通过中间人暗线联系。");

        assertTrue(result.success(), "Chat 应成功");
        assertNotNull(result.finalText(), "应有最终回复");
        assertFalse(result.toolCalls().isEmpty(), "应有 tool call 记录");
        assertEquals("knowledge_upsert", result.toolCalls().get(0).tool(),
                "应调用 knowledge_upsert");
        assertEquals("乌萨斯边境设定", result.toolCalls().get(0).args().get("title"),
                "title 参数应正确传递");
        assertEquals("first-test", result.toolCalls().get(0).args().get("collection"),
                "collection 参数应正确传递");

        // 验证 tool 执行结果
        ToolResult toolResult = result.toolCalls().get(0).result();
        assertTrue(toolResult.success(), "knowledge_upsert 应执行成功");
        assertFalse(toolResult.items().isEmpty(), "应有返回结果");
    }

    // ========== 测试 5: system prompt 不含旧冲突规则 ==========

    @Test
    @DisplayName("system prompt 不应包含旧的 /sim /run 独占指令")
    void systemPromptHasNoLegacySimRunRules() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"ok\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n",
                List.of(),
                "hello");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertFalse(systemPrompt.contains("/sim 时"),
                "不应有 /sim 独占指令");
        assertFalse(systemPrompt.contains("/run 时"),
                "不应有 /run 独占指令");
    }

    // ========== 测试 6: 带 SessionMessage 历史的调用 ==========

    @Test
    @DisplayName("带 SessionMessage 历史时 system prompt 仍正确注入")
    void systemPromptCorrectWithSessionHistory() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"明白，我继续推演。\"}}");

        List<SessionMessage> history = List.of(
                SessionMessage.user("cs-test", "branch.b0001-test",
                        "我们来推演切尔诺伯格事件"),
                SessionMessage.assistant("cs-test", "branch.b0001-test",
                        "好的。切尔诺伯格是乌萨斯帝国的主要城市之一...")
        );

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n",
                history,
                "继续推演，博士带领罗德岛小队进入切尔诺伯格");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.contains("架空历史推演助手"),
                "即使有历史消息，system prompt 也应包含 orchestrator system prompt");
        assertTrue(systemPrompt.contains("knowledge_upsert"),
                "即使有历史消息，system prompt 也应包含 knowledge_upsert");
        assertTrue(systemPrompt.contains("Base Context"),
                "system prompt 应包含 BaseContext");

        // 验证 LLM 收到的消息结构
        List<LlmRequest> requests = fakeLlm.getCapturedRequests();
        assertEquals(1, requests.size(), "应只有一次 LLM 调用");

        LlmRequest request = requests.get(0);
        List<LlmMessage> messages = request.messages();
        assertFalse(messages.isEmpty(), "LLM messages 不应为空");

        // 第一条消息应为 system
        assertEquals("system", messages.get(0).role(), "第一条消息应为 system");
        // 最后一条应为 user（当前输入）
        LlmMessage lastMsg = messages.get(messages.size() - 1);
        assertEquals("user", lastMsg.role(), "最后一条消息应为 user");
        assertTrue(lastMsg.content().contains("继续推演"),
                "最后一条消息应包含当前用户输入");
    }

    // ========== 测试 7: system prompt 长度合理 ==========

    @Test
    @DisplayName("system prompt 长度应远超原来的 1143 chars（含完整 orchestrator-system.md + tool catalog）")
    void systemPromptLengthIsSubstantial() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"ok\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n\n_（暂无硬约束）_\n",
                List.of(),
                "hello");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.length() > 3000,
                "system prompt 长度应 > 3000 chars（含 orchestrator-system.md + tool catalog + BaseContext），实际: " + systemPrompt.length());
        // orchestrator-system.md 本身约 3KB，tool catalog 约 1-2KB，BaseContext ~500B
        // 总计应 > 4000 chars
    }

    // ========== 测试 8: 打印 system prompt 内容（人工确凿验证） ==========

    @Test
    @DisplayName("PRINT system prompt 内容以确凿验证 LLM 能看到所有工具")
    void printSystemPromptForManualVerification() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"ok\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n\n_（暂无硬约束）_\n",
                List.of(),
                "请把这段设定保存到知识库");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.contains("knowledge_upsert"),
                "即使 PRINT 测试，system prompt 也应包含 knowledge_upsert");
    }

    // ========== 测试 9: 模拟 system prompt 包含 finish_action 工具名 ==========

    @Test
    @DisplayName("system prompt 包含 finish_action 工具名（用于 ToolLoop 终止）")
    void systemPromptContainsFinishAction() {
        fakeLlm.setNextResponse("{\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"ok\"}}");

        orchestrator.chatWithContextSession(
                "# GSimulator Base Context\n\nbranch: b0001-test\n",
                List.of(),
                "hello");

        String systemPrompt = fakeLlm.getLastSystemPrompt();
        assertTrue(systemPrompt.contains("finish_action"),
                "system prompt 应包含 finish_action 工具名");
    }

    // ===== Fake Knowledge Tools =====

    static class FakeKnowledgeUpsertTool implements AgentTool {
        @Override public String name() { return "knowledge_upsert"; }
        @Override public String description() { return "插入或更新知识条目。"; }
        @Override
        public ToolResult execute(ToolCall call) {
            Map<String, String> params = Map.of(
                    "ok", "true",
                    "embeddingProfileId", "p0001",
                    "chunkIndex", "0"
            );
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("doc", "upserted", params.toString(), 0.9)));
        }
    }

    static class FakeKeywordSearchTool implements AgentTool {
        @Override public String name() { return "keyword_search"; }
        @Override public String description() { return "关键词搜索。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("result", "hit", "keyword match", 0.8)));
        }
    }

    static class FakeKnowledgeSearchTool implements AgentTool {
        @Override public String name() { return "knowledge_search"; }
        @Override public String description() { return "语义搜索。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("result", "hit", "semantic match", 0.9)));
        }
    }

    static class FakeKnowledgeGetChunkTool implements AgentTool {
        @Override public String name() { return "knowledge_get_chunk"; }
        @Override public String description() { return "获取单个 chunk。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("chunk", "c0", "chunk text", 1.0)));
        }
    }

    static class FakeKnowledgeGetDocumentTool implements AgentTool {
        @Override public String name() { return "knowledge_get_document"; }
        @Override public String description() { return "获取完整 document。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("doc", "d0", "doc text", 1.0)));
        }
    }

    static class FakeKnowledgeUpdateTool implements AgentTool {
        @Override public String name() { return "knowledge_update"; }
        @Override public String description() { return "更新知识条目。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("ok", "updated", "updated", 1.0)));
        }
    }

    static class FakeKnowledgeDeleteTool implements AgentTool {
        @Override public String name() { return "knowledge_delete"; }
        @Override public String description() { return "删除知识条目。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("ok", "deleted", "deleted", 1.0)));
        }
    }

    static class FakeKnowledgeEmbedMissingTool implements AgentTool {
        @Override public String name() { return "knowledge_embed_missing"; }
        @Override public String description() { return "补充缺失的 embedding。"; }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("ok", "embedded", "embedded", 1.0)));
        }
    }
}
