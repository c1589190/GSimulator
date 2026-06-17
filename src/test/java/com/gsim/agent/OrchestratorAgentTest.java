package com.gsim.agent;

import com.gsim.campaign.PlayerAction;
import com.gsim.llm.FakeLlmClient;
import com.gsim.tool.LocalFileSearchService;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.WikiSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrchestratorAgent 测试 — 使用 FakeLlmClient，不访问外网。
 */
@DisplayName("OrchestratorAgent")
class OrchestratorAgentTest {

    private FakeLlmClient fakeLlm;
    private ToolRegistry toolRegistry;
    private OrchestratorAgent orchestrator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // 准备 wiki 数据
        Path wikiDir = tempDir.resolve("web").resolve("prts.wiki");
        Files.createDirectories(wikiDir);
        Files.writeString(wikiDir.resolve("rhodes-island.txt"), """
                # 罗德岛
                Source URL: https://prts.wiki/w/罗德岛
                Fetched At: 2025-01-01 12:00:00
                Site: prts.wiki
                Crawler: mediawiki-batch-allpages
                Collection Hint: world_lore
                Tags: web,prts.wiki
                ---
                罗德岛是泰拉大陆主要的感染者救助组织，拥有武装力量。
                精英干员包括阿米娅、凯尔希、煌等。
                """);

        fakeLlm = new FakeLlmClient();
        LocalFileSearchService searchService = new LocalFileSearchService(wikiDir);
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new WikiSearchTool(searchService));
        orchestrator = new OrchestratorAgent(fakeLlm, toolRegistry, "test-model");
    }

    @Test
    @DisplayName("应解析纯 JSON tool call")
    void testParseToolCall_PlainJson() {
        OrchestratorAgent.ParsedToolCall parsed = OrchestratorAgent.tryParseToolCall(
                "{\"tool\":\"wiki_search\",\"args\":{\"query\":\"罗德岛\",\"limit\":5}}");
        assertNotNull(parsed);
        assertEquals("wiki_search", parsed.tool());
        assertEquals("罗德岛", parsed.args().get("query"));
        assertEquals("5", parsed.args().get("limit"));
    }

    @Test
    @DisplayName("应解析 code-fenced JSON tool call")
    void testParseToolCall_CodeFenced() {
        OrchestratorAgent.ParsedToolCall parsed = OrchestratorAgent.tryParseToolCall(
                "```json\n{\"tool\":\"wiki_search\",\"args\":{\"query\":\"年\"}}\n```");
        assertNotNull(parsed);
        assertEquals("wiki_search", parsed.tool());
        assertEquals("年", parsed.args().get("query"));
    }

    @Test
    @DisplayName("普通文本不应被解析为 tool call")
    void testParseToolCall_PlainText() {
        assertNull(OrchestratorAgent.tryParseToolCall(
                "罗德岛是一家制药公司，位于移动城市上。"));
        assertNull(OrchestratorAgent.tryParseToolCall(""));
        assertNull(OrchestratorAgent.tryParseToolCall(null));
    }

    @Test
    @DisplayName("无 tool 字段的 JSON 不应被解析为 tool call")
    void testParseToolCall_JsonWithoutTool() {
        assertNull(OrchestratorAgent.tryParseToolCall(
                "{\"message\":\"hello\"}"));
    }

    @Test
    @DisplayName("FakeLlmClient 直接返回文本时应产出最终结果")
    void testRun_PlainTextResponse() {
        fakeLlm.setNextResponse("## 推演结果\n\n罗德岛具备军事价值。");

        List<PlayerAction> actions = List.of(
                new PlayerAction("pa-1", "c1", "t1", "测试玩家", "调查罗德岛", Instant.now(), List.of()));

        OrchestratorAgent.RunResult result = orchestrator.run(actions, "本回合允许 wiki_search", "Turn: t1");

        assertTrue(result.finalText().contains("罗德岛"));
        assertEquals(0, result.toolCalls().size());
    }

    @Test
    @DisplayName("FakeLlmClient 返回 tool call JSON 时应执行工具并继续")
    void testRun_ToolCallThenText() {
        fakeLlm.addResponse(
                "{\"tool\":\"wiki_search\",\"args\":{\"query\":\"罗德岛\"}}");
        fakeLlm.addResponse("## 推演结果\n\n罗德岛（来源: rhodes-island.txt）具备显著军事价值。");

        List<PlayerAction> actions = List.of(
                new PlayerAction("pa-1", "c1", "t1", "测试玩家", "调查罗德岛", Instant.now(), List.of()));

        OrchestratorAgent.RunResult result = orchestrator.run(actions, "", "Turn: t1");

        assertTrue(result.finalText().contains("罗德岛"));
        assertTrue(result.finalText().contains("rhodes-island.txt"));
        assertEquals(1, result.toolCalls().size());
        assertEquals("wiki_search", result.toolCalls().get(0).tool());
        assertEquals("罗德岛", result.toolCalls().get(0).args().get("query"));
    }

    @Test
    @DisplayName("无玩家行动时应给出合理输出")
    void testRun_NoActions() {
        fakeLlm.setNextResponse("本回合无玩家行动，无需推演。");

        OrchestratorAgent.RunResult result = orchestrator.run(
                List.of(), "", "Turn: t0");

        assertNotNull(result.finalText());
        assertEquals(0, result.toolCalls().size());
    }
}
