package com.gsim.agent;

import java.io.PrintStream;

/**
 * CLI 模式 AgentProgressSink 实现。
 * 格式化 AgentProgressEvent 为简短状态行打印到 System.out。
 * LLM 流式内容直接 inline 打印到控制台（不换行，打字机效果）。
 */
public class CliAgentProgressSink implements AgentProgressSink {

    private final PrintStream out;
    private final boolean enabled;
    private boolean reasoningOpen = false;
    private boolean contentBold = false;

    private static final String ANSI_GREY = "\033[90m";
    private static final String ANSI_BOLD = "\033[1m";
    private static final String ANSI_RESET = "\033[0m";

    public CliAgentProgressSink(PrintStream out) {
        this(out, true);
    }

    public CliAgentProgressSink(PrintStream out, boolean enabled) {
        this.out = out;
        this.enabled = enabled;
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        if (event == null) return;

        // SubAgent 事件折叠处理（不刷屏，只显示状态行）
        String subAgentId = event.meta().get("agentId");
        if (subAgentId != null && isSubAgentId(subAgentId)) {
            handleSubAgentEvent(event, subAgentId);
            return;
        }

        // LLM 流式事件直接 inline 打印
        if (handleStreamEvent(event)) return;

        if (!enabled) return;
        String line = format(event);
        if (line != null && !line.isBlank()) {
            out.println(line);
        }
    }

    /**
     * 处理 LLM 流式事件。返回 true 表示已处理。
     * content 以粗体高亮打印，reasoning 以灰色 Thinking: 前缀打印。
     */
    private boolean handleStreamEvent(AgentProgressEvent event) {
        return switch (event.phase()) {
            case AgentProgressEvent.LLM_STREAM_STARTED -> {
                reasoningOpen = false;
                contentBold = false;
                out.print("[...] ");
                out.flush();
                yield true;
            }
            case AgentProgressEvent.LLM_CONTENT_DELTA -> {
                if (reasoningOpen) {
                    out.print(ANSI_RESET + "\n");
                    out.flush();
                    reasoningOpen = false;
                }
                if (!contentBold) {
                    out.print(ANSI_BOLD);
                    contentBold = true;
                }
                String delta = event.detail();
                if (delta != null && !delta.isEmpty()) {
                    out.print(delta);
                    out.flush();
                }
                yield true;
            }
            case AgentProgressEvent.LLM_REASONING_DELTA -> {
                String delta = event.detail();
                if (delta != null && !delta.isEmpty()) {
                    if (!reasoningOpen) {
                        out.print(ANSI_GREY + "Thinking: ");
                        reasoningOpen = true;
                    }
                    out.print(delta);
                    out.flush();
                }
                yield true;
            }
            case AgentProgressEvent.LLM_TOOL_CALL_DELTA -> {
                yield true;
            }
            case AgentProgressEvent.LLM_STREAM_COMPLETED -> {
                if (reasoningOpen || contentBold) {
                    out.print(ANSI_RESET);
                    reasoningOpen = false;
                    contentBold = false;
                }
                out.println();
                yield true;
            }
            case AgentProgressEvent.LLM_STREAM_FAILED -> {
                if (reasoningOpen || contentBold) {
                    out.print(ANSI_RESET);
                    reasoningOpen = false;
                    contentBold = false;
                }
                out.println();
                String error = event.meta().getOrDefault("error", "未知错误");
                out.println("[Agent] LLM 流式输出失败: " + error);
                yield true;
            }
            default -> false;
        };
    }

