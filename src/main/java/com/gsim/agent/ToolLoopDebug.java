package com.gsim.agent;

import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ToolLoop DEBUG 日志 helper。
 * 所有方法只在 {@code log.isDebugEnabled()} 时执行，不产生额外 GC 压力。
 */
public final class ToolLoopDebug {

    private ToolLoopDebug() {}

    // ---- LLM response ----

    static void logLlmResult(Logger log, String loopName, int round,
                                String content, int apiToolCallCount, String finishReason) {
        if (!log.isDebugEnabled()) return;
        int len = content != null ? content.length() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP LLM_RESPONSE ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("apiToolCallCount=").append(apiToolCallCount).append('\n');
        sb.append("finishReason=").append(finishReason != null ? finishReason : "n/a").append('\n');
        sb.append("rawChars=").append(len).append('\n');
        sb.append("rawPreview:\n");
        if (content != null) {
            if (len > 2000) {
                sb.append(content, 0, 2000);
                sb.append("\n... [truncated, rawChars=").append(len).append(']');
            } else {
                sb.append(content);
            }
        }
        sb.append("\n=== TOOL_LOOP LLM_RESPONSE END ===");
        log.debug(sb.toString());
    }

    // ---- cleaned draft ----

    static void logCleanedDraft(Logger log, String loopName, int round, String draft) {
        if (!log.isDebugEnabled()) return;
        int len = draft != null ? draft.length() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP CLEANED_DRAFT ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("cleanedChars=").append(len).append('\n');
        sb.append("cleanedPreview:\n");
        if (draft != null) {
            if (len > 1500) {
                sb.append(draft, 0, 1500);
                sb.append("\n... [truncated, cleanedChars=").append(len).append(']');
            } else {
                sb.append(draft);
            }
        }
        sb.append("\n=== TOOL_LOOP CLEANED_DRAFT END ===");
        log.debug(sb.toString());
    }

    // ---- tool extraction ----

    enum ToolCallSource {
        API_TOOL_CALLS,
        TEXT_FALLBACK,
        INVALID_TOOL_INTENT,
        NONE
    }

    static void logToolExtraction(Logger log, String loopName, int round,
                                  List<OrchestratorAgent.ParsedToolCall> tools,
                                  int apiToolCallCount, int textFallbackToolCallCount,
                                  ToolCallSource source, String rawContent) {
        if (!log.isDebugEnabled()) return;
        boolean hasFinish = tools.stream().anyMatch(t -> "finish_action".equals(t.tool()));
        List<String> toolNames = tools.stream().map(t -> t.tool()).collect(Collectors.toList());

        boolean suspect = false;
        String suspectReason = null;
        if (tools.isEmpty() && rawContent != null) {
            String c = rawContent;
            if (c.contains("{\"tool\"") || c.contains("```json")
                    || c.contains("[工具结果]") || c.contains("[TOOL_RESULT]")
                    || c.contains("[工具调用已执行]")
                    || (c.contains("{branchId=") || c.contains("{mode=") || c.contains("{status="))) {
                suspect = true;
                suspectReason = "raw_tool_json_or_fake_tool_result_present";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP TOOL_EXTRACTION ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("apiToolCallCount=").append(apiToolCallCount).append('\n');
        sb.append("textFallbackToolCallCount=").append(textFallbackToolCallCount).append('\n');
        sb.append("toolCallSource=").append(source.name()).append('\n');
        sb.append("toolCallCount=").append(tools.size()).append('\n');
        sb.append("tools=").append(toolNames).append('\n');
        sb.append("containsFinishAction=").append(hasFinish);
        if (suspect) {
            sb.append('\n').append("suspectToolSyntax=").append(true);
            sb.append('\n').append("suspectReason=").append(suspectReason);
        }
        sb.append("\n=== TOOL_LOOP TOOL_EXTRACTION END ===");
        log.debug(sb.toString());
    }

    // ---- tool call ----

    static void logToolCall(Logger log, String loopName, int round, String toolName,
                             String argsPreview, String source) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP TOOL_CALL ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("source=").append(source != null ? source : "API_TOOL_CALLS").append('\n');
        sb.append("tool=").append(toolName).append('\n');
        sb.append("argsPreview=");
        if (argsPreview != null) {
            if (argsPreview.length() > 1000) {
                sb.append(argsPreview, 0, 1000).append("...");
            } else {
                sb.append(argsPreview);
            }
        }
        sb.append("\n=== TOOL_LOOP TOOL_CALL END ===");
        log.debug(sb.toString());
    }

    // ---- tool result ----

    static void logToolResult(Logger log, String loopName, int round,
                              String toolName, boolean success,
                              String errorCode, String message, String resultPreview) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP TOOL_RESULT ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("tool=").append(toolName).append('\n');
        sb.append("success=").append(success);
        if (!success) {
            if (errorCode != null) sb.append("\nerrorCode=").append(errorCode);
            if (message != null) sb.append("\nmessage=").append(message);
        }
        if (resultPreview != null) {
            sb.append("\nresultPreview=");
            if (resultPreview.length() > 1000) {
                sb.append(resultPreview, 0, 1000).append("...");
            } else {
                sb.append(resultPreview);
            }
        }
        sb.append("\n=== TOOL_LOOP TOOL_RESULT END ===");
        log.debug(sb.toString());
    }

