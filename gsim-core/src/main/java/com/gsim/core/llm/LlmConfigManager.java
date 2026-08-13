package com.gsim.core.llm;

import com.gsim.core.util.LogSanitizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 配置管理层 — 封装 llms.json 的运行时读写、字段更新、校验、脱敏。
 *
 * <p>提供 provider 的 CRUD 操作、连通性测试、原子写入等能力。
 * 与 {@link LlmProviderRegistry} 配合使用：本层负责持久化配置，
 * ProviderRegistry 负责根据配置构建运行时实例。
 */
public class LlmConfigManager {

    private final Path llmsPath;

    public LlmConfigManager(Path llmsPath) {
        this.llmsPath = llmsPath;
    }

    /**
     * 列出所有 provider，API Key 已脱敏。
     *
     * @return provider 列表，每个元素为字段映射（不含原始 apiKey）
     */
    public List<Map<String, Object>> listProviders() {
        LlmsConfigFile file = load();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LlmConfig c : file.providers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("baseUrl", c.baseUrl());
            m.put("apiKey", LogSanitizer.maskValue(c.apiKey()));
            m.put("model", c.model());
            m.put("temperature", c.defaultTemperature());
            m.put("maxTokens", c.defaultMaxTokens());
            m.put("isDefault", c.isDefault());
            m.put("hasThinking", c.thinking() != null && !c.thinking().isEmpty());
            list.add(m);
        }
        return list;
    }

    /**
     * 获取单个 provider 详情，API Key 已脱敏。
     *
     * @param id provider ID
     * @return 详情映射，若不存在返回 null
     */
    public Map<String, Object> getProvider(String id) {
        LlmsConfigFile file = load();
        LlmConfig c = file.find(id);
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("baseUrl", c.baseUrl());
        m.put("apiKey", LogSanitizer.maskValue(c.apiKey()));
        m.put("model", c.model());
        m.put("temperature", c.defaultTemperature());
        m.put("maxTokens", c.defaultMaxTokens());
        m.put("isDefault", c.isDefault());
        m.put("extraBody", c.extraBody());
        m.put("thinking", c.thinking());
        return m;
    }

    /**
     * 更新 provider 的单个字段（原子写入）。
     *
     * <p>支持的字段：name, baseUrl, apiKey, model, temperature, maxTokens。
     *
     * @param id    provider ID
     * @param field 字段名
     * @param value 新值
     * @return 更新结果
     */
    public UpdateResult updateProvider(String id, String field, String value) {
        LlmsConfigFile file = load();
        LlmConfig old = file.find(id);
        if (old == null) return UpdateResult.fail("Provider not found: " + id);

        List<LlmConfig> providers = new ArrayList<>(file.providers());
        int idx = -1;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).id().equals(id)) {
                idx = i;
                break;
            }
        }

        LlmConfig updated;
        try {
            updated = switch (field) {
                case "name" -> new LlmConfig(
                        old.id(),
                        value,
                        old.baseUrl(),
                        old.apiKey(),
                        old.model(),
                        old.defaultTemperature(),
                        old.defaultMaxTokens(),
                        old.extraBody(),
                        old.thinking(),
                        old.isDefault());
                case "baseUrl" -> new LlmConfig(
                        old.id(),
                        old.name(),
                        value,
                        old.apiKey(),
                        old.model(),
                        old.defaultTemperature(),
                        old.defaultMaxTokens(),
                        old.extraBody(),
                        old.thinking(),
                        old.isDefault());
                case "apiKey" -> new LlmConfig(
                        old.id(),
                        old.name(),
                        old.baseUrl(),
                        value,
                        old.model(),
                        old.defaultTemperature(),
                        old.defaultMaxTokens(),
                        old.extraBody(),
                        old.thinking(),
                        old.isDefault());
                case "model" -> new LlmConfig(
                        old.id(),
                        old.name(),
                        old.baseUrl(),
                        old.apiKey(),
                        value,
                        old.defaultTemperature(),
                        old.defaultMaxTokens(),
                        old.extraBody(),
                        old.thinking(),
                        old.isDefault());
                case "temperature" -> {
                    double t = Double.parseDouble(value);
                    if (t < 0 || t > 2.0) throw new IllegalArgumentException("Temperature must be 0.0-2.0");
                    yield new LlmConfig(
                            old.id(),
                            old.name(),
                            old.baseUrl(),
                            old.apiKey(),
                            old.model(),
                            t,
                            old.defaultMaxTokens(),
                            old.extraBody(),
                            old.thinking(),
                            old.isDefault());
                }
                case "maxTokens" -> {
                    int mt = Integer.parseInt(value);
                    if (mt < 1) throw new IllegalArgumentException("maxTokens must be >= 1");
                    yield new LlmConfig(
                            old.id(),
                            old.name(),
                            old.baseUrl(),
                            old.apiKey(),
                            old.model(),
                            old.defaultTemperature(),
                            mt,
                            old.extraBody(),
                            old.thinking(),
                            old.isDefault());
                }
                default -> throw new IllegalArgumentException(
                        "Unknown field: " + field + ". Valid: name, baseUrl, apiKey, model, temperature, maxTokens");
            };
        } catch (IllegalArgumentException e) {
            return UpdateResult.fail(e.getMessage());
        }

        providers.set(idx, updated);
        file.setProviders(providers);
        saveAtomically(file);

        return UpdateResult.ok("Updated " + field + " for provider " + id);
    }

    /**
     * 测试指定 provider 的连通性。
     *
     * <p>优先使用注册表中已有的 provider 实例进行测试；
     * 若注册表中不存在，临时创建一个 {@link LlmManager} 实例。
     *
     * @param id       provider ID
     * @param registry 可选的 provider 注册表（可为 null）
     * @return 连接结果描述
     */
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

    /**
     * 添加新 provider（原子写入）。
     *
     * <p>若当前无任何 provider，自动设为默认。
     *
     * @param id      provider 唯一标识
     * @param name    显示名称（可为 null，默认取 id）
     * @param baseUrl API 基础 URL
     * @param apiKey  API 密钥（可为 null 或空）
     * @param model   模型名称
     * @return 添加结果
     */
    public UpdateResult addProvider(String id, String name, String baseUrl, String apiKey, String model) {
        if (id == null || id.isBlank()) return UpdateResult.fail("id is required");
        if (baseUrl == null || baseUrl.isBlank()) return UpdateResult.fail("baseUrl is required");
        if (model == null || model.isBlank()) return UpdateResult.fail("model is required");

        LlmsConfigFile file = load();
        if (file.find(id) != null) return UpdateResult.fail("Provider already exists: " + id);

        List<LlmConfig> providers = new ArrayList<>(file.providers());
        boolean isDefault = providers.isEmpty();
        LlmConfig cfg = new LlmConfig(
                id,
                name != null ? name : id,
                baseUrl,
                apiKey != null ? apiKey : "",
                model,
                0.3,
                4096,
                null,
                null,
                isDefault);
        providers.add(cfg);
        file.setProviders(providers);
        saveAtomically(file);

        return UpdateResult.ok("Added provider " + id + (isDefault ? " (default)" : ""));
    }

    /**
     * 删除指定 provider（不允许删除最后一个）。
     *
     * <p>若删除的是默认 provider，自动将剩余第一个设为默认。
     *
     * @param id 要删除的 provider ID
     * @return 删除结果
     */
    public UpdateResult removeProvider(String id) {
        LlmsConfigFile file = load();
        LlmConfig target = file.find(id);
        if (target == null) return UpdateResult.fail("Provider not found: " + id);

        List<LlmConfig> providers = new ArrayList<>(file.providers());
        if (providers.size() <= 1) {
            return UpdateResult.fail("Cannot remove the last provider");
        }

        providers.removeIf(p -> p.id().equals(id));

        // If we removed the default, make the first remaining one default
        if (target.isDefault()) {
            LlmConfig first = providers.get(0);
            providers.set(
                    0,
                    new LlmConfig(
                            first.id(),
                            first.name(),
                            first.baseUrl(),
                            first.apiKey(),
                            first.model(),
                            first.defaultTemperature(),
                            first.defaultMaxTokens(),
                            first.extraBody(),
                            first.thinking(),
                            true));
        }

        file.setProviders(providers);
        saveAtomically(file);

        return UpdateResult.ok("Removed provider " + id);
    }

    /** @deprecated Use {@link #removeProvider(String)} instead. */
    @Deprecated
    public Map<String, Object> deleteProvider(String id) {
        UpdateResult r = removeProvider(id);
        return Map.of("ok", r.success(), "message", r.message());
    }

    /**
     * 更新操作结果。
     *
     * @param success 是否成功
     * @param message 结果描述信息
     */
    public record UpdateResult(boolean success, String message) {
        /**
         * 创建成功结果。
         *
         * @param msg 成功描述
         * @return UpdateResult 实例
         */
        public static UpdateResult ok(String msg) {
            return new UpdateResult(true, msg);
        }

        /**
         * 创建失败结果。
         *
         * @param msg 失败描述
         * @return UpdateResult 实例
         */
        public static UpdateResult fail(String msg) {
            return new UpdateResult(false, msg);
        }
    }
}
