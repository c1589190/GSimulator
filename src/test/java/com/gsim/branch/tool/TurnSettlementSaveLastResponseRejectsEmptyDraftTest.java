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
 * 验证 turn_settlement_save_last_response 在草稿为空时返回 NO_LAST_ASSISTANT_DRAFT。
 */
@DisplayName("TurnSettlementSaveLastResponse 拒绝空草稿")
class TurnSettlementSaveLastResponseRejectsEmptyDraftTest {

    @TempDir Path tempDir;
    private DataManager dm;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
    }

    @Test
    @DisplayName("空字符串草稿返回 NO_LAST_ASSISTANT_DRAFT")
    void emptyDraftReturnsError() {
        AtomicReference<String> emptyRef = new AtomicReference<>("");
        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(dm, emptyRef::get);

        ToolCall call = new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of("inputSummary", "test"));
        ToolResult result = tool.execute(call);

        assertFalse(result.success(), "Should fail with empty draft");
        assertTrue(result.error().contains("NO_LAST_ASSISTANT_DRAFT"),
                "Should return NO_LAST_ASSISTANT_DRAFT: " + result.error());
    }

    @Test
    @DisplayName("null 草稿返回 NO_LAST_ASSISTANT_DRAFT")
    void nullDraftReturnsError() {
        AtomicReference<String> nullRef = new AtomicReference<>(null);
        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(dm, nullRef::get);

        ToolCall call = new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of());
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("NO_LAST_ASSISTANT_DRAFT"));
    }

    @Test
    @DisplayName("空白草稿返回 NO_LAST_ASSISTANT_DRAFT")
    void blankDraftReturnsError() {
        AtomicReference<String> blankRef = new AtomicReference<>("   ");
        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(dm, blankRef::get);

        ToolCall call = new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of());
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("NO_LAST_ASSISTANT_DRAFT"));
    }

    @Test
    @DisplayName("没有 active root 时返回 NO_ACTIVE_ROOT")
    void noActiveRootReturnsError() {
        DataManager emptyDm = new DataManager(tempDir.resolve("empty"));
        AtomicReference<String> draftRef = new AtomicReference<>("some draft");
        TurnSettlementSaveLastResponseTool tool =
                new TurnSettlementSaveLastResponseTool(emptyDm, draftRef::get);

        ToolCall call = new ToolCall("turn_settlement_save_last_response",
                java.util.Map.of());
        ToolResult result = tool.execute(call);

        assertFalse(result.success());
        assertTrue(result.error().contains("NO_ACTIVE_ROOT"));
    }
}
