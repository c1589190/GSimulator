package com.gsim.agent.core;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agent.AgentConfig;
import com.gsim.agent.core.AbstractAgent.ToolResultPolicy;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import com.gsim.docslib.doc.Document;
import com.gsim.core.event.AgentProgressSink;
import com.gsim.core.llm.LlmManager;
import com.gsim.core.llm.ProviderConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TDD（Todo 9）：验证 {@link AbstractAgent#buildToolFeedback} 的可配内联上限与 TMP 暂存溢出行为。
 *
 * <p>策略（{@link ToolResultPolicy}）：snippet ≤ inlineMaxChars 原样内联；超限且暂存可用 → 暂存为
 * {@code docs/tmp/{docId}.md} 并以暂存提示替换；暂存不可用/被禁用/失败 → 截断到 inlineMaxChars + "...".
 * null policy = 遗留行为（截断到 500，不暂存）。
 */
@DisplayName("ToolResultPolicy: buildToolFeedback 内联上限与 TMP 暂存")
class ToolResultPolicyTest {

    @TempDir
    Path tempDir;

    private static final String PREFIX = "wstg_agent_";

    private ToolRegistry tools;
    private LlmManager llm;

    @BeforeEach
    void setUp() {
        tools = new ToolRegistry();
        llm = new LlmManager(ProviderConfig.generic("test", "http://localhost", "key", "test-model", 0.3, 30));
    }

    /** 暴露 protected buildToolFeedback 的最小测试子类。 */
    private static class FeedbackAgent extends AbstractAgent {
        FeedbackAgent(LlmManager llm, ToolRegistry tools, ToolResultPolicy policy) {
            super(AgentConfig.defaultOrchestrator(), llm, tools, AgentProgressSink.NOOP, "test-model", policy);
        }

        String feedback(String toolName, ToolResult result) {
            return buildToolFeedback(toolName, result);
        }
    }

    /** 注入失败 docStore：create 必抛 IOException。 */
    private static class FailingDocStore extends DocStore {
        FailingDocStore(Path dir) {
            super(dir);
        }

        @Override
        public Document create(String id, DocType type, String title, String content, List<String> tags)
                throws IOException {
            throw new IOException("staging failure injected");
        }
    }

    private static ToolResult okResult(String title, String snippet) {
        return ToolResult.ok("test_tool", List.of(new ToolResult.Item(title, null, snippet, 0)));
    }

    @Test
    @DisplayName("snippet ≤ cap → 原样内联，不暂存")
    void snippetWithinCapIsInlinedUntouched() {
        ToolResultPolicy policy = new ToolResultPolicy(50, true, null, PREFIX);
        FeedbackAgent agent = new FeedbackAgent(llm, tools, policy);

        String feedback = agent.feedback("test_tool", okResult("T", "short snippet"));

        assertTrue(feedback.contains("short snippet"));
        assertFalse(feedback.contains("内容已暂存为文档"));
        assertFalse(feedback.contains("..."));
    }

    @Test
    @DisplayName("snippet > cap + 暂存可用 → 反馈含暂存提示与 docId，TMP 文档落盘")
    void oversizedSnippetStagedToTmpDoc() throws Exception {
        DocStore docStore = new DocStore(tempDir.resolve("docs"));
        docStore.init();
        ToolResultPolicy policy = new ToolResultPolicy(50, true, docStore, PREFIX);
        FeedbackAgent agent = new FeedbackAgent(llm, tools, policy);
        String snippet = "y".repeat(5000);

        String feedback = agent.feedback("test_tool", okResult("BigDoc", snippet));

        assertTrue(feedback.contains("内容已暂存为文档"), "反馈应包含暂存提示");
        assertFalse(feedback.contains(snippet), "原文不应内联在反馈中");
        List<Document> tmpDocs = docStore.list(DocType.TMP, null);
        assertEquals(1, tmpDocs.size(), "应恰好暂存 1 个 TMP 文档");
        Document staged = tmpDocs.get(0);
        assertEquals(snippet, staged.content(), "TMP 文档内容应等于原文");
        assertTrue(staged.id().startsWith(PREFIX), "docId 应带前缀 " + PREFIX);
        assertTrue(feedback.contains("docId=" + staged.id()), "反馈应包含 docId");
        Path stagedFile = tempDir.resolve("docs").resolve("tmp").resolve(staged.id() + ".md");
        assertTrue(Files.exists(stagedFile), "TMP 文档应落盘于 docs/tmp/");
    }

    @Test
    @DisplayName("snippet > cap + docStore=null → 截断到 cap + \"...\"")
    void oversizedSnippetWithNullDocStoreIsTruncated() {
        ToolResultPolicy policy = new ToolResultPolicy(50, true, null, PREFIX);
        FeedbackAgent agent = new FeedbackAgent(llm, tools, policy);
        String snippet = "z".repeat(5000);

        String feedback = agent.feedback("test_tool", okResult("T", snippet));

        assertTrue(feedback.contains("z".repeat(50) + "..."), "应截断到 cap + ...");
        assertFalse(feedback.contains(snippet), "完整原文不应出现");
        assertFalse(feedback.contains("内容已暂存为文档"));
    }

    @Test
    @DisplayName("snippet > cap + stagingEnabled=false → 截断，不暂存")
    void oversizedSnippetWithStagingDisabledIsTruncated() throws Exception {
        DocStore docStore = new DocStore(tempDir.resolve("docs"));
        docStore.init();
        ToolResultPolicy policy = new ToolResultPolicy(50, false, docStore, PREFIX);
        FeedbackAgent agent = new FeedbackAgent(llm, tools, policy);
        String snippet = "w".repeat(5000);

        String feedback = agent.feedback("test_tool", okResult("T", snippet));

        assertTrue(feedback.contains("w".repeat(50) + "..."), "应截断到 cap + ...");
        assertFalse(feedback.contains("内容已暂存为文档"));
        assertTrue(docStore.list(DocType.TMP, null).isEmpty(), "禁用暂存时不应产生 TMP 文档");
    }

    @Test
    @DisplayName("暂存抛 IOException → 回退截断，异常不传播")
    void stagingFailureFallsBackToTruncation() {
        DocStore failingStore = new FailingDocStore(tempDir.resolve("unused"));
        ToolResultPolicy policy = new ToolResultPolicy(50, true, failingStore, PREFIX);
        FeedbackAgent agent = new FeedbackAgent(llm, tools, policy);
        String snippet = "v".repeat(5000);

        String feedback = agent.feedback("test_tool", okResult("T", snippet));

        assertTrue(feedback.contains("v".repeat(50) + "..."), "暂存失败应回退截断");
        assertFalse(feedback.contains("内容已暂存为文档"));
    }
}
