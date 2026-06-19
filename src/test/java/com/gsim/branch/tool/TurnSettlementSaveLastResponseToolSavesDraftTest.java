package com.gsim.branch.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 turn_settlement_save_last_response 使用缓存草稿保存回合结算。
 */
@DisplayName("TurnSettlementSaveLastResponse 保存草稿")
class TurnSettlementSaveLastResponseToolSavesDraftTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private AtomicReference<String> draftRef;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        draftRef = new AtomicReference<>("");
    }

    @Test
    @DisplayName("有草稿时成功保存结算到 branch 文件")
    void savesDraftToBranchFile() throws Exception {
        String settlementBody = "## 第一回合结算\n\n" +
                "### 回合概述\n本回合玩家抵达龙门，与当地势力初步接触。\n\n" +
                "### 关键事件\n1. 玩家A在龙门商业区调查情报\n2. 遇到罗德岛干员\n\n" +
                "### 状态变化\n- 势力关系：龙门近卫局好感+5\n- 资源：获得龙门通行证\n\n" +
                "### 下回合风险\n- 整合运动可能袭击龙门\n- 罗德岛的注意";
        draftRef.set(settlementBody);

        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(dm, draftRef::get);

        ToolCall call = new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of("inputSummary", "玩家抵达龙门"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success(), "Tool should succeed: " + result.error());
        assertEquals(1, result.items().size());

        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("status=OK"), "Should contain status=OK: " + snippet);
        assertTrue(snippet.contains("settlementId=stl"), "Should contain settlementId: " + snippet);
        assertTrue(snippet.contains("savedFrom=lastAssistantDraft"), "Should indicate savedFrom: " + snippet);

        // 验证 branch 文件包含保存的结算内容
        String activeBranch = dm.getActiveBranchId();
        String branchContent = dm.readBranchFile(activeBranch);
        assertNotNull(branchContent);
        assertTrue(branchContent.contains("TURN_SETTLEMENT:stl"),
                "Branch file should contain TURN_SETTLEMENT marker");
    }

    @Test
    @DisplayName("保存后 settlementId 递增")
    void settlementIdIncrements() {
        String draft = "第一回合结算正文。";
        draftRef.set(draft);

        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(dm, draftRef::get);

        // 第一次保存
        ToolResult r1 = tool.execute(new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of("inputSummary", "测试")));
        assertTrue(r1.success());
        String sid1 = extractField(r1.items().get(0).snippet(), "settlementId");

        // 第二次保存（同一草稿可重复保存）
        draftRef.set("修订版结算。");
        ToolResult r2 = tool.execute(new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of("inputSummary", "修订")));
        assertTrue(r2.success());
        String sid2 = extractField(r2.items().get(0).snippet(), "settlementId");

        assertNotEquals(sid1, sid2, "settlementId should increment");
    }

    @Test
    @DisplayName("指定 title 时结算正文前添加标题行")
    void titlePrependsToDraft() throws Exception {
        String draft = "结算正文内容...";
        draftRef.set(draft);

        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(dm, draftRef::get);

        ToolCall call = new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of("title", "第一回合·龙门之夜结算", "inputSummary", "抵达龙门"));
        ToolResult result = tool.execute(call);

        assertTrue(result.success());

        // 验证 branch 文件中的结算包含标题
        String branchContent = dm.readBranchFile(dm.getActiveBranchId());
        assertNotNull(branchContent);
        assertTrue(branchContent.contains("第一回合·龙门之夜结算"),
                "Branch file should contain the title");
    }

    private static String extractField(String snippet, String field) {
        for (String line : snippet.split("\n")) {
            if (line.startsWith(field + "=")) {
                return line.substring(field.length() + 1);
            }
        }
        return null;
    }
}
