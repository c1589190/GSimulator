package com.gsim.agent;

import java.util.Map;

/**
 * Agent 进度事件，通过 AgentProgressSink 发送到 CLI / 日志侧通道。
 * 不写入 BranchMessageStore，不进入 LLM messages。
 */
public record AgentProgressEvent(
        String phase,
        int round,
        int maxRounds,
        String detail,
        Map<String, String> meta
) {
    // ---- phase 常量 ----
    public static final String CONTEXT_LOADED = "CONTEXT_LOADED";
    public static final String WAITING_LLM = "WAITING_LLM";
    public static final String TOOL_SELECTED = "TOOL_SELECTED";
    public static final String TOOL_EXECUTING = "TOOL_EXECUTING";
    public static final String TOOL_SUCCESS = "TOOL_SUCCESS";
    public static final String TOOL_FAILED = "TOOL_FAILED";
    public static final String AWAITING_FINISH_ACTION = "AWAITING_FINISH_ACTION";
    public static final String PLAIN_ANSWER_WITHOUT_FINISH = "PLAIN_ANSWER_WITHOUT_FINISH";
    public static final String INVALID_BRACKET_INTENT = "INVALID_BRACKET_INTENT";
    public static final String FINISH_ACTION_REJECTED = "FINISH_ACTION_REJECTED";
    public static final String FINISH_ACTION_ACCEPTED = "FINISH_ACTION_ACCEPTED";
    public static final String AGENT_PUBLIC_MESSAGE = "AGENT_PUBLIC_MESSAGE";
    public static final String ABORTED = "ABORTED";

    // ---- LLM 流式阶段 ----
    public static final String LLM_STREAM_STARTED = "LLM_STREAM_STARTED";
    public static final String LLM_CONTENT_DELTA = "LLM_CONTENT_DELTA";
    public static final String LLM_REASONING_DELTA = "LLM_REASONING_DELTA";
    public static final String LLM_STREAM_COMPLETED = "LLM_STREAM_COMPLETED";
    public static final String LLM_STREAM_FAILED = "LLM_STREAM_FAILED";

    // ---- factory methods ----

    public static AgentProgressEvent contextLoaded(int round, int maxRounds,
                                                    int requestChars, int toolCount) {
        return new AgentProgressEvent(CONTEXT_LOADED, round, maxRounds,
                "上下文加载完毕", Map.of(
                "requestChars", String.valueOf(requestChars),
                "toolCount", String.valueOf(toolCount)));
    }

    public static AgentProgressEvent waitingLlm(int round, int maxRounds) {
        return new AgentProgressEvent(WAITING_LLM, round, maxRounds,
                "正在等待 LLM 选择工具……", Map.of());
    }

    public static AgentProgressEvent toolSelected(int round, int maxRounds, String toolName) {
        return new AgentProgressEvent(TOOL_SELECTED, round, maxRounds,
                "LLM 选择工具：" + toolName, Map.of("tool", toolName));
    }

    public static AgentProgressEvent toolExecuting(int round, int maxRounds, String toolName) {
        return new AgentProgressEvent(TOOL_EXECUTING, round, maxRounds,
                "正在执行工具：" + toolName, Map.of("tool", toolName));
    }

    public static AgentProgressEvent toolSuccess(int round, int maxRounds, String toolName) {
        return new AgentProgressEvent(TOOL_SUCCESS, round, maxRounds,
                "工具成功：" + toolName, Map.of("tool", toolName));
    }

    public static AgentProgressEvent toolFailed(int round, int maxRounds,
                                                 String toolName, String error) {
        return new AgentProgressEvent(TOOL_FAILED, round, maxRounds,
                "工具失败：" + toolName, Map.of("tool", toolName, "error", error != null ? error : ""));
    }

    public static AgentProgressEvent awaitingFinishAction(int round, int maxRounds) {
        return new AgentProgressEvent(AWAITING_FINISH_ACTION, round, maxRounds,
                "正在整理工具结果并调用 finish_action……", Map.of());
    }

    public static AgentProgressEvent plainAnswerWithoutFinish(int round, int maxRounds) {
        return new AgentProgressEvent(PLAIN_ANSWER_WITHOUT_FINISH, round, maxRounds,
                "模型生成了普通答复，但没有调用 finish_action，正在要求其改用 finish_action 结束。",
                Map.of());
    }

    public static AgentProgressEvent invalidBracketIntent(int round, int maxRounds) {
        return new AgentProgressEvent(INVALID_BRACKET_INTENT, round, maxRounds,
                "模型试图使用非法文本工具调用格式，已打回重写。",
                Map.of());
    }

    public static AgentProgressEvent finishRejected(int round, int maxRounds,
                                                     String rejectReason) {
        return new AgentProgressEvent(FINISH_ACTION_REJECTED, round, maxRounds,
                "finish_action 被拒绝：" + rejectReason + "，正在要求模型重写最终回复。",
                Map.of("rejectReason", rejectReason != null ? rejectReason : ""));
    }

    public static AgentProgressEvent finishAccepted(int round, int maxRounds) {
        return new AgentProgressEvent(FINISH_ACTION_ACCEPTED, round, maxRounds,
                "finish_action 通过，本轮结束。", Map.of());
    }

    public static AgentProgressEvent publicMessage(String message) {
        return new AgentProgressEvent(AGENT_PUBLIC_MESSAGE, 0, 0,
                message,
                Map.of("source", "console_print",
                        "chars", String.valueOf(message != null ? message.length() : 0)));
    }

    public static AgentProgressEvent aborted(int round, int maxRounds, String reason) {
        return new AgentProgressEvent(ABORTED, round, maxRounds,
                "中止：" + reason, Map.of("reason", reason != null ? reason : ""));
    }

    // ---- LLM 流式 factory methods ----

    public static AgentProgressEvent llmStreamStarted() {
        return new AgentProgressEvent(LLM_STREAM_STARTED, 0, 0,
                "LLM 流式输出开始", Map.of());
    }

    public static AgentProgressEvent llmContentDelta(String delta) {
        return new AgentProgressEvent(LLM_CONTENT_DELTA, 0, 0,
                delta,
                Map.of("channel", "content",
                        "chars", String.valueOf(delta != null ? delta.length() : 0)));
    }

    public static AgentProgressEvent llmReasoningDelta(String delta) {
        return new AgentProgressEvent(LLM_REASONING_DELTA, 0, 0,
                delta,
                Map.of("channel", "reasoning",
                        "chars", String.valueOf(delta != null ? delta.length() : 0)));
    }

    public static AgentProgressEvent llmStreamCompleted() {
        return new AgentProgressEvent(LLM_STREAM_COMPLETED, 0, 0,
                "LLM 流式输出完成", Map.of());
    }

    public static AgentProgressEvent llmStreamFailed(String error) {
        return new AgentProgressEvent(LLM_STREAM_FAILED, 0, 0,
                "LLM 流式输出失败：" + (error != null ? error : "未知错误"),
                Map.of("error", error != null ? error : ""));
    }
}
