package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证无工具提醒内容明确提到 finish_action。
 */
@DisplayName("ToolLoop 无工具提醒提到 finish_action")
class ToolLoopNoToolReminderMentionsFinishActionTest {

    @Test
    @DisplayName("buildNoToolReminder 提到 finish_action")
    void reminderMentionsFinishAction() {
        String reminder = ToolLoopDebug.buildNoToolReminder("执行一个任务");

        assertNotNull(reminder);
        assertTrue(reminder.contains("finish_action"),
                "Reminder must mention finish_action: " + reminder);
        assertTrue(reminder.contains("不要直接用普通自然语言结束"),
                "Reminder must warn against plain NL ending");
    }

    @Test
    @DisplayName("buildNoToolReminder 提到调用必要业务工具")
    void reminderMentionsBusinessTools() {
        String reminder = ToolLoopDebug.buildNoToolReminder("查看状态");

        assertTrue(reminder.contains("业务工具")
                        || reminder.contains("工具"),
                "Reminder should mention business tools when needed");
    }

    @Test
    @DisplayName("buildNoToolReminder 不为空")
    void reminderIsNotEmpty() {
        String reminder = ToolLoopDebug.buildNoToolReminder(null);
        assertNotNull(reminder);
        assertFalse(reminder.isBlank());
        // null user input 不应触发 player_action 提示
        assertFalse(reminder.contains("player_action_list"),
                "null user input should not trigger player_action hint");
    }
}
