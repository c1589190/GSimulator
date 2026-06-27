package com.gsim.llm;

import com.gsim.app.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 配置管理层 — 封装 llms.json 的读写、字段更新、校验、脱敏。
 */
public class LlmConfigManager {

    private final Path llmsPath;

    public LlmConfigManager(Path llmsPath) {
        this.llmsPath = llmsPath;
    }

    /** 列出所有 provider（API Key 脱敏）。 */
    public List<Map<String, Object>> listProviders() {
        LlmsConfigFile file = load();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LlmConfig c : file.providers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("baseUrl", c.baseUrl());
            m.put("apiKey", AppConfig.maskValue(c.apiKey()));
            m.put("model", c.model());
            m.put("temperature", c.defaultTemperature());
            m.put("maxTokens", c.defaultMaxTokens());
            m.put("isDefault", c.isDefault());
            m.put("hasThinking", c.thinking() != null && !c.thinking().isEmpty());
            list.add(m);
        }
        return list;
    }

    /** 获取单个 provider 详情（API Key 脱敏）。 */
    public Map<String, Object> getProvider(String id) {
        LlmsConfigFile file = load();
        LlmConfig c = file.find(id);
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("baseUrl", c.baseUrl());
        m.put("apiKey", AppConfig.maskValue(c.apiKey()));
        m.put("model", c.model());
        m.put("temperature", c.defaultTemperature());
        m.put("maxTokens", c.defaultMaxTokens());
        m.put("isDefault", c.isDefault());
        m.put("extraBody", c.extraBody());
        m.put("thinking", c.thinking());
        return m;
    }

    /** 更新 provider 的单个字段。原子写入。 */
    public UpdateResult updateProvider(String id, String field, String value) {
        LlmsConfigFile file = load();
        LlmConfig old = file.find(id);
        if (old == null) return UpdateResult.fail("Provider not found: " + id);

        List<LlmConfig> providers = new ArrayList<>(file.providers());
        int idx = -1;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).id().equals(id)) { idx = i; break; }
        }

        LlmConfig updated;
        try {
            updated = switch (field) {
                case "name" -> new LlmConfig(old.id(), value, old.baseUrl(), old.apiKey(),
                        old.model(), old.defaultTemperature(), old.defaultMaxTokens(),
                        old.extraBody(), old.thinking(), old.isDefault());
                case "baseUrl" -> new LlmConfig(old.id(), old.name(), value, old.apiKey(),
                        old.model(), old.defaultTemperature(), old.defaultMaxTokens(),
                        old.extraBody(), old.thinking(), old.isDefault());
                case "apiKey" -> new LlmConfig(old.id(), old.name(), old.baseUrl(), value,
                        old.model(), old.defaultTemperature(), old.defaultMaxTokens(),
                        old.extraBody(), old.thinking(), old.isDefault());
                case "model" -> new LlmConfig(old.id(), old.name(), old.baseUrl(), old.apiKey(),
                        value, old.defaultTemperature(), old.defaultMaxTokens(),
                        old.extraBody(), old.thinking(), old.isDefault());
                case "temperature" -> {
                    double t = Double.parseDouble(value);
                    if (t < 0 || t > 2.0)
                        throw new IllegalArgumentException("Temperature must be 0.0-2.0");
                    yield new LlmConfig(old.id(), old.name(), old.baseUrl(), old.apiKey(),
                            old.model(), t, old.defaultMaxTokens(),
                            old.extraBody(), old.thinking(), old.isDefault());
                }
                case "maxTokens" -> {
                    int mt = Integer.parseInt(value);
                    if (mt < 1) throw new IllegalArgumentException("maxTokens must be >= 1");
                    yield new LlmConfig(old.id(), old.name(), old.baseUrl(), old.apiKey(),
                            old.model(), old.defaultTemperature(), mt,
                            old.extraBody(), old.thinking(), old.isDefault());
                }
                default -> throw new IllegalArgumentException("Unknown field: " + field
                        + ". Valid: name, baseUrl, apiKey, model, temperature, maxTokens");
            };
        } catch (IllegalArgumentException e) {
            return UpdateResult.fail(e.getMessage());
        }

        providers.set(idx, updated);
        file.setProviders(providers);
        saveAtomically(file);

        return UpdateResult.ok("Updated " + field + " for provider " + id);
    }

    /** 测试 provider 连通性。 */
    public String testProvider(String id, LlmProviderRegistry registry) {
        LlmProvider provider = registry != null ? registry.get(id) : null;
        if (provider == null) {
            LlmsConfigFile file = load();
            LlmConfig c = file.find(id);
            if (c == null) return "Provider not found: " + id;
            ProviderConfig pc = c.toProviderConfig();
            LlmManager temp = new LlmManager(pc, id);
            try {
                boolean ok = temp.isAvailable();
                temp.close();
                return ok ? "Connected OK" : "Connection failed (check baseUrl / apiKey)";
            } catch (Exception e) {
                temp.close();
                return "Connection error: " + e.getMessage();
            }
        }
        try {
            boolean ok = provider.isAvailable();
            return ok ? "Connected OK" : "Connection failed";
        } catch (Exception e) {
            return "Connection error: " + e.getMessage();
        }
    }

    private LlmsConfigFile load() {
        try {
            return LlmsConfigFile.load(llmsPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load llms.json: " + e.getMessage(), e);
        }
    }

    private void saveAtomically(LlmsConfigFile file) {
        try {
            Path tmp = llmsPath.resolveSibling("llms.json.tmp");
            file.save(tmp);
            Files.move(tmp, llmsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save llms.json: " + e.getMessage(), e);
        }
    }

    public record UpdateResult(boolean success, String message) {
        public static UpdateResult ok(String msg) { return new UpdateResult(true, msg); }
        public static UpdateResult fail(String msg) { return new UpdateResult(false, msg); }
    }
}
