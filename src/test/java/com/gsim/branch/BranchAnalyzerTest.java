package com.gsim.branch;

import com.gsim.chat.BranchMessage;
import com.gsim.chat.BranchMessageStore;
import com.gsim.data.DataManager;
import com.gsim.player.PlayerProfile;
import com.gsim.player.PlayerProfileManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchAnalyzer")
class BranchAnalyzerTest {

    @TempDir
    Path tempDir;

    private DataManager dm;
    private BranchMessageStore messageStore;
    private PlayerProfileManager profileManager;
    private BranchAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        profileManager = new PlayerProfileManager(dm);
        analyzer = new BranchAnalyzer(dm, messageStore, profileManager);
    }

    // ---- Node Age Status Tests ----

    @Test
    @DisplayName("根分支有初始化输入 → NEW_WITH_INPUT")
    void testNewEmptyNode() {
        // 根分支 initWorld 时会写入 "世界初始化。" 作为输入
        BranchAnalysis a = analyzer.analyze();
        assertEquals(NodeAgeStatus.NEW_WITH_INPUT, a.nodeAgeStatus());
        assertFalse(a.oldNode(), "只有初始化输入不应为老节点");
        assertEquals(0, a.childBranchCount());
        assertEquals(0, a.messageCount());
        assertEquals("default", a.activeWorld());
        assertTrue(a.activeBranchId().contains("b0000-start"));
    }

    @Test
    @DisplayName("有输入但无推演 → NEW_WITH_INPUT, oldNode=false")
    void testNewNodeWithInput() throws Exception {
        dm.appendInput("玩家A：探索边境");
        dm.createBranch("branch.b0001-test", "测试节点", "T1");

        // 重新加载 to pick up new branch
        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        profileManager = new PlayerProfileManager(dm);
        analyzer = new BranchAnalyzer(dm, messageStore, profileManager);

        BranchAnalysis a = analyzer.analyze();
        assertEquals(NodeAgeStatus.NEW_WITH_INPUT, a.nodeAgeStatus());
        assertFalse(a.oldNode(), "仅有输入不应为老节点");
        assertTrue(a.inputLineCount() > 0);
        assertTrue(a.hasInput());
    }

    @Test
    @DisplayName("有 sim_response 消息 → SIMULATED_NODE, oldNode=true")
    void testOldNodeAfterSimResponse() throws Exception {
        dm.createBranch("branch.b0001-sim", "已推演节点", "T1");
        String bid = dm.getActiveBranch();

        // 写入 sim_response 消息块
        messageStore.appendMessage(bid,
                BranchMessage.create("m0001", "user", "sim_user", "测试输入"));
        messageStore.appendMessage(bid,
                BranchMessage.create("m0002", "assistant", "sim_response", "推演结果内容..."));

        // 重新加载以读取新写入的 message blocks
        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        profileManager = new PlayerProfileManager(dm);
        analyzer = new BranchAnalyzer(dm, messageStore, profileManager);

        BranchAnalysis a = analyzer.analyze(bid);
        assertEquals(NodeAgeStatus.SIMULATED_NODE, a.nodeAgeStatus());
        assertTrue(a.oldNode());
        assertEquals(1, a.simUserCount());
        assertEquals(1, a.simResponseCount());
        assertEquals(2, a.messageCount());
    }

    @Test
    @DisplayName("有子分支 → BRANCHED_OLD_NODE, oldNode=true")
    void testOldNodeWithChildren() throws Exception {
        // 创建父节点
        dm.createBranch("branch.b0001-parent", "父节点", "T1");
        String parentId = dm.getActiveBranch();

        // 创建子节点
        dm.createBranch("branch.b0002-child", "子节点", "T2");

        // 切回父节点分析
        dm.switchBranch(parentId);

        BranchAnalysis a = analyzer.analyze();
        assertEquals(NodeAgeStatus.BRANCHED_OLD_NODE, a.nodeAgeStatus());
        assertTrue(a.oldNode());
        assertEquals(1, a.childBranchCount());
        assertFalse(a.children().isEmpty());
        assertEquals("branch.b0002-child", a.children().get(0).branchId());
    }

    @Test
    @DisplayName("多轮 chat → DISCUSSION_NODE")
    void testDiscussionNode() throws Exception {
        dm.createBranch("branch.b0001-chat", "讨论节点", "T1");
        String bid = dm.getActiveBranch();

        // 写入多轮 chat 消息
        messageStore.appendMessage(bid,
                BranchMessage.create("m0001", "user", "chat_user", "这是老节点吗？"));
        messageStore.appendMessage(bid,
                BranchMessage.create("m0002", "assistant", "chat_response", "不是。"));
        messageStore.appendMessage(bid,
                BranchMessage.create("m0003", "user", "chat_user", "有几个玩家？"));
        messageStore.appendMessage(bid,
                BranchMessage.create("m0004", "assistant", "chat_response", "暂无。"));

        // 重新加载
        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        profileManager = new PlayerProfileManager(dm);
        analyzer = new BranchAnalyzer(dm, messageStore, profileManager);

        BranchAnalysis a = analyzer.analyze(bid);
        assertEquals(NodeAgeStatus.DISCUSSION_NODE, a.nodeAgeStatus());
        assertTrue(a.oldNode(), "多轮 chat 应标记为 oldNode"); // ≥2 对
        assertEquals(2, a.chatUserCount());
        assertEquals(2, a.chatResponseCount());
    }

    @Test
    @DisplayName("status=resolved + 有消息 → oldNode=true")
    void testResolvedStatusMarksOld() throws Exception {
        // DataManager.createBranch 创建的 front matter status 默认就是 resolved
        dm.createBranch("branch.b0001-done", "已完成节点", "T1");
        String bid = dm.getActiveBranch();
        // 添加一条消息，使 resolved 生效
        messageStore.appendMessage(bid,
                BranchMessage.create("m0001", "user", "chat_user", "讨论中"));

        dm = com.gsim.TestWorldFactory.createWithDefaultRoot(tempDir);
        messageStore = new BranchMessageStore(dm, tempDir);
        profileManager = new PlayerProfileManager(dm);
        analyzer = new BranchAnalyzer(dm, messageStore, profileManager);

        BranchAnalysis a = analyzer.analyze(bid);
        // 新创建的分支 status=resolved 且有消息
        assertTrue(a.resolved(), "新建分支 status 应为 resolved");
        assertTrue(a.oldNode(), "status=resolved + 有消息应标记为 oldNode");
    }

    // ---- Content Stats Tests ----

    @Test
    @DisplayName("entityCount 应统计 entities.md 中 ## entity. 标题")
    void testEntityCount() {
        WorldContentStats stats = analyzer.analyzeWorldContent();
        // 模板有 5 个实体：entity.player.a, entity.player.b,
        //   entity.graybridge-council, entity.mining-guild, entity.border-guard
        assertEquals(5, stats.entityCount());
        assertEquals(5, stats.entityNames().size());
        assertTrue(stats.entityNames().contains("player.a"));
        assertTrue(stats.entityNames().contains("graybridge-council"));
    }

    @Test
    @DisplayName("playerCount 应在添加真实玩家后正确计数")
    void testPlayerCount() {
        // 初始只有模板示例玩家
        WorldContentStats stats = analyzer.analyzeWorldContent();
        assertEquals(0, stats.playerCount(), "应排除模板示例玩家");
        assertTrue(stats.onlyTemplatePlayers());

        // 添加真实玩家
        profileManager.addPlayer(PlayerProfile.createTemplate("罗文·艾尔德"));
        stats = analyzer.analyzeWorldContent();
        assertEquals(1, stats.playerCount());
        assertFalse(stats.onlyTemplatePlayers());
        assertTrue(stats.playerNames().contains("罗文·艾尔德"));
    }

    @Test
    @DisplayName("ruleSectionCount / worldSectionCount 应统计 ## 标题")
    void testSectionCounts() {
        WorldContentStats stats = analyzer.analyzeWorldContent();
        // world-template.md 有 1 个 ## 标题: "当前主要矛盾"
        assertEquals(1, stats.worldSectionCount());
        // rules-template.md 无 ## 标题（只有 # 和 bullets）
        assertEquals(0, stats.ruleSectionCount());
    }

    // ---- Output Format Tests ----

    @Test
    @DisplayName("compact markdown 应包含关键字段")
    void testCompactMarkdown() {
        BranchAnalysis a = analyzer.analyze(null, "compact");
        String md = BranchAnalyzer.renderCompactMarkdown(a);

        assertTrue(md.contains("当前节点态势摘要"));
        assertTrue(md.contains("branch.b0000-start"));
        assertTrue(md.contains("实体:"));
        assertTrue(md.contains("玩家:"));
        assertTrue(md.contains("可前进分支"));
        assertTrue(md.contains("建议行动"));
    }

    @Test
    @DisplayName("full markdown 应比 compact 更详细")
    void testFullMarkdown() {
        BranchAnalysis a = analyzer.analyze(null, "full");
        String full = BranchAnalyzer.renderFullMarkdown(a);
        String compact = BranchAnalyzer.renderCompactMarkdown(a);

        assertTrue(full.length() > compact.length());
        assertTrue(full.contains("基本信息"));
        assertTrue(full.contains("节点状态"));
        assertTrue(full.contains("消息统计"));
        assertTrue(full.contains("分支结构"));
    }

    // ---- BranchAnalysisTool Tests ----

    @Test
    @DisplayName("BranchAnalysisTool 无参数应成功返回")
    void testBranchAnalysisTool() {
        BranchAnalysisTool tool = new BranchAnalysisTool(analyzer);

        com.gsim.tool.ToolCall call = new com.gsim.tool.ToolCall(
                "branch_analysis", java.util.Map.of());
        com.gsim.tool.ToolResult result = tool.execute(call);

        assertTrue(result.success());
        assertFalse(result.items().isEmpty());
        assertTrue(result.items().get(0).snippet().contains("当前节点态势摘要"));
    }

    @Test
    @DisplayName("BranchAnalysisTool full 模式应返回更多内容")
    void testBranchAnalysisToolFull() {
        BranchAnalysisTool tool = new BranchAnalysisTool(analyzer);

        com.gsim.tool.ToolCall compactCall = new com.gsim.tool.ToolCall(
                "branch_analysis", java.util.Map.of("detailLevel", "compact"));
        com.gsim.tool.ToolResult compactResult = tool.execute(compactCall);

        com.gsim.tool.ToolCall fullCall = new com.gsim.tool.ToolCall(
                "branch_analysis", java.util.Map.of("detailLevel", "full"));
        com.gsim.tool.ToolResult fullResult = tool.execute(fullCall);

        assertTrue(compactResult.success());
        assertTrue(fullResult.success());
        assertTrue(fullResult.items().get(0).snippet().length()
                > compactResult.items().get(0).snippet().length());
    }

    // ---- isOldNode / hasSimulationResult Tests ----

    @Test
    @DisplayName("hasSimulationResult 对无推演的分支应返回 false")
    void testHasSimulationResultFalse() {
        assertFalse(analyzer.hasSimulationResult(dm.getActiveBranch()));
    }

    @Test
    @DisplayName("isOldNode 对新空节点应返回 false")
    void testIsOldNodeFalse() {
        assertFalse(analyzer.isOldNode(dm.getActiveBranch()));
    }

    // ---- Child branches list test ----

    @Test
    @DisplayName("listChildren 应返回直接子分支")
    void testListChildren() throws Exception {
        dm.createBranch("branch.b0001-parent", "父", "T1");
        dm.createBranch("branch.b0002-child", "子", "T2");
        dm.switchBranch("branch.b0001-parent");

        List<BranchChildSummary> children = analyzer.listChildren(null);
        assertEquals(1, children.size());
        assertEquals("branch.b0002-child", children.get(0).branchId());
    }

    // ---- nextActionHint Tests ----

    @Test
    @DisplayName("nextActionHint 应对空节点提示创建玩家")
    void testNextActionHintForEmptyNode() {
        BranchAnalysis a = analyzer.analyze(null, "compact");
        // 新节点 + playerCount=0 → 应建议创建玩家档案
        assertTrue(a.nextActionHint().contains("玩家") || a.nextActionHint().contains("/players"),
                "空节点且无玩家时应提示创建玩家档案");
    }
}
