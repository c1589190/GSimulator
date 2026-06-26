package com.gsim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM Provider — HTTP 传输层。
 *
 * <p>负责：
 * <ol>
 *   <li>构建 OpenAI-compatible 请求体（含 tools/tool_choice/extra_body/thinking）</li>
 *   <li>通过 OkHttp 发送 POST 请求（stream=true）</li>
 *   <li>将响应流交给 {@link SseParser} 解析并写入 {@link StreamPool}</li>
 *   <li>非流式路径：直接解析 JSON 响应写入 pool</li>
 * </ol>
 *
 * <p>package-private — 只有 {@link LlmManager} 使用。
 */
class Provider {

    private static final Logger log = LoggerFactory.getLogger(Provider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ProviderConfig config;
    private final OkHttpClient httpClient;

    Provider(ProviderConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.timeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.timeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.timeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /** 同步非流式调用。 */
    LlmResult chat(LlmRequest request) throws IOException {
        Map<String, Object> body = buildRequestBody(request, false);
        String json = MAPPER.writeValueAsString(body);

        Request httpRequest = new Request.Builder()
                .url(config.baseUrl() + "/chat/completions")
                .post(RequestBody.create(json, JSON))
                .header("Authorization", "Bearer " + config.apiKey())
                .build();

        log.debug("[LLM] chat request: model={} messages={} tools={} toolChoice={}",
                request.model() != null ? request.model() : config.model(),
                request.messages().size(),
                request.tools() != null ? request.tools().size() : 0,
                request.toolChoice());

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String errBody = responseBody;
                String err = "HTTP " + response.code() + ": " + errBody;
                log.error("[LLM] chat failed: {}", err);
                if (isContextLengthError(errBody)) {
                    return LlmResult.contextLengthExceeded(err);
                }
                return LlmResult.failure(err);
            }
            return parseNonStreamResponse(responseBody);
        }
    }

    /** 异步流式调用 — 在虚拟线程中执行，结果写入 pool。 */
    void stream(LlmRequest request, StreamPool pool) {
        Thread.startVirtualThread(() -> {
            try {
                executeStream(request, pool);
            } catch (Exception e) {
                log.error("[LLM] stream failed: {}", e.getMessage(), e);
                pool.onError(e.getMessage());
            }
        });
    }

    private void executeStream(LlmRequest request, StreamPool pool) throws IOException {
        Map<String, Object> body = buildRequestBody(request, true);
        String json = MAPPER.writeValueAsString(body);

        Request httpRequest = new Request.Builder()
                .url(config.baseUrl() + "/chat/completions")
                .post(RequestBody.create(json, JSON))
                .header("Authorization", "Bearer " + config.apiKey())
                .build();

        log.debug("[LLM] stream request: model={} messages={} tools={} toolChoice={}",
                request.model() != null ? request.model() : config.model(),
                request.messages().size(),
                request.tools() != null ? request.tools().size() : 0,
                request.toolChoice());

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                if (isContextLengthError(errBody)) {
                    pool.onError("CONTEXT_LENGTH_EXCEEDED:" + errBody);
                } else {
                    pool.onError("HTTP " + response.code() + ": " + errBody);
                }
                return;
            }

            var responseBody = response.body();
            if (responseBody == null) {
                pool.onError("Empty response body");
                return;
            }

