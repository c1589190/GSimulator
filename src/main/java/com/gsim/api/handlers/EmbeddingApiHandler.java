package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.app.ApplicationContext;
import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfile;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.EmbeddingVector;
import com.gsim.util.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.*;

/**
 * /api/embedding — Embedding 管理 API。
 *
 * <p>端点：
 * <ul>
 *   <li>GET    /api/embedding/status   — Embedding 状态</li>
 *   <li>POST   /api/embedding/test     — 测试 embedding 模型</li>
 *   <li>GET    /api/embedding/profiles — 列出所有 profiles</li>
 *   <li>POST   /api/embedding/set      — 切换 active profile</li>
 * </ul>
 */
public class EmbeddingApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/embedding";

    private final ApplicationContext ctx;

    public EmbeddingApiHandler(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0) {
                handleStatus(exchange);
                return;
            }

            switch (segs[0]) {
                case "status" -> handleStatus(exchange);
                case "test" -> handleTest(exchange, method);
                case "profiles" -> handleProfiles(exchange);
                case "set" -> handleSet(exchange, method);
                default -> BaseApiHandler.sendNotFound(exchange, "Unknown embedding sub-resource: " + segs[0]);
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        EmbeddingProfileManager pm = ctx.getEmbeddingProfileManager();
        Map<String, Object> data = new LinkedHashMap<>();

        if (pm == null) {
            data.put("available", false);
            data.put("message", "Embedding profile manager not available");
            BaseApiHandler.sendOk(exchange, "Embedding status", data);
            return;
        }

        data.put("available", true);
        Optional<EmbeddingProfile> active = pm.getActiveProfile();
        if (active.isPresent()) {
            EmbeddingProfile p = active.get();
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("profileId", p.profileId());
            profile.put("providerType", p.providerType());
            profile.put("providerName", p.providerName());
            profile.put("modelName", p.modelName());
            profile.put("dimensions", p.dimensions());
            profile.put("distanceMetric", p.distanceMetric());
            profile.put("status", p.status());
            profile.put("configFingerprint", p.configFingerprint());
            profile.put("createdAt", p.createdAt());

            EmbeddingModel model = pm.getEmbeddingModel();
            profile.put("modelAvailable", model != null && model.isAvailable());
            data.put("activeProfile", profile);
        } else {
            data.put("activeProfile", null);
            data.put("message", "No active embedding profile. keyword_search is available.");
        }

        BaseApiHandler.sendOk(exchange, "Embedding status", data);
    }

    private void handleTest(HttpExchange exchange, String method) throws IOException {
        if (!"POST".equalsIgnoreCase(method)) {
            BaseApiHandler.sendError(exchange, 405, "Method not allowed. Use POST.");
            return;
        }

        EmbeddingProfileManager pm = ctx.getEmbeddingProfileManager();
        if (pm == null) {
            BaseApiHandler.sendError(exchange, 503, "Embedding profile manager not available");
            return;
        }

        Optional<EmbeddingProfile> active = pm.getActiveProfile();
        if (active.isEmpty()) {
            BaseApiHandler.sendError(exchange, 400, "No active embedding profile configured");
            return;
        }

        EmbeddingModel model = pm.getEmbeddingModel();
        if (model == null || !model.isAvailable()) {
            BaseApiHandler.sendError(exchange, 503, "Embedding model not available");
            return;
        }

        try {
            EmbeddingVector vec = model.embed("这是一个测试文本。");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("testText", "这是一个测试文本。");
            data.put("profileId", vec.profileId());
            data.put("dimensions", vec.dimensions());
            data.put("vectorPreview", Arrays.toString(Arrays.copyOf(vec.values(), Math.min(4, vec.values().length))));
            data.put("success", true);

            BaseApiHandler.sendOk(exchange, "Embedding test passed", data);
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Embedding test failed: " + e.getMessage());
        }
    }

    private void handleProfiles(HttpExchange exchange) throws IOException {
        EmbeddingProfileManager pm = ctx.getEmbeddingProfileManager();
        if (pm == null) {
            BaseApiHandler.sendOk(exchange, "Embedding profiles",
                    Map.of("profiles", List.of(), "available", false));
            return;
        }

        List<EmbeddingProfile> profiles = pm.listProfiles();
        Optional<EmbeddingProfile> active = pm.getActiveProfile();
        String activeId = active.map(EmbeddingProfile::profileId).orElse(null);

        List<Map<String, Object>> list = new ArrayList<>();
        for (EmbeddingProfile p : profiles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("profileId", p.profileId());
            m.put("providerType", p.providerType());
            m.put("modelName", p.modelName());
            m.put("dimensions", p.dimensions());
            m.put("status", p.status());
            m.put("isActive", p.profileId().equals(activeId));
            list.add(m);
        }

        BaseApiHandler.sendOk(exchange, "Embedding profiles",
                Map.of("profiles", list, "count", list.size(), "activeProfileId", activeId));
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

        Object rawProfileId = reqMap != null ? reqMap.get("profileId") : null;
        String profileId = rawProfileId != null ? rawProfileId.toString() : "";
        if (profileId.isBlank()) {
            BaseApiHandler.sendError(exchange, 400, "Missing required field: profileId");
            return;
        }

        EmbeddingProfileManager pm = ctx.getEmbeddingProfileManager();
        if (pm == null) {
            BaseApiHandler.sendError(exchange, 503, "Embedding profile manager not available");
            return;
        }

        Optional<EmbeddingProfile> profile = pm.getProfile(profileId);
        if (profile.isEmpty()) {
            BaseApiHandler.sendError(exchange, 404, "Profile not found: " + profileId);
            return;
        }

        pm.setActiveProfile(profileId);
        BaseApiHandler.sendOk(exchange, "Active profile switched",
                Map.of("profileId", profileId, "modelName", profile.get().modelName()));
    }
}
