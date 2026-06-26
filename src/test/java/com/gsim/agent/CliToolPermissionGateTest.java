package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CliToolPermissionGate 终端确认交互测试。
 */
@DisplayName("CLI 工具权限门禁")
class CliToolPermissionGateTest {

    private PrintStream captureOutput() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    // ========== ALLOW_ONCE ==========

    @Test
    @DisplayName("输入 Y 返回 ALLOW_ONCE")
    void inputYReturnsAllowOnce() {
        var in = new BufferedReader(new StringReader("Y\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("write_element", ToolCategory.MUTATING,
                "写入工具：write_element 将修改数据，需要用户确认。", Map.of(), null);
        assertEquals(ConfirmationChoice.ALLOW_ONCE, gate.askConfirmation(req));
    }

    @Test
    @DisplayName("输入 yes 返回 ALLOW_ONCE")
    void inputYesReturnsAllowOnce() {
        var in = new BufferedReader(new StringReader("yes\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("simulation_content_append", ToolCategory.MUTATING,
                "写入工具", Map.of(), null);
        assertEquals(ConfirmationChoice.ALLOW_ONCE, gate.askConfirmation(req));
    }

    @Test
    @DisplayName("空输入（直接回车）返回 ALLOW_ONCE")
    void emptyInputReturnsAllowOnce() {
        var in = new BufferedReader(new StringReader("\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("branch_create_child", ToolCategory.MUTATING,
                "写入工具", Map.of(), null);
        assertEquals(ConfirmationChoice.ALLOW_ONCE, gate.askConfirmation(req));
    }

    // ========== ALLOW_ALL_THIS_TURN ==========

    @Test
    @DisplayName("输入 A 返回 ALLOW_ALL_THIS_TURN")
    void inputAReturnsAllowAll() {
        var in = new BufferedReader(new StringReader("a\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("write_element", ToolCategory.MUTATING,
                "写入工具", Map.of(), null);
        assertEquals(ConfirmationChoice.ALLOW_ALL_THIS_TURN, gate.askConfirmation(req));
    }

    @Test
    @DisplayName("输入 all 返回 ALLOW_ALL_THIS_TURN")
    void inputAllReturnsAllowAll() {
        var in = new BufferedReader(new StringReader("all\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("simulation_content_append", ToolCategory.MUTATING,
                "写入工具", Map.of(), null);
        assertEquals(ConfirmationChoice.ALLOW_ALL_THIS_TURN, gate.askConfirmation(req));
    }

    // ========== DENY ==========

    @Test
    @DisplayName("输入 N 返回 DENY")
    void inputNReturnsDeny() {
        var in = new BufferedReader(new StringReader("n\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("knowledge_delete", ToolCategory.DESTRUCTIVE,
                "破坏性操作", Map.of(), null);
        assertEquals(ConfirmationChoice.DENY, gate.askConfirmation(req));
    }

    @Test
    @DisplayName("输入未知字符返回 DENY")
    void unknownInputReturnsDeny() {
        var in = new BufferedReader(new StringReader("x\n"));
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("write_element", ToolCategory.MUTATING,
                "写入工具", Map.of(), null);
        assertEquals(ConfirmationChoice.DENY, gate.askConfirmation(req));
    }

    @Test
    @DisplayName("null 输入返回 DENY")
    void nullInputReturnsDeny() {
        var in = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() { return null; }
        };
        var gate = new CliToolPermissionGate(captureOutput(), in);
        var req = new ToolConfirmationRequest("write_element", ToolCategory.MUTATING,
                "写入工具", Map.of(), null);
        assertEquals(ConfirmationChoice.DENY, gate.askConfirmation(req));
    }

    // ========== 输出格式 ==========

    @Test
    @DisplayName("确认提示包含工具名、分类、原因")
    void promptContainsToolInfo() {
        var out = new ByteArrayOutputStream();
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        var in = new BufferedReader(new StringReader("y\n"));
        var gate = new CliToolPermissionGate(ps, in);
        var req = new ToolConfirmationRequest("write_element", ToolCategory.MUTATING,
                "写入工具：write_element 将修改数据，需要用户确认。",
                Map.of("key", "value"), "branch.b0001-test");
        gate.askConfirmation(req);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("write_element"), "Should contain tool name, got: " + output);
        assertTrue(output.contains("MUTATING"), "Should contain category, got: " + output);
        assertTrue(output.contains("将修改数据"), "Should contain reason, got: " + output);
        assertTrue(output.contains("branch.b0001-test"), "Should contain branch ID, got: " + output);
        assertTrue(output.contains("[Y]"), "Should contain Y option, got: " + output);
        assertTrue(output.contains("[A]"), "Should contain A option, got: " + output);
        assertTrue(output.contains("[N]"), "Should contain N option, got: " + output);
    }

    @Test
    @DisplayName("DESTRUCTIVE 分类显示在提示中")
    void destructiveCategoryDisplayed() {
        var out = new ByteArrayOutputStream();
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        var in = new BufferedReader(new StringReader("n\n"));
        var gate = new CliToolPermissionGate(ps, in);
        var req = new ToolConfirmationRequest("knowledge_delete", ToolCategory.DESTRUCTIVE,
                "⚠ 破坏性操作：knowledge_delete 可能删除数据，需要用户确认。",
                Map.of(), null);
        gate.askConfirmation(req);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("DESTRUCTIVE"), "Should contain DESTRUCTIVE, got: " + output);
    }
}
