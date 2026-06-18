package com.gsim.knowledge.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible 外部 Embedding 模型 — 通过 /v1/embeddings API 调用。
 * 支持任何兼容 OpenAI embeddings 格式的 API（如 deepseek, siliconflow 等）。
 */
public class ExternalEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(ExternalEmbeddingModel.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final EmbeddingProfile profile;
    private final OkHttpClient httpClient;
    private final String embeddingsUrl;

    public ExternalEmbeddingModel(String baseUrl, String apiKey, String modelName,
                                   int dimensions, int timeoutSeconds) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.dimensions = dimensions;

        String fingerprint = EmbeddingProfileManager.computeFingerprint(
                "external", modelName, dimensions, baseUrl);
        // 用 fingerprint 生成确定性 profileId，重启不变
        String profileId = "emb_" + fingerprint;
        this.profile = new EmbeddingProfile(
                profileId,
                "external", "openai-compatible", modelName, dimensions,
                "cosine", 1, fingerprint, "active", Instant.now().toString());

        this.embeddingsUrl = buildEmbeddingsUrl(this.baseUrl);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .readTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * Normalize base URL: strip trailing slash but preserve path structure.
     */
    static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Build the full /v1/embeddings URL from the normalized base URL.
     *
     * Rules:
     *   if baseUrl ends with /v1/embeddings → use as-is
     *   if baseUrl ends with /v1 → append /embeddings
     *   otherwise → append /v1/embeddings
     */
    static String buildEmbeddingsUrl(String normalizedBaseUrl) {
        if (normalizedBaseUrl.endsWith("/v1/embeddings")) {
            return normalizedBaseUrl;
        }
        if (normalizedBaseUrl.endsWith("/v1")) {
            return normalizedBaseUrl + "/embeddings";
        }
        return normalizedBaseUrl + "/v1/embeddings";
    }

    @Override
    public EmbeddingVector embed(String text) {
        List<EmbeddingVector> results = embedAll(List.of(text));
        if (results.isEmpty()) {
            throw new RuntimeException("EMBEDDING_PROVIDER_UNAVAILABLE: External embedding returned no results");
        }
        return results.get(0);
    }

    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        try {
            String requestJson = buildRequestJson(texts);
            String url = embeddingsUrl;

            RequestBody body = RequestBody.create(requestJson, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    log.error("Embedding API returned {}: {}", response.code(), errBody);
                    throw new RuntimeException("EMBEDDING_PROVIDER_UNAVAILABLE: HTTP " + response.code());
                }

                String respBody = response.body() != null ? response.body().string() : "";
                return parseResponse(respBody);
            }
        } catch (IOException e) {
            log.error("Embedding API call failed: {}", e.getMessage());
            throw new RuntimeException("EMBEDDING_PROVIDER_UNAVAILABLE: " + e.getMessage(), e);
        }
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }

    private String buildRequestJson(List<String> texts) {
        try {
            var root = mapper.createObjectNode();
            root.put("model", modelName);
            var inputArr = root.putArray("input");
            for (String t : texts) {
                inputArr.add(t);
            }
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build request JSON", e);
        }
    }

    /**
     * 关闭 OkHttpClient，释放连接池和线程资源。
     */
    public void close() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private List<EmbeddingVector> parseResponse(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                throw new RuntimeException("EMBEDDING_PROVIDER_UNAVAILABLE: No 'data' array in response");
            }

            List<EmbeddingVector> results = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode emb = item.get("embedding");
                if (emb == null || !emb.isArray()) {
                    throw new RuntimeException("EMBEDDING_PROVIDER_UNAVAILABLE: Missing 'embedding' in response item");
                }
                float[] values = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    values[i] = emb.get(i).floatValue();
                }
                if (values.length != dimensions) {
                    throw new RuntimeException("EMBEDDING_DIMENSION_MISMATCH: expected " + dimensions
                            + " but got " + values.length);
                }
                results.add(new EmbeddingVector(values, dimensions, profile.profileId()));
            }
            return results;
        } catch (IOException e) {
            throw new RuntimeException("EMBEDDING_PROVIDER_UNAVAILABLE: Failed to parse response", e);
        }
    }
}
