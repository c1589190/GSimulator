package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.SessionManager;
import com.gsim.app.ApplicationContext;
import com.gsim.app.AppConfig;
import com.gsim.config.ConfigDoctor;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * /api/config — 配置管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/config/status   — 配置状态</li>
 *   <li>GET    /api/config/show     — 显示配置（API Key 脱敏）</li>
 *   <li>GET    /api/config/path     — 配置文件路径</li>
 *   <li>POST   /api/config/test-llm — LLM 连通性测试</li>
 *   <li>POST   /api/config/set      — 修改配置项</li>
 * </ul>
 */
public class ConfigApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/config";

    private final ApplicationContext ctx;
    private final SessionManager sessionManager;

    public ConfigApiHandler(ApplicationContext ctx, SessionManager sessionManager) {
        this.ctx = ctx;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0) {
                // /api/config — 默认显示 status
                handleStatus(exchange);
                return;
            }

            switch (segs[0]) {
                case "status" -> handleStatus(exchange);
                case "show" -> handleShow(exchange);
                case "path" -> handlePath(exchange);
                case "test-llm" -> handleTestLlm(exchange, method);
                case "set" -> handleSet(exchange, method);
                default -> BaseApiHandler.sendNotFound(exchange, "Unknown config sub-resource: " + segs[0]);
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        AppConfig config = ctx.getConfig();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configSource", config.getConfigSourceSummary());

        Path cp = config.getConfigPath();
        data.put("configPath", cp != null ? cp.toAbsolutePath().toString() : null);

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("configured", config.isLlmConfigured());
        llm.put("baseUrl", config.getLlmBaseUrl());
        llm.put("apiKeyMasked", config.maskedApiKey());
        llm.put("model", config.getLlmModel());
        llm.put("timeoutSeconds", config.getLlmTimeoutSeconds());
        data.put("llm", llm);

        Map<String, Object> dirs = new LinkedHashMap<>();
        dirs.put("data", config.getDataDir().toString());
        dirs.put("import", config.getImportDir().toString());
        dirs.put("output", config.getOutputDir().toString());
        dirs.put("log", config.getLogDir().toString());
        data.put("directories", dirs);

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("chromaDb", config.isChromaEnabled() ? "enabled" : "disabled");
        services.put("webResearch", config.isWebResearchEnabled() ? "enabled" : "disabled");
        services.put("streamEnabled", config.isLlmStreamEnabled());
        data.put("services", services);

        BaseApiHandler.sendOk(exchange, "Config status", data);
    }

    private void handleShow(HttpExchange exchange) throws IOException {
        AppConfig config = ctx.getConfig();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("llm.base_url", config.getLlmBaseUrl());
        data.put("llm.api_key", config.maskedApiKey());
        data.put("llm.model", config.getLlmModel());
        data.put("llm.temperature", config.getLlmTemperature());
        data.put("llm.timeout_seconds", config.getLlmTimeoutSeconds());
        data.put("data.dir", config.getDataDir().toString());
        data.put("import.dir", config.getImportDir().toString());
        data.put("output.dir", config.getOutputDir().toString());
        data.put("log.dir", config.getLogDir().toString());
        data.put("chroma.enabled", config.isChromaEnabled());
        data.put("chroma.base_url", config.getChromaBaseUrl());
        data.put("web_research.enabled", config.isWebResearchEnabled());
        data.put("api.enabled", config.isApiEnabled());
        data.put("api.host", config.getApiHost());
        data.put("api.port", config.getApiPort());

        BaseApiHandler.sendOk(exchange, "Config shown (masked)", data);
    }

    private void handlePath(HttpExchange exchange) throws IOException {
        Path cp = ctx.getConfig().getConfigPath();
        if (cp != null && Files.isRegularFile(cp)) {
            BaseApiHandler.sendOk(exchange, "Config file path",
                    Map.of("path", cp.toAbsolutePath().toString()));
        } else {
            BaseApiHandler.sendOk(exchange, "No config file (using env/defaults)",
                    Map.of("path", ""));
        }
    }

    private void handleTestLlm(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        AppConfig config = ctx.getConfig();
        if (!config.isLlmConfigured()) {
            BaseApiHandler.sendError(exchange, 400, "LLM not configured");
            return;
        }

        String result = ConfigDoctor.testLlmConnectivity(config);
        boolean success = result.contains("✅");
        BaseApiHandler.sendOk(exchange, success ? "LLM test passed" : "LLM test failed",
                Map.of("result", result, "success", success));
    }

    private void handleSet(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        String body = BaseApiHandler.readBody(exchange);
        Map<?, ?> reqMap;
        try {
            reqMap = JsonUtils.fromJson(body, Map.class);
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        Object rawKey = reqMap != null ? reqMap.get("key") : null;
        Object rawValue = reqMap != null ? reqMap.get("value") : null;
        String key = rawKey != null ? rawKey.toString() : "";
        String value = rawValue != null ? rawValue.toString() : "";

        if (key.isBlank() || value.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required fields: key, value");
            return;
        }

        // 安全：不允许通过 API 修改 api_key
        if (key.contains("api_key") || key.contains("apikey")) {
            BaseApiHandler.sendError(exchange, 403, "Cannot modify API key via API for security reasons");
            return;
        }

        // 通过 InteractionManager 调用 /config set
        InteractionSession session = sessionManager.getOrCreateSession("default");
        InteractionResult result = ctx.getInteractionManager().handle("/config set " + key + " " + value, session);

        if (result.success()) {
            BaseApiHandler.sendOk(exchange, "Config updated",
                    Map.of("key", key, "value", value, "message", result.displayText()));
        } else {
            BaseApiHandler.sendError(exchange, 400, result.message());
        }
    }
}
