package com.gsim.event;

import java.io.PrintWriter;

/**
 * CLI 事件显示器 — 把 GSimEvent 格式化为控制台输出。
 *
 * <ul>
 *   <li>llm_delta 直接流式打印</li>
 *   <li>llm_reasoning_delta 用 {@code [reasoning] ...} 前缀打印</li>
 *   <li>tool/run/import 事件显示进度</li>
 * </ul>
 */
public class ConsoleEventSink implements EventSink {

    private final PrintWriter out;
    private boolean reasoningOpen = false;

    public ConsoleEventSink() {
        this(new PrintWriter(System.out, true));
    }

    public ConsoleEventSink(PrintWriter out) {
        this.out = out;
    }

    @Override
    public void accept(GSimEvent event) {
        switch (event.type()) {
            case "command_started":
                out.println("[>] 命令开始: " + event.getString("command"));
                break;
            case "command_done":
                out.println("[✓] 命令完成");
                break;
            case "command_error":
                out.println("[✗] 命令错误: " + event.getString("error"));
                break;

            case "run_stage":
                out.println("[…] " + event.getString("stage") + ": " + event.getString("message"));
                break;

            case "tool_started":
                out.println("[🔧] 调用工具: " + event.getString("tool") + " - " + event.getString("message"));
                break;
            case "tool_done":
                Object count = event.data().get("count");
                out.println("[🔧] 工具完成: " + event.getString("tool")
                        + (count != null ? " (" + count + " 条结果)" : ""));
                break;
            case "tool_error":
                out.println("[🔧] 工具错误: " + event.getString("tool") + " - " + event.getString("error"));
                break;

            case "import_progress":
                out.println("[📥] 导入: " + event.getString("message"));
                break;
            case "search_progress":
                out.println("[🔍] 搜索: " + event.getString("message"));
                break;

            case "llm_started":
                out.println("[🤖] LLM 请求开始");
                break;
            case "llm_delta":
                if (reasoningOpen) {
                    out.println();
                    reasoningOpen = false;
                }
                out.print(event.getString("text"));
                out.flush();
                break;
            case "llm_reasoning_delta":
                if (!reasoningOpen) {
                    out.println();
                    reasoningOpen = true;
                }
                out.print("[reasoning] " + event.getString("text"));
                out.flush();
                break;
            case "llm_done":
                if (reasoningOpen) {
                    out.println();
                    reasoningOpen = false;
                }
                out.println();
                out.println("[🤖] LLM 请求完成");
                break;

            case "log":
                out.println("[log] " + event.getString("message"));
                break;

            case "result":
                String displayText = event.getString("displayText");
                if (displayText != null && !displayText.isBlank()) {
                    out.println(displayText);
                }
                break;

            case "done":
                // 结束标记，通常不额外输出
                break;

            default:
                // 未知事件类型静默忽略
                break;
        }
    }

    @Override
    public void close() {
        if (reasoningOpen) {
            out.println();
            reasoningOpen = false;
        }
        out.flush();
    }
}
