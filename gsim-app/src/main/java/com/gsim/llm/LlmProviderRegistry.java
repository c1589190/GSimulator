package com.gsim.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * LLM Provider 注册表 — 管理所有已加载的 Provider 实例。
 *
 * <p>由 {@link LlmsConfigLoader} 加载配置后创建。
 * 线程安全：所有写入在启动阶段完成，运行时只读。
 *
 * <h3>用法</h3>
 * <pre>{@code
 *   LlmProviderRegistry registry = LlmProviderRegistry.fromConfig(llmsConfigFile);
 *   LlmProvider deepseek = registry.get("deepseek");  // 按 ID 获取
 *   LlmProvider fallback = registry.get("unknown");     // 不存在 → 返回 default
 * }</pre>
 */
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private volatile String defaultId;

    /** 从 LlmsConfigFile 构建所有 provider 实例。 */
    public static LlmProviderRegistry fromConfig(LlmsConfigFile configFile) {
        LlmProviderRegistry registry = new LlmProviderRegistry();
        for (LlmConfig cfg : configFile.providers()) {
            LlmManager manager = new LlmManager(cfg.toProviderConfig(), cfg.id());
            registry.register(cfg.id(), manager);
            if (cfg.isDefault()) {
                registry.defaultId = cfg.id();
            }
            log.info("[LlmProviderRegistry] registered provider '{}': {} @ {}",
                    cfg.id(), cfg.name(), cfg.toProviderConfig().toSafeString());
        }
        // 若没有标记 default，用第一个
        if (registry.defaultId == null && !registry.providers.isEmpty()) {
            registry.defaultId = registry.providers.keySet().iterator().next();
        }
        return registry;
    }

    /** 注册一个 provider。 */
    public void register(String id, LlmProvider provider) {
        providers.put(id, provider);
    }

    /**
     * 按 ID 获取 provider。
     * 若 id 为 null 或不存在 → 返回默认 provider。
     */
    public LlmProvider get(String id) {
        if (id == null) return getDefault();
        LlmProvider p = providers.get(id);
        if (p != null) return p;
        log.warn("[LlmProviderRegistry] provider '{}' not found, falling back to default '{}'",
                id, defaultId);
        return getDefault();
    }

    /** 获取默认 provider。 */
    public LlmProvider getDefault() {
        LlmProvider p = providers.get(defaultId);
        if (p == null && !providers.isEmpty()) {
            p = providers.values().iterator().next();
        }
        if (p == null) {
            throw new IllegalStateException("No LLM providers registered");
        }
        return p;
    }

    /** 获取默认 provider ID。 */
    public String getDefaultId() {
        return defaultId;
    }

    /** 所有 provider ID。 */
    public Set<String> providerIds() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /** 获取所有 provider（用于遍历检查连通性等）。 */
    public Map<String, LlmProvider> all() {
        return Collections.unmodifiableMap(providers);
    }

    /** provider 数量。 */
    public int size() {
        return providers.size();
    }

    /** 关闭所有 provider。 */
    public void closeAll() {
        for (LlmProvider p : providers.values()) {
            try { p.close(); } catch (Exception e) {
                log.warn("[LlmProviderRegistry] error closing provider: {}", e.getMessage());
            }
        }
    }
}
