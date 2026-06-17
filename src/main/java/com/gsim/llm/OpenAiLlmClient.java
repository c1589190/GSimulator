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

/**
 * OpenAI 兼容 API 的 LLM 客户端实现。
 * 使用 OkHttp 发送 chat completions 请求。
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

    public OpenAiLlmClient(String baseUrl, String apiKey, String model, double temperature, int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.mapper = new ObjectMapper();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .writeTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
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
                JsonNode root = mapper.readTree(body);

                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    return LlmResponse.failure("No choices in response");
                }

                JsonNode message = choices.get(0).get("message");
                if (message == null) {
                    return LlmResponse.failure("No message in choice");
                }

                String content = message.has("content") ? message.get("content").asText() : "";
                int tokens = root.has("usage") && root.get("usage").has("total_tokens")
                        ? root.get("usage").get("total_tokens").asInt() : 0;

                log.debug("LLM response: {} tokens, {} chars", tokens, content.length());
                return LlmResponse.success(content, model, tokens);
            }
        } catch (IOException e) {
            log.error("LLM call failed: {}", e.getMessage());
            return LlmResponse.failure(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
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

        return mapper.writeValueAsString(root);
    }
}
