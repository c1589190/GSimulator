package com.gsim.context;

import com.gsim.branch.BranchAnalyzer;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataManager;
import com.gsim.player.PlayerProfileManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchContextRenderer")
class BranchContextRendererTest {
    @TempDir Path tempDir;
    private DataManager dm;
    private BranchContextRenderer renderer;
    private BranchMessageStore messageStore;
    private BranchAnalyzer branchAnalyzer;

    @BeforeEach void setUp() throws Exception {
        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        PlayerProfileManager pm = new PlayerProfileManager(dm);
        branchAnalyzer = new BranchAnalyzer(dm, messageStore, pm);
        renderer = new BranchContextRenderer(dm, tempDir, messageStore, branchAnalyzer);
    }

    @Test @DisplayName("System.md auto-created")
    void testSystemPromptCreated() {
        assertTrue(renderer.ensureSystemPrompt());
        assertFalse(renderer.getSystemPrompt().isBlank());
    }

    @Test @DisplayName("render produces messages with system + data + input")
    void testRender() {
        RenderedContext ctx = renderer.render();
        assertTrue(ctx.systemPromptExists());
        assertFalse(ctx.messages().isEmpty());
        assertTrue(ctx.messages().stream().anyMatch(m -> "system_prompt".equals(m.type())));
        assertTrue(ctx.messages().stream().anyMatch(m -> "effective_world".equals(m.type())));
        assertTrue(ctx.messages().stream().anyMatch(m -> "current_input".equals(m.type())));
        // 态势摘要应存在
        assertTrue(ctx.messages().stream().anyMatch(m -> "node_situation".equals(m.type())));
    }

    @Test @DisplayName("node situation summary includes key fields")
    void testNodeSituationSummary() {
        RenderedContext ctx = renderer.render();
        var sitMsg = ctx.messages().stream()
                .filter(m -> "node_situation".equals(m.type()))
                .findFirst();
        assertTrue(sitMsg.isPresent());
        String content = sitMsg.get().content();
        assertTrue(content.contains("当前节点态势摘要"), "应包含态势摘要标题");
        assertTrue(content.contains("branch.b0000-start"), "应包含节点ID");
        assertTrue(content.contains("实体:"), "应包含实体统计");
        assertTrue(content.contains("玩家:"), "应包含玩家统计");
    }

    @Test @DisplayName("branch messages extracted from LLM context record")
    void testBranchMessages() throws Exception {
        dm.appendInput("测试输入");
        dm.createBranch("branch.b0001-test", "测试", "T1");
        RenderedContext ctx = renderer.render();
        assertEquals("branch.b0001-test", ctx.activeBranch());
        assertTrue(ctx.chainLength() >= 2);
    }

    @Test @DisplayName("brother branch not rendered after switch")
    void testBrotherNotRendered() throws Exception {
        dm.createBranch("branch.b0001-a", "A", "T1");
        dm.switchBranch("branch.b0000-start");
        dm.createBranch("branch.b0001-b", "B", "T2");
        RenderedContext ctx = renderer.render();
        // 不应该包含 branch.b0001-a (兄弟分支)
        String md = renderer.renderAsMarkdown();
        assertTrue(md.contains("branch.b0001-b"));
        assertFalse(md.contains("branch.b0001-a"));
    }

    @Test @DisplayName("renderAsMarkdown produces valid output")
    void testRenderAsMarkdown() {
        String md = renderer.renderAsMarkdown();
        assertTrue(md.contains("Rendered Context"));
        assertTrue(md.contains("branch.b0000-start"));
    }

