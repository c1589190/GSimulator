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

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat Completions API 客户端。
 * 调用 /chat/completions 端点，支持 system/user/assistant 消息。
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
            String requestBody = buildRequestBody(request);
            String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

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
    public boolean isAvailable() {
        return true;
    }

    @SuppressWarnings("unchecked")
    private String buildRequestBody(LlmRequest request) throws IOException {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList();

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", request.model() != null ? request.model() : model);
        body.put("messages", messages);
        body.put("temperature", request.temperature() > 0 ? request.temperature() : temperature);
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }

        // Serialize tools if present
        if (request.tools() != null && !request.tools().isEmpty()) {
            List<Map<String, Object>> tools = new java.util.ArrayList<>();
            for (ToolDef tool : request.tools()) {
                Map<String, Object> fnDef = new java.util.LinkedHashMap<>();
                fnDef.put("name", tool.name());
                fnDef.put("description", tool.description());
                Map<String, Object> params = new java.util.LinkedHashMap<>();
                params.put("type", "object");
                params.put("properties", Map.of());
                params.put("additionalProperties", true);
                fnDef.put("parameters", params);

                Map<String, Object> t = new java.util.LinkedHashMap<>();
                t.put("type", "function");
                t.put("function", fnDef);
                tools.add(t);
            }
            body.put("tools", tools);
            body.put("tool_choice", request.toolChoice() != null ? request.toolChoice() : "auto");
        }

        return MAPPER.writeValueAsString(body);
    }

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
        List<LlmToolCall> result = new java.util.ArrayList<>();
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

            Map<String, String> args = new java.util.HashMap<>();
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
