package com.gsim.knowledge.embed;

import com.gsim.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 本地小模型 Embedding — 本阶段只做 stub。
 * 检查配置和模型文件存在性，文件缺失时返回 LOCAL_MODEL_NOT_FOUND。
 * 不做 ONNX Runtime 集成，不做真实本地推理，不引入大模型文件。
 */
public class LocalSmallEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LocalSmallEmbeddingModel.class);

    private final String modelDir;
    private final String modelName;
    private final int dimensions;
    private final EmbeddingProfile profile;

    public LocalSmallEmbeddingModel(String modelDir, String modelName, int dimensions) {
        this.modelDir = modelDir;
        this.modelName = modelName;
        this.dimensions = dimensions;

        String fingerprint = EmbeddingProfileManager.computeFingerprint(
                "local-small", modelName, dimensions, modelDir);

        // 检查模型文件
        boolean available = checkModelFiles();
        String status = available ? "active" : "unavailable";

        this.profile = new EmbeddingProfile(
                IdGenerator.embeddingProfileId(),
                "local-small", "local-small", modelName, dimensions,
                "cosine", 1, fingerprint, status, Instant.now().toString());
    }

    private boolean checkModelFiles() {
        Path dir = Path.of(modelDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[Embedding] local-small 模型目录不存在: {}", modelDir);
            return false;
        }
        // 检查基本文件存在性
        boolean hasConfig = Files.exists(dir.resolve("config.json"));
        boolean hasModel = Files.exists(dir.resolve("onnx"))
                || Files.exists(dir.resolve("model.onnx"));
        if (!hasConfig || !hasModel) {
            log.warn("[Embedding] local-small 模型文件不完整 (config.json={}, model={})",
                    hasConfig, hasModel);
            return false;
        }
        return true;
    }

    @Override
    public EmbeddingVector embed(String text) {
        if (!profile.isAvailable()) {
            throw new RuntimeException("LOCAL_MODEL_NOT_FOUND: 本地模型文件不存在。请放置模型文件到 "
                    + modelDir + " 或改用 external embedding。");
        }
        throw new RuntimeException("LOCAL_MODEL_NOT_FOUND: 本地模型推理尚未实现。"
                + " 请使用 external embedding 或 keyword_search。");
    }

    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        if (!profile.isAvailable()) {
            throw new RuntimeException("LOCAL_MODEL_NOT_FOUND: 本地模型文件不存在。请放置模型文件到 "
                    + modelDir + " 或改用 external embedding。");
        }
        throw new RuntimeException("LOCAL_MODEL_NOT_FOUND: 本地模型推理尚未实现。"
                + " 请使用 external embedding 或 keyword_search。");
    }

    @Override
    public EmbeddingProfile profile() {
        return profile;
    }
}
