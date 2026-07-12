package com.gsim.agent.tool;

import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;

/**
 * finish_action — 控制流工具，标记 Agent 工作流本轮结束。
 *
 * <p>此工具不写业务数据。LLM 必须显式调用 finish_action 才能结束每轮对话。
 * 系统不再接受"无工具调用 = 结束"的隐式约定。
 *
 * <p>参数：
 * <ul>
 *   <li>status — 必填，"success" | "partial" | "failed" | "needs_user_input"</li>
 *   <li>message — 必填，最终展示给用户的自然语言回复</li>
 *   <li>summary — 可选，本轮操作简短摘要</li>
 * </ul>
 *
 * <p>验证（占位符、raw JSON、成功宣称）由 OrchestratorAgent ToolLoop 在
 * 执行后完成，不在此工具内处理。
 */
public class FinishActionTool implements AgentTool {

    public static final String NAME = "finish_action";

    /** 有效的 status 值 */
    public static final List<String> VALID_STATUSES = List.of(
            "success", "partial", "failed", "needs_user_input");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "结束当前工具调用循环并返回最终回复。你必须调用此工具才能结束本轮对话。"
                + "参数: status（必填，\"success\"|\"partial\"|\"failed\"|\"needs_user_input\"），"
                + "message（必填，给用户的最终自然语言回复，不要包含[工具结果]/[工具调用已执行]/raw JSON），"
                + "summary（可选）。"
                + "示例: {\"tool\":\"finish_action\",\"args\":{\"status\":\"success\","
                + "\"message\":\"已完成本轮推演…\"}}";
    }

    @Override
    public java.util.Map<String, Object> getParameters() {
        java.util.Map<String, java.util.Map<String, Object>> props = new java.util.LinkedHashMap<>();
        props.put("status", java.util.Map.of(
                "type", "string",
                "enum", java.util.List.of("success", "partial", "failed", "needs_user_input"),
                "description", "本轮操作状态：success（全部完成）、partial（部分完成）、failed（执行失败）、"
                        + "needs_user_input（需要用户补充信息/做出选择）。"));
        props.put("message", java.util.Map.of(
                "type", "string",
                "description", "最终展示给用户的完整回复正文。必须自包含，"
                        + "不得使用「以上」「如上」「刚才」「前文」等引用不可见内容。"));
        props.put("summary", java.util.Map.of(
                "type", "string",
                "description", "本轮操作的简短摘要（可选，但强烈建议提供）。"
                        + "一句话总结你做了什么，用蓝色高亮显示在终端上供用户快速浏览。"));
        return com.gsim.llm.ToolDef.strictSchema(props,
                java.util.List.of("status", "message"));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String status = call.param("status", "");
        String message = call.param("message", "");
        String summary = call.param("summary", "");

        if (status.isBlank()) {
            return ToolResult.fail(NAME,
                    "status 不能为空。必须是 success / partial / failed / needs_user_input 之一。");
        }
        if (!VALID_STATUSES.contains(status)) {
            return ToolResult.fail(NAME,
                    "无效的 status '" + status + "'。必须是 success / partial / failed / needs_user_input 之一。");
        }
        if (message.isBlank()) {
            return ToolResult.fail(NAME, "message 不能为空。请提供给用户的最终自然语言回复。");
        }

        var items = new java.util.ArrayList<ToolResult.Item>();
        items.add(new ToolResult.Item("finish_action: " + status,
                NAME,
                message,
                1.0));
        if (!summary.isBlank()) {
            items.add(new ToolResult.Item("finish_action_summary",
                    NAME,
                    summary,
                    0.5));
        }
        return ToolResult.ok(NAME, items);
    }
}
