package com.gsim.core.event;

import java.util.Map;

/**
 * AgentProgressEvent -- Agent 进度事件，通过 AgentProgressSink 发送到 CLI / 日志侧通道。
 *
 * <p>不写入 BranchMessageStore，不进入 LLM messages。
 * 包含多种阶段常量及对应的工厂方法。
 *
 * @param phase     事件阶段标识（使用本类的阶段常量）
 * @param round     当前轮次
 * @param maxRounds 最大轮次
 * @param detail    事件详情描述文本
 * @param meta      附加元数据键值对
 */
public record AgentProgressEvent(String phase, int round, int maxRounds, String detail, Map<String, String> meta) {
    // ---- phase 常量 ----
    public static final String CONTEXT_LOADED = "CONTEXT_LOADED";
    public static final String WAITING_LLM = "WAITING_LLM";
    public static final String TOOL_SELECTED = "TOOL_SELECTED";
    public static final String TOOL_EXECUTING = "TOOL_EXECUTING";
    public static final String TOOL_SUCCESS = "TOOL_SUCCESS";
    public static final String TOOL_FAILED = "TOOL_FAILED";
    public static final String AWAITING_TOOL_CONFIRMATION = "AWAITING_TOOL_CONFIRMATION";
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
    public static final String LLM_TOOL_CALL_DELTA = "LLM_TOOL_CALL_DELTA";
    public static final String LLM_STREAM_COMPLETED = "LLM_STREAM_COMPLETED";
    public static final String LLM_STREAM_FAILED = "LLM_STREAM_FAILED";

    // ---- factory methods ----

    /**
     * 创建"上下文加载完毕"事件。
     *
     * @param round        当前轮次
     * @param maxRounds    最大轮次
     * @param requestChars 请求字符数
     * @param toolCount    可用工具数量
     * @return 上下文加载完毕事件
     */
    public static AgentProgressEvent contextLoaded(int round, int maxRounds, int requestChars, int toolCount) {
        return new AgentProgressEvent(
                CONTEXT_LOADED,
                round,
                maxRounds,
                "上下文加载完毕",
                Map.of(
                        "requestChars", String.valueOf(requestChars),
                        "toolCount", String.valueOf(toolCount)));
    }

    /**
     * 创建"正在等待 LLM"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @return 等待 LLM 事件
     */
    public static AgentProgressEvent waitingLlm(int round, int maxRounds) {
        return new AgentProgressEvent(WAITING_LLM, round, maxRounds, "正在等待 LLM 选择工具……", Map.of());
    }

    /**
     * 创建"LLM 选择工具"事件（无参数摘要）。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @param toolName  工具名称
     * @return 工具选择事件
     */
    public static AgentProgressEvent toolSelected(int round, int maxRounds, String toolName) {
        return toolSelected(round, maxRounds, toolName, "");
    }

    /**
     * 创建"LLM 选择工具"事件（带参数摘要，供 CLI 展示）。
     *
     * @param round          当前轮次
     * @param maxRounds      最大轮次
     * @param toolName       工具名称
     * @param paramsSummary  参数摘要文本
     * @return 工具选择事件
     */
    public static AgentProgressEvent toolSelected(int round, int maxRounds, String toolName, String paramsSummary) {
        return new AgentProgressEvent(
                TOOL_SELECTED,
                round,
                maxRounds,
                "LLM 选择工具：" + toolName,
                Map.of("tool", toolName, "paramsSummary", paramsSummary != null ? paramsSummary : ""));
    }

    /**
     * 创建"工具执行中"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @param toolName  正在执行的工具名称
     * @return 工具执行事件
     */
    public static AgentProgressEvent toolExecuting(int round, int maxRounds, String toolName) {
        return new AgentProgressEvent(TOOL_EXECUTING, round, maxRounds, "正在执行工具：" + toolName, Map.of("tool", toolName));
    }

    /**
     * 创建"工具执行成功"事件（无结果摘要）。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @param toolName  成功的工具名称
     * @return 工具成功事件
     */
    public static AgentProgressEvent toolSuccess(int round, int maxRounds, String toolName) {
        return toolSuccess(round, maxRounds, toolName, "");
    }

