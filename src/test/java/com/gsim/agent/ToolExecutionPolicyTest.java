package com.gsim.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutionPolicy 执行前门禁单元测试。
 */
@DisplayName("工具执行前门禁")
class ToolExecutionPolicyTest {

    private ToolExecutionPolicy policy;
    private ToolPermissionConfig permConfig;

    @BeforeEach
    void setUp() {
        policy = new ToolExecutionPolicy();
        permConfig = new ToolPermissionConfig();
    }

    // ========== READ_ONLY → ALLOW ==========

    @Test
    @DisplayName("READ_ONLY 工具直接放行")
    void readOnlyToolAllowed() {
        var route = new ToolRoutePolicy().decide(
                UserIntent.GENERAL, ExpectedNextStep.CALL_TOOL,
                permConfig.defaultEnabledTools());
        var dec = policy.validateBeforeExecute(
                "root_status", Map.of(), route, ExpectedNextStep.CALL_TOOL, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
        assertTrue(dec.allowedByRoute());
    }

    @Test
    @DisplayName("player_action_list (READ_ONLY) 直接放行")
    void playerActionListAllowed() {
        var route = new ToolRouteDecision(
                Set.of("player_action_list", "finish_action"),
                "PLAYER_ACTION_QUERY", "test");
        var dec = policy.validateBeforeExecute(
                "player_action_list", Map.of(), route, ExpectedNextStep.CALL_TOOL, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    // ========== MUTATING → NEED_CONFIRMATION ==========

    @Test
    @DisplayName("MUTATING 工具需要确认（allowAllMutations=false）")
    void mutatingToolNeedsConfirmation() {
        var route = new ToolRouteDecision(
                Set.of("knowledge_upsert", "finish_action"),
                "KNOWLEDGE_WRITE", "test");
        var dec = policy.validateBeforeExecute(
                "knowledge_upsert", Map.of(), route, ExpectedNextStep.CALL_TOOL, false);
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, dec.decision());
        assertEquals(ToolCategory.MUTATING, dec.category());
    }

    @Test
    @DisplayName("MUTATING 工具 allowAllMutations=true 时放行")
    void mutatingToolAllowedWhenAllowAll() {
        var route = new ToolRouteDecision(
                Set.of("knowledge_upsert", "finish_action"),
                "KNOWLEDGE_WRITE", "test");
        var dec = policy.validateBeforeExecute(
                "knowledge_upsert", Map.of(), route, ExpectedNextStep.CALL_TOOL, true);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    // ========== DESTRUCTIVE → 永远需要确认 ==========

    @Test
    @DisplayName("DESTRUCTIVE 工具永远需要确认（即使 allowAllMutations=true）")
    void destructiveAlwaysNeedsConfirmation() {
        var route = new ToolRouteDecision(
                Set.of("knowledge_delete", "finish_action"),
                "KNOWLEDGE_WRITE", "test");
        var dec = policy.validateBeforeExecute(
                "knowledge_delete", Map.of(), route, ExpectedNextStep.CALL_TOOL, true);
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, dec.decision());
        assertEquals(ToolCategory.DESTRUCTIVE, dec.category());
    }

    // ========== CONTROL (finish_action) → ALLOW ==========

    @Test
    @DisplayName("finish_action 直接放行")
    void finishActionAllowed() {
        var route = new ToolRouteDecision(
                Set.of("finish_action"), "FINISH_ACTION_STEP", "test");
        var dec = policy.validateBeforeExecute(
                "finish_action", Map.of(), route, ExpectedNextStep.FINISH_ACTION, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, dec.decision());
    }

    // ========== 路由拒绝 ==========

    @Test
    @DisplayName("工具不在 allowedTools 中 → REJECT")
    void toolNotInRouteRejected() {
        var route = new ToolRouteDecision(
                Set.of("player_action_list", "finish_action"),
                "PLAYER_ACTION_QUERY", "test");
        var dec = policy.validateBeforeExecute(
                "knowledge_upsert", Map.of(), route, ExpectedNextStep.CALL_TOOL, false);
        assertEquals(ToolExecutionDecisionType.REJECT, dec.decision());
        assertFalse(dec.allowedByRoute());
    }

    // ========== FINISH_ACTION 阶段拒绝 ==========

    @Test
    @DisplayName("FINISH_ACTION 阶段调用业务工具 → REJECT")
    void businessToolRejectedInFinishActionStep() {
        var route = new ToolRouteDecision(
                Set.of("finish_action"), "FINISH_ACTION_STEP",
                "当前阶段已获得足够工具结果");
        var dec = policy.validateBeforeExecute(
                "root_status", Map.of(), route, ExpectedNextStep.FINISH_ACTION, false);
        assertEquals(ToolExecutionDecisionType.REJECT, dec.decision());
    }

    // ========== 通配路由跳过工具名检查 ==========

    @Test
    @DisplayName("通配路由允许任意工具（含未注册工具）")
    void wildcardRouteAllowsAnyTool() {
        var route = ToolRouteDecision.wildcard(
                Set.of("finish_action"), "GENERAL", "通配路由");
        var dec = policy.validateBeforeExecute(
                "unknown_tool", Map.of(), route, ExpectedNextStep.CALL_TOOL, false);
        // 未知工具默认 MUTATING → NEED_CONFIRMATION
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, dec.decision());
    }

    // ========== buildRejectionReprompt ==========

    @Test
    @DisplayName("拒绝重提示消息包含工具名和允许列表")
    void rejectionRepromptContainsToolNameAndAllowed() {
        var route = new ToolRouteDecision(
                Set.of("player_action_list", "finish_action"),
                "PLAYER_ACTION_QUERY", "test");
        var dec = ToolExecutionDecision.reject("测试拒绝", ToolCategory.MUTATING, false);
        String reprompt = policy.buildRejectionReprompt("knowledge_upsert", dec, route);
        assertTrue(reprompt.contains("knowledge_upsert"));
        assertTrue(reprompt.contains("player_action_list"));
        assertTrue(reprompt.contains("finish_action"));
    }

    // ========== buildDenyStopMessage ==========

    @Test
    @DisplayName("拒绝终止消息包含工具名")
    void denyStopMessageContainsToolName() {
        String msg = policy.buildDenyStopMessage("knowledge_delete");
        assertTrue(msg.contains("knowledge_delete"));
        assertTrue(msg.contains("拒绝"));
    }
}
