package com.gsim.agent.tool;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;

import java.util.List;

/**
 * console_print — 用户可见输出工具，向当前交互界面输出一段文本。
 *
 * <p>用途：在 ToolLoop 中将阶段性内容、长模板、草稿、报名表、表格预览
 * 直接打印到 CLI 公屏，不中断 ToolLoop。
 *
 * <p>行为：
 * <ul>
 *   <li>仅负责显示文本，不写入数据库，不修改世界状态</li>
 *   <li>调用后 ToolLoop 继续，最终仍需单独调用 finish_action 结束</li>
 *   <li>参数 message 必须是非空用户可读自然语言</li>
 *   <li>内容通过 AgentProgressSink 发送 AGENT_PUBLIC_MESSAGE 事件</li>
 * </ul>
 *
 * <p>参数：
 * <ul>
 *   <li>message — 必填，要展示给用户的完整文本。不得包含内部工具结果、raw JSON 或 API 密钥。</li>
 * </ul>
 */
public class ConsolePrintTool implements AgentTool {

    public static final String NAME = "console_print";

    private final AgentProgressSink progressSink;

    public ConsolePrintTool(AgentProgressSink progressSink) {
        this.progressSink = progressSink != null ? progressSink : AgentProgressSink.NOOP;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向当前交互界面输出一段用户可见文本。适用于长模板、报名表、设定草稿、阶段性说明。"
                + "该工具只负责显示文本，不写入数据库，不修改世界状态。"
                + "最终仍必须调用 finish_action 结束本轮。"
                + "参数: message（必填，要展示给用户的完整文本，必须是用户可读自然语言，"
                + "不得包含内部工具结果、raw JSON 或 API 密钥）。"
                + "示例: {\"tool\":\"console_print\",\"args\":{\"message\":\"# 报名表\\n\\n…\"}}";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String message = call.param("message", "");

        if (message.isBlank()) {
            return ToolResult.fail(NAME, "message 不能为空。请提供给用户的可读文本。");
        }

        // 防御：拒绝包含敏感标记的消息
        if (message.contains("[TOOL_RESULT]") || message.contains("[工具结果]")
                || message.contains("Authorization") || message.contains("api_key")
                || message.contains("Bearer ")) {
            return ToolResult.fail(NAME,
                    "message 包含内部工具结果、raw JSON 或 API 密钥等敏感内容，请移除。");
        }

        // 发送用户可见事件
        progressSink.onProgress(AgentProgressEvent.publicMessage(message));

        return ToolResult.ok(NAME, List.of(
                new ToolResult.Item("console_print: 已输出到用户界面",
                        NAME,
                        "message length=" + message.length() + " chars",
                        1.0)));
    }
}
