package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutionPolicy 执行前门禁单元测试（v2：不再需要 ExpectedNextStep）。
 */
@DisplayName("工具执行前门禁")
class ToolExecutionPolicyTest {

    private ToolExecutionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ToolExecutionPolicy();
    }

    // ========== READ_ONLY → ALLOW ==========

    @Test
    @DisplayName("READ_ONLY 工具直接放行")
    void readOnlyToolAllowed() {
        var route = new ToolRouteDecision(
                Set.of("query_element", "finish_action"),
                "GROUP_BASED", "test");
        var dec = policy.validateBeforeExecute(
                "query_element", Map.of(), route, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
        assertTrue(dec.allowedByRoute());
    }

    @Test
    @DisplayName("node_list (READ_ONLY) 直接放行")
    void nodeListAllowed() {
        var route = new ToolRouteDecision(
                Set.of("node_list", "finish_action"),
                "NODE_MGMT_QUERY", "test");
        var dec = policy.validateBeforeExecute(
                "node_list", Map.of(), route, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    // ========== MUTATING → NEED_CONFIRMATION ==========

    @Test
    @DisplayName("MUTATING 工具需要确认（allowAllMutations=false）")
    void mutatingToolNeedsConfirmation() {
        var route = new ToolRouteDecision(
                Set.of("write_element", "finish_action"),
                "KNOWLEDGE_WRITE", "test");
        var dec = policy.validateBeforeExecute(
                "write_element", Map.of(), route, false);
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, dec.decision());
        assertEquals(ToolCategory.MUTATING, dec.category());
    }

    @Test
    @DisplayName("MUTATING 工具 allowAllMutations=true 时放行")
    void mutatingToolAllowedWhenAllowAll() {
        var route = new ToolRouteDecision(
                Set.of("write_element", "finish_action"),
                "KNOWLEDGE_WRITE", "test");
        var dec = policy.validateBeforeExecute(
                "write_element", Map.of(), route, true);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    // ========== MUTATING 始终需确认（allowAllMutations=false） ==========

    @Test
    @DisplayName("已知 MUTATING 工具在 allowAllMutations=false 时需确认")
    void destructiveAlwaysNeedsConfirmation() {
        // create_checkpoint 是已知 MUTATING 工具（在 WORLD_INFO ToolGroup 中）
        var route = new ToolRouteDecision(
                Set.of("create_checkpoint", "finish_action"),
                "GENERAL", "test");
        var dec = policy.validateBeforeExecute(
                "create_checkpoint", Map.of(), route, false);
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, dec.decision());
        assertEquals(ToolCategory.MUTATING, dec.category());
    }

    // ========== CONTROL (finish_action, activate_tool_groups) → ALLOW ==========

    @Test
    @DisplayName("finish_action 直接放行")
    void finishActionAllowed() {
        var route = new ToolRouteDecision(
                Set.of("finish_action"), "GROUP_BASED", "test");
        var dec = policy.validateBeforeExecute(
                "finish_action", Map.of(), route, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    @Test
    @DisplayName("activate_tool_groups 直接放行")
    void activateToolGroupsAllowed() {
        var route = new ToolRouteDecision(
                Set.of("activate_tool_groups", "finish_action"),
                "GROUP_BASED", "test");
        var dec = policy.validateBeforeExecute(
                "activate_tool_groups", Map.of("groups", "[\"world_info\"]"), route, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    // ========== 路由拒绝 ==========

    @Test
    @DisplayName("工具不在 allowedTools 中 → REJECT")
    void toolNotInRouteRejected() {
        var route = new ToolRouteDecision(
                Set.of("node_list", "finish_action"),
                "NODE_MGMT_QUERY", "test");
        // create_checkpoint 在 WORLD_INFO ToolGroup 中，不在 NODE_MGMT_QUERY 路由允许列表
        var dec = policy.validateBeforeExecute(
                "create_checkpoint", Map.of(), route, false);
        assertEquals(ToolExecutionDecisionType.REJECT, dec.decision());
        assertFalse(dec.allowedByRoute());
    }

    // ========== 通配路由跳过工具名检查 ==========

    @Test
    @DisplayName("通配路由允许任意工具（含未注册工具）")
    void wildcardRouteAllowsAnyTool() {
        var route = ToolRouteDecision.wildcard(
                Set.of("finish_action"), "GENERAL", "通配路由");
        var dec = policy.validateBeforeExecute(
                "unknown_tool", Map.of(), route, false);
        // 未知工具默认 MUTATING → NEED_CONFIRMATION
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, dec.decision());
    }

    // ========== buildRejectionReprompt ==========

    @Test
    @DisplayName("拒绝重提示消息包含工具名和允许列表")
    void rejectionRepromptContainsToolNameAndAllowed() {
        var route = new ToolRouteDecision(
                Set.of("node_list", "finish_action"),
                "NODE_MGMT_QUERY", "test");
        var dec = ToolExecutionDecision.reject("测试拒绝", ToolCategory.MUTATING, false);
        String reprompt = policy.buildRejectionReprompt("write_element", dec, route);
        assertTrue(reprompt.contains("write_element"));
        assertTrue(reprompt.contains("node_list"));
        assertTrue(reprompt.contains("finish_action"));
    }

    // ========== buildDenyStopMessage ==========

    @Test
    @DisplayName("拒绝终止消息包含工具名")
    void denyStopMessageContainsToolName() {
        String msg = policy.buildDenyStopMessage("node_create");
        assertTrue(msg.contains("node_create"));
        assertTrue(msg.contains("拒绝"));
    }
}
