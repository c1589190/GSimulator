package com.gsim.app;

import com.gsim.config.ConfigLoader;
import com.gsim.config.ConfigSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

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

    /** Bootstrap root 创建时是否允许调用 LLM 生成 draft。默认 false（直接 deterministic fallback）。 */
    private final boolean bootstrapRootLlmEnabled;

    /** 对话上下文最近保留轮数（1..50，默认 12）。 */
    private final int contextSessionHistoryTurns;
    /** 单条 SessionMessage 渲染进 LLM 上下文的最大字符数（500..20000，默认 4000）。 */
    private final int contextSessionMessageMaxChars;

    /** Agent ToolLoop 最大工具轮数（≥1，默认 32）。 */
    private final int agentToolLoopMaxRounds;

    /**
     * 从 ConfigLoader 结果构造。
     */
    public AppConfig(ConfigLoader.ConfigResult result) {
        this.llmBaseUrl = result.get("llm.base_url");
        this.llmApiKey = result.get("llm.api_key");
        this.llmModel = result.get("llm.model");
        this.llmTemperature = parseDouble(result.get("llm.temperature"), 0.3);
        this.llmTimeoutSeconds = parseInt(result.get("llm.timeout_seconds"), 120);

        this.chromaBaseUrl = result.get("chroma.base_url");
        this.chromaEnabled = parseBoolean(result.get("chroma.enabled"), false);

        this.webResearchEnabled = parseBoolean(result.get("web_research.enabled"), false);
        this.webResearchTimeoutSeconds = parseInt(result.get("web_research.timeout_seconds"), 30);
        this.webResearchUserAgent = result.get("web_research.user_agent");

        // 路径：基于配置文件所在目录解析相对路径
        Path baseDir = result.configPath() != null ? result.configPath().getParent() : Path.of("").toAbsolutePath();
        this.dataDir = resolvePath(result.get("data.dir"), baseDir, "data");
        this.importDir = resolvePath(result.get("import.dir"), baseDir, "import");
        this.outputDir = resolvePath(result.get("output.dir"), baseDir, "data/outputs");
        this.logDir = resolvePath(result.get("log.dir"), baseDir, "data/logs");

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

        // Knowledge DB
        Path knowledgeDir = baseDir.resolve("data").resolve("knowledge");
        this.knowledgeDbPath = knowledgeDir.resolve("gsim.db").toAbsolutePath();

        this.configPath = result.configPath();
        this.sourceSummary = result.sourceSummary();

        // LLM 配置判定
        this.llmConfigured = !isBlank(llmBaseUrl)
                && !isBlank(llmApiKey) && !"no-api-key".equals(llmApiKey)
                && !isBlank(llmModel);

        // Bootstrap root LLM 开关（默认 false，快速 deterministic fallback）
        this.bootstrapRootLlmEnabled = parseBoolean(result.get("bootstrap.root.llm.enabled"), false);

        // Context session 历史配置
        this.contextSessionHistoryTurns = clamp(
                parseInt(result.get("context.session.history.turns"), 12), 1, 50);
        this.contextSessionMessageMaxChars = clamp(
                parseInt(result.get("context.session.message.max_chars"), 4000), 500, 20000);

        // Agent ToolLoop 配置（下限 1，无上限）
        this.agentToolLoopMaxRounds = Math.max(1,
                parseInt(result.get("agent.tool_loop.max_rounds"), 32));
    }

    // ---- Getters ----

    public String getLlmBaseUrl() { return llmBaseUrl; }
    public String getLlmApiKey() { return llmApiKey; }
    public String getLlmModel() { return llmModel; }
    public double getLlmTemperature() { return llmTemperature; }
    public int getLlmTimeoutSeconds() { return llmTimeoutSeconds; }

    public String getChromaBaseUrl() { return chromaBaseUrl; }
    public boolean isChromaEnabled() { return chromaEnabled; }

    public boolean isWebResearchEnabled() { return webResearchEnabled; }
    public int getWebResearchTimeoutSeconds() { return webResearchTimeoutSeconds; }
    public String getWebResearchUserAgent() { return webResearchUserAgent; }

    public Path getDataDir() { return dataDir; }
    public Path getImportDir() { return importDir; }
    public Path getOutputDir() { return outputDir; }
    public Path getLogDir() { return logDir; }

    public String getApiHost() { return apiHost; }
    public int getApiPort() { return apiPort; }
    public boolean isApiEnabled() { return apiEnabled; }

    public String getEmbeddingProvider() { return embeddingProvider; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public String getEmbeddingModelDir() { return embeddingModelDir; }
    public Path getKnowledgeDbPath() { return knowledgeDbPath; }

    /** 是否配置了 embedding provider。 */
    public boolean isEmbeddingConfigured() {
        return !isBlank(embeddingProvider)
                && ("external".equals(embeddingProvider) || "local-small".equals(embeddingProvider));
    }

    // ---- 新增方法 ----

    /** 判定 LLM 是否已完整配置。 */
    public boolean isLlmConfigured() {
        return llmConfigured;
    }

    /** Bootstrap root 创建时是否允许调用 LLM 生成 draft（默认关闭）。 */
    public boolean isBootstrapRootLlmEnabled() {
        return bootstrapRootLlmEnabled;
    }

    /** 对话上下文最近保留轮数（1..50，默认 12）。 */
    public int getContextSessionHistoryTurns() {
        return contextSessionHistoryTurns;
    }

    /** 单条 SessionMessage 渲染进 LLM 上下文的最大字符数（500..20000，默认 4000）。 */
    public int getContextSessionMessageMaxChars() {
        return contextSessionMessageMaxChars;
    }

    /** Agent ToolLoop 最大工具轮数（≥1，默认 32）。 */
    public int getAgentToolLoopMaxRounds() {
        return agentToolLoopMaxRounds;
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
        try { return isBlank(s) ? def : Double.parseDouble(s); }
        catch (NumberFormatException e) { return def; }
    }

    private static int parseInt(String s, int def) {
        try { return isBlank(s) ? def : Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
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
     */
    public static String maskValue(String value) {
        if (value == null || value.isBlank()) return "(未设置)";
        if (value.length() <= 5) return "<configured>";
        return value.substring(0, 2) + "..." + value.substring(value.length() - 2);
    }
}
