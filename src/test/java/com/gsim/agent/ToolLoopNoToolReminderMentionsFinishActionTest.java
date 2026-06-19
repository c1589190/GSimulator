package com.gsim.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证无工具提醒内容明确提到 finish_action 以及不可见答复的完整重写要求。
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

    @Test
    @DisplayName("buildNoToolReminder 明确告知模型上一轮答复用户看不到")
    void reminderTellsModelPreviousAnswerNotShown() {
        String reminder = ToolLoopDebug.buildNoToolReminder("查看状态");

        assertTrue(reminder.contains("没有展示给用户"),
                "Reminder must tell model its previous answer was NOT shown to user");
        assertTrue(reminder.contains("用户看不到"),
                "Reminder must tell model the user cannot see previous answer");
    }

    @Test
    @DisplayName("buildNoToolReminder 要求将完整最终答复写入 finish_action.message")
    void reminderRequiresFullAnswerInFinishActionMessage() {
        String reminder = ToolLoopDebug.buildNoToolReminder("生成了一个报名表");

        assertTrue(reminder.contains("finish_action.message")
                        || reminder.contains("完整"),
                "Reminder must require full answer in finish_action.message");
        assertTrue(reminder.contains("完整写入")
                        || reminder.contains("完整重新写入"),
                "Reminder must require rewriting the full content");
    }

    @Test
    @DisplayName("buildNoToolReminder 禁止使用以上/如上等引用不可见内容")
    void reminderForbidsReferringToInvisibleContent() {
        String reminder = ToolLoopDebug.buildNoToolReminder("写一个模板");

        assertTrue(reminder.contains("禁止使用"),
                "Reminder must forbid referencing invisible content");
        assertTrue(reminder.contains("以上") || reminder.contains("如上")
                        || reminder.contains("刚才"),
                "Reminder must specifically forbid 以上/如上/刚才: " + reminder);
    }
}
