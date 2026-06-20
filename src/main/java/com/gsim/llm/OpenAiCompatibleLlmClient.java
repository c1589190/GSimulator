package com.gsim.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gsim.app.AppConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat Completions API 客户端。
 * 调用 /chat/completions 端点，支持 system/user/assistant 消息。
 * 支持真 SSE 流式传输。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final OkHttpClient httpClient;

    public OpenAiCompatibleLlmClient(AppConfig config) {
        this.baseUrl = config.getLlmBaseUrl();
        this.apiKey = config.getLlmApiKey();
        this.model = config.getLlmModel();
        this.temperature = config.getLlmTemperature();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.getLlmTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getLlmTimeoutSeconds()))
                .build();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        try {
            String requestBody = buildRequestBody(request, false);
            String url = buildUrl();

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("LLM API error: HTTP {} — {}", response.code(), errorBody);
                    return LlmResponse.failure("HTTP " + response.code() + ": " + errorBody);
                }

                String body = response.body() != null ? response.body().string() : "";
                return parseResponse(body);
            }
        } catch (IOException e) {
            log.error("LLM API call failed: {}", e.getMessage(), e);
            return LlmResponse.failure(e.getMessage());
        }
    }

    @Override
    public void stream(LlmRequest request, LlmStreamListener listener) {
        String requestModel = request.model() != null ? request.model() : model;
        log.debug("[LLM_STREAM] request stream=true model={}", requestModel);

        try {
            listener.onStart();

            String requestBody = buildRequestBody(request, true);
            String url = buildUrl();

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                int code = response.code();
                String contentType = response.header("Content-Type", "");

                log.debug("[LLM_STREAM] response code={} contentType={}", code, contentType);

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("LLM stream API error: HTTP {} — {}", code, errorBody);
                    listener.onError(new RuntimeException("HTTP " + code + ": " + errorBody));
                    listener.onComplete();
                    return;
                }

                var body = response.body();
                if (body == null) {
                    log.debug("[LLM_STREAM] firstBytesKind=EMPTY");
                    setEmptyResponse(listener);
                    listener.onComplete();
                    return;
                }

                processSseStream(body, listener);
            }
        } catch (IOException e) {
            log.error("LLM stream API call failed: {}", e.getMessage(), e);
            listener.onError(e);
            try {
                listener.onComplete();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ======================== SSE 流式处理（重写版） ========================

    /**
     * 处理响应体：先检测首行格式（SSE 还是 JSON），然后分流处理。
     * 不再使用 PushbackReader，改用简单的 BufferedReader + 首行回放。
     */
    void processSseStream(okhttp3.ResponseBody body,
                                  LlmStreamListener listener) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));

        // 跳过开头空行，找到第一个非空行
        String firstLine = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                firstLine = line.strip();
                break;
            }
        }

        if (firstLine == null) {
            log.debug("[LLM_STREAM] firstBytesKind=EMPTY");
            setEmptyResponse(listener);
            listener.onComplete();
            return;
        }

        String firstLineLower = firstLine.toLowerCase();
        boolean isSse = firstLineLower.startsWith("data:");

        String firstBytesKind;
        if (isSse) {
            firstBytesKind = "SSE";
        } else if (firstLine.startsWith("{")) {
            firstBytesKind = "JSON";
        } else {
            firstBytesKind = "UNKNOWN";
        }
        log.debug("[LLM_STREAM] firstBytesKind={}", firstBytesKind);

        // 重置 SSE 缓冲区
        sseContentBuf.setLength(0);
        sseReasoningBuf.setLength(0);
        sseToolCallBuf.clear();

        if (isSse) {
            processSseLines(firstLine, reader, listener);
        } else {
            processJsonFallback(firstLine, reader, listener);
        }
    }

    /** 处理 SSE data: 行流。 */
    private void processSseLines(String firstLine, BufferedReader reader,
                                 LlmStreamListener listener) throws IOException {
        int sseLineCount = 0;
        int contentDeltaCount = 0;
        int reasoningDeltaCount = 0;
        int toolCallDeltaCount = 0;

        // 处理首行
        sseLineCount++;
        String data = firstLine.substring(5).strip();
        if (!data.isEmpty() && !"[DONE]".equals(data)) {
            DeltaCounts dc = processSseData(data, listener);
            contentDeltaCount += dc.content;
            reasoningDeltaCount += dc.reasoning;
            toolCallDeltaCount += dc.toolCalls;
        }

        // 处理剩余行
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String stripped = line.strip();
            if (!stripped.startsWith("data:")) continue;

            sseLineCount++;
            String dataLine = stripped.substring(5).strip();
            if (dataLine.isEmpty()) continue;
            if ("[DONE]".equals(dataLine)) break;

            try {
                DeltaCounts dc = processSseData(dataLine, listener);
                contentDeltaCount += dc.content;
                reasoningDeltaCount += dc.reasoning;
                toolCallDeltaCount += dc.toolCalls;
            } catch (Exception e) {
                log.debug("Failed to parse SSE chunk: {} — line: {}",
                        e.getMessage(),
                        dataLine.length() > 200 ? dataLine.substring(0, 200) + "..." : dataLine);
            }
        }

        log.debug("[LLM_STREAM] sseLineCount={} contentDeltaCount={} reasoningDeltaCount={} toolCallDeltaCount={}",
                sseLineCount, contentDeltaCount, reasoningDeltaCount, toolCallDeltaCount);

        // 组装最终结果
        finalizeStreamResult(listener);
    }

    /** 处理 JSON fallback（API 不支持 SSE，返回完整 JSON）。 */
    private void processJsonFallback(String firstLine, BufferedReader reader,
                                     LlmStreamListener listener) throws IOException {
        log.debug("[LLM_STREAM] fallbackJson=true (API returned non-SSE response)");

        // 读取剩余行
        StringBuilder fullBody = new StringBuilder(firstLine);
        String line;
        while ((line = reader.readLine()) != null) {
            fullBody.append(line);
        }

        String bodyStr = fullBody.toString();
        LlmResponse response = parseResponse(bodyStr);

        int finalContentChars = response.content() != null ? response.content().length() : 0;
        int finalToolCalls = response.hasApiToolCalls() ? response.toolCalls().size() : 0;
        log.debug("[LLM_STREAM] finalContentChars={} finalToolCalls={}",
                finalContentChars, finalToolCalls);

        // 即使非流式，也必须通过 listener 发射 delta，
        // 确保 CLI 灰框至少能看到内容（否则永远显示"等待输出……"）
        if (listener instanceof LlmStreamCollector collector) {
            collector.setFinalResponse(response);

            if (response.success()) {
                // 尝试从 message 中提取 reasoning
                String reasoning = extractReasoningFromMessage(bodyStr);
                if (reasoning != null && !reasoning.isEmpty()) {
                    log.debug("[LLM_STREAM] reasoningDeltaCount=1 (from JSON message)");
                    collector.setReasoning(reasoning);
                    try {
                        listener.onReasoningDelta(reasoning);
                    } catch (Exception ex) {
                        log.warn("Listener onReasoningDelta threw: {}", ex.getMessage());
                    }
                }

                // 发射完整 content 作为单个 delta
                if (response.content() != null && !response.content().isEmpty()) {
                    log.debug("[LLM_STREAM] contentDeltaCount=1 (from JSON message)");
                    try {
                        listener.onContentDelta(response.content());
                    } catch (Exception ex) {
                        log.warn("Listener onContentDelta threw: {}", ex.getMessage());
                    }
                }

                if (response.hasApiToolCalls()) {
                    collector.setToolCalls(response.toolCalls());
                }
            }
        }

        listener.onComplete();
    }

    // ---- SSE 数据处理 ----

    /** 单条 SSE data 的 delta 计数。 */
    private record DeltaCounts(int content, int reasoning, int toolCalls) {}

    /** 处理单条 SSE data 行，返回各类型 delta 计数。 */
    @SuppressWarnings("unchecked")
    private DeltaCounts processSseData(String data, LlmStreamListener listener) {
        int contentCount = 0, reasoningCount = 0, toolCallCount = 0;

        Map<String, Object> chunk;
        try {
            chunk = MAPPER.readValue(data, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse SSE data JSON: {}", e.getMessage());
            return new DeltaCounts(0, 0, 0);
        }

        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) chunk.get("choices");
        if (choices == null || choices.isEmpty()) {
            return new DeltaCounts(0, 0, 0);
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
        if (delta == null) {
            return new DeltaCounts(0, 0, 0);
        }

        // --- content delta ---
        Object contentDelta = delta.get("content");
        if (contentDelta instanceof String s && !s.isEmpty()) {
            sseContentBuf.append(s);
            contentCount = 1;
            try {
                listener.onContentDelta(s);
            } catch (Exception ex) {
                log.warn("Listener onContentDelta threw: {}", ex.getMessage());
            }
        }

        // --- reasoning delta (兼容多种字段名) ---
        String reasoningText = extractReasoningDelta(delta);
        if (reasoningText != null && !reasoningText.isEmpty()) {
            sseReasoningBuf.append(reasoningText);
            reasoningCount = 1;
            try {
                listener.onReasoningDelta(reasoningText);
            } catch (Exception ex) {
                log.warn("Listener onReasoningDelta threw: {}", ex.getMessage());
            }
        }

        // --- tool_calls delta ---
        Object tcDelta = delta.get("tool_calls");
        if (tcDelta instanceof List<?> tcList && !tcList.isEmpty()) {
            for (Object tcObj : tcList) {
                if (!(tcObj instanceof Map<?, ?> tcMap)) continue;
                mergeToolCallDelta(sseToolCallBuf, (Map<String, Object>) tcMap);
            }
            toolCallCount = 1;
        }

        return new DeltaCounts(contentCount, reasoningCount, toolCallCount);
    }

    /** SSE 流结束后，组装最终结果写入 collector。 */
    private void finalizeStreamResult(LlmStreamListener listener) {
        List<LlmToolCall> toolCalls = assembleToolCalls(sseToolCallBuf);

        int finalContentChars = sseContentBuf.length();
        int finalReasoningChars = sseReasoningBuf.length();

        log.debug("[LLM_STREAM] finalContentChars={} finalReasoningChars={} finalToolCalls={}",
                finalContentChars, finalReasoningChars, toolCalls.size());

        if (!toolCalls.isEmpty()) {
            for (LlmToolCall tc : toolCalls) {
                try {
                    listener.onToolCallDelta("tool:" + tc.name());
                } catch (Exception ex) {
                    log.warn("Listener onToolCallDelta threw: {}", ex.getMessage());
                }
            }
        }

        if (listener instanceof LlmStreamCollector collector) {
            if (!toolCalls.isEmpty()) {
                collector.setFinalResponse(LlmResponse.successWithToolCalls(
                        toolCalls, model, 0));
            } else {
                String content = sseContentBuf.toString();
                if (content.isEmpty() && sseReasoningBuf.isEmpty()) {
                    log.warn("SSE stream completed but no content or reasoning was collected."
                            + " The API may not support streaming."
                            + " Check your LLM provider's documentation.");
                }
                collector.setFinalResponse(new LlmResponse(
                        content, model, 0, true, null,
                        List.of(), "stop"));
            }
            collector.setReasoning(sseReasoningBuf.toString());
            collector.setToolCalls(toolCalls);
        }

        // 重置 SSE 缓冲区
        sseContentBuf.setLength(0);
        sseReasoningBuf.setLength(0);
        sseToolCallBuf.clear();

        listener.onComplete();
    }

    /** 空响应时设置空结果。 */
    private void setEmptyResponse(LlmStreamListener listener) {
        log.warn("Empty response body from LLM stream API");
        if (listener instanceof LlmStreamCollector collector) {
            collector.setFinalResponse(LlmResponse.success("", model, 0));
        }
    }

    // SSE 流式缓冲区（实例级，支持跨 chunk 拼接）
    private final StringBuilder sseContentBuf = new StringBuilder();
    private final StringBuilder sseReasoningBuf = new StringBuilder();
    private final Map<Integer, Map<String, Object>> sseToolCallBuf = new LinkedHashMap<>();

    /**
     * 从 delta 中提取 reasoning/thinking 文本。
     * 兼容多种模型字段名。
     */
    private String extractReasoningDelta(Map<String, Object> delta) {
        // 按优先级尝试已知字段名
        String[] keys = {"reasoning_content", "reasoning", "thinking", "thought"};
        for (String key : keys) {
            Object val = delta.get(key);
            if (val instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 从非流式 JSON 响应的 message 中提取 reasoning。
     * 兼容 choices[0].message.reasoning_content 等字段。
     */
    @SuppressWarnings("unchecked")
    private String extractReasoningFromMessage(String bodyStr) {
        try {
            Map<String, Object> map = MAPPER.readValue(bodyStr, Map.class);
            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) return null;

            Map<String, Object> message =
                    (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return null;

            String[] keys = {"reasoning_content", "reasoning", "thinking", "thought"};
            for (String key : keys) {
                Object val = message.get(key);
                if (val instanceof String s && !s.isEmpty()) {
                    return s;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract reasoning from JSON message: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将 streaming tool_calls delta 合并到缓冲区。
     * OpenAI 格式：每个 chunk 可能包含 index, id, type, function.name, function.arguments。
     */
    @SuppressWarnings("unchecked")
    private void mergeToolCallDelta(Map<Integer, Map<String, Object>> buf,
                                    Map<String, Object> tcMap) {
        Object idxObj = tcMap.get("index");
        if (idxObj == null) return;
        int idx = ((Number) idxObj).intValue();

        Map<String, Object> entry = buf.computeIfAbsent(idx, k -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", "");
            e.put("name", "");
            e.put("arguments", "");
            return e;
        });

        // id
        Object idVal = tcMap.get("id");
        if (idVal instanceof String s && !s.isEmpty()) {
            entry.put("id", s);
        }

        // function
        Object fnObj = tcMap.get("function");
        if (fnObj instanceof Map<?, ?> fnMap) {
            Map<String, Object> fn = (Map<String, Object>) fnMap;
            Object nameVal = fn.get("name");
            if (nameVal instanceof String s && !s.isEmpty()) {
                entry.put("name", s);
            }
            Object argsVal = fn.get("arguments");
            if (argsVal instanceof String s) {
                entry.put("arguments", (String) entry.get("arguments") + s);
            }
        }
    }

    /**
     * 将缓冲的工具调用 delta 组装为 LlmToolCall 列表。
     */
    private List<LlmToolCall> assembleToolCalls(Map<Integer, Map<String, Object>> buf) {
        List<LlmToolCall> result = new ArrayList<>();
        for (Map<String, Object> entry : buf.values()) {
            String id = (String) entry.getOrDefault("id", "");
            String name = (String) entry.getOrDefault("name", "");
            String argsJson = (String) entry.getOrDefault("arguments", "");

            if (name == null || name.isBlank()) continue;

            Map<String, String> args = new LinkedHashMap<>();
            if (argsJson != null && !argsJson.isBlank()) {
                try {
                    Map<String, Object> parsed = MAPPER.readValue(argsJson, Map.class);
                    for (Map.Entry<String, Object> e : parsed.entrySet()) {
                        args.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse streaming tool_call arguments: {}", e.getMessage());
                    // 保留原始 JSON
                    args.put("_raw", argsJson);
                }
            }

            result.add(new LlmToolCall(id, name, args));
        }
        return result;
    }

    // ---- 请求体构建 ----

    private String buildUrl() {
        return baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";
    }

    @SuppressWarnings("unchecked")
    private String buildRequestBody(LlmRequest request, boolean stream) throws IOException {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model() != null ? request.model() : model);
        body.put("messages", messages);
        body.put("temperature", request.temperature() > 0 ? request.temperature() : temperature);
        body.put("stream", stream);
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }

        // Serialize tools if present
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolDef tool : request.tools()) {
                Map<String, Object> fnDef = new LinkedHashMap<>();
                fnDef.put("name", tool.name());
                fnDef.put("description", tool.description());
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("type", "object");
                params.put("properties", Map.of());
                params.put("additionalProperties", true);
                fnDef.put("parameters", params);

                Map<String, Object> t = new LinkedHashMap<>();
                t.put("type", "function");
                t.put("function", fnDef);
                tools.add(t);
            }
            body.put("tools", tools);
            body.put("tool_choice", request.toolChoice() != null ? request.toolChoice() : "auto");
        }

        return MAPPER.writeValueAsString(body);
    }

    // ---- 非流式响应解析 ----

    @SuppressWarnings("unchecked")
    LlmResponse parseResponse(String body) {
        try {
            Map<String, Object> map = MAPPER.readValue(body, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
            if (choices == null || choices.isEmpty()) {
                return LlmResponse.failure("No choices in response");
            }

            Map<String, Object> choice = choices.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            String content = message != null ? (String) message.get("content") : "";

            String finishReason = (String) choice.getOrDefault("finish_reason", "stop");
            String responseModel = (String) map.getOrDefault("model", model);
            Map<String, Object> usage = (Map<String, Object>) map.get("usage");
            int tokens = usage != null ? ((Number) usage.getOrDefault("total_tokens", 0)).intValue() : 0;

            // Parse tool_calls
            List<LlmToolCall> toolCalls = parseToolCalls(message);

            if (!toolCalls.isEmpty()) {
                return LlmResponse.successWithToolCalls(toolCalls, responseModel, tokens);
            }
            return new LlmResponse(content != null ? content : "", responseModel, tokens,
                    true, null, List.of(), finishReason);
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return LlmResponse.failure("Failed to parse response: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<LlmToolCall> parseToolCalls(Map<String, Object> message) {
        List<LlmToolCall> result = new ArrayList<>();
        if (message == null) return result;

        Object tcObj = message.get("tool_calls");
        if (!(tcObj instanceof List)) return result;

        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) tcObj;
        for (Map<String, Object> tc : toolCalls) {
            String id = (String) tc.getOrDefault("id", "");
            Map<String, Object> fn = (Map<String, Object>) tc.get("function");
            if (fn == null) continue;

            String name = (String) fn.get("name");
            if (name == null || name.isBlank()) continue;

            Map<String, String> args = new LinkedHashMap<>();
            Object argsObj = fn.get("arguments");
            if (argsObj instanceof String argsStr && !argsStr.isBlank()) {
                try {
                    Map<String, Object> parsed = MAPPER.readValue(argsStr, Map.class);
                    for (Map.Entry<String, Object> e : parsed.entrySet()) {
                        args.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                } catch (Exception e) {
                    // Leave args empty
                }
            }

            result.add(new LlmToolCall(id, name, args));
        }
        return result;
    }
}
