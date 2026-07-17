package com.gsim.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LlmConfigManager {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigManager.class);

    private final Path llmsPath;

    public LlmConfigManager(Path llmsPath) {
        this.llmsPath = llmsPath;
    }

    public List<Map<String, Object>> listProviders() throws IOException {
        LlmsConfigFile file = loadOrCreate();
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmConfig cfg : file.providers()) {
            result.add(providerToMap(cfg));
        }
        return result;
    }

    public Map<String, Object> getProvider(String id) throws IOException {
        LlmsConfigFile file = loadOrCreate();
        LlmConfig cfg = file.find(id);
        if (cfg == null) return Map.of("error", "Provider not found: " + id);
        return providerToMap(cfg);
    }

    public Map<String, Object> addProvider(String id, String name, String baseUrl,
                                            String model, String apiKey) throws IOException {
        LlmsConfigFile file = loadOrCreate();
        if (file.find(id) != null) {
            return Map.of("ok", false, "error", "Provider already exists: " + id);
        }
        LlmConfig cfg = new LlmConfig(
                id, name != null ? name : id, baseUrl, apiKey, model,
                0.3, 4096, null, null, file.providers().isEmpty());
        file.providers().add(cfg);
        file.save(llmsPath);
        log.info("Added LLM provider: {}", id);
        return Map.of("ok", true, "id", id);
    }

    public Map<String, Object> updateProvider(String id, String field, String value) throws IOException {
        LlmsConfigFile file = loadOrCreate();
        LlmConfig old = file.find(id);
        if (old == null) return Map.of("ok", false, "error", "Provider not found: " + id);

        LlmConfig updated = switch (field) {
            case "name" -> new LlmConfig(id, value, old.baseUrl(), old.apiKey(), old.model(),
                    old.defaultTemperature(), old.defaultMaxTokens(), old.extraBody(), old.thinking(), old.isDefault());
            case "baseUrl" -> new LlmConfig(id, old.name(), value, old.apiKey(), old.model(),
                    old.defaultTemperature(), old.defaultMaxTokens(), old.extraBody(), old.thinking(), old.isDefault());
            case "model" -> new LlmConfig(id, old.name(), old.baseUrl(), old.apiKey(), value,
                    old.defaultTemperature(), old.defaultMaxTokens(), old.extraBody(), old.thinking(), old.isDefault());
            case "apiKey" -> new LlmConfig(id, old.name(), old.baseUrl(), value, old.model(),
                    old.defaultTemperature(), old.defaultMaxTokens(), old.extraBody(), old.thinking(), old.isDefault());
            default -> null;
        };
        if (updated == null) return Map.of("ok", false, "error", "Unknown field: " + field);

        for (int i = 0; i < file.providers().size(); i++) {
            if (file.providers().get(i).id().equals(id)) {
                file.providers().set(i, updated);
                break;
            }
        }
        file.save(llmsPath);
        log.info("Updated LLM provider {} field={}", id, field);
        return Map.of("ok", true, "id", id, "field", field);
    }

    public Map<String, Object> deleteProvider(String id) throws IOException {
        LlmsConfigFile file = loadOrCreate();
        boolean removed = file.providers().removeIf(c -> c.id().equals(id));
        if (!removed) return Map.of("ok", false, "error", "Provider not found: " + id);
        file.save(llmsPath);
        log.info("Deleted LLM provider: {}", id);
        return Map.of("ok", true, "id", id);
    }

    public LlmConfig getRawConfig(String id) throws IOException {
        LlmsConfigFile file = loadOrCreate();
        return file.find(id);
    }

    private LlmsConfigFile loadOrCreate() throws IOException {
        if (Files.exists(llmsPath)) {
            return LlmsConfigFile.load(llmsPath);
        }
        LlmsConfigFile template = LlmsConfigFile.createDefaultTemplate();
        template.save(llmsPath);
        return template;
    }

    private static Map<String, Object> providerToMap(LlmConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cfg.id());
        m.put("name", cfg.name());
        m.put("baseUrl", cfg.baseUrl());
        m.put("model", cfg.model());
        m.put("defaultTemperature", cfg.defaultTemperature());
        m.put("defaultMaxTokens", cfg.defaultMaxTokens());
        m.put("isDefault", cfg.isDefault());
        m.put("hasApiKey", cfg.apiKey() != null && !cfg.apiKey().isBlank());
        return m;
    }
}
