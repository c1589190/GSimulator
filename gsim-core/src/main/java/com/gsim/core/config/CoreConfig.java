package com.gsim.core.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * 主链（ConfigLoader）合并配置的只读视图 — 供 worldinfo 工具读取暂存阈值。
 *
 * <p>不再独立加载 classpath {@code core.properties}；实例由 {@link #from(Map, Map)} 从
 * ConfigLoader 合并结果构造。{@link #load()} / {@link #load(Path)} 仅为测试兼容保留
 * （classpath 资源已删除，返回空值视图，{@code getInt} 落到调用方默认参数）。
 */
public final class CoreConfig {
    public static final String QUERY_STAGING_THRESHOLD = "core.doc.query.staging.threshold";
    public static final String CACHE_STAGING_THRESHOLD = "agent.subagent.cache.staging.threshold";
    private final Map<String, String> values;
    private final Map<String, String> defaults;

    private CoreConfig(Map<String, String> values, Map<String, String> defaults) {
        this.values = Map.copyOf(values);
        this.defaults = Map.copyOf(defaults);
    }

    /**
     * 从主链合并结果构造只读视图。
     *
     * @param mergedValues 合并后的配置值（含用户覆盖，ConfigLoader 主链结果）
     * @param defaults     兜底默认值（主链未携带或值非法时使用；可为空，此时 getInt 落到调用方默认）
     */
    public static CoreConfig from(Map<String, String> mergedValues, Map<String, String> defaults) {
        return new CoreConfig(mergedValues, defaults);
    }

    /** 测试兼容：classpath {@code core.properties} 已删除，返回空值视图。 */
    public static CoreConfig load() {
        return new CoreConfig(Map.of(), Map.of());
    }

    /** 测试兼容：classpath {@code core.properties} 已删除，外部文件不再被读取。 */
    public static CoreConfig load(Path externalFile) {
        return load();
    }

    public String get(String key) {
        return values.get(key);
    }

    public int getInt(String key, int defaultValue) {
        String v = values.get(key);
        if (v != null && !v.isBlank()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                // values 非法 → 回退 defaults
            }
        }
        String d = defaults.get(key);
        if (d == null || d.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(d.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
