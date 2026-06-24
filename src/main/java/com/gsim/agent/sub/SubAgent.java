package com.gsim.agent.sub;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.ParsedToolCall;
import com.gsim.agent.ToolCallExtractor;
import com.gsim.agent.ToolCategory;
import com.gsim.agent.ToolCategoryRegistry;
import com.gsim.llm.LlmCall;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmMessage;
import com.gsim.llm.LlmRequest;
import com.gsim.llm.LlmResult;
import com.gsim.llm.LlmToolCall;
import com.gsim.llm.StreamPool;
import com.gsim.llm.ToolDef;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SubAgent — 子代理抽象基类。
 *
 * <p>每个 SubAgent 拥有独立的 ToolLoop 上下文和 StreamPool，
 * 只允许使用 READ_ONLY + CONTROL 工具。
 * 通过 {@link #run()} 在 Virtual Thread 中执行，
 * 结果通过 {@link #future()} 获取。
 *
 * <h3>与 OrchestratorAgent 的关键差异</h3>
 * <ul>
 *   <li>无 ToolGroupManager — 所有 READ_ONLY 工具始终可用</li>
 *   <li>无 PermissionGate — 工具已预过滤为安全集</li>
 *   <li>无 ToolRoutePolicy / ToolExecutionPolicy</li>
 *   <li>无 draft 缓存 / settlement 集成</li>
 *   <li>maxToolRounds 默认 16（OrchAgent 为 32）</li>
 *   <li>流式 streamId = agentId（前端按 agentId 路由）</li>
 * </ul>
 */
public abstract class SubAgent implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    protected final String agentId;
    protected final LlmManager llmManager;
    protected final ToolRegistry toolRegistry;
    protected final String model;
    protected final AgentProgressSink progressSink;
    protected final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    protected int maxToolRounds = 16;
    protected boolean streamEnabled = true;

    private final CompletableFuture<SubAgentResult> resultFuture = new CompletableFuture<>();

    protected SubAgent(String agentId, LlmManager llmManager, ToolRegistry toolRegistry,
                       String model, AgentProgressSink progressSink) {
        this.agentId = agentId;
        this.llmManager = llmManager;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.progressSink = progressSink;
    }

    // ---- subclass contract ----

    /** 构建 system prompt。由子类实现，通常从 PromptResourceManager 加载。 */
    protected abstract String buildSystemPrompt();

    /** 构建 user prompt。由子类实现，包含具体的任务指令。 */
    protected abstract String buildUserPrompt();

    // ---- Runnable entry ----

    @Override
    public void run() {
        try {
            log.info("[SubAgent:{}] started", agentId);
            progressSink.onProgress(AgentProgressEvent.llmStreamStarted(agentId));

            SubAgentResult result = executeToolLoop();

            if (result.success()) {
                progressSink.onProgress(AgentProgressEvent.llmStreamCompleted(agentId));
            } else {
                progressSink.onProgress(AgentProgressEvent.llmStreamFailed(agentId,
                        result.error() != null ? result.error() : "unknown"));
            }
            resultFuture.complete(result);
            log.info("[SubAgent:{}] completed, success={}", agentId, result.success());
        } catch (Exception e) {
            log.error("[SubAgent:{}] fatal error: {}", agentId, e.getMessage(), e);
            progressSink.onProgress(AgentProgressEvent.llmStreamFailed(agentId, e.getMessage()));
            resultFuture.complete(SubAgentResult.fail(agentId, e.getMessage()));
        }
    }

    // ---- core tool loop ----

    private SubAgentResult executeToolLoop() {
        List<LlmMessage> messages = new ArrayList<>();
        List<ToolDef> toolDefs = buildReadOnlyToolDefs();

        // 1. system prompt
        messages.add(LlmMessage.system(buildSystemPrompt()));

        // 2. user prompt
        messages.add(LlmMessage.user(buildUserPrompt()));

        String finalText = null;
        int toolRound = 1;
        int consecutiveNoToolRounds = 0;

        while (toolRound <= maxToolRounds) {
            // ESC cancel check
            if (cancelRequested.get()) {
                log.info("[SubAgent:{}] cancelled at round {}", agentId, toolRound);
                return SubAgentResult.fail(agentId, "cancelled");
            }

            log.debug("[SubAgent:{}] round {}/{} requestTools={}",
                    agentId, toolRound, maxToolRounds, toolDefs.size());

            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048, toolDefs);
            LlmResult response = callLlm(request);

            if (!response.success()) {
                if (response.isContextLengthExceeded()) {
                    return SubAgentResult.fail(agentId,
                            "上下文窗口不足: " + response.errorMessage());
                }
                return SubAgentResult.fail(agentId,
                        "LLM 调用失败: " + response.errorMessage());
            }

            String content = response.content();
            messages.add(LlmMessage.assistant(content != null ? content : ""));

            // parse tool calls: API native first, then text fallback
            List<ParsedToolCall> allParsed = new ArrayList<>();
            if (response.hasApiToolCalls()) {
                allParsed.addAll(fromApiToolCalls(response.toolCalls()));
            }
            if (allParsed.isEmpty()) {
                ParsedToolCall parsed = ToolCallExtractor.extractFirstToolCall(content);
                if (parsed != null) allParsed.add(parsed);
            }

            if (!allParsed.isEmpty()) {
                consecutiveNoToolRounds = 0;
                boolean finishActionSeen = false;
                String finishMessage = null;

                for (ParsedToolCall parsed : allParsed) {
                    log.debug("[SubAgent:{}] tool call: {}", agentId, parsed.tool());

                    // execute tool
                    ToolCall call = new ToolCall(parsed.tool(), parsed.args());
                    ToolResult result = toolRegistry.call(call);

                    // feedback to LLM
                    String toolFeedback = buildToolResultFeedback(parsed.tool(), result);
                    messages.add(LlmMessage.user(toolFeedback));

                    // check for finish_action
                    if ("finish_action".equals(parsed.tool()) && result.success()) {
                        finishActionSeen = true;
                        for (ToolResult.Item item : result.items()) {
                            if (!"finish_action_summary".equals(item.title())) {
                                finishMessage = item.snippet();
                                break;
                            }
                        }
                    }
                }

                if (finishActionSeen && finishMessage != null) {
                    String validationError = validateFinishActionMessage(finishMessage);
                    if (validationError != null) {
                        messages.add(LlmMessage.user(validationError));
                        toolRound++;
                        continue;
                    }
                    finalText = finishMessage;
                    break;
                }

                toolRound++;
                continue;
            }

            // --- no tool calls ---
            consecutiveNoToolRounds++;

            // show plain text to user
            if (content != null && !content.isBlank()) {
                progressSink.onProgress(AgentProgressEvent.publicMessage(
                        "[" + agentId + "] " + content.strip()));
            }

            // after 3 consecutive no-tool rounds, abort
            if (consecutiveNoToolRounds >= 3) {
                return SubAgentResult.fail(agentId,
                        "SubAgent 连续 " + consecutiveNoToolRounds + " 轮未调用工具，已终止");
            }

            // remind to use finish_action
            String reminder = "你没有调用任何工具。如果你已完成任务，请调用 finish_action 结束本轮。"
                    + "如果还需要查询资料，请使用可用的只读工具继续搜索。";
            messages.add(LlmMessage.user(reminder));
            toolRound++;
        }

        // max rounds reached — try to use last plain text as result
        if (finalText == null) {
            messages.add(LlmMessage.user(
                    "已达到最大工具轮数。请基于以上所有查询结果，"
                    + "写一段完整的总结并调用 finish_action 结束。不要调用更多工具。"));
            LlmRequest finalReq = new LlmRequest(model, new ArrayList<>(messages), 0.3, 2048,
                    buildReadOnlyToolDefs());
            LlmResult finalResp = callLlm(finalReq);
            if (finalResp.success()) {
                String fc = finalResp.content();
                if (fc != null && !fc.isBlank()) {
                    // try to extract finish_action from final response
                    ParsedToolCall parsed = ToolCallExtractor.extractFirstToolCall(fc);
                    if (parsed != null && "finish_action".equals(parsed.tool())) {
                        ToolCall fcCall = new ToolCall(parsed.tool(), parsed.args());
                        ToolResult fcResult = toolRegistry.call(fcCall);
                        if (fcResult.success()) {
                            for (ToolResult.Item item : fcResult.items()) {
                                if (!"finish_action_summary".equals(item.title())) {
                                    finalText = item.snippet();
                                    break;
                                }
                            }
                        }
                    }
                    if (finalText == null) {
                        finalText = fc; // use raw content as fallback
                    }
                }
            }
            if (finalText == null) {
                return SubAgentResult.fail(agentId,
                        "SubAgent 达到最大工具轮数后未能生成总结");
            }
        }

        return SubAgentResult.ok(agentId, finalText);
    }

    // ---- LLM call ----

    private LlmResult callLlm(LlmRequest request) {
        if (!streamEnabled) {
            return llmManager.chat(request);
        }

        // streaming path
        LlmCall call = llmManager.submit(request);
        StreamPool pool = call.pool();
        String streamId = pool.streamId();

        String lastContent = "";
        try {
            while (!pool.isComplete()) {
                String content = pool.getContent();
                if (!content.equals(lastContent)) {
                    String delta = content.substring(lastContent.length());
                    lastContent = content;
                    if (!delta.isEmpty()) {
                        progressSink.onProgress(
                                AgentProgressEvent.llmContentDelta(streamId, delta));
                    }
                }
                Thread.sleep(50);

                if (cancelRequested.get()) {
                    log.info("[SubAgent:{}] LLM stream cancelled", agentId);
                    return LlmResult.failure("cancelled");
                }
            }

            // drain remaining
            String content = pool.getContent();
            if (!content.equals(lastContent)) {
                String delta = content.substring(lastContent.length());
                if (!delta.isEmpty()) {
                    progressSink.onProgress(
                            AgentProgressEvent.llmContentDelta(streamId, delta));
                }
            }

            LlmResult result = call.await(100);
            log.debug("[SubAgent:{}] LLM stream completed, contentChars={}",
                    agentId,
                    result.content() != null ? result.content().length() : 0);
            return result;
        } catch (Exception e) {
            log.error("[SubAgent:{}] LLM stream error: {}", agentId, e.getMessage(), e);
            return LlmResult.failure(e.getMessage());
        }
    }

    // ---- tool helpers ----

    /** 构建只包含 READ_ONLY + CONTROL 的工具定义列表。 */
    protected List<ToolDef> buildReadOnlyToolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        for (var tool : toolRegistry.all().values()) {
            String name = tool.name();
            if (ToolCategoryRegistry.isReadOnly(name)
                    || ToolCategoryRegistry.isControl(name)) {
                var params = tool.getParameters();
                defs.add(params != null
                        ? new ToolDef(name, tool.description(), params)
                        : new ToolDef(name, tool.description()));
            }
        }
        log.debug("[SubAgent:{}] read-only tool defs: {} tools (from {} total)",
                agentId, defs.size(), toolRegistry.all().size());
        return defs;
    }

    /** 格式化工具结果反馈给 LLM。 */
    static String buildToolResultFeedback(String toolName, ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TOOL_RESULT]\n");
        sb.append("tool: ").append(toolName).append("\n");
        sb.append("success: ").append(result.success()).append("\n");
        sb.append("content:\n");
        if (!result.success()) {
            sb.append("error: ").append(result.error()).append("\n");
        } else {
            for (int i = 0; i < result.items().size(); i++) {
                ToolResult.Item item = result.items().get(i);
                sb.append("[").append(i + 1).append("] ").append(item.title()).append("\n");
                sb.append("    path: ").append(item.path()).append("\n");
                String snippet = item.snippet();
                if (snippet != null) {
                    if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "...";
                    sb.append("    snippet: ").append(snippet).append("\n");
                }
            }
        }
        sb.append("[/TOOL_RESULT]\n\n");
        sb.append("请基于以上工具结果继续完成用户请求。如果已经足够，调用 finish_action 结束。");
        return sb.toString();
    }

    /** 验证 finish_action.message 不包含工具内部标记。 */
    static String validateFinishActionMessage(String message) {
        if (message == null || message.isBlank()) {
            return "finish_action message 不能为空";
        }
        if (message.contains("[工具调用已执行]")) {
            return "finish_action.message 包含 [工具调用已执行] 占位符，"
                    + "请用自然语言描述结果，重新调用 finish_action。";
        }
        if (message.contains("[工具结果]") || message.contains("[TOOL_RESULT]")) {
            return "finish_action.message 包含伪造的 [工具结果] / [TOOL_RESULT] 标记，"
                    + "请移除所有工具内部标记，只输出给用户的自然语言回复，重新调用 finish_action。";
        }
        if (message.contains("```json") || (message.contains("```") && message.contains("\"tool\""))) {
            return "finish_action.message 包含 fenced JSON tool call，"
                    + "请移除所有 ``` 代码块，用自然语言重写，重新调用 finish_action。";
        }
        return null;
    }

    /** 将 API tool_calls 转为 ParsedToolCall 列表。 */
    private static List<ParsedToolCall> fromApiToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();
        List<ParsedToolCall> result = new ArrayList<>();
        for (LlmToolCall tc : toolCalls) {
            result.add(new ParsedToolCall(tc.name(),
                    tc.arguments() != null ? tc.arguments() : Map.of()));
        }
        return result;
    }

    // ---- public API ----

    public String agentId() { return agentId; }
    public CompletableFuture<SubAgentResult> future() { return resultFuture; }
    public void cancel() { cancelRequested.set(true); }

    public void setMaxToolRounds(int rounds) { this.maxToolRounds = Math.max(1, rounds); }
    public void setStreamEnabled(boolean enabled) { this.streamEnabled = enabled; }
}
