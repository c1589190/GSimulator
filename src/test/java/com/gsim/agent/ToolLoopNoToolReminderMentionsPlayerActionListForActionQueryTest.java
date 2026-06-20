package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证当用户询问行动记录时，无工具提醒额外提示用户正在询问行动记录。
 * v2: 不再直接提示 player_action_list（改为提示 activate_tool_groups + finish_action）。
 */
@DisplayName("ToolLoop 无工具提醒对行动查询提示")
class ToolLoopNoToolReminderMentionsPlayerActionListForActionQueryTest {

    @Test
    @DisplayName("用户问'第二回合有没有玩家行动记录' → 提醒包含行动记录相关提示")
    void playerActionQueryTriggersPlayerActionListHint() {
        String reminder = ToolLoopDebug.buildNoToolReminder("确认一下第二回合有没有玩家行动记录");

        assertTrue(reminder.contains("行动记录")
                        || reminder.contains("玩家行动"),
                "Reminder for action query should mention 行动记录: " + reminder);
        assertTrue(reminder.contains("finish_action"),
                "Reminder must still mention finish_action");
    }

    @Test
    @DisplayName("用户问'当前回合行动' → 提醒包含行动记录相关提示")
    void currentTurnActionQueryTriggersHint() {
        String reminder = ToolLoopDebug.buildNoToolReminder("当前回合行动是什么");

        assertTrue(reminder.contains("行动")
                        || reminder.contains("finish_action"),
                "Current turn action query should trigger relevant hint: " + reminder);
    }

    @Test
    @DisplayName("用户问'列出玩家行动' → 提醒包含行动记录相关提示")
    void listPlayerActionsQueryTriggersHint() {
        String reminder = ToolLoopDebug.buildNoToolReminder("列出玩家行动");

        assertTrue(reminder.contains("玩家行动")
                        || reminder.contains("行动"),
                "List actions query should trigger relevant hint: " + reminder);
    }

    @Test
    @DisplayName("用户问'行动记录' → 提醒包含行动记录相关提示")
    void actionRecordQueryTriggersHint() {
        String reminder = ToolLoopDebug.buildNoToolReminder("这个节点有没有行动记录");

        assertTrue(reminder.contains("行动记录"),
                "Action record query should trigger hint: " + reminder);
    }

    @Test
    @DisplayName("用户问'player action' → 提醒包含行动记录相关提示（英文触发）")
    void englishPlayerActionQueryTriggersHint() {
        String reminder = ToolLoopDebug.buildNoToolReminder("check player action records");

        assertTrue(reminder.contains("行动记录"),
                "English 'player action' query should trigger hint: " + reminder);
    }

    @Test
    @DisplayName("普通任务（非行动查询）→ 提醒不包含行动记录")
    void normalQueryDoesNotTriggerPlayerActionHint() {
        String reminder = ToolLoopDebug.buildNoToolReminder("结算本回合并进入下一回合");

        assertFalse(reminder.contains("行动记录"),
                "Normal settlement query should NOT trigger 行动记录 hint: " + reminder);
    }

    @Test
    @DisplayName("isPlayerActionQuery 正确匹配各类行动查询")
    void isPlayerActionQueryMatchesCorrectly() {
        assertTrue(ToolLoopDebug.isPlayerActionQuery("第二回合有没有玩家行动记录"));
        assertTrue(ToolLoopDebug.isPlayerActionQuery("当前回合行动"));
        assertTrue(ToolLoopDebug.isPlayerActionQuery("列出行动记录"));
        assertTrue(ToolLoopDebug.isPlayerActionQuery("有没有行动"));
        assertTrue(ToolLoopDebug.isPlayerActionQuery("player action check"));
        assertFalse(ToolLoopDebug.isPlayerActionQuery("结算回合"));
        assertFalse(ToolLoopDebug.isPlayerActionQuery("搜索龙门"));
        assertFalse(ToolLoopDebug.isPlayerActionQuery(null));
    }
}
