package com.gsim.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 API 的 LLM 客户端实现。
 * 使用 OkHttp 发送 chat completions 请求，支持原生 tool_calls。
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlmClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final boolean configured;

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, double temperature, int timeoutSeconds) {
        this.baseUrl = baseUrl != null && baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.mapper = new ObjectMapper();

        this.configured = baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank() && !"no-api-key".equals(apiKey)
                && model != null && !model.isBlank();

        int effectiveTimeout = timeoutSeconds > 0 ? timeoutSeconds : 10;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(effectiveTimeout))
                .readTimeout(Duration.ofSeconds(effectiveTimeout))
                .writeTimeout(Duration.ofSeconds(effectiveTimeout))
                .build();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (!configured) {
            return LlmResponse.failure("LLM is not configured. Run /config init to set up your LLM.");
        }

        try {
            String reqBody = buildRequestBody(request);
            log.debug("LLM request: {} chars to {}", reqBody.length(), baseUrl);

            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(reqBody, JSON))
                    .build();

            try (Response response = http.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("LLM HTTP {}: {}", response.code(), errBody.substring(0, Math.min(200, errBody.length())));
                    return LlmResponse.failure("HTTP " + response.code() + ": " + errBody);
                }

                String body = response.body() != null ? response.body().string() : "";
                return parseResponse(body);
            }
        } catch (IOException e) {
            log.error("LLM call failed: {}", e.getMessage());
            return LlmResponse.failure(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return configured;
    }

    public void close() {
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }

    private String buildRequestBody(LlmRequest req) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", req.model() != null ? req.model() : model);
        root.put("temperature", req.temperature() > 0 ? req.temperature() : temperature);

        ArrayNode messages = root.putArray("messages");
        for (LlmMessage msg : req.messages()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role());
            m.put("content", msg.content());
        }

        root.put("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : 2048);

        // Serialize tools if present
        if (req.tools() != null && !req.tools().isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDef tool : req.tools()) {
                ObjectNode t = toolsArray.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                // Minimal parameters schema — LLM 从 description 中理解参数
                ObjectNode params = fn.putObject("parameters");
                params.put("type", "object");
                params.putObject("properties");
                params.put("additionalProperties", true);
            }
            // tool_choice: "auto" by default, "none" to disable, "required" to force
            if (req.toolChoice() != null) {
                root.put("tool_choice", req.toolChoice());
            } else {
                root.put("tool_choice", "auto");
            }
        }

        return mapper.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    private LlmResponse parseResponse(String body) {
        try {
            JsonNode root = mapper.readTree(body);

            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return LlmResponse.failure("No choices in response");
            }

            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message == null) {
                return LlmResponse.failure("No message in choice");
            }

            String content = message.has("content") && !message.get("content").isNull()
                    ? message.get("content").asText() : "";

            String finishReason = choice.has("finish_reason")
                    ? choice.get("finish_reason").asText() : "stop";

            int tokens = root.has("usage") && root.get("usage").has("total_tokens")
                    ? root.get("usage").get("total_tokens").asInt() : 0;

            String responseModel = root.has("model") ? root.get("model").asText() : model;

            // Parse tool_calls from message
            List<LlmToolCall> toolCalls = parseToolCalls(message);

            if (!toolCalls.isEmpty()) {
                log.debug("LLM response: {} tokens, {} tool_calls", tokens, toolCalls.size());
                return LlmResponse.successWithToolCalls(toolCalls, responseModel, tokens);
            }

            log.debug("LLM response: {} tokens, {} chars, finish_reason={}", tokens, content.length(), finishReason);
            return new LlmResponse(content, responseModel, tokens, true, null, List.of(), finishReason);
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return LlmResponse.failure("Failed to parse response: " + e.getMessage());
        }
    }

    /** 从 message JSON 中解析 tool_calls。 */
    private List<LlmToolCall> parseToolCalls(JsonNode message) {
        List<LlmToolCall> result = new ArrayList<>();
        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode == null || !toolCallsNode.isArray()) return result;

        for (JsonNode tc : toolCallsNode) {
            String id = tc.has("id") ? tc.get("id").asText() : "";
            JsonNode fn = tc.get("function");
            if (fn == null) continue;

            String name = fn.has("name") ? fn.get("name").asText() : "";
            if (name.isBlank()) continue;

            Map<String, String> args = new HashMap<>();
            if (fn.has("arguments")) {
                String argsStr = fn.get("arguments").asText();
                if (argsStr != null && !argsStr.isBlank()) {
                    try {
                        JsonNode argsNode = mapper.readTree(argsStr);
                        if (argsNode.isObject()) {
                            var iter = argsNode.fields();
                            while (iter.hasNext()) {
                                var entry = iter.next();
                                args.put(entry.getKey(), entry.getValue().asText());
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse tool_call arguments: {}", argsStr);
                        // Leave args as empty — the tool will handle missing params
                    }
                }
            }

            result.add(new LlmToolCall(id, name, args));
        }
        return result;
    }
}
