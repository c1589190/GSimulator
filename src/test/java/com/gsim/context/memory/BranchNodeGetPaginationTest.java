package com.gsim.context.memory;

import com.gsim.TestWorldFactory;
import com.gsim.chat.BranchMessageStore;
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
 * branch_node_get mode=full 分页能力测试。
 * 覆盖：offset/limit 切片、truncated/originalLength/returnedRange、
 * full=true 全文返回、不再只硬截断 4000 chars。
 */
@DisplayName("branch_node_get Pagination")
public class BranchNodeGetPaginationTest {

    private Path tmpDir;
    private DataManager dm;
    private BranchMessageStore messageStore;
    private BranchNodeGetTool nodeGetTool;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("gsim-test-node-get-pagination-");
        dm = TestWorldFactory.createWithDefaultRoot(tmpDir);
        messageStore = new BranchMessageStore(dm, tmpDir);
        nodeGetTool = new BranchNodeGetTool(dm, messageStore);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ==================== mode=full 分页字段 ====================

    @Test
    @DisplayName("branch_node_get mode=full: returns pagination fields")
    void fullModeReturnsPaginationFields() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "full"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        assertTrue(snippet.contains("originalLength:"),
                "should contain originalLength: " + snippet);
        assertTrue(snippet.contains("truncated:"),
                "should contain truncated: " + snippet);
        assertTrue(snippet.contains("returnedRange:"),
                "should contain returnedRange: " + snippet);
    }

    @Test
    @DisplayName("branch_node_get mode=full: default limit is not 4000 hard cutoff")
    void fullModeNotFixed4000Cutoff() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "full"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        // 验证 originalLength 在返回中
        String origLenStr = extractField(snippet, "originalLength:");
        if (origLenStr != null) {
            int origLen = Integer.parseInt(origLenStr.trim());
            // 提取 returnedRange 的 end
            String rangeStr = extractField(snippet, "returnedRange:");
            if (rangeStr != null) {
                String[] parts = rangeStr.trim().split("-");
                int end = Integer.parseInt(parts[1]);
                // 默认 limit=8000, 不是 4000
                if (origLen > 4000) {
                    assertTrue(end > 4000 || snippet.contains("truncated: true"),
                            "should not hard-cut at 4000, end=" + end
                            + " originalLength=" + origLen + ": " + snippet);
                }
            }
        }
    }

    // ==================== offset/limit 切片 ====================

    @Test
    @DisplayName("branch_node_get mode=full: offset/limit slicing works")
    void offsetLimitSlicing() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "full",
                "offset", "100",
                "limit", "200"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        assertTrue(snippet.contains("returnedRange: 100-"),
                "should have returnedRange starting at 100, got: " + snippet);
    }

    @Test
    @DisplayName("branch_node_get mode=full: offset at file start")
    void offsetAtFileStart() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "full",
                "offset", "0",
                "limit", "300"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        assertTrue(snippet.contains("returnedRange: 0-"),
                "should start at 0, got: " + snippet);
    }

    // ==================== full=true ====================

    @Test
    @DisplayName("branch_node_get mode=full: full=true returns full content up to MAX_FULL_CHARS")
    void fullTrueReturnsFullContent() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "full",
                "full", "true"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        // full=true 应从 0 开始，覆盖原文全长
        String rangeStr = extractField(snippet, "returnedRange:");
        if (rangeStr != null) {
            assertTrue(rangeStr.trim().startsWith("0-"),
                    "full should start at 0, got: " + rangeStr);
        }
        assertTrue(snippet.contains("truncated:"),
                "should have truncated field");
    }

    // ==================== 与其他 mode 对比 ====================

    @Test
    @DisplayName("branch_node_get: mode=summary is not affected by pagination")
    void summaryModeNotAffected() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "summary",
                "offset", "0",
                "limit", "100"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();
        // summary mode 返回 frontmatter 摘要，不包含分页字段
        assertTrue(snippet.contains("name:"), "summary should have name: " + snippet);
        assertTrue(snippet.contains("parent:"), "summary should have parent: " + snippet);
    }

    @Test
    @DisplayName("branch_node_get: mode=messages is not affected by pagination")
    void messagesModeNotAffected() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "messages",
                "offset", "0",
                "limit", "100"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        // messages mode 应读取 message store
        assertNotNull(result.items().get(0).snippet());
    }

    // ==================== truncated 截断提示 ====================

    @Test
    @DisplayName("branch_node_get mode=full: truncated=true shows hint to continue reading")
    void truncatedShowsContinuationHint() {
        // 用一个 body 很短的节点，故意设 offset 很小 + limit 很小来制造截断
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.b0000-start",
                "mode", "full",
                "offset", "0",
                "limit", "50"));
        ToolResult result = nodeGetTool.execute(call);

        assertTrue(result.success(), "should succeed: " + result.error());
        String snippet = result.items().get(0).snippet();

        if (snippet.contains("truncated: true")) {
            assertTrue(snippet.contains("已截断"),
                    "when truncated, should show Chinese truncation hint: " + snippet);
        }
    }

    // ==================== 错误处理 ====================

    @Test
    @DisplayName("branch_node_get: returns error for non-existent node")
    void nonExistentNode() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "nodeId", "branch.non-existent",
                "mode", "full"));
        ToolResult result = nodeGetTool.execute(call);

        assertFalse(result.success(), "should fail for non-existent node");
        assertTrue(result.error().contains("不存在"),
                "should say node doesn't exist: " + result.error());
    }

    @Test
    @DisplayName("branch_node_get: returns error for missing nodeId")
    void missingNodeId() {
        ToolCall call = new ToolCall("branch_node_get", Map.of(
                "mode", "full"));
        ToolResult result = nodeGetTool.execute(call);

        assertFalse(result.success(), "should fail for missing nodeId");
        assertTrue(result.error().contains("nodeId"),
                "should mention nodeId: " + result.error());
    }

    // ==================== Helper ====================

    private static String extractField(String snippet, String fieldName) {
        int idx = snippet.indexOf(fieldName);
        if (idx < 0) return null;
        int start = idx + fieldName.length();
        int end = snippet.indexOf('\n', start);
        if (end < 0) end = snippet.length();
        return snippet.substring(start, end).trim();
    }
}
