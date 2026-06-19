package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 TaskBrief 推断用户意图为 PLAYER_ACTION_QUERY。
 */
@DisplayName("TaskBrief 推断 PLAYER_ACTION_QUERY 意图")
class ToolLoopTaskBriefShowsPlayerActionQueryExpectedToolTest {

    @Test
    @DisplayName("用户问'确认行动记录' → PLAYER_ACTION_QUERY")
    void playerActionQueryDetected() {
        assertEquals("PLAYER_ACTION_QUERY",
                ToolLoopDebug.inferUserIntent("确认一下第二回合有没有玩家行动记录"));
        assertEquals("PLAYER_ACTION_QUERY",
                ToolLoopDebug.inferUserIntent("玩家行动列表"));
        assertEquals("PLAYER_ACTION_QUERY",
                ToolLoopDebug.inferUserIntent("查询当前回合行动记录"));
    }

    @Test
    @DisplayName("用户问搜索 → KNOWLEDGE_SEARCH")
    void knowledgeSearchDetected() {
        assertEquals("KNOWLEDGE_SEARCH",
                ToolLoopDebug.inferUserIntent("搜索龙门的相关知识"));
        assertEquals("KNOWLEDGE_SEARCH",
                ToolLoopDebug.inferUserIntent("查找一下感染者"));
    }

    @Test
    @DisplayName("用户问推演 → WORLD_SIM")
    void worldSimDetected() {
        assertEquals("WORLD_SIM",
                ToolLoopDebug.inferUserIntent("推演一下下一回合的情况"));
        assertEquals("WORLD_SIM",
                ToolLoopDebug.inferUserIntent("结算当前回合进入下一回合"));
    }

    @Test
    @DisplayName("一般查询 → GENERAL")
    void generalDetected() {
        assertEquals("GENERAL",
                ToolLoopDebug.inferUserIntent("hello"));
        assertEquals("GENERAL",
                ToolLoopDebug.inferUserIntent("帮我看一下"));
    }

    @Test
    @DisplayName("buildCliTaskLine 包含 activeBranch")
    void cliTaskLineIncludesBranch() {
        String line = ToolLoopDebug.buildCliTaskLine(
                "确认一下第二回合有没有玩家行动记录", "branch.b0002");
        assertNotNull(line);
        assertTrue(line.contains("branch.b0002"),
                "task line should include activeBranch: " + line);
        assertTrue(line.contains("查询"),
                "task line should describe action: " + line);
        assertTrue(line.startsWith("[Agent]"),
                "task line should start with [Agent]: " + line);
    }
}
