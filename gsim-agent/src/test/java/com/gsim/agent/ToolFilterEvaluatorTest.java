package com.gsim.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.AgentTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolFilterEvaluator 工具过滤")
class ToolFilterEvaluatorTest {

    // ── read_only 模式 ──

    @Test
    @DisplayName("read_only 放行 READ permission 工具（含未注册的 gsimap_*）")
    void readOnlyAllowsReadPermission() {
        // gsimap_get_hex 不在 ToolCategoryRegistry（未注册），但 permission=READ → 应放行（修复核心）
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY, "gsimap_get_hex", AgentTool.Permission.READ, null, null));
        // 已注册的只读工具同样放行
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY, "query_node", AgentTool.Permission.READ, null, null));
    }

    @Test
    @DisplayName("read_only 拒绝 WRITE/SYSTEM permission 工具")
    void readOnlyRejectsWritePermission() {
        assertFalse(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY, "write_element", AgentTool.Permission.WRITE, null, null));
        assertFalse(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY, "node_create", AgentTool.Permission.SYSTEM, null, null));
    }

    @Test
    @DisplayName("read_only 放行 SELF 工具（finish_action）")
    void readOnlyAllowsSelfPermission() {
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY, "finish_action", AgentTool.Permission.SELF, null, null));
    }

    @Test
    @DisplayName("read_only + maxPermission 组合仍生效（READ ≤ READ / READ ≤ WRITE）")
    void readOnlyWithMaxPermission() {
        // READ ≤ READ
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY,
                "gsimap_get_hex",
                AgentTool.Permission.READ,
                AgentTool.Permission.READ,
                null));
        // READ ≤ WRITE
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY,
                "gsimap_get_hex",
                AgentTool.Permission.READ,
                AgentTool.Permission.WRITE,
                null));
        // 未注册工具 + READ + maxPermission=READ 仍放行
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY,
                "gsimap_query_radius",
                AgentTool.Permission.READ,
                AgentTool.Permission.READ,
                null));
        // WRITE 工具即使 maxPermission=SYSTEM 在 read_only 下仍被拒（read_only 优先）
        assertFalse(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.READ_ONLY,
                "write_element",
                AgentTool.Permission.WRITE,
                AgentTool.Permission.SYSTEM,
                null));
    }

    // ── 其他模式回归 ──

    @Test
    @DisplayName("all 模式全部放行（回归）")
    void allModeAllowsEverything() {
        assertTrue(ToolFilterEvaluator.allows(ToolFilterConfig.ALL, "write_element"));
        assertTrue(ToolFilterEvaluator.allows(ToolFilterConfig.ALL, "unknown_tool"));
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.ALL, "write_element", AgentTool.Permission.WRITE, null, null));
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.ALL, "unknown_tool", AgentTool.Permission.READ, null, null));
    }

    @Test
    @DisplayName("none 模式仅放行 finish_action（回归）")
    void noneModeOnlyFinishAction() {
        assertTrue(ToolFilterEvaluator.allows(ToolFilterConfig.NONE, "finish_action"));
        assertFalse(ToolFilterEvaluator.allows(ToolFilterConfig.NONE, "query_node"));
        assertFalse(ToolFilterEvaluator.allows(ToolFilterConfig.NONE, "write_element"));
        assertFalse(ToolFilterEvaluator.allows(ToolFilterConfig.NONE, "gsimap_get_hex"));
    }

    @Test
    @DisplayName("maxPermission 门禁在非 read_only 模式仍生效（WRITE > READ 拒绝）")
    void maxPermissionGateInAllMode() {
        assertFalse(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.ALL, "write_element", AgentTool.Permission.WRITE, AgentTool.Permission.READ, null));
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.ALL, "write_element", AgentTool.Permission.WRITE, AgentTool.Permission.WRITE, null));
        // SYSTEM > WRITE 拒绝
        assertFalse(ToolFilterEvaluator.allowsWithPermission(
                ToolFilterConfig.ALL, "delete_world", AgentTool.Permission.SYSTEM, AgentTool.Permission.WRITE, null));
    }

    // ── custom 模式（白名单）──

    @Test
    @DisplayName("custom 空 allow 拒绝所有工具（fail-closed），仅 finish_action 经 SELF 放行")
    void customEmptyAllowRejectsAll() {
        assertFalse(ToolFilterEvaluator.allows(new ToolFilterConfig("custom", List.of(), List.of()), "query_node"));
        assertFalse(ToolFilterEvaluator.allows(new ToolFilterConfig("custom", List.of(), List.of()), "write_element"));
        assertFalse(ToolFilterEvaluator.allows(new ToolFilterConfig("custom", List.of(), List.of()), "gsimap_get_hex"));
        assertTrue(ToolFilterEvaluator.allowsWithPermission(
                new ToolFilterConfig("custom", List.of(), List.of()),
                "finish_action",
                AgentTool.Permission.SELF,
                null,
                null));
    }

    @Test
    @DisplayName("custom 非空 allow 仅放行列表内工具")
    void customAllowListOnly() {
        ToolFilterConfig config = new ToolFilterConfig("custom", List.of("query_node"), List.of());
        assertTrue(ToolFilterEvaluator.allows(config, "query_node"));
        assertFalse(ToolFilterEvaluator.allows(config, "write_element"));
    }

    @Test
    @DisplayName("custom deny 优先于 allow（黑名单命中拒绝）")
    void customDenyOverridesAllow() {
        ToolFilterConfig config = new ToolFilterConfig("custom", List.of("query_node"), List.of("query_node"));
        assertFalse(ToolFilterEvaluator.allows(config, "query_node"));
    }

    @Test
    @DisplayName("custom 空 allow + deny 仍拒绝所有（纯黑名单模式移除，fail-closed）")
    void customEmptyAllowWithDenyRejectsAll() {
        assertFalse(ToolFilterEvaluator.allows(
                new ToolFilterConfig("custom", List.of(), List.of("query_node")), "query_node"));
    }
}
