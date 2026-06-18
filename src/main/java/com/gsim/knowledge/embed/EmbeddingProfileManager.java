package com.gsim.knowledge.embed;

import com.gsim.knowledge.KnowledgeSettings;
import com.gsim.knowledge.store.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Embedding Profile 管理器 — 负责 profile 的创建、匹配、切换和生命周期。
 */
public class EmbeddingProfileManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProfileManager.class);

    private final KnowledgeStore store;
    private final EmbeddingModel embeddingModel;

    public EmbeddingProfileManager(KnowledgeStore store, EmbeddingModel embeddingModel) {
        this.store = store;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 初始化：检查当前配置，匹配或创建 profile，设置 active。
     */
    public void initialize() {
        if (embeddingModel == null) {
            log.info("[Embedding] 未配置 embedding provider。");
            log.info("[Embedding] keyword_search 可用。knowledge_search 需要配置 embedding。");
            return;
        }

        EmbeddingProfile modelProfile = embeddingModel.profile();

        if (modelProfile == null) {
            log.info("[Embedding] 未配置 embedding provider。");
            log.info("[Embedding] keyword_search 可用。knowledge_search 需要配置 embedding。");
            return;
        }

        // 查找匹配 fingerprint 的已有 profile
        Optional<EmbeddingProfile> existing = findMatchingProfile(modelProfile.configFingerprint());
        if (existing.isPresent()) {
            EmbeddingProfile ep = existing.get();
            log.info("[Embedding] 匹配已有 profile: {} ({})", ep.profileId(), ep.modelName());
            if (!ep.isAvailable()) {
                log.warn("[Embedding] profile {} 状态为 {}，可能不可用。", ep.profileId(), ep.status());
            }
            setActiveProfile(ep.profileId());
            return;
        }

        // 创建新 profile
        EmbeddingProfile newProfile = modelProfile;
        store.saveProfile(newProfile);
        setActiveProfile(newProfile.profileId());
        log.info("[Embedding] 创建新 profile: {} ({}, {}d)",
                newProfile.profileId(), newProfile.modelName(), newProfile.dimensions());
    }

    /** 获取当前 active profile。 */
    public Optional<EmbeddingProfile> getActiveProfile() {
        Optional<String> activeId = store.getSetting(KnowledgeSettings.KEY_ACTIVE_EMBEDDING_PROFILE_ID);
        if (activeId.isEmpty()) {
            return Optional.empty();
        }
        return store.getProfile(activeId.get());
    }

    /** 设置 active profile。 */
    public void setActiveProfile(String profileId) {
        store.setSetting(KnowledgeSettings.KEY_ACTIVE_EMBEDDING_PROFILE_ID, profileId);
    }

    /** 清除 active profile。 */
    public void clearActiveProfile() {
        store.setSetting(KnowledgeSettings.KEY_ACTIVE_EMBEDDING_PROFILE_ID, "");
    }

    /** 列出所有 profiles。 */
    public List<EmbeddingProfile> listProfiles() {
        return store.listProfiles();
    }

    /** 获取指定 profile。 */
    public Optional<EmbeddingProfile> getProfile(String profileId) {
        return store.getProfile(profileId);
    }

    /** 获取当前 EmbeddingModel。 */
    public EmbeddingModel getEmbeddingModel() {
        return embeddingModel;
    }

    /** 查找与 fingerprint 完全匹配的已有 profile。 */
    private Optional<EmbeddingProfile> findMatchingProfile(String fingerprint) {
        return store.listProfiles().stream()
                .filter(p -> p.configFingerprint().equals(fingerprint))
                .findFirst();
    }

    /**
     * 根据 provider 配置生成 config_fingerprint。
     * fingerprint 应唯一标识一组 (provider, model, dimensions, baseUrl) 配置。
     */
    public static String computeFingerprint(String providerType, String modelName,
                                             int dimensions, String baseUrl) {
        String input = providerType + "|" + modelName + "|" + dimensions + "|"
                + (baseUrl != null ? baseUrl : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
