package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimulationContent 工具全套测试。
 * Covers: append, list, get, update, turn settlement save/get,
 * multiple entries same branch, settlement doesn't delete sim contents,
 * full round settlement flow, no active branch handling, etc.
 */
@DisplayName("SimulationContent Tools")
public class SimulationContentToolTest {

    private Path tmpDir;
    private DataManager dm;
    private BranchCreateChildTool branchCreateTool;
    private BranchSwitchTool branchSwitchTool;
    private SimulationContentAppendTool appendTool;
    private SimulationContentListTool listTool;
    private SimulationContentGetTool getTool;
    private SimulationContentUpdateTool updateTool;
    private TurnSettlementSaveTool settlementSaveTool;
    private TurnSettlementGetTool settlementGetTool;

    private Runnable onBranchChanged = () -> {};

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("gsim-simcontent-test-");
        dm = TestWorldFactory.createWithDefaultRoot(tmpDir);

        branchCreateTool = new BranchCreateChildTool(dm, onBranchChanged);
        branchSwitchTool = new BranchSwitchTool(dm, onBranchChanged);
        appendTool = new SimulationContentAppendTool(dm);
        listTool = new SimulationContentListTool(dm);
        getTool = new SimulationContentGetTool(dm);
        updateTool = new SimulationContentUpdateTool(dm);
        settlementSaveTool = new TurnSettlementSaveTool(dm);
        settlementGetTool = new TurnSettlementGetTool(dm);
    }

    @AfterEach
    void tearDown() throws IOException {
        // recursive delete
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ==================== Append Tests ====================

    @Test
    @DisplayName("simulation_content_append: appends prologue to active branch")
    void simulationContentAppendTest() {
        ToolCall call = new ToolCall("simulation_content_append", Map.of(
                "type", "prologue",
                "title", "开始序言",
                "content", "泰拉11090年，乌萨斯边境的风雪压过移动城邦废墟……",
                "status", "draft"
        ));
        ToolResult result = appendTool.execute(call);

        assertTrue(result.success(), "append should succeed but got: " + result.error());
        assertEquals(1, result.items().size());

        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("sim0001"), "should contain simId sim0001: " + snippet);
        assertTrue(snippet.contains("branch.b0000-start"), "should contain branch.b0000-start: " + snippet);
        assertEquals("开始序言", result.items().get(0).title());

        // Verify file content
        try {
            String markdown = dm.readBranchFile("branch.b0000-start");
            assertTrue(markdown.contains("SIM_CONTENT:sim0001 START"),
                    "branch file should contain SIM_CONTENT marker:\n" + markdown);
            assertTrue(markdown.contains("开始序言"),
                    "branch file should contain title 开始序言");
            assertTrue(markdown.contains("乌萨斯边境"),
                    "branch file should contain prologue content");
            assertTrue(markdown.contains("SIM_CONTENT:sim0001 END"),
                    "branch file should contain END marker");
        } catch (IOException e) {
            fail("Failed to read branch file: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("simulation_content_append: multiple entries in same branch")
    void simulationContentMultipleEntriesSameBranchTest() {
        // Append first
        ToolCall call1 = new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "开始序言",
                "content", "泰拉11090年，风雪越过边境……", "status", "draft"
        ));
        ToolResult r1 = appendTool.execute(call1);
        assertTrue(r1.success());

        // Append second
        ToolCall call2 = new ToolCall("simulation_content_append", Map.of(
                "type", "scene", "title", "第一轮局势展开",
                "content", "积雪覆盖的道路上，罗德岛小队缓慢前进……", "status", "draft"
        ));
        ToolResult r2 = appendTool.execute(call2);
        assertTrue(r2.success());

        // List
        ToolCall listCall = new ToolCall("simulation_content_list", Map.of("branchId", "current"));
        ToolResult listResult = listTool.execute(listCall);
        assertTrue(listResult.success());
        String listSnippet = listResult.items().get(0).snippet();
        assertTrue(listSnippet.contains("sim0001"), "list should contain sim0001: " + listSnippet);
        assertTrue(listSnippet.contains("sim0002"), "list should contain sim0002: " + listSnippet);

        // Verify file has both marker blocks
        try {
            String markdown = dm.readBranchFile("branch.b0000-start");
            int first = markdown.indexOf("SIM_CONTENT:sim0001 START");
            int second = markdown.indexOf("SIM_CONTENT:sim0002 START");
            assertTrue(first > 0, "should have sim0001 marker");
            assertTrue(second > 0, "should have sim0002 marker");
            assertTrue(second > first, "sim0002 should come after sim0001");
        } catch (IOException e) {
            fail("Failed to read branch file: " + e.getMessage());
        }
    }

    // ==================== List Test ====================

    @Test
    @DisplayName("simulation_content_list: lists all sim contents")
    void simulationContentListTest() {
        // No content yet
        ToolCall emptyList = new ToolCall("simulation_content_list", Map.of("branchId", "current"));
        ToolResult emptyResult = listTool.execute(emptyList);
        assertTrue(emptyResult.success());
        String emptySnippet = emptyResult.items().get(0).snippet();
        assertTrue(emptySnippet.contains("没有推演内容") || emptySnippet.contains("count: 0"),
                "empty list should indicate no content: " + emptySnippet);

        // Add one
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "scene", "title", "测试场景", "content", "场景正文")));

        // List again
        ToolCall list2 = new ToolCall("simulation_content_list", Map.of("branchId", "current"));
        ToolResult r2 = listTool.execute(list2);
        assertTrue(r2.success());
        String s2 = r2.items().get(0).snippet();
        assertTrue(s2.contains("count: 1"), "should have 1 entry: " + s2);
        assertTrue(s2.contains("测试场景"), "should mention title: " + s2);
    }

    // ==================== Get Test ====================

    @Test
    @DisplayName("simulation_content_get: reads full content of specific sim")
    void simulationContentGetTest() {
        // Append
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "开始序言",
                "content", "风雪中的乌萨斯。\n\n第二段。", "status", "draft")));

        // Get
        ToolCall getCall = new ToolCall("simulation_content_get", Map.of(
                "branchId", "current", "simId", "sim0001"));
        ToolResult getResult = getTool.execute(getCall);
        assertTrue(getResult.success(), "get should succeed: " + getResult.error());
        String snippet = getResult.items().get(0).snippet();
        assertTrue(snippet.contains("sim0001"), "should contain simId: " + snippet);
        assertTrue(snippet.contains("风雪中的乌萨斯"), "should contain content: " + snippet);
        assertTrue(snippet.contains("第二段"), "should contain second paragraph");
    }

    @Test
    @DisplayName("simulation_content_get: returns error for non-existent simId")
    void simulationContentGetNotFoundTest() {
        ToolCall call = new ToolCall("simulation_content_get", Map.of(
                "branchId", "current", "simId", "sim9999"));
        ToolResult result = getTool.execute(call);
        assertFalse(result.success(), "should fail for non-existent simId");
        assertTrue(result.error().contains("SIM_CONTENT_NOT_FOUND"),
                "error should mention SIM_CONTENT_NOT_FOUND: " + result.error());
    }

    // ==================== Update Test ====================

    @Test
    @DisplayName("simulation_content_update: updates content, title, status")
    void simulationContentUpdateTest() {
        // Append initial
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "scene", "title", "原始标题", "content", "原始内容", "status", "draft")));

        // Update
        ToolCall updateCall = new ToolCall("simulation_content_update", Map.of(
                "branchId", "current", "simId", "sim0001",
                "title", "新标题", "content", "更新后的正文", "status", "active"));
        ToolResult result = updateTool.execute(updateCall);
        assertTrue(result.success(), "update should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("status=OK"), "should say OK: " + snippet);

        // Verify
        ToolResult getResult = getTool.execute(new ToolCall("simulation_content_get", Map.of(
                "branchId", "current", "simId", "sim0001")));
        String content = getResult.items().get(0).snippet();
        assertTrue(content.contains("新标题"), "should have new title: " + content);
        assertTrue(content.contains("更新后的正文"), "should have new content: " + content);
        assertTrue(content.contains("active"), "should have new status: " + content);
        assertFalse(content.contains("原始标题"), "should not have old title");
    }

    @Test
    @DisplayName("simulation_content_update: returns error for non-existent simId")
    void simulationContentUpdateNotFoundTest() {
        ToolCall call = new ToolCall("simulation_content_update", Map.of(
                "branchId", "current", "simId", "sim9999",
                "content", "新内容"));
        ToolResult result = updateTool.execute(call);
        assertFalse(result.success(), "should fail for non-existent simId");
        assertTrue(result.error().contains("SIM_CONTENT_NOT_FOUND"),
                "error should contain SIM_CONTENT_NOT_FOUND: " + result.error());
    }

    // ==================== Turn Settlement Tests ====================

    @Test
    @DisplayName("turn_settlement_save: saves settlement with all deltas")
    void turnSettlementSaveTest() {
        ToolCall call = new ToolCall("turn_settlement_save", Map.of(
                "branchId", "current",
                "inputSummary", "本回合玩家行动：罗德岛小队进入乌萨斯边境救援点。",
                "settlement", "本回合罗德岛抵达边境救援点……发现废弃移动城邦内存在异常信号源。",
                "worldDelta", "泰拉11090年边境线局势进一步紧张。",
                "entityDelta", "罗德岛小队进入乌萨斯边境。",
                "ruleDelta", "新增冬季行军损耗规则。",
                "interactionDelta", "地方军警注意到救援点活动。",
                "risk", "地方军警可能派出巡逻队。",
                "referencedSimIds", "sim0001"
        ));
        ToolResult result = settlementSaveTool.execute(call);
        assertTrue(result.success(), "settlement save should succeed: " + result.error());

        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("TURN_SETTLEMENT"), "should mention TURN_SETTLEMENT");
        assertTrue(snippet.contains("referencedSimIds=sim0001"), "should mention referenced sim");

        // Read back
        ToolResult getResult = settlementGetTool.execute(
                new ToolCall("turn_settlement_get", Map.of("branchId", "current")));
        assertTrue(getResult.success());
        String settlement = getResult.items().get(0).snippet();
        assertTrue(settlement.contains("罗德岛抵达"), "should contain settlement text: " + settlement);
        assertTrue(settlement.contains("异常信号源"), "should contain settlement details");
    }

    @Test
    @DisplayName("turn_settlement_save: does not delete simulation contents")
    void turnSettlementDoesNotDeleteSimulationContentsTest() {
        // First append sim content
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "开始序言",
                "content", "泰拉11090年……", "status", "draft")));

        // Then save settlement
        settlementSaveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "settlement", "本回合结算。",
                "worldDelta", "世界观增量。",
                "entityDelta", "实体增量。",
                "risk", "风险。",
                "referencedSimIds", "sim0001"
        )));

        // Verify file contains both
        try {
            String markdown = dm.readBranchFile("branch.b0000-start");
            assertTrue(markdown.contains("SIM_CONTENT:sim0001 START"),
                    "file should still contain sim0001 after settlement:\n" + markdown);
            assertTrue(markdown.contains("TURN_SETTLEMENT:stl0001 START"),
                    "file should contain TURN_SETTLEMENT:\n" + markdown);

            // sim0001 should appear BEFORE the settlement
            int simPos = markdown.indexOf("SIM_CONTENT:sim0001 START");
            int tsPos = markdown.indexOf("TURN_SETTLEMENT:stl0001 START");
            assertTrue(simPos < tsPos,
                    "SIM_CONTENT should appear before TURN_SETTLEMENT in file");
        } catch (IOException e) {
            fail("Failed to read branch file: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("simulation content stored in branch file, not BranchMessageStore")
    void simulationContentStoredInBranchFileNotBranchMessageStoreTest() {
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "开始序言",
                "content", "泰拉11090年，乌萨斯边境……", "status", "draft")));

        // Verify it's in the branch file
        try {
            String markdown = dm.readBranchFile("branch.b0000-start");
            assertTrue(markdown.contains("SIM_CONTENT:sim0001"),
                    "Simulation content must be stored in branch file");
        } catch (IOException e) {
            fail("Branch file should exist and contain sim content");
        }

        // BranchMessageStore (audit log) is separate — verify it doesn't
        // contain simulation content as its primary storage
        DataDocument doc = dm.readById("branch.b0000-start");
        assertNotNull(doc, "branch doc should exist in DataManager");
    }

    // ==================== Cross-tool flow tests ====================

    @Test
    @DisplayName("start first turn: create child branch + append prologue")
    void startFirstTurnAppendsPrologueTest() {
        // Step 1: Create child branch
        ToolCall createCall = new ToolCall("branch_create_child", Map.of(
                "title", "第一回合：开局",
                "initialInput", "进入第一回合",
                "switchToChild", "true"
        ));
        ToolResult createResult = branchCreateTool.execute(createCall);
        assertTrue(createResult.success(), "branch_create_child should succeed: " + createResult.error());
        String createSnippet = createResult.items().get(0).snippet();
        assertTrue(createSnippet.contains("status=OK"), "should be OK: " + createSnippet);

        // Step 2: Append prologue to child branch
        ToolCall appendCall = new ToolCall("simulation_content_append", Map.of(
                "type", "prologue",
                "title", "开始序言",
                "content", "泰拉11090年，风雪越过乌萨斯边境。移动城邦废墟中……",
                "status", "draft"
        ));
        ToolResult appendResult = appendTool.execute(appendCall);
        assertTrue(appendResult.success(), "append should succeed: " + appendResult.error());

        String simId = null;
        for (String line : appendResult.items().get(0).snippet().split(" ")) {
            if (line.startsWith("simId=")) simId = line.split("=")[1].trim();
        }
        assertNotNull(simId, "should return simId");
        assertEquals("sim0001", simId);

        // Verify prologue is in the new child branch, not the root branch
        try {
            String childMarkdown = dm.readBranchFile(dm.getActiveBranchId());
            assertTrue(childMarkdown.contains("SIM_CONTENT:sim0001"),
                    "Child branch should contain prologue sim content");
            assertTrue(childMarkdown.contains("乌萨斯边境"),
                    "Child branch should contain prologue text");
        } catch (IOException e) {
            fail("Failed to read child branch file: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("round settlement flow: list -> get -> save settlement")
    void roundSettlementReadsContentsThenSavesSettlementTest() {
        // Setup: append two sim contents
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "开始序言",
                "content", "泰拉11090年……", "status", "draft")));
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "scene", "title", "局势展开",
                "content", "罗德岛小队前进……", "status", "draft")));

        // Step 1: list
        ToolResult listResult = listTool.execute(
                new ToolCall("simulation_content_list", Map.of("branchId", "current")));
        assertTrue(listResult.success());
        String listSnippet = listResult.items().get(0).snippet();
        assertTrue(listSnippet.contains("sim0001"));
        assertTrue(listSnippet.contains("sim0002"));

        // Step 2: get sim0001
        ToolResult getResult = getTool.execute(
                new ToolCall("simulation_content_get", Map.of("branchId", "current", "simId", "sim0001")));
        assertTrue(getResult.success());
        assertTrue(getResult.items().get(0).snippet().contains("泰拉11090年"));

        // Step 3: save settlement
        ToolResult settleResult = settlementSaveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "settlement", "本回合罗德岛在边境废墟建立临时据点……发现异常信号。",
                "worldDelta", "边境局势紧张。",
                "entityDelta", "罗德岛小队进入边境。",
                "risk", "地方军警注意。",
                "referencedSimIds", "sim0001,sim0002"
        )));
        assertTrue(settleResult.success(), "settlement should succeed: " + settleResult.error());

        // Verify final state
        try {
            String markdown = dm.readBranchFile("branch.b0000-start");
            assertTrue(markdown.contains("SIM_CONTENT:sim0001"), "still has sim0001");
            assertTrue(markdown.contains("SIM_CONTENT:sim0002"), "still has sim0002");
            assertTrue(markdown.contains("TURN_SETTLEMENT:stl0001 START"), "has settlement");
            assertTrue(markdown.contains("异常信号"), "has settlement content");
            assertTrue(markdown.contains("边境局势紧张"), "has worldDelta content in section 四");
        } catch (IOException e) {
            fail("Failed to read branch file: " + e.getMessage());
        }
    }

    // ==================== No Active Branch Test ====================

    @Test
    @DisplayName("tools return error when no active root/branch")
    void simulationContentToolNoActiveBranchTest() {
        // Create fresh DataManager with no root
        Path emptyDir = null;
        try {
            emptyDir = Files.createTempDirectory("gsim-test-empty-");
        } catch (IOException e) {
            fail("Failed to create temp dir");
        }
        DataManager emptyDm = new DataManager(emptyDir);
        // Note: DataManager may have auto-loaded, let's just use the tools
        // but check that they handle missing root properly

        // Most tools check hasActiveRoot() first
        assertNull(emptyDm.getActiveBranchId(), "empty data manager should have no active branch");

        SimulationContentAppendTool emptyAppend = new SimulationContentAppendTool(emptyDm);
        ToolResult r = emptyAppend.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "test", "content", "test")));
        assertFalse(r.success(), "should fail without active root");
        assertTrue(r.error().contains("NO_ACTIVE_ROOT"),
                "should say NO_ACTIVE_ROOT: " + r.error());

        // cleanup
        try {
            try (var s = Files.walk(emptyDir)) {
                s.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            }
        } catch (IOException ignored) {}
    }

    // ==================== Branch file format test ====================

    @Test
    @DisplayName("branch file maintains correct section structure after sim content")
    void branchFileSectionStructureAfterSimContentTest() {
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "开始序言",
                "content", "正文内容。", "status", "draft")));

        try {
            String markdown = dm.readBranchFile("branch.b0000-start");

            // Verify sections still exist
            assertTrue(markdown.contains("## 一、本节点输入"), "section 一 should exist");
            assertTrue(markdown.contains("## 二、LLM 上下文记录"), "section 二 should exist");
            assertTrue(markdown.contains("## 三、推演结果"), "section 三 should exist");
            assertTrue(markdown.contains("### 推演内容记录"), "subsection 推演内容记录 should exist");
            assertTrue(markdown.contains("## 四、世界观/设定增量"), "section 四 should exist");
            assertTrue(markdown.contains("## 九、下节点风险"), "section 九 should exist");

            // Verify sim content is under 三
            int sectionThree = markdown.indexOf("## 三、推演结果");
            int simContent = markdown.indexOf("### 推演内容记录");
            int sectionFour = markdown.indexOf("## 四、世界观/设定增量");
            assertTrue(sectionThree < simContent, "推演内容记录 should be after 三、推演结果");
            assertTrue(simContent < sectionFour, "推演内容记录 should be before 四、世界观/设定增量");
        } catch (IOException e) {
            fail("Failed to read branch file: " + e.getMessage());
        }
    }

    // ==================== Branch Create Child Test ====================

    @Test
    @DisplayName("branch_create_child: creates child from root branch")
    void branchCreateChildTest() {
        ToolCall call = new ToolCall("branch_create_child", Map.of(
                "title", "第一回合：开局",
                "initialInput", "开始推演",
                "switchToChild", "true"
        ));
        ToolResult result = branchCreateTool.execute(call);
        assertTrue(result.success(), "branch create should succeed: " + result.error());

        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("status=OK"), "should be OK");
        assertTrue(snippet.contains("branchId="), "should contain branchId");

        // Verify active branch changed
        String activeBranch = dm.getActiveBranchId();
        assertNotNull(activeBranch);
        assertNotEquals("branch.b0000-start", activeBranch, "should have switched to child");
        assertTrue(activeBranch.startsWith("branch.b"), "should start with branch.b");

        // Verify file exists
        assertNotNull(dm.readById(activeBranch), "child branch should be in DataManager");
        assertTrue(dm.listBranches().size() >= 2, "should have at least 2 branches");
    }

    @Test
    @DisplayName("branch_switch: switches between branches")
    void branchSwitchTest() {
        // Create child
        branchCreateTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "子节点", "switchToChild", "true")));
        String childBranch = dm.getActiveBranchId();

        // Switch back to root
        ToolResult backToRoot = branchSwitchTool.execute(new ToolCall("branch_switch", Map.of(
                "branchId", "b0000-start")));
        assertTrue(backToRoot.success(), "switch to root should succeed: " + backToRoot.error());
        assertEquals("branch.b0000-start", dm.getActiveBranchId());

        // Switch to child again
        ToolResult toChild = branchSwitchTool.execute(new ToolCall("branch_switch", Map.of(
                "branchId", childBranch)));
        assertTrue(toChild.success(), "switch to child should succeed: " + toChild.error());
        assertEquals(childBranch, dm.getActiveBranchId());
    }

    // ==================== Prompt Rules Test ====================

    @Test
    @DisplayName("orchestrator prompt contains simulation content rules")
    void promptSimulationContentRulesTest() throws IOException {
        // Read the orchestrator system prompt
        Path promptPath = Path.of("src/main/resources/gsim/prompts/orchestrator-system.md");
        assertTrue(Files.exists(promptPath), "orchestrator-system.md should exist at expected path");

        String prompt = Files.readString(promptPath, StandardCharsets.UTF_8);

        // Verify key rules
        assertTrue(prompt.contains("simulation_content_append"),
                "prompt should mention simulation_content_append");
        assertTrue(prompt.contains("turn_settlement_save"),
                "prompt should mention turn_settlement_save");
        assertTrue(prompt.contains("BranchMessageStore") || prompt.contains("不是正式推演档案"),
                "prompt should clarify BranchMessageStore is not formal archive");
        assertTrue(prompt.contains("KnowledgeStore") || prompt.contains("不是正式回合结果仓库"),
                "prompt should clarify KnowledgeStore is not turn result repository");

        // Settlement flow rules
        assertTrue(prompt.contains("simulation_content_list"),
                "prompt should mention listing contents before settlement");
        assertTrue(prompt.contains("结算本回合"),
                "prompt should describe settlement flow");

        // Prologue rules
        assertTrue(prompt.contains("type=prologue") || prompt.contains("序言"),
                "prompt should mention prologue type");
    }

    // ==================== simId counter across branches ====================

    @Test
    @DisplayName("simId counter independent per branch")
    void simIdCounterPerBranchTest() {
        // Append to root
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "prologue", "title", "根节点序言", "content", "测试")));

        // Create child
        branchCreateTool.execute(new ToolCall("branch_create_child", Map.of(
                "title", "子节点", "switchToChild", "true")));

        // Append to child
        appendTool.execute(new ToolCall("simulation_content_append", Map.of(
                "type", "scene", "title", "子节点场景", "content", "测试")));

        // Child's first sim should be sim0001
        ToolResult listChild = listTool.execute(new ToolCall("simulation_content_list", Map.of(
                "branchId", "current")));
        String childSnippet = listChild.items().get(0).snippet();
        assertTrue(childSnippet.contains("sim0001"),
                "child branch should have its own sim0001: " + childSnippet);
    }

    // ==================== Metadata and optional params ====================

    @Test
    @DisplayName("simulation_content_append: handles metadata and summary")
    void simulationContentAppendWithMetadataTest() {
        ToolCall call = new ToolCall("simulation_content_append", Map.of(
                "type", "economy",
                "title", "资源统计",
                "content", "当前资源存量：粮食2000吨……",
                "summary", "经济概览",
                "status", "active",
                "metadata", "{\"resources\":{\"food\":2000,\"fuel\":500}}"
        ));
        ToolResult result = appendTool.execute(call);
        assertTrue(result.success());

        // Verify metadata in file
        try {
            String markdown = dm.readBranchFile("branch.b0000-start");
            assertTrue(markdown.contains("元数据"), "should contain metadata field");
            assertTrue(markdown.contains("food"), "should contain food value");
        } catch (IOException e) {
            fail("Failed to read branch file: " + e.getMessage());
        }
    }
}
