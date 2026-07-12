package com.gsim.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * llms.json 文件模型 — 包含所有 LLM Provider 定义。
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "providers": [
 *     { "id": "deepseek", "name": "...", "baseUrl": "...", "apiKey": "${ENV}", "model": "..." }
 *   ]
 * }
 * }</pre>
 */
public class LlmsConfigFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("version")
    private int version;

    @JsonProperty("providers")
    private List<LlmConfig> providers;

    public LlmsConfigFile() {
        this.version = 1;
        this.providers = new ArrayList<>();
    }

    public LlmsConfigFile(int version, List<LlmConfig> providers) {
        this.version = version;
        this.providers = providers;
    }

    public int version() { return version; }
    public List<LlmConfig> providers() { return providers; }

    public void setVersion(int v) { this.version = v; }
    public void setProviders(List<LlmConfig> v) { this.providers = v; }

    /** 获取默认 provider（有且仅有一个标记 default: true）。若无标记，返回第一个。 */
    public LlmConfig defaultConfig() {
        for (LlmConfig c : providers) {
            if (c.isDefault()) return c;
        }
        return providers.isEmpty() ? null : providers.get(0);
    }

    /** 按 ID 查找。 */
    public LlmConfig find(String id) {
        for (LlmConfig c : providers) {
            if (c.id().equals(id)) return c;
        }
        return null;
    }

    // ---- 文件 I/O ----

    /** 从 JSON 文件加载。 */
    public static LlmsConfigFile load(Path path) throws IOException {
        String raw = Files.readString(path);
        JsonNode root = MAPPER.readTree(raw);
        int ver = root.path("version").asInt(1);
        List<LlmConfig> list = new ArrayList<>();
        JsonNode arr = root.path("providers");
        if (arr.isArray()) {
            for (JsonNode node : arr) {
                list.add(LlmConfig.fromJson(node));
            }
        }
        return new LlmsConfigFile(ver, list);
    }

    /** 写入 JSON 文件。 */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        Files.writeString(path, json);
    }

    /** 创建默认模板（从环境变量读取 LLM_* 配置）。Provider 固定命名为 "base"。 */
    public static LlmsConfigFile createDefaultTemplate() {
        String baseUrl = System.getenv("LLM_BASE_URL");
        String apiKey = System.getenv("LLM_API_KEY");
        String model = System.getenv("LLM_MODEL");

        // Fallback to common defaults if env vars not set
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-v4-pro";
        }
        // If no apiKey env var, use placeholder
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = "${LLM_API_KEY}";
        }

        LlmConfig defaultProvider = new LlmConfig(
                "base",
                "Base LLM",
                baseUrl,
                apiKey,
                model,
                0.3,
                4096,
                null,
                null,
                true
        );

        return new LlmsConfigFile(1, List.of(defaultProvider));
    }

    @Override
    public String toString() {
        return "LlmsConfigFile{version=" + version + ", providers=" + providers.size() + "}";
    }
}
