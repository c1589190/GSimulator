package com.gsim.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        boolean noCli = false;
        boolean agent = false;

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
                case "--no-cli":
                    noCli = true;
                    break;
                case "--agent":
                    agent = true;
                    break;
                    // ignore unknown args
            }
        }

        return new CliArgs(configPath, initConfig, doctor, noWizard, help, noCli, agent);
    }

    public record CliArgs(
            String configPath,
            boolean initConfig,
            boolean doctor,
            boolean noWizard,
            boolean help,
            boolean noCli,
            boolean agent) {}

    public CliArgs getCliArgs() {
        return cliArgs;
    }

    // ---- 主加载方法 ----

    /**
     * 按优先级链加载配置，返回最终结果。
     * <p>优先级（由低到高）：内置默认值 → 系统环境变量 → 用户 .env → 用户 config.properties
     * → CWD .env → CWD gsim.properties → GSIM_CONFIG 环境变量 → CLI --config。</p>
     *
     * @return 配置加载结果，包含所有已合并的配置条目和有效配置文件路径
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
     * 加载 {@code .properties} 文件并返回 Properties 对象。
     * <p>文件不存在或读取失败时返回空 Properties，不会抛出异常。</p>
     *
     * @param path properties 文件路径
     * @return 加载的 Properties 对象（不会为 null）
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
     * 解析 {@code .env} 文件为简单的字符串键值对 Map。
     * <p>支持格式：{@code KEY=VALUE}、{@code KEY="VALUE"}、{@code #} 注释行、空行。
     * 自动去除值两侧的引号。</p>
     *
     * @param path .env 文件路径
     * @return 解析后的键值对 Map（不会为 null）
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
     * <p>例如：{@code LLM_BASE_URL → llm.base_url}，{@code GSIM_DATA_DIR → data.dir}。</p>
     *
     * @param envKey 环境变量名（如 {@code LLM_BASE_URL}）
     * @return 映射后的 properties key，若无法识别则返回 {@code null}
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
            case "API_HOST" -> "api.host";
            case "API_PORT" -> "api.port";
            case "API_ENABLED" -> "api.enabled";
            case "WEBUI_HOST" -> "webui.host";
            case "WEBUI_PORT" -> "webui.port";
            case "WEBUI_ENABLED" -> "webui.enabled";
            case "GSIMAP_PORT", "GSIM_MAP_PORT" -> "map.port";
            case "CLI_WS_PORT", "GSIM_CLI_WS_PORT" -> "cli.ws.port";
            case "MCP_HTTP_PORT", "GSIM_MCP_HTTP_PORT" -> "mcp.http.port";
            case "EMBEDDING_PROVIDER" -> "embedding.provider";
            case "EMBEDDING_BASE_URL" -> "embedding.base_url";
            case "EMBEDDING_API_KEY" -> "embedding.api_key";
            case "EMBEDDING_MODEL" -> "embedding.model";
            case "EMBEDDING_DIMENSIONS" -> "embedding.dimensions";
            case "EMBEDDING_MODEL_DIR" -> "embedding.model_dir";
            case "GSIM_CONTEXT_SESSION_HISTORY_TURNS" -> "context.session.history.turns";
            case "GSIM_CONTEXT_SESSION_MESSAGE_MAX_CHARS" -> "context.session.message.max_chars";
            case "GSIM_AGENT_TOOL_LOOP_MAX_ROUNDS" -> "agent.tool_loop.max_rounds";
            case "GSIM_LLM_STREAM_ENABLED" -> "llm.stream.enabled";
            case "GSIM_CLI_STREAM_PREVIEW_ENABLED" -> "cli.stream.preview.enabled";
            case "GSIM_CLI_STREAM_PREVIEW_MAX_CHARS" -> "cli.stream.preview.max_chars";
            case "GSIM_CLI_STREAM_PREVIEW_SHOW_REASONING" -> "cli.stream.preview.show_reasoning";
            case "GSIM_AGENT_TOOL_LOOP_RESULT_INLINE_MAX_CHARS" -> "agent.tool_loop.result_inline_max_chars";
            case "GSIM_AGENT_TOOL_LOOP_RESULT_STAGING_ENABLED" -> "agent.tool_loop.result_staging.enabled";
            case "GSIM_MCP_RESPONSE_MAX_JSON_BYTES" -> "mcp.response.max_json_bytes";
            case "GSIM_MCP_RESPONSE_SNIPPET_MAX_CHARS" -> "mcp.response.snippet_max_chars";
            case "GSIM_MCP_RESPONSE_DEFAULT_PAGE_SIZE" -> "mcp.response.default_page_size";
            case "GSIM_MCP_RESPONSE_MAX_PAGE_SIZE" -> "mcp.response.max_page_size";
            case "GSIM_MCP_RESPONSE_OVERFLOW_STAGING_ENABLED" -> "mcp.response.overflow_staging.enabled";
            case "GSIM_CORE_DOC_STAGING_THRESHOLD" -> "core.doc.staging.threshold";
            case "GSIM_CORE_DOC_QUERY_STAGING_THRESHOLD" -> "core.doc.query.staging.threshold";
            case "GSIM_CORE_DOC_TMP_MAX_AGE_HOURS" -> "core.doc.tmp.max_age_hours";
            case "GSIM_CORE_DOC_TMP_CLEANUP_ENABLED" -> "core.doc.tmp.cleanup_enabled";
            case "GSIM_DOCS_DIR" -> "docs.dir";
            case "GSIM_CACHES_DIR" -> "caches.dir";
            case "GSIM_KNOWLEDGE_DB_PATH" -> "knowledge.db.path";
            case "GSIM_EMBEDDING_TIMEOUT_CONNECT_SECONDS" -> "embedding.timeout_connect_seconds";
            case "GSIM_EMBEDDING_TIMEOUT_READ_SECONDS" -> "embedding.timeout_read_seconds";
            case "GSIM_EMBEDDING_TIMEOUT_WRITE_SECONDS" -> "embedding.timeout_write_seconds";
            case "GSIM_AGENT_SUBAGENT_COLLECT_TIMEOUT_SECONDS" -> "agent.subagent.collect.timeout_seconds";
            case "GSIM_AGENT_SUBAGENT_MAX_COMPLETED" -> "agent.subagent.max_completed";
            case "GSIM_IMPORT_DOC_MAX_FULL_READ_CHARS" -> "import.doc.max_full_read_chars";
            case "GSIM_IMPORT_DOC_DEFAULT_LIMIT" -> "import.doc.default_limit";
            case "GSIM_WEB_RESEARCH_WIKI_URL" -> "web_research.wiki.url";
            case "GSIM_MAP_RADIUS_DEFAULT" -> "map.radius.default";
            case "GSIM_MAP_CACHE_MAX_ENTRIES" -> "map.cache.max_entries";
            case "GSIM_MAP_CONTOUR_CACHE_MAX" -> "map.contour.cache.max";
            case "GSIM_MAP_LASSO_MAX_RADIUS" -> "map.lasso.max_radius";
            case "GSIM_MAP_LASSO_MAX_FILL" -> "map.lasso.max_fill";
            case "GSIM_MAP_COMPRESSION_MIN_REGION_SIZE" -> "map.compression.min_region_size";
            case "GSIM_MAP_RESOLVER_MAX_CHAIN_DEPTH" -> "map.resolver.max_chain_depth";
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
        defaults.put("worlds.dir", "worlds");

        defaults.put("api.host", "127.0.0.1");
        defaults.put("api.port", "8710");
        defaults.put("api.enabled", "false");

        // 本地服务端口 — 可在 gsim.properties 中覆盖；环境变量映射作为 fallback
        defaults.put("webui.host", "127.0.0.1");
        defaults.put("webui.port", "8710");
        defaults.put("webui.enabled", "false");
        defaults.put("map.port", "8711");
        defaults.put("cli.ws.port", "8712");
        defaults.put("mcp.http.port", "37201");

        defaults.put("context.session.history.turns", "12");
        defaults.put("context.session.message.max_chars", "4000");

        defaults.put("agent.tool_loop.max_rounds", "64");

        // Agent ToolLoop 结果回传 — 超限时改写为 @cache 引用，落盘 staging
        defaults.put("agent.tool_loop.result_inline_max_chars", "4000");
        defaults.put("agent.tool_loop.result_staging.enabled", "true");

        // MCP 响应限流与分页 — 超出走 overflow staging
        defaults.put("mcp.response.max_json_bytes", "50000");
        defaults.put("mcp.response.snippet_max_chars", "300");
        defaults.put("mcp.response.default_page_size", "20");
        defaults.put("mcp.response.max_page_size", "100");
        defaults.put("mcp.response.overflow_staging.enabled", "true");

        // 文档暂存与临时目录清理
        defaults.put("core.doc.staging.threshold", "500");
        defaults.put("core.doc.query.staging.threshold", "3000");
        defaults.put("core.doc.tmp.max_age_hours", "168");
        defaults.put("core.doc.tmp.cleanup_enabled", "true");

        // 目录与知识库（空串 = 未设置，下游解析为 worldsDir 同级目录）
        defaults.put("docs.dir", "");
        defaults.put("caches.dir", "");
        defaults.put("knowledge.db.path", "");

        // 嵌入服务超时（秒）
        defaults.put("embedding.timeout_connect_seconds", "30");
        defaults.put("embedding.timeout_read_seconds", "60");
        defaults.put("embedding.timeout_write_seconds", "30");

        // SubAgent 收集超时与缓存上限
        defaults.put("agent.subagent.collect.timeout_seconds", "300");
        defaults.put("agent.subagent.max_completed", "100");

        // 导入文档读取限制
        defaults.put("import.doc.max_full_read_chars", "30000");
        defaults.put("import.doc.default_limit", "8000");

        // Web 研究
        defaults.put("web_research.wiki.url", "https://en.wikipedia.org/w/api.php");

        // 地图查询与编辑限制
        defaults.put("map.radius.default", "80");
        defaults.put("map.cache.max_entries", "32");
        defaults.put("map.contour.cache.max", "5000");
        defaults.put("map.lasso.max_radius", "200");
        defaults.put("map.lasso.max_fill", "30000");
        defaults.put("map.compression.min_region_size", "100");
        defaults.put("map.resolver.max_chain_depth", "200");

        defaults.put("llm.stream.enabled", "true");
        defaults.put("cli.stream.preview.enabled", "true");
        defaults.put("cli.stream.preview.max_chars", "3000");
        defaults.put("cli.stream.preview.show_reasoning", "true");

        return defaults;
    }

    // ---- helpers ----

    /**
     * 判断当前是否为交互式终端（有可用 Console）。
     *
     * @return 如果是交互式终端则返回 {@code true}
     */
    public static boolean isInteractiveTerminal() {
        return System.console() != null;
    }

    /**
     * 获取用户配置目录路径 {@code ~/.gsimulator/}。
     *
     * @return 用户配置目录的 Path
     */
    public static Path userConfigDir() {
        return Path.of(System.getProperty("user.home"), ".gsimulator");
    }

    /**
     * 获取指定 key 的配置来源信息。
     *
     * @param entries 配置条目 Map
     * @param key     要查询的配置 key
     * @return 配置来源枚举，未找到时返回 {@link ConfigSource#DEFAULT}
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
        if (Files.isRegularFile(Path.of("gsim.properties")))
            return Path.of("gsim.properties").toAbsolutePath();
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
         * 获取指定 key 的字符串配置值。
         *
         * @param key 配置 key
         * @return 配置值字符串，不存在时返回空字符串
         */
        public String get(String key) {
            ConfigEntry entry = entries.get(key);
            return entry != null ? entry.value() : "";
        }

        /**
         * 按配置来源分组显示统计摘要。
         *
         * @return 来源统计文本，每行格式为 "{来源标签}: {key 数量} keys"
         */
        public String sourceSummary() {
            Map<ConfigSource, Long> counts = entries.values().stream()
                    .collect(Collectors.groupingBy(ConfigEntry::source, LinkedHashMap::new, Collectors.counting()));
            StringBuilder sb = new StringBuilder();
            for (var entry : counts.entrySet()) {
                sb.append("  ")
                        .append(entry.getKey().label())
                        .append(": ")
                        .append(entry.getValue())
                        .append(" keys\n");
            }
            return sb.toString().trim();
        }
    }
}