            String modelName = request.model() != null ? request.model() : config.model();
            SseParser parser = new SseParser(pool, modelName);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                parser.parse(reader);
            }
        }
    }

    /** 检查连通性。 */
    boolean isAvailable() {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            body.put("max_tokens", 10);
            body.put("stream", false);

            String json = MAPPER.writeValueAsString(body);
            Request httpRequest = new Request.Builder()
                    .url(config.baseUrl() + "/chat/completions")
                    .post(RequestBody.create(json, JSON))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    ProviderConfig config() { return config; }

    // ═══════════════════════════════════════════════
    // 请求体构建
    // ═══════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model() != null ? request.model() : config.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", stream);

        // messages
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (LlmMessage msg : request.messages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            // "tool" role requires tool_call_id per OpenAI spec — our synthetic
            // tool feedback uses [TOOL_RESULT] markers, so downgrade to "user".
            String role = "tool".equals(msg.role()) ? "user" : msg.role();
            m.put("role", role);
            m.put("content", msg.content());
            msgs.add(m);
        }
        body.put("messages", msgs);

        // tools
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> toolDefs = new ArrayList<>();
            for (ToolDef tool : request.tools()) {
                Map<String, Object> td = new LinkedHashMap<>();
                td.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                if (tool.parameters() != null) {
                    Map<String, Object> params = new LinkedHashMap<>(tool.parameters());
                    // 确保 strict schema 符合 JSON Schema 规范
                    if (!params.containsKey("type")) {
                        params.put("type", "object");
                    }
                    fn.put("parameters", params);
                } else {
                    fn.put("parameters", Map.of("type", "object", "properties", Map.of()));
                }
                td.put("function", fn);
                toolDefs.add(td);
            }
            body.put("tools", toolDefs);
        }

        // tool_choice
        if (request.toolChoice() != null) {
            body.put("tool_choice", request.toolChoice());
        }

        // extra_body（注入顶层，如讯飞的 enable_thinking）
        if (config.extraBody() != null) {
            body.putAll(config.extraBody());
        }
        if (request.extraBody() != null) {
            body.putAll(request.extraBody());
        }

        // thinking（DeepSeek 的 {type: "disabled"}）
        Map<String, Object> thinking = request.thinking() != null
                ? request.thinking() : config.thinking();
        if (thinking != null) {
            // 检查是否已有 chat_template_kwargs 包装
            if (thinking.containsKey("type")) {
                body.put("thinking", thinking);
            } else {
                body.put("thinking", thinking);
            }
        }

        return body;
    }

    // ═══════════════════════════════════════════════
    // 响应解析
    // ═══════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private LlmResult parseNonStreamResponse(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);

            JsonNode errorNode = root.get("error");
            if (errorNode != null) {
                String errMsg = errorNode.has("message")
                        ? errorNode.get("message").asText()
                        : errorNode.toString();
                if (isContextLengthError(errMsg)) {
                    return LlmResult.contextLengthExceeded(errMsg);
                }
                return LlmResult.failure(errMsg);
            }

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return LlmResult.failure("No choices in response");
            }

            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            String content = "";
            String reasoning = "";
            List<LlmToolCall> toolCalls = new ArrayList<>();

            if (message != null) {
                JsonNode contentNode = message.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    content = contentNode.asText();
                }

                JsonNode rn = message.get("reasoning_content");
                if (rn == null) rn = message.get("reasoning");
                if (rn != null && !rn.isNull()) {
                    reasoning = rn.asText();
                }

                JsonNode tcNode = message.get("tool_calls");
                if (tcNode != null && tcNode.isArray()) {
                    for (int i = 0; i < tcNode.size(); i++) {
                        JsonNode tc = tcNode.get(i);
                        String id = tc.has("id") ? tc.get("id").asText() : "call_" + i;
                        JsonNode fn = tc.get("function");
                        if (fn != null) {
                            String name = fn.get("name").asText();
                            String argsJson = fn.has("arguments")
                                    ? fn.get("arguments").asText() : "{}";
                            Map<String, String> args = parseArgs(argsJson);
                            toolCalls.add(new LlmToolCall(id, name, args));
                        }
                    }
                }
            }

            int tokensUsed = 0;
            JsonNode usage = root.get("usage");
            if (usage != null && usage.has("total_tokens")) {
                tokensUsed = usage.get("total_tokens").asInt();
            }

            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                    ? choice.get("finish_reason").asText() : "stop";

            if (!toolCalls.isEmpty()) {
                return LlmResult.withToolCalls(toolCalls, config.model(), tokensUsed);
            }
            return new LlmResult(content, reasoning, config.model(), tokensUsed, true, null,
                    List.of(), finishReason, false);

        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return LlmResult.failure("Failed to parse response: " + e.getMessage());
        }
    }

    /** 检测错误消息是否与上下文长度超限相关。 */
    static boolean isContextLengthError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("max context")
                || lower.contains("too long")
                || lower.contains("reduce the length")
                || lower.contains("请求长度超过")
                || lower.contains("上下文长度")
                || lower.contains("上下文过长")
                || lower.contains("超出上下文")
                || (lower.contains("413") && lower.contains("request entity too large"))
                || (lower.contains("400") && (lower.contains("token") && lower.contains("truncat")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = MAPPER.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                Object v = entry.getValue();
                result.put(entry.getKey(), v != null ? v.toString() : "");
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
