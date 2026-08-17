package com.gsim.app;

import com.gsim.core.config.ConfigLoader;
import com.gsim.core.util.LogSanitizer;
import com.gsim.map.config.MapConfig;
import java.nio.file.Path;

/**
 * 应用配置，所有配置读取统一走此类。
 * 从 ConfigLoader.Result 初始化，支持多源优先级链。
 */
public class AppConfig {

    private final String llmBaseUrl;
    private final String llmApiKey;
    private final String llmModel;
    private final double llmTemperature;
    private final int llmTimeoutSeconds;

    private final String chromaBaseUrl;
    private final boolean chromaEnabled;

    private final boolean webResearchEnabled;
    private final int webResearchTimeoutSeconds;
    private final String webResearchUserAgent;

    private final Path dataDir;
    private final Path importDir;
    private final Path outputDir;
    private final Path logDir;
    private final Path worldsDir;
    private final Path promptsDir;
    private final Path agentsDir;

    private final String apiHost;
    private final int apiPort;
    private final boolean apiEnabled;

    // Embedding 配置
    private final String embeddingProvider;
    private final String embeddingBaseUrl;
    private final String embeddingApiKey;
    private final String embeddingModel;
    private final int embeddingDimensions;
    private final String embeddingModelDir;

    // Knowledge 配置
    private final Path knowledgeDbPath;

    private final Path configPath;
    private final String sourceSummary;

    private final boolean llmConfigured;

    /** 对话上下文最近保留轮数（1..50，默认 12）。 */
    private final int contextSessionHistoryTurns;
    /** 单条 SessionMessage 渲染进 LLM 上下文的最大字符数（500..20000，默认 4000）。 */
    private final int contextSessionMessageMaxChars;

    /** Agent ToolLoop 最大工具轮数（≥1，默认 64，与 ConfigLoader 默认一致）。 */
    private final int agentToolLoopMaxRounds;

    /** Agent ToolLoop 结果内联最大字符数（默认 4000，超限走 staging）。 */
    private final int resultInlineMaxChars;
    /** Agent ToolLoop 结果超限时是否启用 doc staging（默认 true）。 */
    private final boolean resultStagingEnabled;
    /** MCP 响应 JSON 序列化最大字节数（默认 50000）。 */
    private final int mcpResponseMaxJsonBytes;
    /** MCP 响应单条 snippet 最大字符数（默认 300）。 */
    private final int mcpResponseSnippetMaxChars;
    /** MCP 响应默认分页大小（默认 20）。 */
    private final int mcpResponseDefaultPageSize;
    /** MCP 响应分页大小上限（默认 100）。 */
    private final int mcpResponseMaxPageSize;
    /** MCP 响应溢出时是否启用 doc staging（默认 true）。 */
    private final boolean mcpResponseOverflowStagingEnabled;
    /** core doc staging 阈值（默认 500）。 */
    private final int stagingThreshold;
    /** core doc query staging 阈值（默认 3000）。 */
    private final int queryStagingThreshold;
    /** TMP 文档最大保留时长（小时，默认 168）。 */
    private final int tmpMaxAgeHours;
    /** TMP 文档启动清扫开关（默认 true）。 */
    private final boolean tmpCleanupEnabled;

    /** 文档目录（默认 worldsDir 同级 docs）。 */
    private final Path docsDir;
    /** 缓存目录（默认 worldsDir 同级 caches）。 */
    private final Path cachesDir;

    /** Embedding 连接超时（秒，默认 30）。 */
    private final int embeddingTimeoutConnectSeconds;
    /** Embedding 读超时（秒，默认 60）。 */
    private final int embeddingTimeoutReadSeconds;
    /** Embedding 写超时（秒，默认 30）。 */
    private final int embeddingTimeoutWriteSeconds;
    /** SubAgent 结果收集超时（秒，默认 300）。 */
    private final int subagentCollectTimeoutSeconds;
    /** SubAgent 已完成缓存上限（默认 100）。 */
    private final int subagentMaxCompleted;
    /** 导入文档最大全文读取字符数（默认 30000）。 */
    private final int importDocMaxFullReadChars;
    /** 导入文档默认限制（默认 8000）。 */
    private final int importDocDefaultLimit;
    /** Web 研究 Wikipedia API 地址（默认 en.wikipedia.org）。 */
    private final String wikiUrl;