    /**
     * 创建"工具执行成功"事件（带结果摘要，供 CLI 展示）。
     *
     * @param round          当前轮次
     * @param maxRounds      最大轮次
     * @param toolName       成功的工具名称
     * @param resultSummary  结果摘要文本
     * @return 工具成功事件
     */
    public static AgentProgressEvent toolSuccess(int round, int maxRounds, String toolName, String resultSummary) {
        return new AgentProgressEvent(
                TOOL_SUCCESS,
                round,
                maxRounds,
                "工具成功：" + toolName,
                Map.of("tool", toolName, "resultSummary", resultSummary != null ? resultSummary : ""));
    }

    /**
     * 创建"工具执行失败"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @param toolName  失败的工具名称
     * @param error     错误信息
     * @return 工具失败事件
     */
    public static AgentProgressEvent toolFailed(int round, int maxRounds, String toolName, String error) {
        return new AgentProgressEvent(
                TOOL_FAILED,
                round,
                maxRounds,
                "工具失败：" + toolName,
                Map.of("tool", toolName, "error", error != null ? error : ""));
    }

    /**
     * 创建"等待工具确认"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @param toolName  待确认的工具名称
     * @return 等待工具确认事件
     */
    public static AgentProgressEvent awaitingToolConfirmation(int round, int maxRounds, String toolName) {
        return new AgentProgressEvent(
                AWAITING_TOOL_CONFIRMATION,
                round,
                maxRounds,
                toolName,
                Map.of("tool", toolName != null ? toolName : ""));
    }

    /**
     * 创建"等待 finish_action"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @return 等待 finish_action 事件
     */
    public static AgentProgressEvent awaitingFinishAction(int round, int maxRounds) {
        return new AgentProgressEvent(
                AWAITING_FINISH_ACTION, round, maxRounds, "正在整理工具结果并调用 finish_action……", Map.of());
    }

    /**
     * 创建"模型未调用 finish_action"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @return 未调用 finish_action 事件
     */
    public static AgentProgressEvent plainAnswerWithoutFinish(int round, int maxRounds) {
        return new AgentProgressEvent(
                PLAIN_ANSWER_WITHOUT_FINISH,
                round,
                maxRounds,
                "模型生成了普通答复，但没有调用 finish_action，正在要求其改用 finish_action 结束。",
                Map.of());
    }

    /**
     * 创建"非法文本工具调用"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @return 非法工具调用事件
     */
    public static AgentProgressEvent invalidBracketIntent(int round, int maxRounds) {
        return new AgentProgressEvent(INVALID_BRACKET_INTENT, round, maxRounds, "模型试图使用非法文本工具调用格式，已打回重写。", Map.of());
    }

    /**
     * 创建"finish_action 被拒绝"事件。
     *
     * @param round        当前轮次
     * @param maxRounds    最大轮次
     * @param rejectReason 拒绝原因
     * @return finish_action 拒绝事件
     */
    public static AgentProgressEvent finishRejected(int round, int maxRounds, String rejectReason) {
        return new AgentProgressEvent(
                FINISH_ACTION_REJECTED,
                round,
                maxRounds,
                "finish_action 被拒绝：" + rejectReason + "，正在要求模型重写最终回复。",
                Map.of("rejectReason", rejectReason != null ? rejectReason : ""));
    }

    /**
     * 创建"finish_action 通过"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @return finish_action 通过事件
     */
    public static AgentProgressEvent finishAccepted(int round, int maxRounds) {
        return new AgentProgressEvent(FINISH_ACTION_ACCEPTED, round, maxRounds, "finish_action 通过，本轮结束。", Map.of());
    }

    /**
     * 创建"公开消息"事件。
     *
     * @param message 消息内容
     * @return 公开消息事件
     */
    public static AgentProgressEvent publicMessage(String message) {
        return new AgentProgressEvent(
                AGENT_PUBLIC_MESSAGE,
                0,
                0,
                message,
                Map.of("source", "console_print", "chars", String.valueOf(message != null ? message.length() : 0)));
    }

