package com.gsim.agent;

import com.gsim.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolExecutionPolicy + permissionGate 的 fail-closed 行为。
 * 不依赖 LLM — 直接测试执行策略层。
 */
@DisplayName("Orchestrator 工具权限门禁 fail-closed")
class OrchestratorPermissionTest {

    private ToolExecutionPolicy policy;
    private ToolRouteDecision openRoute;

    @BeforeEach
    void setUp() {
        policy = new ToolExecutionPolicy();
        // Route that allows all tools (allToolsAllowed=true)
        openRoute = ToolRouteDecision.wildcard(
                java.util.Set.of("query_keyword", "write_element", "finish_action"),
                "OPEN",
                "Allow all tools"
        );
    }

    @Test
    @DisplayName("READ_ONLY 工具永远允许执行")
    void readOnlyToolAlwaysAllowed() {
        ToolExecutionDecision d = policy.validateBeforeExecute(
                "query_keyword", java.util.Map.of(), openRoute, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, d.decision());
    }

    @Test
    @DisplayName("MUTATING 工具在未授权时需要确认")
    void mutatingToolNeedsConfirmation() {
        ToolExecutionDecision d = policy.validateBeforeExecute(
                "write_element", java.util.Map.of(), openRoute, false);
        assertEquals(ToolExecutionDecisionType.NEED_CONFIRMATION, d.decision());
    }

    @Test
    @DisplayName("MUTATING 工具在 allowAllMutations=true 时允许")
    void mutatingToolAllowedWhenAllAllowed() {
        ToolExecutionDecision d = policy.validateBeforeExecute(
                "write_element", java.util.Map.of(), openRoute, true);
        assertEquals(ToolExecutionDecisionType.ALLOW, d.decision());
    }

    @Test
    @DisplayName("CONTROL 工具永远允许（finish_action）")
    void controlToolAlwaysAllowed() {
        ToolExecutionDecision d = policy.validateBeforeExecute(
                "finish_action", java.util.Map.of(), openRoute, false);
        assertEquals(ToolExecutionDecisionType.ALLOW, d.decision());
    }

    @Test
    @DisplayName("不在路由允许列表中的工具被拒绝")
    void toolNotInRouteIsRejected() {
        ToolRouteDecision restricted = new ToolRouteDecision(
                java.util.Collections.unmodifiableSet(java.util.Set.of("query_keyword")),
                "RESTRICTED",
                "Only read-only"
        );
        ToolExecutionDecision d = policy.validateBeforeExecute(
                "write_element", java.util.Map.of(), restricted, false);
        assertEquals(ToolExecutionDecisionType.REJECT, d.decision());
    }

    @Test
    @DisplayName("permissionGate 为 null 时 Orchestrator 可正常构造")
    void orchestratorCanBeConstructedWithNullGate() {
        // OrchestratorAgent should accept null permissionGate (it rejects at execution time, not construction)
        ToolRegistry tools = new ToolRegistry();
        OrchestratorAgent agent = new OrchestratorAgent(
                null, tools, "test-model", AgentProgressSink.NOOP, null);
        assertNotNull(agent);
    }
}
