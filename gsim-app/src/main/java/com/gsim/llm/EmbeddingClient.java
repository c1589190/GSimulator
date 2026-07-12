package com.gsim.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Embedding API 客户端 — 调用 OpenAI-compatible /v1/embeddings 端点。
 *
 * <p>配置来源：AppConfig 的 EMBEDDING_BASE_URL / EMBEDDING_API_KEY / EMBEDDING_MODEL。
 *
 * <h3>用法</h3>
 * <pre>{@code
 *   EmbeddingClient client = new EmbeddingClient("https://api.siliconflow.cn/v1", "sk-xxx", "BAAI/bge-large-zh-v1.5");
 *   float[] vec = client.embed("一句话描述");
 *   double sim = EmbeddingClient.cosineSimilarity(vec1, vec2);
 * }</pre>
 */
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient http;

    public EmbeddingClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** 对单段文本获取 embedding 向量。 */
    public float[] embed(String text) throws IOException {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /** 批量获取 embedding 向量。 */
    @SuppressWarnings("unchecked")
    public List<float[]> embedBatch(List<String> inputs) throws IOException {
        if (inputs == null || inputs.isEmpty()) return List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        // encoding_format: float is the default

        String json = MAPPER.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .post(RequestBody.create(json, JSON))
                .header("Authorization", "Bearer " + apiKey)
                .build();

        log.debug("[Embedding] request: model={} inputs={}", model, inputs.size());

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errBody = response.body() != null ? response.body().string() : "";
                throw new IOException("HTTP " + response.code() + ": " + errBody);
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            Map<String, Object> root = MAPPER.readValue(responseBody, Map.class);

            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
            if (data == null || data.isEmpty()) {
                throw new IOException("No embedding data in response: " + responseBody);
            }

            List<float[]> results = new ArrayList<>();
            for (Map<String, Object> item : data) {
                List<Double> embList = (List<Double>) item.get("embedding");
                if (embList == null) continue;
                float[] vec = new float[embList.size()];
                for (int i = 0; i < embList.size(); i++) {
                    vec[i] = embList.get(i).floatValue();
                }
                results.add(vec);
            }

            log.debug("[Embedding] response: {} vectors, dim={}",
                    results.size(), results.isEmpty() ? 0 : results.get(0).length);
            return results;
        }
    }

    /** 余弦相似度。值域 [-1, 1]，越高越相似。 */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0;
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 检查是否已配置（baseUrl 和 apiKey 均非空）。 */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    public String model() { return model; }
}