    /**
     * 推演内容（推文）事件 -- 前端渲染为推文卡片，CLI 回显黄字。
     *
     * @param title 推文标题
     * @param body  推文正文
     * @return 推演内容进度事件
     */
    public static AgentProgressEvent simulationContent(String title, String body) {
        String t = title != null ? title : "";
        String b = body != null ? body : "";
        String display = "# " + t + "\n\n" + b;
        return new AgentProgressEvent(
                AGENT_PUBLIC_MESSAGE,
                0,
                0,
                "\033[33m" + display + "\033[0m",
                Map.of(
                        "source",
                        "simulation_content",
                        "subType",
                        "simulation_content",
                        "title",
                        t,
                        "body",
                        b,
                        "chars",
                        String.valueOf(b.length())));
    }

    /**
     * 创建"Agent 中止"事件。
     *
     * @param round     当前轮次
     * @param maxRounds 最大轮次
     * @param reason    中止原因
     * @return 中止事件
     */
    public static AgentProgressEvent aborted(int round, int maxRounds, String reason) {
        return new AgentProgressEvent(
                ABORTED, round, maxRounds, "中止：" + reason, Map.of("reason", reason != null ? reason : ""));
    }

    // ---- LLM 流式 factory methods (all require streamId) ----

    /**
     * 创建"LLM 流式输出开始"事件。
     *
     * @param streamId 流 ID
     * @return LLM 流式开始事件
     */
    public static AgentProgressEvent llmStreamStarted(String streamId) {
        return new AgentProgressEvent(
                LLM_STREAM_STARTED, 0, 0, "LLM 流式输出开始", Map.of("streamId", streamId != null ? streamId : ""));
    }

    /**
     * 创建"LLM 内容增量"事件。
     *
     * @param streamId 流 ID
     * @param delta    内容增量文本
     * @return LLM 内容增量事件
     */
    public static AgentProgressEvent llmContentDelta(String streamId, String delta) {
        return new AgentProgressEvent(
                LLM_CONTENT_DELTA,
                0,
                0,
                delta,
                Map.of(
                        "streamId",
                        streamId != null ? streamId : "",
                        "channel",
                        "content",
                        "chars",
                        String.valueOf(delta != null ? delta.length() : 0)));
    }

    /**
     * 创建"LLM 推理增量"事件。
     *
     * @param streamId 流 ID
     * @param delta    推理增量文本
     * @return LLM 推理增量事件
     */
    public static AgentProgressEvent llmReasoningDelta(String streamId, String delta) {
        return new AgentProgressEvent(
                LLM_REASONING_DELTA,
                0,
                0,
                delta,
                Map.of(
                        "streamId",
                        streamId != null ? streamId : "",
                        "channel",
                        "reasoning",
                        "chars",
                        String.valueOf(delta != null ? delta.length() : 0)));
    }

    /**
     * 创建"LLM 工具调用标记"事件。
     *
     * @param streamId 流 ID
     * @return LLM 工具调用标记事件
     */
    public static AgentProgressEvent llmToolCallDelta(String streamId) {
        return new AgentProgressEvent(
                LLM_TOOL_CALL_DELTA,
                0,
                0,
                "",
                Map.of("streamId", streamId != null ? streamId : "", "channel", "tool_call"));
    }

    /**
     * 创建"LLM 流式输出完成"事件。
     *
     * @param streamId 流 ID
     * @return LLM 流式完成事件
     */
    public static AgentProgressEvent llmStreamCompleted(String streamId) {
        return new AgentProgressEvent(
                LLM_STREAM_COMPLETED, 0, 0, "LLM 流式输出完成", Map.of("streamId", streamId != null ? streamId : ""));
    }

    /**
     * 创建"LLM 流式输出失败"事件。
     *
     * @param streamId 流 ID
     * @param error    错误信息
     * @return LLM 流式失败事件
     */
    public static AgentProgressEvent llmStreamFailed(String streamId, String error) {
        return new AgentProgressEvent(
                LLM_STREAM_FAILED,
                0,
                0,
                "LLM 流式输出失败：" + (error != null ? error : "未知错误"),
                Map.of("streamId", streamId != null ? streamId : "", "error", error != null ? error : ""));
    }
}
