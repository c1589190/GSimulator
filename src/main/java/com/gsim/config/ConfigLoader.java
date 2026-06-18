package com.gsim.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 配置加载器 — 按优先级链加载配置。
 *
 * 优先级：
 * 1. CLI --config &lt;path&gt;
 * 2. env GSIM_CONFIG
 * 3. ./gsim.properties
 * 4. ./.env
 * 5. ~/.gsimulator/config.properties
 * 6. ~/.gsimulator/.env
 * 7. System.getenv()
 * 8. 内置默认值
 *
 * 同名 key 先匹配到的 wins。
 */
public class ConfigLoader {

    private final String[] args;
    private final CliArgs cliArgs;

    public ConfigLoader(String[] args) {
        this.args = args != null ? args.clone() : new String[0];
        this.cliArgs = parseCliArgs(this.args);
    }

    // ---- CLI 参数解析 ----

    private static CliArgs parseCliArgs(String[] args) {
        String configPath = null;
        boolean initConfig = false;
        boolean doctor = false;
        boolean noWizard = false;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                    if (i + 1 < args.length) configPath = args[++i];
                    break;
                case "--init-config":
                    initConfig = true;
                    break;
                case "--doctor":
                    doctor = true;
                    break;
                case "--no-wizard":
                    noWizard = true;
                    break;
                case "--help":
                    help = true;
                    break;
                // ignore unknown args
            }
        }

        return new CliArgs(configPath, initConfig, doctor, noWizard, help);
    }

    public record CliArgs(String configPath, boolean initConfig, boolean doctor, boolean noWizard, boolean help) {}

    public CliArgs getCliArgs() {
        return cliArgs;
    }

    // ---- 主加载方法 ----

    /**
     * 按优先级加载配置，返回最终结果。
     */
    public ConfigResult load() {
        Map<String, ConfigEntry> merged = new LinkedHashMap<>();

        // Step 1: 内置默认值 (最低优先级)
        Map<String, String> defaults = buildDefaults();
        for (var entry : defaults.entrySet()) {
            merged.put(entry.getKey(), new ConfigEntry(entry.getKey(), entry.getValue(), ConfigSource.DEFAULT));
        }

        // Step 2: 系统环境变量
        Map<String, String> sysEnv = System.getenv();
        for (var entry : sysEnv.entrySet()) {
            String mappedKey = mapEnvKey(entry.getKey());
            if (mappedKey != null && !entry.getValue().isBlank()) {
                merged.put(mappedKey, new ConfigEntry(mappedKey, entry.getValue(), ConfigSource.SYSTEM_ENV));
            }
        }

        // Step 3: ~/.gsimulator/.env
        loadDotEnvIfExists(ConfigSource.USER_DOTENV, userConfigDir().resolve(".env"), merged);

        // Step 4: ~/.gsimulator/config.properties
        loadPropertiesIfExists(ConfigSource.USER_PROPERTIES, userConfigDir().resolve("config.properties"), merged);

        // Step 5: ./.env
        loadDotEnvIfExists(ConfigSource.CWD_DOTENV, Path.of(".env"), merged);

        // Step 6: ./gsim.properties
        loadPropertiesIfExists(ConfigSource.CWD_PROPERTIES, Path.of("gsim.properties"), merged);

        // Step 7: env GSIM_CONFIG → properties
        String gsimConfigPath = sysEnv.get("GSIM_CONFIG");
        if (gsimConfigPath != null && !gsimConfigPath.isBlank()) {
            loadPropertiesIfExists(ConfigSource.GSIM_CONFIG_ENV, Path.of(gsimConfigPath), merged);
        }

        // Step 8: CLI --config (最高优先级)
        if (cliArgs.configPath != null && !cliArgs.configPath.isBlank()) {
            loadPropertiesIfExists(ConfigSource.CLI, Path.of(cliArgs.configPath), merged);
        }

        // 确定有效的配置文件路径
        Path effectiveConfigPath = resolveEffectiveConfigPath();

        return new ConfigResult(Map.copyOf(merged), effectiveConfigPath);
    }

    // ---- 私有：加载器 ----

    private void loadPropertiesIfExists(ConfigSource source, Path path, Map<String, ConfigEntry> merged) {
        if (Files.isRegularFile(path)) {
            Properties props = loadPropertiesFile(path);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value != null && !value.isBlank()) {
                    merged.put(key, new ConfigEntry(key, value, source));
                }
            }
        }
    }

    private void loadDotEnvIfExists(ConfigSource source, Path path, Map<String, ConfigEntry> merged) {
        if (Files.isRegularFile(path)) {
            Map<String, String> envMap = loadDotEnvFile(path);
            for (var entry : envMap.entrySet()) {
                String mappedKey = mapEnvKey(entry.getKey());
                if (mappedKey != null && !entry.getValue().isBlank()) {
                    merged.put(mappedKey, new ConfigEntry(mappedKey, entry.getValue(), source));
                }
            }
        }
    }

    /**
     * 加载 .properties 文件。
     */
    public static Properties loadPropertiesFile(Path path) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            // skip corrupted files
        }
        return props;
    }

    /**
     * 解析 .env 文件为简单 Map。
     * 支持：KEY=VALUE, KEY="VALUE", # 注释, 空行。
     */
    static Map<String, String> loadDotEnvFile(Path path) {
        Map<String, String> result = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eqIdx = line.indexOf('=');
                if (eqIdx <= 0) continue;
                String key = line.substring(0, eqIdx).trim();
                String value = line.substring(eqIdx + 1).trim();

                // 去除引号
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }

                if (!key.isEmpty()) {
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            // skip
        }
        return result;
    }

    /**
     * 将环境变量风格 key 映射为 properties 风格 key。
     * e.g. LLM_BASE_URL → llm.base_url
     * e.g. GSIM_DATA_DIR → data.dir
     */
    static String mapEnvKey(String envKey) {
        return switch (envKey) {
            case "LLM_BASE_URL" -> "llm.base_url";
            case "LLM_API_KEY" -> "llm.api_key";
            case "LLM_MODEL" -> "llm.model";
            case "LLM_TEMPERATURE" -> "llm.temperature";
            case "LLM_TIMEOUT_SECONDS" -> "llm.timeout_seconds";
            case "GSIM_DATA_DIR" -> "data.dir";
            case "GSIM_IMPORT_DIR" -> "import.dir";
            case "GSIM_OUTPUT_DIR" -> "output.dir";
            case "GSIM_LOG_DIR" -> "log.dir";
            case "CHROMA_BASE_URL" -> "chroma.base_url";
            case "CHROMA_ENABLED" -> "chroma.enabled";
            case "WEB_RESEARCH_ENABLED" -> "web_research.enabled";
            case "WEB_RESEARCH_TIMEOUT_SECONDS" -> "web_research.timeout_seconds";
            case "WEB_RESEARCH_USER_AGENT" -> "web_research.user_agent";
            default -> null; // unrecognized env vars ignored
        };
    }

    // ---- 内置默认值 ----

    private Map<String, String> buildDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("llm.base_url", "");
        defaults.put("llm.api_key", "");
        defaults.put("llm.model", "deepseek-v4-pro");
        defaults.put("llm.temperature", "0.3");
        defaults.put("llm.timeout_seconds", "120");

        defaults.put("data.dir", "data");
        defaults.put("import.dir", "import");
        defaults.put("output.dir", "data/outputs");
        defaults.put("log.dir", "data/logs");

        defaults.put("chroma.enabled", "false");
        defaults.put("chroma.base_url", "http://localhost:8000");

        defaults.put("web_research.enabled", "false");
        defaults.put("web_research.timeout_seconds", "30");
        defaults.put("web_research.user_agent", "GSimulator/0.1.0 (research-bot)");

        return defaults;
    }

    // ---- helpers ----

    /**
     * 当前是否为交互式终端（有 console）。
     */
    public static boolean isInteractiveTerminal() {
        return System.console() != null;
    }

    /**
     * 用户配置目录 ~/.gsimulator/
     */
    public static Path userConfigDir() {
        return Path.of(System.getProperty("user.home"), ".gsimulator");
    }

    /**
     * 获取指定 key 的配置来源信息。
     */
    public static ConfigSource getSource(Map<String, ConfigEntry> entries, String key) {
        ConfigEntry entry = entries.get(key);
        return entry != null ? entry.source() : ConfigSource.DEFAULT;
    }

    /**
     * 解析有效配置文件路径（最高优先级的 properties 文件）。
     */
    private Path resolveEffectiveConfigPath() {
        if (cliArgs.configPath != null && !cliArgs.configPath.isBlank()) return Path.of(cliArgs.configPath);
        String gsimConfig = System.getenv("GSIM_CONFIG");
        if (gsimConfig != null && !gsimConfig.isBlank()) return Path.of(gsimConfig);
        if (Files.isRegularFile(Path.of("gsim.properties"))) return Path.of("gsim.properties").toAbsolutePath();
        Path userProps = userConfigDir().resolve("config.properties");
        if (Files.isRegularFile(userProps)) return userProps;
        return null;
    }

    // ---- 结果类型 ----

    /**
     * 配置条目：key + value + 来源。
     */
    public record ConfigEntry(String key, String value, ConfigSource source) {}

    /**
     * 完整加载结果。
     */
    public record ConfigResult(Map<String, ConfigEntry> entries, Path configPath) {
        /**
         * 获取字符串值。
         */
        public String get(String key) {
            ConfigEntry entry = entries.get(key);
            return entry != null ? entry.value() : "";
        }

        /**
         * 按来源分组显示摘要。
         */
        public String sourceSummary() {
            Map<ConfigSource, Long> counts = entries.values().stream()
                    .collect(Collectors.groupingBy(ConfigEntry::source, LinkedHashMap::new, Collectors.counting()));
            StringBuilder sb = new StringBuilder();
            for (var entry : counts.entrySet()) {
                sb.append("  ").append(entry.getKey().label()).append(": ").append(entry.getValue()).append(" keys\n");
            }
            return sb.toString().trim();
        }
    }
}