    /** LLM 流式输出开关（默认 true）。 */
    private final boolean llmStreamEnabled;
    /** CLI 流式预览开关（默认 true）。 */
    private final boolean cliStreamPreviewEnabled;
    /** CLI 流式预览灰框最大字符数（默认 3000）。 */
    private final int cliStreamPreviewMaxChars;
    /** CLI 流式预览是否显示 reasoning（默认 true）。 */
    private final boolean cliStreamPreviewShowReasoning;

    /** CLI 是否监控 HTTP API 交互并以颜色输出（默认 false）。 */
    private final boolean cliMonitorHttpApi;

    // WebUI / Map / CLI-WS / MCP 本地服务配置
    private final String webUiHost;
    private final int webUiPort;
    private final boolean webUiEnabled;
    private final int mapPort;
    private final int cliWsPort;
    private final int mcpHttpPort;

    /** gsim-map 限值（默认 80/32/5000/200/30000/100/200）。 */
    private final MapConfig mapConfig;

    /**
     * 从 ConfigLoader 结果构造。
     */
    @SuppressWarnings("this-escape")
    public AppConfig(ConfigLoader.ConfigResult result) {
        // 先从旧 properties/env 读取作为 fallback
        String fallbackBaseUrl = result.get("llm.base_url");
        String fallbackApiKey = result.get("llm.api_key");
        String fallbackModel = result.get("llm.model");
        double fallbackTemperature = parseDouble(result.get("llm.temperature"), 0.3);
        int fallbackTimeout = parseInt(result.get("llm.timeout_seconds"), 120);

        // 再用 llms.json 的 base provider 覆盖（主配置源）
        LlmsOverride llmsOverride = resolveLlmsOverride(result);
        this.llmBaseUrl = llmsOverride.baseUrl != null ? llmsOverride.baseUrl : fallbackBaseUrl;
        this.llmApiKey = llmsOverride.apiKey != null ? llmsOverride.apiKey : fallbackApiKey;
        this.llmModel = llmsOverride.model != null ? llmsOverride.model : fallbackModel;
        this.llmTemperature = llmsOverride.temperature > 0 ? llmsOverride.temperature : fallbackTemperature;
        this.llmTimeoutSeconds = llmsOverride.timeoutSeconds > 0 ? llmsOverride.timeoutSeconds : fallbackTimeout;

        this.chromaBaseUrl = result.get("chroma.base_url");
        this.chromaEnabled = parseBoolean(result.get("chroma.enabled"), false);

        this.webResearchEnabled = parseBoolean(result.get("web_research.enabled"), false);
        this.webResearchTimeoutSeconds = parseInt(result.get("web_research.timeout_seconds"), 30);
        this.webResearchUserAgent = result.get("web_research.user_agent");

        // 路径：基于配置文件所在目录解析相对路径
        Path baseDir = result.configPath() != null
                ? result.configPath().getParent()
                : Path.of("").toAbsolutePath();
        this.dataDir = resolvePath(result.get("data.dir"), baseDir, "data");
        this.importDir = resolvePath(result.get("import.dir"), baseDir, "import");
        this.outputDir = resolvePath(result.get("output.dir"), baseDir, "data/outputs");
        this.logDir = resolvePath(result.get("log.dir"), baseDir, "data/logs");
        this.worldsDir = resolvePath(result.get("worlds.dir"), baseDir, "worlds");
        this.promptsDir = resolvePath(result.get("prompts.dir"), baseDir, "prompts");
        this.agentsDir = resolvePath(result.get("agents.dir"), baseDir, "agents");

        this.apiHost = isBlank(result.get("api.host")) ? "127.0.0.1" : result.get("api.host");
        this.apiPort = parseInt(result.get("api.port"), 8710);
        this.apiEnabled = parseBoolean(result.get("api.enabled"), false);

        // Embedding 配置
        this.embeddingProvider = isBlank(result.get("embedding.provider")) ? "" : result.get("embedding.provider");
        this.embeddingBaseUrl = result.get("embedding.base_url");
        this.embeddingApiKey = result.get("embedding.api_key");
        this.embeddingModel = result.get("embedding.model");
        this.embeddingDimensions = parseInt(result.get("embedding.dimensions"), 0);
        this.embeddingModelDir = result.get("embedding.model_dir");

        // Knowledge DB — 空串 = 未设置，回退 baseDir/data/knowledge/gsim.db
        this.knowledgeDbPath = isBlank(result.get("knowledge.db.path"))
                ? baseDir.resolve("data").resolve("knowledge").resolve("gsim.db").toAbsolutePath()
                : resolvePath(result.get("knowledge.db.path"), baseDir, "data/knowledge/gsim.db");

        this.configPath = result.configPath();
        this.sourceSummary = result.sourceSummary();

        // LLM 配置判定 — 仅检查 llms.json
        this.llmConfigured = isLlmsJsonConfigured();

        // Context session 历史配置
        this.contextSessionHistoryTurns = clamp(parseInt(result.get("context.session.history.turns"), 12), 1, 50);
        this.contextSessionMessageMaxChars =
                clamp(parseInt(result.get("context.session.message.max_chars"), 4000), 500, 20000);

        // Agent ToolLoop 配置（下限 1，无上限；默认 64 与 ConfigLoader 默认一致）
        this.agentToolLoopMaxRounds = Math.max(1, parseInt(result.get("agent.tool_loop.max_rounds"), 64));

        // Agent ToolLoop 结果回传与 MCP 响应限流
        this.resultInlineMaxChars = parseInt(result.get("agent.tool_loop.result_inline_max_chars"), 4000);
        this.resultStagingEnabled = parseBoolean(result.get("agent.tool_loop.result_staging.enabled"), true);
        this.mcpResponseMaxJsonBytes = parseInt(result.get("mcp.response.max_json_bytes"), 50000);
        this.mcpResponseSnippetMaxChars = parseInt(result.get("mcp.response.snippet_max_chars"), 300);
        this.mcpResponseDefaultPageSize = parseInt(result.get("mcp.response.default_page_size"), 20);
        this.mcpResponseMaxPageSize = parseInt(result.get("mcp.response.max_page_size"), 100);
        this.mcpResponseOverflowStagingEnabled =
                parseBoolean(result.get("mcp.response.overflow_staging.enabled"), true);

        // 文档暂存与临时目录清理
        this.stagingThreshold = parseInt(result.get("core.doc.staging.threshold"), 500);
        this.queryStagingThreshold = parseInt(result.get("core.doc.query.staging.threshold"), 3000);
        this.tmpMaxAgeHours = parseInt(result.get("core.doc.tmp.max_age_hours"), 168);
        this.tmpCleanupEnabled = parseBoolean(result.get("core.doc.tmp.cleanup_enabled"), true);
        // docs.dir / caches.dir：空串=未设置，回退 worldsDir 同级目录
        this.docsDir = isBlank(result.get("docs.dir"))
                ? worldsDir.resolveSibling("docs")
                : resolvePath(result.get("docs.dir"), baseDir, "docs");
        this.cachesDir = isBlank(result.get("caches.dir"))
                ? worldsDir.resolveSibling("caches")
                : resolvePath(result.get("caches.dir"), baseDir, "caches");

        // Embedding / SubAgent / Import / Web 研究
        this.embeddingTimeoutConnectSeconds = parseInt(result.get("embedding.timeout_connect_seconds"), 30);
        this.embeddingTimeoutReadSeconds = parseInt(result.get("embedding.timeout_read_seconds"), 60);
        this.embeddingTimeoutWriteSeconds = parseInt(result.get("embedding.timeout_write_seconds"), 30);
        this.subagentCollectTimeoutSeconds = parseInt(result.get("agent.subagent.collect.timeout_seconds"), 300);
        this.subagentMaxCompleted = parseInt(result.get("agent.subagent.max_completed"), 100);
        this.importDocMaxFullReadChars = parseInt(result.get("import.doc.max_full_read_chars"), 30000);
        this.importDocDefaultLimit = parseInt(result.get("import.doc.default_limit"), 8000);
        this.wikiUrl = isBlank(result.get("web_research.wiki.url"))
                ? "https://en.wikipedia.org/w/api.php"
                : result.get("web_research.wiki.url");

        // LLM 流式 + CLI 预览配置
        this.llmStreamEnabled = parseBoolean(result.get("llm.stream.enabled"), true);
        this.cliStreamPreviewEnabled = parseBoolean(result.get("cli.stream.preview.enabled"), true);
        this.cliStreamPreviewMaxChars = clamp(parseInt(result.get("cli.stream.preview.max_chars"), 3000), 100, 100000);
        this.cliStreamPreviewShowReasoning = parseBoolean(result.get("cli.stream.preview.show_reasoning"), true);

        // CLI HTTP API 监控（默认关闭）
        this.cliMonitorHttpApi = parseBoolean(result.get("cli.monitor.http_api"), false);

        // WebUI / Map / CLI-WS / MCP 本地服务配置
        this.webUiHost = isBlank(result.get("webui.host")) ? "127.0.0.1" : result.get("webui.host");
        this.webUiPort = parsePort(result.get("webui.port"), 8710);
        this.webUiEnabled = parseBoolean(result.get("webui.enabled"), false);
        this.mapPort = parsePort(result.get("map.port"), 8711);
        this.cliWsPort = parsePort(result.get("cli.ws.port"), 8712);
        this.mcpHttpPort = parsePort(result.get("mcp.http.port"), 37201);

        // gsim-map 限值（默认值与 MapConfig.DEFAULT 一致，行为零变化）
        this.mapConfig = new MapConfig(
                parseInt(result.get("map.radius.default"), 80),
                parseInt(result.get("map.cache.max_entries"), 32),
                parseInt(result.get("map.contour.cache.max"), 5000),
                parseInt(result.get("map.lasso.max_radius"), 200),
                parseInt(result.get("map.lasso.max_fill"), 30000),
                parseInt(result.get("map.compression.min_region_size"), 100),
                parseInt(result.get("map.resolver.max_chain_depth"), 200));
    }

