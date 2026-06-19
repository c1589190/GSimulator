package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRoutePolicy 路由策略单元测试。
 */
@DisplayName("工具路由策略")
class ToolRoutePolicyTest {

    private final ToolRoutePolicy policy = new ToolRoutePolicy();
    private final Set<String> defaults = ToolPermissionConfig.DEFAULT_ENABLED;

    // ========== PLAYER_ACTION_QUERY ==========

    @Test
    @DisplayName("PLAYER_ACTION_QUERY 允许 player_action_list")
    void playerActionQueryAllowsList() {
        var r = policy.decide(UserIntent.PLAYER_ACTION_QUERY, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allowedTools().contains("player_action_list"));
        assertTrue(r.allowedTools().contains("finish_action"));
    }

    @Test
    @DisplayName("PLAYER_ACTION_QUERY 拒绝写入工具")
    void playerActionQueryRejectsWrite() {
        var r = policy.decide(UserIntent.PLAYER_ACTION_QUERY, ExpectedNextStep.CALL_TOOL, defaults);
        assertFalse(r.allowedTools().contains("knowledge_upsert"));
        assertFalse(r.allowedTools().contains("simulation_content_append"));
    }

    // ========== NEXT_TURN_SETTLE ==========

    @Test
    @DisplayName("NEXT_TURN_SETTLE 允许结算+分支工具")
    void nextTurnSettleAllowsSettlementTools() {
        var r = policy.decide(UserIntent.NEXT_TURN_SETTLE, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allowedTools().contains("turn_settlement_save"));
        assertTrue(r.allowedTools().contains("branch_next_turn"));
        assertTrue(r.allowedTools().contains("branch_create_child"));
        assertTrue(r.allowedTools().contains("finish_action"));
    }

    // ========== KNOWLEDGE_SEARCH ==========

    @Test
    @DisplayName("KNOWLEDGE_SEARCH 允许知识搜索工具")
    void knowledgeSearchAllowsSearchTools() {
        var r = policy.decide(UserIntent.KNOWLEDGE_SEARCH, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allowedTools().contains("knowledge_search"));
        assertTrue(r.allowedTools().contains("keyword_search"));
        assertFalse(r.allowedTools().contains("knowledge_upsert"),
                "Search intent should NOT allow upsert");
    }

    // ========== KNOWLEDGE_WRITE ==========

    @Test
    @DisplayName("KNOWLEDGE_WRITE 允许知识写入工具")
    void knowledgeWriteAllowsWriteTools() {
        var r = policy.decide(UserIntent.KNOWLEDGE_WRITE, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allowedTools().contains("knowledge_upsert"));
        assertTrue(r.allowedTools().contains("knowledge_search"));
        assertTrue(r.allowedTools().contains("knowledge_get_document"));
    }

    // ========== FINISH_ACTION_STEP ==========

    @Test
    @DisplayName("FINISH_ACTION 阶段只允许 finish_action")
    void finishActionStepOnlyAllowsFinishAction() {
        var r = policy.decide(UserIntent.GENERAL, ExpectedNextStep.FINISH_ACTION, defaults);
        assertEquals(1, r.allowedTools().size());
        assertTrue(r.allowedTools().contains("finish_action"));
    }

    // ========== GENERAL 通配路由 ==========

    @Test
    @DisplayName("GENERAL 返回通配路由")
    void generalReturnsWildcard() {
        var r = policy.decide(UserIntent.GENERAL, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allToolsAllowed(), "GENERAL should be wildcard route");
        assertTrue(r.allowedTools().contains("finish_action"));
        assertTrue(r.allowedTools().contains("root_status"));
    }

    @Test
    @DisplayName("WORLD_SIM 返回通配路由")
    void worldSimReturnsWildcard() {
        var r = policy.decide(UserIntent.WORLD_SIM, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allToolsAllowed());
    }

    @Test
    @DisplayName("STATUS_CHECK 返回通配路由")
    void statusCheckReturnsWildcard() {
        var r = policy.decide(UserIntent.STATUS_CHECK, ExpectedNextStep.CALL_TOOL, defaults);
        assertTrue(r.allToolsAllowed());
    }

    // ========== 默认只读工具合并 ==========

    @Test
    @DisplayName("所有路由都包含默认只读工具")
    void allRoutesIncludeDefaultReadTools() {
        for (UserIntent intent : UserIntent.values()) {
            var r = policy.decide(intent, ExpectedNextStep.CALL_TOOL, defaults);
            assertTrue(r.allowedTools().contains("finish_action"),
                    intent + " should include finish_action");
            assertTrue(r.allowedTools().contains("root_status"),
                    intent + " should include root_status");
            assertTrue(r.allowedTools().contains("player_action_list"),
                    intent + " should include player_action_list");
        }
    }

    @Test
    @DisplayName("路由名包含意图")
    void routeNameContainsIntent() {
        var r = policy.decide(UserIntent.SHORT_POST_REWRITE, ExpectedNextStep.CALL_TOOL, defaults);
        assertEquals("SHORT_POST_REWRITE", r.routeName());
    }

    @Test
    @DisplayName("路由原因不为空")
    void routeReasonNotEmpty() {
        for (UserIntent intent : UserIntent.values()) {
            var r = policy.decide(intent, ExpectedNextStep.CALL_TOOL, defaults);
            assertNotNull(r.reason());
            assertFalse(r.reason().isBlank());
        }
    }
}