    /** 格式化非流式事件为简短状态行。保证长度 ≤ 120 chars。 */
    static String format(AgentProgressEvent event) {
        if (event == null) return null;
        return switch (event.phase()) {
            case AgentProgressEvent.CONTEXT_LOADED -> {
                int chars = parseIntMeta(event.meta(), "requestChars");
                int tools = parseIntMeta(event.meta(), "toolCount");
                String activeBranch = event.meta().getOrDefault("activeBranch", "");
                String contextMode = event.meta().getOrDefault("contextMode", "");
                yield "[Agent] 上下文：activeBranch=" + activeBranch
                        + "，mode=" + contextMode
                        + "，requestChars=" + chars
                        + "，tools=" + tools + " 个";
            }
            case AgentProgressEvent.WAITING_LLM ->
                    "[Agent] 正在等待 LLM 选择工具……";
            case AgentProgressEvent.TOOL_SELECTED -> {
                String tool = event.meta().getOrDefault("tool", "");
                yield "[Agent] LLM 选择工具：" + tool;
            }
            case AgentProgressEvent.TOOL_EXECUTING -> {
                String tool = event.meta().getOrDefault("tool", "");
                yield "[Agent] 正在执行工具：" + tool;
            }
            case AgentProgressEvent.TOOL_SUCCESS -> {
                String tool = event.meta().getOrDefault("tool", "");
                yield "[Agent] 工具成功：" + tool;
            }
            case AgentProgressEvent.TOOL_FAILED -> {
                String tool = event.meta().getOrDefault("tool", "");
                String error = event.meta().getOrDefault("error", "");
                yield "[Agent] 工具失败：" + tool + (error.isBlank() ? "" : "，原因：" + error);
            }
            case AgentProgressEvent.AWAITING_TOOL_CONFIRMATION ->
                    "[Agent] 等待确认：" + event.detail();
            case AgentProgressEvent.AWAITING_FINISH_ACTION ->
                    "[Agent] 正在让 LLM 根据工具结果生成 finish_action……";
            case AgentProgressEvent.PLAIN_ANSWER_WITHOUT_FINISH ->
                    "[Agent] " + event.detail();
            case AgentProgressEvent.INVALID_BRACKET_INTENT ->
                    "[Agent] " + event.detail();
            case AgentProgressEvent.FINISH_ACTION_REJECTED ->
                    "[Agent] finish_action 被拒绝：" + reasonText(
                            event.meta().getOrDefault("rejectReason", ""));
            case AgentProgressEvent.FINISH_ACTION_ACCEPTED ->
                    null;
            case AgentProgressEvent.AGENT_PUBLIC_MESSAGE ->
                    event.detail();
            case AgentProgressEvent.ABORTED ->
                    "[Agent] " + event.detail();
            case AgentProgressEvent.LLM_STREAM_STARTED,
                 AgentProgressEvent.LLM_CONTENT_DELTA,
                 AgentProgressEvent.LLM_REASONING_DELTA,
                 AgentProgressEvent.LLM_TOOL_CALL_DELTA,
                 AgentProgressEvent.LLM_STREAM_COMPLETED,
                 AgentProgressEvent.LLM_STREAM_FAILED -> null;
            default -> null;
        };
    }

    static String reasonText(String reasonCode) {
        if (reasonCode == null) return "未知原因";
        return switch (reasonCode) {
            case "FINISH_ACTION_WITH_OTHER_TOOLS" ->
                    "与其他工具同轮混用，要求模型先单独执行工具。";
            case "CLAIM_WITHOUT_SUPPORTING_TOOL_RESULT" ->
                    "声称了未经真实工具执行支持的结果。";
            default -> reasonCode;
        };
    }

    private static int parseIntMeta(java.util.Map<String, String> meta, String key) {
        if (meta == null) return 0;
        try {
            return Integer.parseInt(meta.getOrDefault(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ══════════════════════════════════════════
    // SubAgent 折叠输出
    // ══════════════════════════════════════════

    /** 跟踪已输出过 "working" 的 SubAgent（避免重复打印）。 */
    private final java.util.Set<String> subAgentSeen = new java.util.HashSet<>();

    /** SubAgent ID 格式：type-number（如 sim-1, search-2）。 */
    private static boolean isSubAgentId(String agentId) {
        return agentId.matches("^(sim|search)-\\d+$");
    }

    /** 处理 SubAgent 事件 — 流式内容折叠为状态行，工具调用显示简短信息。 */
    private void handleSubAgentEvent(AgentProgressEvent event, String agentId) {
        switch (event.phase()) {
            case AgentProgressEvent.LLM_STREAM_STARTED -> {
                if (subAgentSeen.add(agentId)) {
                    out.println(ANSI_GREY + "[SubAgent " + agentId + "] working..." + ANSI_RESET);
                }
            }
            case AgentProgressEvent.LLM_STREAM_COMPLETED -> {
                subAgentSeen.remove(agentId);
                out.println(ANSI_GREY + "[SubAgent " + agentId + "] done" + ANSI_RESET);
            }
            case AgentProgressEvent.LLM_STREAM_FAILED -> {
                subAgentSeen.remove(agentId);
                String error = event.meta().getOrDefault("error", "unknown");
                out.println("[SubAgent " + agentId + "] failed: " + error);
            }
            case AgentProgressEvent.TOOL_SUCCESS -> {
                String tool = event.meta().getOrDefault("tool", "");
                out.println(ANSI_GREY + "[SubAgent " + agentId + "] tool: " + tool + ANSI_RESET);
            }
            case AgentProgressEvent.TOOL_FAILED -> {
                String tool = event.meta().getOrDefault("tool", "");
                String error = event.meta().getOrDefault("error", "");
                out.println("[SubAgent " + agentId + "] tool failed: " + tool
                        + (error.isBlank() ? "" : " — " + error));
            }
            // 所有其他事件（含 LLM_CONTENT_DELTA / LLM_REASONING_DELTA）静默丢弃
            default -> {}
        }
    }
}
