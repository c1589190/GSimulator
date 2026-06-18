package com.gsim.interaction.commands;

import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfile;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.EmbeddingVector;

import java.util.List;
import java.util.Optional;

/**
 * /embedding — Embedding 管理命令。
 */
public class EmbeddingCommand implements InteractionCommand {

    private final EmbeddingProfileManager profileManager;

    public EmbeddingCommand(EmbeddingProfileManager profileManager) {
        this.profileManager = profileManager;
    }

    @Override
    public String name() { return "embedding"; }

    @Override
    public String description() { return "Embedding 管理：status / test / profiles / set"; }

    @Override
    public String usage() {
        return "/embedding status           — 显示 embedding 状态\n" +
                "/embedding test             — 测试当前 embedding 模型\n" +
                "/embedding profiles         — 列出所有 profiles\n" +
                "/embedding set <profileId>  — 切换 active profile";
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0 || args[0].isBlank()) {
            return InteractionResult.ok(usage());
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "status" -> doStatus();
            case "test" -> doTest();
            case "profiles" -> doProfiles();
            case "set" -> doSet(args);
            default -> InteractionResult.ok("未知子命令: " + sub + "\n" + usage());
        };
    }

    private InteractionResult doStatus() {
        var sb = new StringBuilder();
        sb.append("========== Embedding 状态 ==========\n\n");

        Optional<EmbeddingProfile> active = profileManager.getActiveProfile();
        if (active.isPresent()) {
            EmbeddingProfile p = active.get();
            sb.append("Active Profile:\n");
            sb.append("  ID:          ").append(p.profileId()).append("\n");
            sb.append("  Provider:    ").append(p.providerType()).append(" / ").append(p.providerName()).append("\n");
            sb.append("  Model:       ").append(p.modelName()).append("\n");
            sb.append("  Dimensions:  ").append(p.dimensions()).append("\n");
            sb.append("  Distance:    ").append(p.distanceMetric()).append("\n");
            sb.append("  Status:      ").append(p.status()).append("\n");
            sb.append("  Fingerprint: ").append(p.configFingerprint()).append("\n");
            sb.append("  Created:     ").append(p.createdAt()).append("\n");

            EmbeddingModel model = profileManager.getEmbeddingModel();
            sb.append("  Available:   ").append(model != null && model.isAvailable() ? "是" : "否").append("\n");
        } else {
            sb.append("当前未配置 active embedding profile。\n");
            sb.append("keyword_search 可用。\n");
            sb.append("knowledge_search 需要配置 external 或 local-small embedding。\n");
        }

        sb.append("\n====================================\n");
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult doTest() {
        Optional<EmbeddingProfile> active = profileManager.getActiveProfile();
        if (active.isEmpty()) {
            return InteractionResult.fail("NO_ACTIVE_EMBEDDING_PROFILE: 未配置 active embedding profile。");
        }

        EmbeddingModel model = profileManager.getEmbeddingModel();
        if (model == null || !model.isAvailable()) {
            return InteractionResult.fail("EMBEDDING_PROVIDER_UNAVAILABLE: Embedding model 不可用。");
        }

        try {
            EmbeddingVector vec = model.embed("这是一个测试文本。");
            var sb = new StringBuilder();
            sb.append("========== Embedding Test ==========\n\n");
            sb.append("测试文本: \"这是一个测试文本。\"\n");
            sb.append("Profile:   ").append(vec.profileId()).append("\n");
            sb.append("Dimensions: ").append(vec.dimensions()).append("\n");
            sb.append("Vector[0..3]: ");
            for (int i = 0; i < Math.min(4, vec.values().length); i++) {
                sb.append(String.format("%.6f", vec.values()[i])).append(" ");
            }
            sb.append("\n\n✅ Embedding 模型工作正常。\n");
            sb.append("====================================\n");
            return InteractionResult.ok(sb.toString());
        } catch (Exception e) {
            return InteractionResult.fail("Embedding test failed: " + e.getMessage());
        }
    }

    private InteractionResult doProfiles() {
        List<EmbeddingProfile> profiles = profileManager.listProfiles();
        if (profiles.isEmpty()) {
            return InteractionResult.ok("========== Embedding Profiles ==========\n\n(无)\n\n====================================\n");
        }

        Optional<EmbeddingProfile> active = profileManager.getActiveProfile();
        String activeId = active.map(EmbeddingProfile::profileId).orElse(null);

        var sb = new StringBuilder();
        sb.append("========== Embedding Profiles ==========\n\n");
        for (EmbeddingProfile p : profiles) {
            sb.append(p.profileId().equals(activeId) ? "▶ " : "  ");
            sb.append(p.profileId()).append("  ")
                    .append(p.providerType()).append("  ")
                    .append(p.modelName()).append("  ")
                    .append(p.dimensions()).append("d  ")
                    .append(p.status()).append("\n");
        }
        sb.append("\nActive: ").append(activeId != null ? activeId : "(无)").append("\n");
        sb.append("====================================\n");
        return InteractionResult.ok(sb.toString());
    }

    private InteractionResult doSet(String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            return InteractionResult.ok("用法: /embedding set <profileId>\n\n可用的 profiles:\n"
                    + String.join("\n", profileManager.listProfiles().stream()
                            .map(p -> "  " + p.profileId() + " (" + p.modelName() + ")")
                            .toList()));
        }

        String profileId = args[1];
        Optional<EmbeddingProfile> profile = profileManager.getProfile(profileId);
        if (profile.isEmpty()) {
            return InteractionResult.fail("Profile not found: " + profileId);
        }

        profileManager.setActiveProfile(profileId);
        return InteractionResult.ok("已切换 active profile 为: " + profileId
                + " (" + profile.get().modelName() + ")");
    }
}
