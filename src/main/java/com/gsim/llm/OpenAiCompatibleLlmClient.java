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
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("LLM stream API error: HTTP {} — {}", response.code(), errorBody);
                    listener.onError(new RuntimeException("HTTP " + response.code() + ": " + errorBody));
                    listener.onComplete();
                    return;
                }

                var body = response.body();
                if (body == null) {
                    listener.onError(new RuntimeException("Empty response body"));
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

    // ---- SSE 流式处理 ----

    @SuppressWarnings("unchecked")
    private void processSseStream(okhttp3.ResponseBody body,
                                  LlmStreamListener listener) throws IOException {
        // 先用 PushbackReader 探测第一行，判断是 SSE 还是普通 JSON
        java.io.PushbackReader pushback = new java.io.PushbackReader(
                new java.io.InputStreamReader(body.byteStream(), java.nio.charset.StandardCharsets.UTF_8),
                65536);

        // 跳过开头空行
        int firstChar;
        do {
            firstChar = pushback.read();
        } while (firstChar == '\n' || firstChar == '\r');

        if (firstChar < 0) {
            // 空响应
            setEmptyResponse(listener);
            listener.onComplete();
            return;
        }

        // 将该字符放回，从开头重读
        pushback.unread(firstChar);

        // 读取第一行
        StringBuilder firstLineSb = new StringBuilder();
        int ch;
        while ((ch = pushback.read()) >= 0 && ch != '\n' && ch != '\r') {
            firstLineSb.append((char) ch);
        }
        // 跳过 \r\n 或 \n
        if (ch == '\r') {
            int next = pushback.read();
            if (next != '\n' && next >= 0) pushback.unread(next);
        }

        String firstLine = firstLineSb.toString().trim();
        String firstLineLower = firstLine.toLowerCase();

        if (firstLineLower.startsWith("data:")) {
            // ======== SSE 流式路径 ========
            String data = firstLine.substring(5).trim();
            if (!data.isEmpty() && !"[DONE]".equals(data)) {
                processSseData(data, listener);
            }

            // 继续读取剩余 SSE 行
            BufferedReader reader = new BufferedReader(pushback);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data:")) continue;
                String dataLine = line.substring(5).trim();
                if (dataLine.isEmpty()) continue;
                if ("[DONE]".equals(dataLine)) break;
                try {
                    processSseData(dataLine, listener);
                } catch (Exception e) {
                    log.debug("Failed to parse SSE chunk: {} — line: {}",
                            e.getMessage(),
                            dataLine.length() > 200 ? dataLine.substring(0, 200) + "..." : dataLine);
                }
            }

            // SSE 流结束：组装最终结果
            finalizeStreamResult(listener);
        } else {
            // ======== 非 SSE 响应 → 按普通 JSON 解析（API 不支持流式时的降级）========
            log.info("Non-SSE response detected (first line does not start with 'data:'),"
                    + " falling back to full JSON parse. First line preview: {}",
                    firstLine.length() > 100 ? firstLine.substring(0, 100) + "..." : firstLine);

            // 读取剩余内容
            StringBuilder fullBody = new StringBuilder(firstLine);
            BufferedReader reader = new BufferedReader(pushback);
            String line;
            while ((line = reader.readLine()) != null) {
                fullBody.append(line);
            }

            String bodyStr = fullBody.toString();
            log.debug("Non-streaming response body length: {}", bodyStr.length());

            LlmResponse response = parseResponse(bodyStr);
            if (listener instanceof LlmStreamCollector collector) {
                collector.setFinalResponse(response);
                if (response.success() && response.content() != null) {
                    collector.onContentDelta(response.content());
                }
                if (response.hasApiToolCalls()) {
                    collector.setToolCalls(response.toolCalls());
                    for (LlmToolCall tc : response.toolCalls()) {
                        collector.onToolCallDelta("tool:" + tc.name());
                    }
                }
            }
            listener.onComplete();
        }
    }

    // ---- SSE 数据处理辅助方法 ----

    /** 处理单条 SSE data 行 */
    @SuppressWarnings("unchecked")
    private void processSseData(String data, LlmStreamListener listener) {
        Map<String, Object> chunk;
        try {
            chunk = MAPPER.readValue(data, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse SSE data JSON: {}", e.getMessage());
            return;
        }

        List<Map<String, Object>> choices =
                (List<Map<String, Object>>) chunk.get("choices");
        if (choices == null || choices.isEmpty()) return;

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
        if (delta == null) return;

        // --- content delta ---
        Object contentDelta = delta.get("content");
        if (contentDelta instanceof String s && !s.isEmpty()) {
            sseContentBuf.append(s);
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
        }
    }

    /** SSE 流结束后，组装最终结果写入 collector */
    private void finalizeStreamResult(LlmStreamListener listener) {
        List<LlmToolCall> toolCalls = assembleToolCalls(sseToolCallBuf);

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
                String reasoning = sseReasoningBuf.toString();
                if (content.isEmpty() && reasoning.isEmpty()) {
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

    /** 空响应时设置空结果 */
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
    private LlmResponse parseResponse(String body) {
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
