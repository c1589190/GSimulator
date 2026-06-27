package com.gsim.agent.core;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;
import com.gsim.agent.OrchestratorAgent.ToolCallRecord;
import com.gsim.agent.ParsedToolCall;
import com.gsim.agent.ToolCallExtractor;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * AbstractAgent — 统一 Agent 基类，所有 Agent（主/辅）共用同一个 ToolLoop。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>根据 {@link AgentConfig} 配置 ToolLoop 参数</li>
 *   <li>过滤工具列表（ToolFilterConfig）</li>
 *   <li>流式 LLM 调用 + 进度事件</li>
 *   <li>每轮完整记录到 {@link AgentRound}</li>
 *   <li>纯文本自动接受（不强制 finish_action）</li>
 * </ul>
 *
 * <h3>子类扩展点</h3>
 * OrchestratorAgent 通过覆盖以下方法增加：
 * <ul>
 *   <li>PermissionGate — 写入工具确认</li>
 *   <li>ToolGroupManager — 工具组按需激活</li>
 *   <li>Draft 缓存 — turn_settlement_save_last_response</li>
 * </ul>
 */
public class AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(AbstractAgent.class);

    protected String agentId;
    protected final AgentConfig config;
    protected final LlmManager llm;
    protected final ToolRegistry allTools;
    protected final String model;
    protected final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    /** Override for the system prompt injected via setSystemPrompt(). */
    protected String systemPromptOverride = null;

    /** Progress sink — may be replaced post-construction via AgentFactory.dispatch(). */
    protected AgentProgressSink progressSink;

    protected final List<AgentRound> rounds = new ArrayList<>();

    /** Write-through cache saver — called for every NEW message added during a run.
     *  System prompts and replayed prior messages are NOT passed to this sink. */
    private Consumer<LlmMessage> messageSaver;

    /** Set a write-through saver that persists each new message as it is added. */
    public void setMessageSaver(Consumer<LlmMessage> saver) {
        this.messageSaver = saver;
    }

    /**
     * Override the empty AgentConfig system prompt with a rendered template.
     * Used by OrchestratorAgent to inject FreeMarker-rendered context.
     */
    public void setSystemPrompt(String prompt) {
        this.systemPromptOverride = prompt;
    }

    /**
     * Replace the progress sink after construction.
     * Used by AgentFactory.dispatch() to inject a TaggedAgentProgressSink
     * with the correct instanceId + taskId/sessionId after the agent is built.
     */
    public void replaceProgressSink(AgentProgressSink newSink) {
        this.progressSink = newSink != null ? newSink : AgentProgressSink.NOOP;
    }

    /**
     * Override the agentId (used for logging). Called by AgentFactory.dispatch()
     * to set the instance ID (e.g. "sim-1") instead of the config type ("sim").
     */
    public void setAgentId(String id) {
        this.agentId = id;
    }

    public AbstractAgent(AgentConfig config, LlmManager llm, ToolRegistry allTools,
                         AgentProgressSink progressSink, String model) {
        this.config = config;
        this.agentId = config.agentId();
        this.llm = llm;
        this.allTools = allTools;
        this.progressSink = progressSink != null ? progressSink : AgentProgressSink.NOOP;
        this.model = model;
    }

    // ══════════════════════════════════════════
    // 公共 API
    // ══════════════════════════════════════════

    /** 执行 ToolLoop，返回完整结果（含每轮记录） */
    public AgentResult run(String userInput) {
        return run(userInput, List.of());
    }

    /** 执行 ToolLoop，注入历史消息作为对话上文。 */
    public AgentResult run(String userInput, List<LlmMessage> priorMessages) {
        rounds.clear();
        cancelRequested.set(false);
        try {
            return executeToolLoop(userInput, priorMessages);
        } catch (Exception e) {
            log.error("[{}] fatal: {}", agentId, e.getMessage(), e);
            return AgentResult.fail(agentId, e.getMessage());
        }
    }

    public void cancel() { cancelRequested.set(true); }
    public String agentId() { return agentId; }
    public AgentConfig config() { return config; }
    public List<AgentRound> rounds() { return List.copyOf(rounds); }

    /** 子类可覆盖以提供不同的最大轮数（如 OrchestratorAgent 使用注入值）。 */
    protected int effectiveMaxToolRounds() {
        return config.maxToolRounds();
    }

    /** 子类可覆盖：是否强制 finish_action（拒绝纯文本自动接受）。Orchestrator 应返回 true。 */
    protected boolean requireFinishAction() {
        return false;
    }

    // ══════════════════════════════════════════
    // ToolLoop
    // ══════════════════════════════════════════

    protected AgentResult executeToolLoop(String userInput, List<LlmMessage> priorMessages) {
        List<LlmMessage> messages = new ArrayList<>();
        List<ToolCallRecord> allToolCalls = new ArrayList<>();
        int maxRounds = effectiveMaxToolRounds();

        // system prompt — build from staticSystemPrompt + rendered template
        StringBuilder spBuilder = new StringBuilder();
        String staticSys = config.staticSystemPrompt();
        if (staticSys != null && !staticSys.isBlank()) {
            spBuilder.append(staticSys);
        }
        String templatePart = systemPromptOverride != null
                ? systemPromptOverride
                : config.effectiveSystemPromptTemplate();
        if (templatePart != null && !templatePart.isBlank()) {
            if (!spBuilder.isEmpty()) spBuilder.append("\n\n---\n\n");
            spBuilder.append(templatePart);
        }
        String sp = spBuilder.toString();
        if (!sp.isBlank()) {
            messages.add(LlmMessage.system(sp));  // system prompt is regenerated — never cached
        }

        // Replay prior conversation from cache (skip stale system messages)
        if (priorMessages != null) {
            for (LlmMessage m : priorMessages) {
                if (!"system".equals(m.role())) {
                    messages.add(m);
                }
            }
        }

        // user prompt (write-through to cache)
        if (userInput != null && !userInput.isBlank()) {
            addMessage(messages, LlmMessage.user(userInput));
        }

        // filtered tool defs
        List<ToolDef> toolDefs = buildFilteredToolDefs();

        int toolRound = 1;
        int consecutiveNoTool = 0;
        String finalText = null;

        while (toolRound <= maxRounds) {
            if (cancelRequested.get()) {
                log.info("[{}] cancelled by user at round {}/{}",
                        agentId, toolRound, maxRounds);
                return AgentResult.fail(agentId, "cancelled");
            }

            log.debug("[{}] round {}/{} tools={}", agentId, toolRound, maxRounds, toolDefs.size());

            LlmRequest request = new LlmRequest(model, new ArrayList<>(messages),
                    config.temperature(), config.maxTokens(), toolDefs);
            LlmResult response = callLlm(request);

            if (!response.success()) {
                if (response.isContextLengthExceeded()) {
                    return AgentResult.fail(agentId, "上下文窗口不足: " + response.errorMessage());
                }
                return AgentResult.fail(agentId, "LLM 调用失败: " + response.errorMessage());
            }

            String content = response.content();

            // parse tool calls — 优先 API 原生 tool_calls，fallback 文本提取
            List<ParsedToolCall> allParsed = new ArrayList<>();
            List<LlmToolCall> nativeToolCalls = null;  // 保留引用以构造 tool 消息
            if (response.hasApiToolCalls()) {
                nativeToolCalls = response.toolCalls();
                for (LlmToolCall tc : nativeToolCalls) {
                    allParsed.add(new ParsedToolCall(tc.name(),
                            tc.arguments() != null ? tc.arguments() : Map.of()));
                }
            }
            if (allParsed.isEmpty()) {
                ParsedToolCall parsed = ToolCallExtractor.extractFirstToolCall(content);
                if (parsed != null) allParsed.add(parsed);
            }

            // 记录 assistant 消息（含 tool_calls 如有 API 原生调用）
            if (nativeToolCalls != null && !nativeToolCalls.isEmpty()) {
                addMessage(messages, LlmMessage.assistantWithToolCalls(
                        content != null ? content : "", nativeToolCalls));
            } else {
                addMessage(messages, LlmMessage.assistant(content != null ? content : ""));
            }

            // ═══ 有工具调用 ═══
            if (!allParsed.isEmpty()) {
                consecutiveNoTool = 0;
                List<ToolCallRecord> roundToolCalls = new ArrayList<>();
                boolean finishSeen = false;
                String finishMsg = null;

                for (int i = 0; i < allParsed.size(); i++) {
                    ParsedToolCall parsed = allParsed.get(i);
                    // 子类钩子：执行前检查
                    if (!beforeToolExecute(parsed, messages)) continue;

                    // 通知前端工具已选中（SessionPoolBridge 创建 TOOL_CALL 节点）
                    progressSink.onProgress(AgentProgressEvent.toolSelected(
                            toolRound, maxRounds, parsed.tool()));

                    ToolCall call = new ToolCall(parsed.tool(), parsed.args());
                    ToolResult result = allTools.call(call);
                    roundToolCalls.add(new ToolCallRecord(parsed.tool(), parsed.args(), result));

                    // 进度事件
                    progressSink.onProgress(result.success()
                            ? AgentProgressEvent.toolSuccess(toolRound, maxRounds, parsed.tool())
                            : AgentProgressEvent.toolFailed(toolRound, maxRounds, parsed.tool(),
                                    result.error()));

                    String feedback = buildToolFeedback(parsed.tool(), result);
                    // 如果有 API 原生 tool_call，使用 toolWithId 保留 tool_call_id
                    if (nativeToolCalls != null && i < nativeToolCalls.size()) {
                        LlmToolCall ntc = nativeToolCalls.get(i);
                        addMessage(messages, LlmMessage.toolWithId(ntc.id(), ntc.name(), feedback));
                    } else {
                        addMessage(messages, LlmMessage.tool(feedback));
                    }

                    // 子类钩子：执行后处理
                    afterToolExecute(parsed, result);

                    if ("finish_action".equals(parsed.tool()) && result.success()) {
                        finishSeen = true;
                        for (ToolResult.Item item : result.items()) {
                            if (!"finish_action_summary".equals(item.title())) {
                                finishMsg = item.snippet();
                                break;
                            }
                        }
                    }
                }

                allToolCalls.addAll(roundToolCalls);
                rounds.add(new AgentRound(toolRound, List.copyOf(messages),
                        List.copyOf(roundToolCalls), finishMsg, ""));

                if (finishSeen && finishMsg != null) {
                    String validationError = validateFinishMessage(finishMsg);
                    if (validationError != null) {
                        addMessage(messages, LlmMessage.user(validationError));
                        toolRound++;
                        continue;
                    }
                    finalText = finishMsg;
                    log.info("[{}] completed via finish_action at round {}/{} ({} tool calls)",
                            agentId, toolRound, maxRounds, allToolCalls.size());
                    break;
                }

                toolRound++;
                continue;
            }

            // ═══ 无工具调用：纯文本直接接受 ═══
            consecutiveNoTool++;
            if (content != null && !content.isBlank()
                    && isMeaningful(content)) {
                if (requireFinishAction()) {
                    // Orchestrator: reject plain text, demand tool call or finish_action
                    addMessage(messages, LlmMessage.user(
                            "请调用工具执行操作，完成后调用 finish_action 结束。"
                            + "不要只描述你将要做什么——请实际执行。"));
                    toolRound++;
                    continue;
                }
                finalText = content;
                rounds.add(new AgentRound(toolRound, List.copyOf(messages),
                        List.of(), finalText, ""));
                log.info("[{}] completed with plain text at round {}/{} ({} chars)",
                        agentId, toolRound, maxRounds,
                        finalText != null ? finalText.length() : 0);
                break;
            }

            if (consecutiveNoTool >= 3) {
                log.warn("[{}] aborted: {} consecutive rounds with no output",
                        agentId, consecutiveNoTool);
                return AgentResult.fail(agentId,
                        "Agent 连续 " + consecutiveNoTool + " 轮无有效输出");
            }

            addMessage(messages, LlmMessage.user("回复内容为空。如需操作请调用工具，已完成请调用 finish_action。"));
            toolRound++;
        }

        // max rounds reached
        if (finalText == null) {
            log.warn("[{}] aborted: max rounds ({}) reached without finish_action",
                    agentId, maxRounds);
            return AgentResult.fail(agentId,
                    "Agent 达到最大轮数 (" + maxRounds + ") 未完成");
        }

        return AgentResult.ok(agentId, finalText, List.copyOf(rounds), allToolCalls.size());
    }

    /** Add a message to the list and write-through to cache saver if configured. */
    private void addMessage(List<LlmMessage> messages, LlmMessage msg) {
        messages.add(msg);
        if (messageSaver != null) {
            messageSaver.accept(msg);
        }
    }

    // ══════════════════════════════════════════
    // 子类钩子（OrchestratorAgent 覆盖）
    // ══════════════════════════════════════════

    /** 工具执行前检查。返回 false 跳过该工具。 */
    protected boolean beforeToolExecute(ParsedToolCall parsed, List<LlmMessage> messages) {
        return true;
    }

    /** 工具执行后处理（draft 缓存等）。 */
    protected void afterToolExecute(ParsedToolCall parsed, ToolResult result) {
    }

    /** finish_action message 额外验证。 */
    protected String validateFinishMessage(String message) {
        return validateFinishActionMessage(message);
    }

    // ══════════════════════════════════════════
    // LLM 调用
    // ══════════════════════════════════════════

    protected LlmResult callLlm(LlmRequest request) {
        LlmCall call = llm.submit(request);
        StreamPool pool = call.pool();

        progressSink.onProgress(AgentProgressEvent.llmStreamStarted(agentId));

        String lastContent = "";
        try {
            while (!pool.isComplete()) {
                String c = pool.getContent();
                if (!c.equals(lastContent)) {
                    String delta = c.substring(lastContent.length());
                    lastContent = c;
                    if (!delta.isEmpty()) {
                        progressSink.onProgress(AgentProgressEvent.llmContentDelta(agentId, delta));
                    }
                }
                Thread.sleep(50);
                if (cancelRequested.get()) return LlmResult.failure("cancelled");
            }
            String final_ = pool.getContent();
            if (!final_.equals(lastContent)) {
                String delta = final_.substring(lastContent.length());
                if (!delta.isEmpty())
                    progressSink.onProgress(AgentProgressEvent.llmContentDelta(agentId, delta));
            }
            LlmResult result = call.await(100);
            if (result.success()) {
                progressSink.onProgress(AgentProgressEvent.llmStreamCompleted(agentId));
            } else {
                progressSink.onProgress(AgentProgressEvent.llmStreamFailed(agentId,
                        result.errorMessage() != null ? result.errorMessage() : "unknown"));
            }
            return result;
        } catch (Exception e) {
            log.error("[{}] LLM error: {}", agentId, e.getMessage(), e);
            progressSink.onProgress(AgentProgressEvent.llmStreamFailed(agentId, e.getMessage()));
            return LlmResult.failure(e.getMessage());
        }
    }

    // ══════════════════════════════════════════
    // 工具过滤
    // ══════════════════════════════════════════

    protected List<ToolDef> buildFilteredToolDefs() {
        List<ToolDef> defs = new ArrayList<>();
        for (var tool : allTools.all().values()) {
            if (config.toolFilter().allows(tool.name())) {
                var params = tool.getParameters();
                defs.add(params != null
                        ? new ToolDef(tool.name(), tool.description(), params)
                        : new ToolDef(tool.name(), tool.description()));
            }
        }
        return defs;
    }

    // ══════════════════════════════════════════
    // 工具反馈
    // ══════════════════════════════════════════

    protected String buildToolFeedback(String toolName, ToolResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[TOOL_RESULT]\ntool: ").append(toolName)
                .append("\nsuccess: ").append(result.success()).append("\ncontent:\n");
        if (!result.success()) {
            sb.append("error: ").append(result.error()).append("\n");
        } else {
            for (int i = 0; i < result.items().size(); i++) {
                var item = result.items().get(i);
                sb.append("[").append(i + 1).append("] ").append(item.title()).append("\n");
                sb.append("    path: ").append(item.path()).append("\n");
                String snippet = item.snippet();
                if (snippet != null) {
                    if (snippet.length() > 500) snippet = snippet.substring(0, 500) + "...";
                    sb.append("    snippet: ").append(snippet).append("\n");
                }
            }
        }
        sb.append("[/TOOL_RESULT]\n\n请基于以上工具结果继续。已足够则调用 finish_action 结束。");
        return sb.toString();
    }

    // ══════════════════════════════════════════
    // 验证
    // ══════════════════════════════════════════

    protected static boolean isMeaningful(String text) {
        if (text == null) return false;
        String t = text.strip();
        if (t.isEmpty()) return false;
        return switch (t) {
            case "null", "NULL", "Null", "undefined", "UNDEFINED", "{}", "[]" -> false;
            default -> true;
        };
    }

    protected static String validateFinishActionMessage(String message) {
        if (message == null || message.isBlank()) return "finish_action message 不能为空";
        if (message.contains("[工具调用已执行]"))
            return "finish_action.message 包含 [工具调用已执行] 占位符，请用自然语言重写。";
        if (message.contains("[工具结果]") || message.contains("[TOOL_RESULT]"))
            return "finish_action.message 包含伪造的工具结果标记，请移除。";
        if (message.contains("```json") || (message.contains("```") && message.contains("\"tool\"")))
            return "finish_action.message 包含 fenced JSON tool call，请用自然语言重写。";
        return null;
    }
}
