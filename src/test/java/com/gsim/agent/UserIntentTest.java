package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserIntent 意图识别单元测试。
 */
@DisplayName("用户意图识别")
class UserIntentTest {

    // ========== PLAYER_ACTION_QUERY ==========

    @Test
    @DisplayName("查看行动 → PLAYER_ACTION_QUERY")
    void viewAction() {
        assertEquals(UserIntent.PLAYER_ACTION_QUERY,
                UserIntent.infer("查看行动"));
        assertEquals(UserIntent.PLAYER_ACTION_QUERY,
                UserIntent.infer("当前回合有没有什么行动"));
        assertEquals(UserIntent.PLAYER_ACTION_QUERY,
                UserIntent.infer("列出玩家行动"));
        assertEquals(UserIntent.PLAYER_ACTION_QUERY,
                UserIntent.infer("有没有玩家行动记录"));
    }

    @Test
    @DisplayName("player action 关键词 → PLAYER_ACTION_QUERY")
    void playerActionKeyword() {
        assertEquals(UserIntent.PLAYER_ACTION_QUERY,
                UserIntent.infer("player action list"));
    }

    // ========== SHORT_POST_REWRITE ==========

    @Test
    @DisplayName("短推/复写 → SHORT_POST_REWRITE")
    void shortPostRewrite() {
        assertEquals(UserIntent.SHORT_POST_REWRITE,
                UserIntent.infer("把这段改写成短推"));
        assertEquals(UserIntent.SHORT_POST_REWRITE,
                UserIntent.infer("复写这个行动"));
        assertEquals(UserIntent.SHORT_POST_REWRITE,
                UserIntent.infer("重写一下"));
        assertEquals(UserIntent.SHORT_POST_REWRITE,
                UserIntent.infer("整理成推文"));
    }

    // ========== KNOWLEDGE_WRITE ==========

    @Test
    @DisplayName("写入知识库 → KNOWLEDGE_WRITE")
    void knowledgeWrite() {
        assertEquals(UserIntent.KNOWLEDGE_WRITE,
                UserIntent.infer("把这个写入知识库"));
        assertEquals(UserIntent.KNOWLEDGE_WRITE,
                UserIntent.infer("记录到知识库"));
        assertEquals(UserIntent.KNOWLEDGE_WRITE,
                UserIntent.infer("保存为事实"));
        assertEquals(UserIntent.KNOWLEDGE_WRITE,
                UserIntent.infer("更新知识库资料"));
    }

    // ========== KNOWLEDGE_SEARCH ==========

    @Test
    @DisplayName("搜索 → KNOWLEDGE_SEARCH")
    void knowledgeSearch() {
        assertEquals(UserIntent.KNOWLEDGE_SEARCH,
                UserIntent.infer("搜索乌萨斯"));
        assertEquals(UserIntent.KNOWLEDGE_SEARCH,
                UserIntent.infer("查一下有没有关于罗德岛的资料"));
        assertEquals(UserIntent.KNOWLEDGE_SEARCH,
                UserIntent.infer("知不知道整合运动"));
        assertEquals(UserIntent.KNOWLEDGE_SEARCH,
                UserIntent.infer("wiki上有什么"));
    }

    // ========== NEXT_TURN_SETTLE ==========

    @Test
    @DisplayName("下一回合/结算 → NEXT_TURN_SETTLE")
    void nextTurnSettle() {
        assertEquals(UserIntent.NEXT_TURN_SETTLE,
                UserIntent.infer("保存结算并进入下一回合"));
        assertEquals(UserIntent.NEXT_TURN_SETTLE,
                UserIntent.infer("创建下一回合"));
        assertEquals(UserIntent.NEXT_TURN_SETTLE,
                UserIntent.infer("next turn"));
        assertEquals(UserIntent.NEXT_TURN_SETTLE,
                UserIntent.infer("结算并进入"));
    }

    @Test
    @DisplayName("创建下一回合资料 → NEXT_TURN_SETTLE")
    void createNextTurnMaterial() {
        assertEquals(UserIntent.NEXT_TURN_SETTLE,
                UserIntent.infer("检测一下你能不能创建下一回合资料"));
    }

    // ========== GENERAL ==========

    @Test
    @DisplayName("闲聊/未识别 → GENERAL")
    void general() {
        assertEquals(UserIntent.GENERAL,
                UserIntent.infer("你好"));
        assertEquals(UserIntent.GENERAL,
                UserIntent.infer("今天天气怎么样"));
        assertEquals(UserIntent.GENERAL,
                UserIntent.infer("帮我推演一下这个场景"));
        assertEquals(UserIntent.GENERAL,
                UserIntent.infer("继续"));
    }

    // ========== 边界情况 ==========

    @Test
    @DisplayName("null 或空 → GENERAL")
    void nullOrBlankReturnsGeneral() {
        assertEquals(UserIntent.GENERAL, UserIntent.infer(null));
        assertEquals(UserIntent.GENERAL, UserIntent.infer(""));
        assertEquals(UserIntent.GENERAL, UserIntent.infer("   "));
    }

    @Test
    @DisplayName("大小写不敏感")
    void caseInsensitive() {
        assertEquals(UserIntent.NEXT_TURN_SETTLE,
                UserIntent.infer("NEXT TURN"));
    }

    // ========== 优先级测试 ==========

    @Test
    @DisplayName("短推优先级高于知识搜索")
    void shortPostBeforeSearch() {
        // "复写" 同时触发 short post + 可能不触发 search
        assertEquals(UserIntent.SHORT_POST_REWRITE,
                UserIntent.infer("复写这个资料"));
    }

    @Test
    @DisplayName("知识写入优先级高于 next turn")
    void knowledgeWriteBeforeNextTurn() {
        // "写入知识库" 应该在 next turn 之前匹配
        assertEquals(UserIntent.KNOWLEDGE_WRITE,
                UserIntent.infer("写入知识库关于下一回合的内容"));
    }
}