    // ---- Getters ----

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public String getLlmApiKey() {
        return llmApiKey;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public double getLlmTemperature() {
        return llmTemperature;
    }

    public int getLlmTimeoutSeconds() {
        return llmTimeoutSeconds;
    }

    public String getChromaBaseUrl() {
        return chromaBaseUrl;
    }

    public boolean isChromaEnabled() {
        return chromaEnabled;
    }

    public boolean isWebResearchEnabled() {
        return webResearchEnabled;
    }

    public int getWebResearchTimeoutSeconds() {
        return webResearchTimeoutSeconds;
    }

    public String getWebResearchUserAgent() {
        return webResearchUserAgent;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public Path getImportDir() {
        return importDir;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public Path getLogDir() {
        return logDir;
    }

    public Path worldsDir() {
        return worldsDir;
    }

    public Path promptsDir() {
        return promptsDir;
    }

    public Path agentsDir() {
        return agentsDir;
    }

    public String getApiHost() {
        return apiHost;
    }

    public int getApiPort() {
        return apiPort;
    }

    public boolean isApiEnabled() {
        return apiEnabled;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public String getEmbeddingModelDir() {
        return embeddingModelDir;
    }

    /** Knowledge DB 文件路径 — knowledge.db.path 未设置时回退 baseDir/data/knowledge/gsim.db。 */
    public Path knowledgeDbPath() {
        return knowledgeDbPath;
    }

    /** 是否配置了 embedding provider。 */
    public boolean isEmbeddingConfigured() {
        return !isBlank(embeddingProvider)
                && ("external".equals(embeddingProvider) || "local-small".equals(embeddingProvider));
    }

    // ---- 新增方法 ----

    /** 判定 LLM 是否已完整配置（检查 llms.json 或旧环境变量）。 */
    public boolean isLlmConfigured() {
        return llmConfigured;
    }

    /** 检查 llms.json 是否存在且包含至少一个有效的 provider。 */
    private boolean isLlmsJsonConfigured() {
        Path llmsPath = getLlmsPath();
        if (!java.nio.file.Files.exists(llmsPath)) return false;
        try {
            // 用 LlmsConfigFile 完整解析验证
            var file = com.gsim.core.llm.LlmsConfigFile.load(llmsPath);
            if (file.providers().isEmpty()) return false;
            // 至少有一个 provider 有非空 baseUrl 和 model
            return file.providers().stream()
                    .anyMatch(p -> p.baseUrl() != null
                            && !p.baseUrl().isBlank()
                            && p.model() != null
                            && !p.model().isBlank());
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 llms.json 的 base provider 解析 LLM 配置，覆盖旧 properties/env 值。 */
    private LlmsOverride resolveLlmsOverride(ConfigLoader.ConfigResult result) {
        // 确定 llms.json 路径（与 gsim.properties 同级，或 CWD）
        Path baseDir = result.configPath() != null
                ? result.configPath().getParent()
                : Path.of("").toAbsolutePath();
        Path llmsPath = baseDir.resolve("llms.json");
        if (!java.nio.file.Files.exists(llmsPath)) {
            return LlmsOverride.EMPTY;
        }
        try {
            var file = com.gsim.core.llm.LlmsConfigFile.load(llmsPath);
            var base = file.find("base");
            if (base == null) {
                base = file.defaultConfig();
            }
            if (base == null) return LlmsOverride.EMPTY;
            int timeoutSeconds = parseInt(result.get("llm.timeout_seconds"), 120);
            return new LlmsOverride(base.baseUrl(), base.apiKey(), base.model(), base.defaultTemperature(), timeoutSeconds);
        } catch (Exception e) {
            return LlmsOverride.EMPTY;
        }
    }

    /** llms.json base provider 覆盖值。 */
    private record LlmsOverride(String baseUrl, String apiKey, String model, double temperature, int timeoutSeconds) {
        static final LlmsOverride EMPTY = new LlmsOverride(null, null, null, 0, 0);
    }

    /** llms.json 文件路径 — 与 worlds/、import/ 同级。 */
    public Path getLlmsPath() {
        return worldsDir.getParent().resolve("llms.json");
    }

    /** 对话上下文最近保留轮数（1..50，默认 12）。 */
    public int getContextSessionHistoryTurns() {
        return contextSessionHistoryTurns;
    }

    /** 单条 SessionMessage 渲染进 LLM 上下文的最大字符数（500..20000，默认 4000）。 */
    public int getContextSessionMessageMaxChars() {
        return contextSessionMessageMaxChars;
    }

    /** Agent ToolLoop 最大工具轮数（≥1，默认 64）。 */
    public int getAgentToolLoopMaxRounds() {
        return agentToolLoopMaxRounds;
    }

    /** Agent ToolLoop 结果内联最大字符数（默认 4000）。 */
    public int resultInlineMaxChars() {
        return resultInlineMaxChars;
    }

    /** Agent ToolLoop 结果超限时是否启用 doc staging（默认 true）。 */
    public boolean resultStagingEnabled() {
        return resultStagingEnabled;
    }

    /** MCP 响应 JSON 序列化最大字节数（默认 50000）。 */
    public int mcpResponseMaxJsonBytes() {
        return mcpResponseMaxJsonBytes;
    }

    /** MCP 响应单条 snippet 最大字符数（默认 300）。 */
    public int mcpResponseSnippetMaxChars() {
        return mcpResponseSnippetMaxChars;
    }

    /** MCP 响应默认分页大小（默认 20）。 */
    public int mcpResponseDefaultPageSize() {
        return mcpResponseDefaultPageSize;
    }

    /** MCP 响应分页大小上限（默认 100）。 */
    public int mcpResponseMaxPageSize() {
        return mcpResponseMaxPageSize;
    }

    /** MCP 响应溢出时是否启用 doc staging（默认 true）。 */
    public boolean mcpResponseOverflowStagingEnabled() {
        return mcpResponseOverflowStagingEnabled;
    }

    /** core doc staging 阈值（默认 500）。 */
    public int stagingThreshold() {
        return stagingThreshold;
    }

    /** core doc query staging 阈值（默认 3000）。 */
    public int queryStagingThreshold() {
        return queryStagingThreshold;
    }

    /** TMP 文档最大保留时长（小时，默认 168）。 */
    public int tmpMaxAgeHours() {
        return tmpMaxAgeHours;
    }

    /** TMP 文档启动清扫开关（默认 true）。 */
    public boolean tmpCleanupEnabled() {
        return tmpCleanupEnabled;
    }

    /** 文档目录 — docs.dir 未设置时回退 worldsDir 同级 docs。 */
    public Path docsDir() {
        return docsDir;
    }

    /** 缓存目录 — caches.dir 未设置时回退 worldsDir 同级 caches。 */
    public Path cachesDir() {
        return cachesDir;
    }

    /** Embedding 连接超时（秒，默认 30）。 */
    public int embeddingTimeoutConnectSeconds() {
        return embeddingTimeoutConnectSeconds;
    }

    /** Embedding 读超时（秒，默认 60）。 */
    public int embeddingTimeoutReadSeconds() {
        return embeddingTimeoutReadSeconds;
    }

    /** Embedding 写超时（秒，默认 30）。 */
    public int embeddingTimeoutWriteSeconds() {
        return embeddingTimeoutWriteSeconds;
    }

    /** SubAgent 结果收集超时（秒，默认 300）。 */
    public int subagentCollectTimeoutSeconds() {
        return subagentCollectTimeoutSeconds;
    }

    /** SubAgent 已完成缓存上限（默认 100）。 */
    public int subagentMaxCompleted() {
        return subagentMaxCompleted;
    }

    /** 导入文档最大全文读取字符数（默认 30000）。 */
    public int importDocMaxFullReadChars() {
        return importDocMaxFullReadChars;
    }

    /** 导入文档默认限制（默认 8000）。 */
    public int importDocDefaultLimit() {
        return importDocDefaultLimit;
    }

    /** Web 研究 Wikipedia API 地址（默认 en.wikipedia.org）。 */
    public String wikiUrl() {
        return wikiUrl;
    }

    /** LLM 流式输出是否启用（默认 true）。 */
    public boolean isLlmStreamEnabled() {
        return llmStreamEnabled;
    }
    /** CLI 流式预览是否启用（默认 true）。 */
    public boolean isCliStreamPreviewEnabled() {
        return cliStreamPreviewEnabled;
    }
    /** CLI 流式预览灰框最大字符数（默认 3000）。 */
    public int getCliStreamPreviewMaxChars() {
        return cliStreamPreviewMaxChars;
    }
    /** CLI 流式预览是否显示 reasoning（默认 true）。 */
    public boolean isCliStreamPreviewShowReasoning() {
        return cliStreamPreviewShowReasoning;
    }
    /** CLI HTTP API 监控（默认 false）。 */
    public boolean isCliMonitorHttpApi() {
        return cliMonitorHttpApi;
    }

    public String getWebUiHost() {
        return webUiHost;
    }

    public int getWebUiPort() {
        return webUiPort;
    }

    public boolean isWebUiEnabled() {
        return webUiEnabled;
    }

    /** Map HTTP 服务端口（默认 8711）。 */
    public int getMapPort() {
        return mapPort;
    }

    /** CLI WebSocket 服务端口（默认 8712）。 */
    public int getCliWsPort() {
        return cliWsPort;
    }

    /** MCP HTTP 服务端口（默认 37201）。 */
    public int getMcpHttpPort() {
        return mcpHttpPort;
    }

    /** gsim-map 限值配置（默认 80/32/5000/200/30000/100/200）。 */
    public MapConfig mapConfig() {
        return mapConfig;
    }

    /** 获取当前生效的配置文件路径。 */
    public Path getConfigPath() {
        return configPath;
    }

    /** 获取配置来源摘要。 */
    public String getConfigSourceSummary() {
        return sourceSummary;
    }

    /** 脱敏显示 API Key。 */
    public String maskedApiKey() {
        return maskValue(llmApiKey);
    }

    // ---- helpers ----

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static double parseDouble(String s, double def) {
        try {
            return isBlank(s) ? def : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return isBlank(s) ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parsePort(String s, int def) {
        return clamp(parseInt(s, def), 1, 65535);
    }

    private static boolean parseBoolean(String s, boolean def) {
        if (isBlank(s)) return def;
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 解析路径。相对路径基于 baseDir 解析。
     */
    private static Path resolvePath(String raw, Path baseDir, String fallback) {
        Path p = isBlank(raw) ? Path.of(fallback) : Path.of(raw);
        if (!p.isAbsolute()) {
            p = baseDir.resolve(p).normalize();
        }
        return p.toAbsolutePath();
    }

    /**
     * 测试用工厂方法 — 从环境变量和系统属性加载配置。
     * 保持与旧版 AppConfig() 兼容，供测试使用。
     */
    public static AppConfig forTesting() {
        ConfigLoader loader = new ConfigLoader(new String[0]);
        return new AppConfig(loader.load());
    }

    /**
     * 脱敏：显示前2和后2字符，如 "sk...xx"。
     * 实现位于 core 层 {@link LogSanitizer#maskValue(String)}（core 层与 app 层共用）。
     */
    public static String maskValue(String value) {
        return LogSanitizer.maskValue(value);
    }
}
