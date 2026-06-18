package com.gsim.knowledge.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 LocalSmallEmbeddingModel profileId 确定性生成。
 * 同一配置重复构造得到相同 profileId。
 */
@DisplayName("LocalSmallEmbedding Profile Deterministic ID")
class LocalSmallEmbeddingProfileDeterministicIdTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("同一 modelDir + modelName + dimensions → 相同 profileId")
    void sameLocalSmallConfigProducesSameProfileId() throws Exception {
        Path modelDir = tempDir.resolve("test-model");
        Files.createDirectories(modelDir);
        Files.createFile(modelDir.resolve("config.json"));
        Files.createFile(modelDir.resolve("model.onnx"));

        LocalSmallEmbeddingModel m1 = new LocalSmallEmbeddingModel(
                modelDir.toString(), "local-model", 384);
        LocalSmallEmbeddingModel m2 = new LocalSmallEmbeddingModel(
                modelDir.toString(), "local-model", 384);

        assertEquals(m1.profile().profileId(), m2.profile().profileId(),
                "同一配置应生成相同 profileId");
        assertEquals(m1.profile().configFingerprint(), m2.profile().configFingerprint());
    }

    @Test
    @DisplayName("不同 modelName → 不同 profileId")
    void differentModelNameProducesDifferentProfileId() throws Exception {
        Path modelDir = tempDir.resolve("test-model");
        Files.createDirectories(modelDir);
        Files.createFile(modelDir.resolve("config.json"));
        Files.createFile(modelDir.resolve("model.onnx"));

        LocalSmallEmbeddingModel m1 = new LocalSmallEmbeddingModel(
                modelDir.toString(), "model-v1", 384);
        LocalSmallEmbeddingModel m2 = new LocalSmallEmbeddingModel(
                modelDir.toString(), "model-v2", 384);

        assertNotEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("不同 dimensions → 不同 profileId")
    void differentDimensionsProducesDifferentProfileId() throws Exception {
        Path modelDir = tempDir.resolve("test-model");
        Files.createDirectories(modelDir);
        Files.createFile(modelDir.resolve("config.json"));
        Files.createFile(modelDir.resolve("model.onnx"));

        LocalSmallEmbeddingModel m1 = new LocalSmallEmbeddingModel(
                modelDir.toString(), "local-model", 384);
        LocalSmallEmbeddingModel m2 = new LocalSmallEmbeddingModel(
                modelDir.toString(), "local-model", 768);

        assertNotEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("不同 modelDir → 不同 profileId")
    void differentModelDirProducesDifferentProfileId() throws Exception {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);
        Files.createFile(dir1.resolve("config.json"));
        Files.createFile(dir1.resolve("model.onnx"));
        Files.createFile(dir2.resolve("config.json"));
        Files.createFile(dir2.resolve("model.onnx"));

        LocalSmallEmbeddingModel m1 = new LocalSmallEmbeddingModel(
                dir1.toString(), "local-model", 384);
        LocalSmallEmbeddingModel m2 = new LocalSmallEmbeddingModel(
                dir2.toString(), "local-model", 384);

        assertNotEquals(m1.profile().profileId(), m2.profile().profileId());
    }

    @Test
    @DisplayName("profileId 以 emb_ 开头")
    void profileIdStartsWithEmb() throws Exception {
        Path modelDir = tempDir.resolve("test-model");
        Files.createDirectories(modelDir);
        Files.createFile(modelDir.resolve("config.json"));
        Files.createFile(modelDir.resolve("model.onnx"));

        LocalSmallEmbeddingModel m = new LocalSmallEmbeddingModel(
                modelDir.toString(), "local-model", 384);
        assertTrue(m.profile().profileId().startsWith("emb_"),
                "profileId should start with emb_, got: " + m.profile().profileId());
    }

    @Test
    @DisplayName("模型文件缺失 → profile status 为 unavailable，但 profileId 仍稳定")
    void missingModelFilesProducesUnavailableButStableProfileId() {
        // 目录不存在 → unavailable，但 profileId 仍由配置决定
        LocalSmallEmbeddingModel m1 = new LocalSmallEmbeddingModel(
                "/nonexistent/path/model", "local-model", 384);
        LocalSmallEmbeddingModel m2 = new LocalSmallEmbeddingModel(
                "/nonexistent/path/model", "local-model", 384);

        assertEquals(m1.profile().profileId(), m2.profile().profileId(),
                "即使模型不可用，profileId 仍应稳定");
        assertEquals("unavailable", m1.profile().status());
        assertEquals("unavailable", m2.profile().status());
    }

    @Test
    @DisplayName("model dir 存在但缺 config.json → unavailable 但 profileId 稳定")
    void missingConfigFileUnavailableButStable() throws Exception {
        Path modelDir = tempDir.resolve("partial-model");
        Files.createDirectories(modelDir);
        // 没有 config.json，只有 model.onnx
        Files.createFile(modelDir.resolve("model.onnx"));

        LocalSmallEmbeddingModel m = new LocalSmallEmbeddingModel(
                modelDir.toString(), "local-model", 384);
        assertEquals("unavailable", m.profile().status());
        assertTrue(m.profile().profileId().startsWith("emb_"));
    }
}