    @Test @DisplayName("污染消息渲染时被跳过")
    void testPollutedMessageSkipped() throws Exception {
        // 创建一个包含污染内容的 branch 文件，其 parent 是 b0000-start
        String pollutedBranchContent = """
                id: branch.b0001-polluted
                type: branch
                name: 污染测试
                parent: branch.b0000-start
                turn: 1
                world_time: T1
                status: resolved
                tags: [时间节点]
                updated: 2026-06-18
                -------------------

                # 污染测试

                ## 一、本节点输入
                测试输入。

                ## 二、LLM 上下文记录

                ### assistant

                * test: Run tests with the given coverage strategy. Use when asked to run tests, check coverage, or verify test results.

                ## 三、推演结果
                无。

                ## 四、世界观/设定增量
                无。

                ## 五、实体状态增量
                无。

                ## 六、推演规则增量
                无。

                ## 七、交互逻辑增量
                无。

                ## 八、未总结 Skill 增量
                无。

                ## 九、下节点风险
                待后续推演。
                """;

        java.nio.file.Path bf = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0001-polluted.md");
        java.nio.file.Files.createDirectories(bf.getParent());
        java.nio.file.Files.writeString(bf, pollutedBranchContent, java.nio.charset.StandardCharsets.UTF_8);

        // 切换 active branch 到污染 branch
        java.nio.file.Files.writeString(
                tempDir.resolve("worlds").resolve("default").resolve("active-branch.txt"),
                "branch.b0001-polluted", java.nio.charset.StandardCharsets.UTF_8);

        // 重新加载
        dm = new com.gsim.data.DataManager(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        PlayerProfileManager pm2 = new PlayerProfileManager(dm);
        branchAnalyzer = new BranchAnalyzer(dm, messageStore, pm2);
        renderer = new BranchContextRenderer(dm, tempDir, messageStore, branchAnalyzer);

        RenderedContext ctx = renderer.render();
        // 应该包含 [skipped] 标记
        boolean hasSkipped = ctx.messages().stream()
                .anyMatch(m -> m.content().contains("[skipped polluted tool definition message"));
        assertTrue(hasSkipped, "污染消息应该被跳过并标记");

        // 不应该包含原始污染文本
        boolean hasPollution = ctx.messages().stream()
                .anyMatch(m -> m.content().contains("Run tests with the given coverage strategy"));
        assertFalse(hasPollution, "渲染结果不应包含原始污染文本");
    }

    @Test @DisplayName("System.md 在渲染结果中只出现一次")
    void testSystemPromptAppearsOnce() {
        RenderedContext ctx = renderer.render();
        long systemCount = ctx.messages().stream()
                .filter(m -> "system_prompt".equals(m.type()))
                .count();
        assertEquals(1, systemCount, "System.md 应该只出现一次");
    }

    @Test @DisplayName("renderAsMarkdown 不包含污染文本")
    void testRenderAsMarkdown_NoPollution() throws Exception {
        // 创建包含污染的 branch 文件
        String pollutedBranchContent = """
                id: branch.b0001-clean-test
                type: branch
                name: 清洁测试
                parent: branch.b0000-start
                turn: 1
                world_time: T1
                status: resolved
                tags: [时间节点]
                updated: 2026-06-18
                -------------------

                # 清洁测试

                ## 一、本节点输入
                测试输入。

                ## 二、LLM 上下文记录

                ### assistant

                * test: Run tests with the given coverage strategy. Use when asked to run tests, check coverage, or verify test results.

                ## 三、推演结果
                无。

                ## 四、世界观/设定增量
                无。

                ## 五、实体状态增量
                无。

                ## 六、推演规则增量
                无。

                ## 七、交互逻辑增量
                无。

                ## 八、未总结 Skill 增量
                无。

                ## 九、下节点风险
                待后续推演。
                """;

        java.nio.file.Path bf = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0001-clean-test.md");
        java.nio.file.Files.createDirectories(bf.getParent());
        java.nio.file.Files.writeString(bf, pollutedBranchContent, java.nio.charset.StandardCharsets.UTF_8);

        // 切换 active branch
        java.nio.file.Files.writeString(
                tempDir.resolve("worlds").resolve("default").resolve("active-branch.txt"),
                "branch.b0001-clean-test", java.nio.charset.StandardCharsets.UTF_8);

        dm = new com.gsim.data.DataManager(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        PlayerProfileManager pm3 = new PlayerProfileManager(dm);
        branchAnalyzer = new BranchAnalyzer(dm, messageStore, pm3);
        renderer = new BranchContextRenderer(dm, tempDir, messageStore, branchAnalyzer);

        String md = renderer.renderAsMarkdown();
        assertFalse(md.contains("Run tests with the given coverage strategy"),
                "renderAsMarkdown 不应包含污染文本");
        assertTrue(md.contains("[skipped"), "应该包含跳过标记");
    }
}
