package com.gsim.app;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用配置，所有环境变量读取统一走此类。
 * 不在业务代码中直接调用 System.getenv()。
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

    public AppConfig() {
        this.llmBaseUrl = envOrDefault("LLM_BASE_URL", "http://localhost:8080/v1");
        this.llmApiKey = envOrDefault("LLM_API_KEY", "no-api-key");
        this.llmModel = envOrDefault("LLM_MODEL", "deepseek-v4-pro");
        this.llmTemperature = Double.parseDouble(envOrDefault("LLM_TEMPERATURE", "0.3"));
        this.llmTimeoutSeconds = Integer.parseInt(envOrDefault("LLM_TIMEOUT_SECONDS", "120"));

        this.chromaBaseUrl = envOrDefault("CHROMA_BASE_URL", "http://localhost:8000");
        this.chromaEnabled = Boolean.parseBoolean(envOrDefault("CHROMA_ENABLED", "false"));

        this.webResearchEnabled = Boolean.parseBoolean(envOrDefault("WEB_RESEARCH_ENABLED", "false"));
        this.webResearchTimeoutSeconds = Integer.parseInt(envOrDefault("WEB_RESEARCH_TIMEOUT_SECONDS", "30"));
        this.webResearchUserAgent = envOrDefault("WEB_RESEARCH_USER_AGENT", "GSimulator/0.1.0 (research-bot)");

        this.dataDir = Paths.get(envOrDefault("GSIM_DATA_DIR", "data")).toAbsolutePath();
        this.importDir = Paths.get(envOrDefault("GSIM_IMPORT_DIR", "import")).toAbsolutePath();
        this.outputDir = Paths.get(envOrDefault("GSIM_OUTPUT_DIR", "data/outputs")).toAbsolutePath();
        this.logDir = Paths.get(envOrDefault("GSIM_LOG_DIR", "data/logs")).toAbsolutePath();
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

    // ---- helpers ----

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
