package com.gsim.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 轻量配置 — classpath 内置默认（core.properties）+ 外部文件覆盖。
 *
 * <p>独立于 ConfigLoader：零依赖，仅使用 JDK 与自身 classpath 资源。
 */
public final class CoreConfig {
    public static final String STAGING_THRESHOLD = "core.doc.staging.threshold";
    private static final String RESOURCE = "/core.properties";
    private final Map<String, String> values;
    private final Map<String, String> defaults;

    private CoreConfig(Map<String, String> values, Map<String, String> defaults) {
        this.values = Map.copyOf(values);
        this.defaults = Map.copyOf(defaults);
    }

    public static CoreConfig load() {
        Map<String, String> m = new LinkedHashMap<>();
        try (var in = CoreConfig.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                p.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
            }
        } catch (IOException e) {
            // classpath 资源不可读时以空默认继续（不应发生）
        }
        return new CoreConfig(m, m);
    }

    public static CoreConfig load(Path externalFile) {
        CoreConfig base = load();
        if (externalFile == null || !Files.isRegularFile(externalFile)) return base;
        Map<String, String> m = new LinkedHashMap<>(base.values);
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(externalFile));
            p.forEach((k, v) -> m.put(String.valueOf(k), String.valueOf(v)));
        } catch (IOException e) {
            // 外部文件不可读时用 classpath 默认
        }
        return new CoreConfig(m, base.values);
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
                // values 非法 → 回退 classpath 默认（defaults）
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