    // ---- finish_action validation ----

    static void logFinishAction(Logger log, String loopName, int round,
                                String status, String messagePreview, int messageChars) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP FINISH_ACTION ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("status=").append(status).append('\n');
        sb.append("messageChars=").append(messageChars).append('\n');
        sb.append("messagePreview=");
        if (messagePreview != null) {
            if (messagePreview.length() > 1000) {
                sb.append(messagePreview, 0, 1000).append("...");
            } else {
                sb.append(messagePreview);
            }
        }
        sb.append("\n=== TOOL_LOOP FINISH_ACTION END ===");
        log.debug(sb.toString());
    }

    static void logFinishAccepted(Logger log, String loopName, int round, boolean accepted,
                                  String rejectReason, String claim, String requiredTool,
                                  Set<String> successTools) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP FINISH_ACTION VALIDATION ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("finishAccepted=").append(accepted);
        if (!accepted && rejectReason != null) {
            sb.append('\n').append("rejectReason=").append(rejectReason);
            if (claim != null) sb.append('\n').append("claim=").append(claim);
            if (requiredTool != null) sb.append('\n').append("requiredTool=").append(requiredTool);
            if (successTools != null) sb.append('\n').append("successTools=").append(successTools);
        }
        sb.append("\n=== TOOL_LOOP FINISH_ACTION VALIDATION END ===");
        log.debug(sb.toString());
    }

    // ---- no-tool round ----

    static void logNoToolRound(Logger log, String loopName, int round, int consecutiveNoToolRounds) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP NO_TOOL_ROUND ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("consecutiveNoToolRounds=").append(consecutiveNoToolRounds).append('\n');
        sb.append("action=append_finish_action_reminder_and_continue");
        sb.append("\n=== TOOL_LOOP NO_TOOL_ROUND END ===");
        log.debug(sb.toString());
    }

    static void logNoToolAbort(Logger log, String loopName, int round,
                               String reason, int consecutiveNoToolRounds) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP NO_TOOL_ABORT ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("reason=").append(reason).append('\n');
        sb.append("consecutiveNoToolRounds=").append(consecutiveNoToolRounds);
        sb.append("\n=== TOOL_LOOP NO_TOOL_ABORT END ===");
        log.debug(sb.toString());
    }

    // ---- invalid tool intent ----

    static void logInvalidToolIntent(Logger log, String loopName, int round,
                                      int consecutiveInvalid, String content) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP INVALID_TOOL_INTENT ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("consecutiveInvalid=").append(consecutiveInvalid).append('\n');
        sb.append("action=reprompt_with_correction").append('\n');
        sb.append("contentPreview=");
        if (content != null) {
            int max = Math.min(content.length(), 500);
            sb.append(content, 0, max);
        }
        sb.append("\n=== TOOL_LOOP INVALID_TOOL_INTENT END ===");
        log.debug(sb.toString());
    }

    // ---- finish_action mixed with other tools rejection ----

    static void logFinishActionWithOtherToolsRejected(Logger log, String loopName,
                                                      int round,
                                                      java.util.List<OrchestratorAgent.ParsedToolCall> allParsed) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP FINISH_ACTION ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("finishAccepted=false\n");
        sb.append("rejectReason=FINISH_ACTION_WITH_OTHER_TOOLS\n");
        sb.append("allTools=");
        sb.append(allParsed.stream()
                .map(OrchestratorAgent.ParsedToolCall::tool)
                .collect(java.util.stream.Collectors.joining(", ")));
        sb.append("\n=== TOOL_LOOP FINISH_ACTION END ===");
        log.debug(sb.toString());
    }

    // ---- finalText ----

    static void logFinalText(Logger log, String loopName, String source,
                             String finalText) {
        if (!log.isDebugEnabled()) return;
        int len = finalText != null ? finalText.length() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP FINAL_TEXT ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("source=").append(source).append('\n');
        sb.append("chars=").append(len).append('\n');
        sb.append("preview:\n");
        if (finalText != null) {
            if (len > 1500) {
                sb.append(finalText, 0, 1500);
                sb.append("\n... [truncated, chars=").append(len).append(']');
            } else {
                sb.append(finalText);
            }
        }
        sb.append("\n=== TOOL_LOOP FINAL_TEXT END ===");
        log.debug(sb.toString());
    }

    // ---- context load ----

    /**
     * 拆分 messages 和 tools schema 字符数，帮助解释总 request chars 来源。
     * toolsJsonChars 用 Jackson 序列化 tools[] 估算。
     */
    static void logContextLoad(Logger log, String loopName, int round,
                               java.util.List<com.gsim.llm.LlmMessage> messages,
                               java.util.List<com.gsim.llm.ToolDef> toolDefs,
                               AgentContextMeta contextMeta) {
        if (!log.isDebugEnabled()) return;
        int systemPromptChars = 0;
        int rootContextChars = 0;
        int branchContextChars = 0;
        int messageHistoryChars = 0;
        int toolResultChars = 0;
        int userInputChars = 0;
        int systemCount = 0;
        int userCount = 0;
        int assistantCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            int len = msg.content() != null ? msg.content().length() : 0;
            switch (msg.role()) {
                case "system" -> {
                    if (i == 0) {
                        systemPromptChars += len;
                    } else {
                        rootContextChars += len;
                    }
                    systemCount++;
                }
                case "user" -> {
                    if (i == messages.size() - 1) {
                        userInputChars = len;
                    } else {
                        boolean isToolResult = msg.content() != null
                                && (msg.content().startsWith("[工具结果]")
                                    || msg.content().startsWith("[TOOL_RESULT]"));
                        if (isToolResult) {
                            toolResultChars += len;
                        } else {
                            messageHistoryChars += len;
                        }
                    }
                    userCount++;
                }
                case "assistant" -> {
                    messageHistoryChars += len;
                    assistantCount++;
                }
            }
        }

        int messageChars = 0;
        for (var msg : messages) {
            if (msg.content() != null) messageChars += msg.content().length();
        }

        // 估算 tools[] JSON 大小
        int toolsJsonChars = estimateToolsJsonChars(toolDefs);

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP CONTEXT_LOAD ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("activeRoot=").append(contextMeta.activeRoot()).append('\n');
        sb.append("activeBranch=").append(contextMeta.activeBranch()).append('\n');
        sb.append("contextMode=").append(contextMeta.contextMode()).append('\n');
        sb.append("fullWorldContextLoaded=").append(contextMeta.fullWorldContextLoaded()).append('\n');
        sb.append("contextModeReason=").append(contextMeta.contextModeReason()).append('\n');
        sb.append("\nparts:\n");
        sb.append("- systemPrompt chars=").append(systemPromptChars).append('\n');
        sb.append("- rootContext chars=").append(rootContextChars).append('\n');
        sb.append("- branchContext chars=").append(0).append("  ;;; embedded in messages\n");
        sb.append("- parentBranchSummary chars=").append(0).append('\n');
        sb.append("- messageHistory chars=").append(messageHistoryChars).append('\n');
        sb.append("- toolResult chars=").append(toolResultChars).append('\n');
        sb.append("- userInput chars=").append(userInputChars).append('\n');
        sb.append("- toolSchemas chars=").append(toolsJsonChars).append('\n');
        sb.append("\n---\n");
        sb.append("messageCounts: system=").append(systemCount)
                .append(" user=").append(userCount)
                .append(" assistant=").append(assistantCount).append('\n');
        sb.append("messageChars=").append(messageChars).append('\n');
        sb.append("toolsJsonChars=").append(toolsJsonChars).append('\n');
        sb.append("estimatedRequestChars=").append(messageChars + toolsJsonChars).append('\n');
        sb.append("\nbranchPath=").append(contextMeta.branchPath()).append('\n');
        sb.append("currentBranchLoaded=").append(contextMeta.currentBranchLoaded()).append('\n');
        sb.append("parentBranchesLoaded=").append(contextMeta.loadedParentBranches()).append('\n');
        sb.append("tools=").append(toolDefs.stream()
                .map(com.gsim.llm.ToolDef::name).collect(java.util.stream.Collectors.toList())).append('\n');
        sb.append("=== TOOL_LOOP CONTEXT_LOAD END ===");
        log.debug(sb.toString());
    }

    /** 用 Jackson 估算 tools[] JSON 序列化后的大小。 */
    static int estimateToolsJsonChars(java.util.List<com.gsim.llm.ToolDef> toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) return 0;
        try {
            // 手工构造近似 JSON 以避 Jackson ObjectMapper 开销
            int total = 0;
            total += 2; // []
            for (int i = 0; i < toolDefs.size(); i++) {
                if (i > 0) total += 1; // comma
                var t = toolDefs.get(i);
                // {"type":"function","function":{"name":"...","description":"...","parameters":{...}}}
                total += 19 + 2 + 104; // boilerplate
                total += t.name().length();
                total += t.description() != null ? t.description().length() : 0;
                total += 50; // parameters: {"type":"object","properties":{},"additionalProperties":true}
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- task brief ----

    /**
     * 每轮开始前的任务简报，仅用于 debug 和 progress，
     * 不改变工具选择或上下文构造策略。
     */
    static void logTaskBrief(Logger log, String loopName, int round,
                              String userText, String lastToolName,
                              boolean lastToolSuccess, boolean hasToolResult,
                              java.util.List<String> expectedTools) {
        logTaskBrief(log, loopName, round, UserIntent.infer(userText),
                computeExpectedNextStep(hasToolResult, lastToolSuccess),
                lastToolName, lastToolSuccess, expectedTools);
    }

    /** 带 UserIntent / ExpectedNextStep 的重载版本。 */
    static void logTaskBrief(Logger log, String loopName, int round,
                              UserIntent userIntent,
                              ExpectedNextStep expectedNextStep,
                              String lastToolName,
                              boolean lastToolSuccess,
                              java.util.List<String> expectedTools) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_LOOP TASK_BRIEF ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("userIntent=").append(userIntent).append('\n');
        sb.append("expectedNextStep=").append(expectedNextStep).append('\n');
        sb.append("expectedTools=").append(expectedTools).append('\n');
        sb.append("finishRequired=true\n");
        sb.append("lastToolName=").append(lastToolName != null ? lastToolName : "none").append('\n');
        sb.append("lastToolSuccess=").append(lastToolSuccess).append('\n');
        sb.append("=== TOOL_LOOP TASK_BRIEF END ===");
        log.debug(sb.toString());
    }

    private static ExpectedNextStep computeExpectedNextStep(
            boolean hasToolResult, boolean lastToolSuccess) {
        if (!hasToolResult) {
            return ExpectedNextStep.CALL_TOOL;
        } else if (lastToolSuccess) {
            return ExpectedNextStep.FINISH_ACTION;
        } else {
            return ExpectedNextStep.CALL_TOOL;
        }
    }

    /** 从用户输入粗略推断意图，仅用于 debug 显示。 */
    static String inferUserIntent(String userText) {
        if (userText == null || userText.isBlank()) return "GENERAL";
        String s = userText;
        if (s.contains("玩家行动") || s.contains("行动记录") || s.contains("有没有行动")
                || s.contains("player action")) {
            return "PLAYER_ACTION_QUERY";
        }
        if (s.contains("搜索") || s.contains("查找") || s.contains("知识") || s.contains("查询")) {
            return "KNOWLEDGE_SEARCH";
        }
        if (s.contains("推演") || s.contains("sim") || s.contains("结算") || s.contains("下一回合")) {
            return "WORLD_SIM";
        }
        if (s.contains("状态") || s.contains("status") || s.contains("当前")) {
            return "STATUS_CHECK";
        }
        return "GENERAL";
    }

    /**
     * 生成 CLI 可显示的简短任务描述行。
     */
    static String buildCliTaskLine(String userText, String activeBranch) {
        String intent = inferUserIntent(userText);
        String desc = switch (intent) {
            case "PLAYER_ACTION_QUERY" -> "查询玩家行动记录";
            case "KNOWLEDGE_SEARCH" -> "搜索知识库";
            case "WORLD_SIM" -> "世界推演";
            case "STATUS_CHECK" -> "查看状态";
            default -> "处理用户请求";
        };
        return "[Agent] 当前任务：" + desc + "；activeBranch=" + activeBranch;
    }

    // ---- finish intent detection ----

    enum FinishIntent {
        NONE,
        PLAIN_ANSWER_WITHOUT_FINISH,
        INVALID_BRACKET_TOOL_INTENT,
        FINISH_ACTION_REJECTED
    }

    /**
     * 检测「模型想结束但没合法结束」的 3 种情况。
     * 不做复杂语义判断，纯信号检测。
     */
    static FinishIntent detectFinishIntent(int apiToolCallCount, int textFallbackCount,
                                            boolean invalidToolIntent, String cleanedDraft,
                                            boolean finishActionRejected) {
        if (finishActionRejected) {
            return FinishIntent.FINISH_ACTION_REJECTED;
        }
        if (invalidToolIntent) {
            return FinishIntent.INVALID_BRACKET_TOOL_INTENT;
        }
        // 情况 A：无 tool call + cleanedDraft 非空 + 非 invalid intent → 普通语言结束
        if (apiToolCallCount == 0 && textFallbackCount == 0
                && cleanedDraft != null && !cleanedDraft.isBlank()) {
            return FinishIntent.PLAIN_ANSWER_WITHOUT_FINISH;
        }
        return FinishIntent.NONE;
    }

    // ---- player action intent ----

    static boolean isPlayerActionQuery(String userInput) {
        if (userInput == null) return false;
        String s = userInput;
        return s.contains("玩家行动") || s.contains("行动记录")
                || s.contains("当前回合行动") || s.contains("有没有行动")
                || s.contains("player action");
    }

    static String buildNoToolReminder(String userInput) {
        String base = "你没有调用任何工具，也没有调用 finish_action。\n\n"
                + "你的纯文本回复已展示给用户。\n\n"
                + "规则：\n"
                + "1. 如果任务已经可以直接回答，请调用 finish_action，并把完整最终回复放入 message，"
                + "在 summary 中简要总结你做了什么。\n"
                + "2. 如果任务需要查询/写入/保存/切换，请先调用 activate_tool_groups 激活所需的工具组，"
                + "再调用对应业务工具。\n"
                + "3. 不要直接用普通自然语言结束而不调用 finish_action。\n"
                + "4. 不要输出 [工具调用已执行]、[工具结果] 或 raw JSON。\n"
                + "5. 禁止使用\"以上\"\"如上\"\"刚才\"\"已生成\"\"前文\"等引用不可见内容的表达。";

        if (isPlayerActionQuery(userInput)) {
            base += "\n\n用户正在询问玩家行动记录。"
                    + "请调用 finish_action 返回结果。";
        }

        return base;
    }

    static String noToolAbortError(int consecutiveNoToolRounds) {
        return "[错误] Agent 连续 " + consecutiveNoToolRounds
                + " 轮没有调用任何工具，也没有调用 finish_action，"
                + "本轮无法完成。请检查 ToolLoop prompt 或模型输出。详情见 DEBUG 日志。";
    }

    // ---- invalid tool intent ----

    /**
     * 检测 content 中是否有明显工具意图但格式非法。
     * 包括：[调用 xxx]、[工具结果]、{key=value}、裸 {"tool": 等文本。
     */
    static boolean isInvalidToolIntent(String content) {
        if (content == null || content.isBlank()) return false;
        // [调用 toolName] — Chinese bracket invoke
        if (ToolCallExtractor.hasBracketInvokeSyntax(content)) return true;
        // [工具结果] / [TOOL_RESULT]
        if (content.contains("[工具结果]") || content.contains("[TOOL_RESULT]")) return true;
        // [工具调用已执行]
        if (content.contains("[工具调用已执行]")) return true;
        // {key=value} pattern (non-JSON map format)
        if (content.contains("{branchId=") || content.contains("{mode=")
                || content.contains("{status=")) return true;
        // 包含 "调用 xxx 工具" 等口头工具意图
        if (content.contains("调用 ") && (content.contains("工具") || content.contains(" player_action")
                || content.contains(" knowledge_") || content.contains(" finish_action")
                || content.contains(" branch_"))) return true;
        return false;
    }

    /** 非法工具意图的回撤提示。 */
    static String buildInvalidToolIntentReprompt() {
        return "你输出了非法工具调用文本。请使用 API tool_calls，或输出合法 fallback JSON：\n"
                + "{\"tool\":\"工具名\",\"args\":{...}}\n\n"
                + "不得输出以下格式：\n"
                + "- [调用 工具名] {...}\n"
                + "- [工具结果] {...}\n"
                + "- {key=value}\n"
                + "- 调用 xxx 工具\n\n"
                + "如果你需要调用工具，请使用标准格式。如果需要结束，请调用 finish_action。";
    }

    /** 连续非法工具意图错误消息。 */
    static String invalidToolIntentAbortError(int consecutive) {
        return "[错误] Agent 连续 " + consecutive
                + " 次输出非法工具调用文本，未能生成可执行 tool_calls。"
                + "请检查 ToolLoop prompt 或模型输出。";
    }

    // ===== 工具路由 / 执行策略 / 确认决策 =====

    /** 记录本轮工具路由决策。 */
    static void logToolRouteDecision(Logger log, String loopName, int round,
                                     UserIntent userIntent,
                                     ExpectedNextStep expectedNextStep,
                                     ToolRouteDecision route,
                                     boolean allowAllMutations) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_ROUTE_DECISION ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("userIntent=").append(userIntent).append('\n');
        sb.append("expectedNextStep=").append(expectedNextStep).append('\n');
        sb.append("routeName=").append(route.routeName()).append('\n');
        sb.append("allowedTools=").append(route.allowedTools()).append('\n');
        sb.append("reason=").append(route.reason()).append('\n');
        sb.append("allowAllMutations=").append(allowAllMutations).append('\n');
        sb.append("=== TOOL_ROUTE_DECISION END ===");
        log.debug(sb.toString());
    }

    /** 记录单个工具的执行策略校验。 */
    static void logToolExecutionPolicy(Logger log, String loopName, int round,
                                       String toolName,
                                       ToolExecutionDecision decision) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_EXECUTION_POLICY ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("tool=").append(toolName).append('\n');
        sb.append("category=").append(decision.category()).append('\n');
        sb.append("allowedByRoute=").append(decision.allowedByRoute()).append('\n');
        sb.append("decision=").append(decision.decision()).append('\n');
        sb.append("reason=").append(decision.reason()).append('\n');
        sb.append("=== TOOL_EXECUTION_POLICY END ===");
        log.debug(sb.toString());
    }

    /** 记录用户对工具确认请求的选择。 */
    static void logToolPermissionDecision(Logger log, String loopName, int round,
                                          String toolName,
                                          ConfirmationChoice choice) {
        if (!log.isDebugEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== TOOL_PERMISSION_DECISION ===\n");
        sb.append("loop=").append(loopName).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("tool=").append(toolName).append('\n');
        sb.append("userChoice=").append(choice).append('\n');
        sb.append("=== TOOL_PERMISSION_DECISION END ===");
        log.debug(sb.toString());
    }
}
