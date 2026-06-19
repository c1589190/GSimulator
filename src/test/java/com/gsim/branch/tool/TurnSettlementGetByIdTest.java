package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * turn_settlement_get settlementId 参数测试。
 * 覆盖：按 settlementId 读取历史版本全文、SETTLEMENT_NOT_FOUND、
 * 旧行为（不传 settlementId）仍可用。
 */
@DisplayName("turn_settlement_get by settlementId")
public class TurnSettlementGetByIdTest {

    private Path tmpDir;
    private DataManager dm;
    private TurnSettlementSaveTool saveTool;
    private TurnSettlementGetTool getTool;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("gsim-test-settlement-get-by-id-");
        dm = TestWorldFactory.createWithDefaultRoot(tmpDir);
        saveTool = new TurnSettlementSaveTool(dm);
        getTool = new TurnSettlementGetTool(dm);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ==================== 按 settlementId 读取 ====================

    @Test
    @DisplayName("turn_settlement_get: reads specific settlement by settlementId")
    void readsSpecificSettlementById() {
        // 保存第一个结算
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "inputSummary", "第一回合：罗德岛抵达边境",
                "settlement", "罗德岛在边境建立临时据点。发现异常信号源位于废弃城邦内部。",
                "worldDelta", "边境局势升温。",
                "entityDelta", "罗德岛小队进入边境区域。",
                "referencedSimIds", "sim0001",
                "referencedActionIds", "act0001"
        )));

        // 保存第二个结算（修订版）
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "inputSummary", "第一回合修订：补充侦察细节",
                "settlement", "罗德岛在边境建立临时据点后，侦察小队发现异常信号源的具体位置。",
                "worldDelta", "边境局势升温，周边势力开始关注。",
                "entityDelta", "罗德岛侦察小队深入废弃城邦。",
                "revisionOf", "stl0001",
                "referencedSimIds", "sim0002"
        )));

        // 按 settlementId 读取第一个
        ToolCall getCall1 = new ToolCall("turn_settlement_get", Map.of(
                "branchId", "current",
                "settlementId", "stl0001"));
        ToolResult r1 = getTool.execute(getCall1);
        assertTrue(r1.success(), "should find stl0001: " + r1.error());
        String s1 = r1.items().get(0).snippet();
        assertTrue(s1.contains("settlementId: stl0001"),
                "should contain settlementId stl0001: " + s1);
        assertTrue(s1.contains("罗德岛在边境建立临时据点"),
                "should contain first settlement text");
        assertTrue(s1.contains("referencedSimIds: sim0001"),
                "should contain referencedSimIds: " + s1);
        assertTrue(s1.contains("referencedActionIds: act0001"),
                "should contain referencedActionIds: " + s1);

        // 按 settlementId 读取第二个（修订版）
        ToolCall getCall2 = new ToolCall("turn_settlement_get", Map.of(
                "branchId", "current",
                "settlementId", "stl0002"));
        ToolResult r2 = getTool.execute(getCall2);
        assertTrue(r2.success(), "should find stl0002: " + r2.error());
        String s2 = r2.items().get(0).snippet();
        assertTrue(s2.contains("settlementId: stl0002"),
                "should contain settlementId stl0002: " + s2);
        assertTrue(s2.contains("revisionOf: stl0001"),
                "should contain revisionOf stl0001: " + s2);
        assertTrue(s2.contains("侦察小队发现异常信号源的具体位置"),
                "should contain second settlement text");
        assertTrue(s2.contains("referencedSimIds: sim0002"),
                "should contain referencedSimIds: " + s2);

        // stl0002 的 inputSummary
        assertTrue(s2.contains("inputSummary:"),
                "should contain inputSummary: " + s2);
    }

    // ==================== SETTLEMENT_NOT_FOUND ====================

    @Test
    @DisplayName("turn_settlement_get: returns SETTLEMENT_NOT_FOUND for non-existent ID")
    void settlementNotFound() {
        ToolCall call = new ToolCall("turn_settlement_get", Map.of(
                "branchId", "current",
                "settlementId", "stl9999"));
        ToolResult result = getTool.execute(call);

        assertFalse(result.success(), "should fail for non-existent settlementId");
        assertTrue(result.error().contains("SETTLEMENT_NOT_FOUND"),
                "should contain SETTLEMENT_NOT_FOUND: " + result.error());
        assertTrue(result.error().contains("stl9999"),
                "should mention the requested ID: " + result.error());
    }

    @Test
    @DisplayName("turn_settlement_get: SETTLEMENT_NOT_FOUND when no settlements exist")
    void settlementNotFoundWhenEmpty() {
        ToolCall call = new ToolCall("turn_settlement_get", Map.of(
                "branchId", "current",
                "settlementId", "stl0001"));
        ToolResult result = getTool.execute(call);

        assertFalse(result.success(), "should fail when no settlements exist");
        assertTrue(result.error().contains("SETTLEMENT_NOT_FOUND"),
                "should contain SETTLEMENT_NOT_FOUND: " + result.error());
    }

    // ==================== 旧行为保留 ====================

    @Test
    @DisplayName("turn_settlement_get: old behavior (no settlementId) lists all + latest")
    void oldBehaviorWithoutSettlementId() {
        // 保存两个结算
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "inputSummary", "第一回合",
                "settlement", "第一回合结算内容：罗德岛出发。",
                "referencedSimIds", "sim0001"
        )));
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "inputSummary", "第二回合",
                "settlement", "第二回合结算内容：抵达边境据点。"
        )));

        // 不传 settlementId，应返回列表 + 最新
        ToolCall call = new ToolCall("turn_settlement_get", Map.of("branchId", "current"));
        ToolResult result = getTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("settlementCount: 2"), "should have 2 settlements: " + snippet);
        assertTrue(snippet.contains("stl0001"), "should list stl0001: " + snippet);
        assertTrue(snippet.contains("stl0002"), "should list stl0002: " + snippet);
        // 最新结算内容
        assertTrue(snippet.contains("抵达边境据点"),
                "should contain latest settlement content: " + snippet);
    }

    @Test
    @DisplayName("turn_settlement_get: old behavior shows revisionOf in list")
    void oldBehaviorShowsRevisionOfInList() {
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "settlement", "初始结算。",
                "referencedSimIds", "sim0001"
        )));
        saveTool.execute(new ToolCall("turn_settlement_save", Map.of(
                "settlement", "修订后结算。",
                "revisionOf", "stl0001"
        )));

        ToolCall call = new ToolCall("turn_settlement_get", Map.of("branchId", "current"));
        ToolResult result = getTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("revisionOf: stl0001"),
                "list should show revisionOf for stl0002: " + snippet);
    }
}
