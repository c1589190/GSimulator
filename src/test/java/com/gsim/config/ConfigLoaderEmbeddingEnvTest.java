package com.gsim.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 EMBEDDING_* 环境变量能正确映射到 embedding.* 配置 key。
 */
@DisplayName("ConfigLoader Embedding Env Mapping")
class ConfigLoaderEmbeddingEnvTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("EMBEDDING_PROVIDER → embedding.provider")
    void shouldMapEmbeddingProvider() {
        assertEquals("embedding.provider", ConfigLoader.mapEnvKey("EMBEDDING_PROVIDER"));
    }

    @Test
    @DisplayName("EMBEDDING_BASE_URL → embedding.base_url")
    void shouldMapEmbeddingBaseUrl() {
        assertEquals("embedding.base_url", ConfigLoader.mapEnvKey("EMBEDDING_BASE_URL"));
    }

    @Test
    @DisplayName("EMBEDDING_API_KEY → embedding.api_key")
    void shouldMapEmbeddingApiKey() {
        assertEquals("embedding.api_key", ConfigLoader.mapEnvKey("EMBEDDING_API_KEY"));
    }

    @Test
    @DisplayName("EMBEDDING_MODEL → embedding.model")
    void shouldMapEmbeddingModel() {
        assertEquals("embedding.model", ConfigLoader.mapEnvKey("EMBEDDING_MODEL"));
    }

    @Test
    @DisplayName("EMBEDDING_DIMENSIONS → embedding.dimensions")
    void shouldMapEmbeddingDimensions() {
        assertEquals("embedding.dimensions", ConfigLoader.mapEnvKey("EMBEDDING_DIMENSIONS"));
    }

    @Test
    @DisplayName("EMBEDDING_MODEL_DIR → embedding.model_dir")
    void shouldMapEmbeddingModelDir() {
        assertEquals("embedding.model_dir", ConfigLoader.mapEnvKey("EMBEDDING_MODEL_DIR"));
    }

    @Test
    @DisplayName("all 6 EMBEDDING_* keys mapped, no nulls")
    void allSixEmbeddingKeysMapped() {
        String[] envKeys = {"EMBEDDING_PROVIDER", "EMBEDDING_BASE_URL", "EMBEDDING_API_KEY",
                "EMBEDDING_MODEL", "EMBEDDING_DIMENSIONS", "EMBEDDING_MODEL_DIR"};
        for (String key : envKeys) {
            assertNotNull(ConfigLoader.mapEnvKey(key), "Should map: " + key);
        }
    }

    @Test
    @DisplayName("embedding defaults exist in built-in defaults")
    void shouldHaveEmbeddingDefaults() {
        ConfigLoader loader = new ConfigLoader(new String[]{});
        ConfigLoader.ConfigResult result = loader.load();

        // embedding defaults should exist even without any config files
        assertNotNull(result.entries().get("embedding.provider"));
        assertNotNull(result.entries().get("embedding.base_url"));
        assertNotNull(result.entries().get("embedding.api_key"));
        assertNotNull(result.entries().get("embedding.model"));
        assertNotNull(result.entries().get("embedding.dimensions"));
        assertNotNull(result.entries().get("embedding.model_dir"));
    }

    @Test
    @DisplayName("embedding config from .env file")
    void shouldLoadEmbeddingFromDotEnv() throws IOException {
        Path envFile = tempDir.resolve(".env");
        writeFile(envFile,
                "EMBEDDING_PROVIDER=external\n" +
                        "EMBEDDING_BASE_URL=https://example.com/v1\n" +
                        "EMBEDDING_API_KEY=sk-embed-test-key\n" +
                        "EMBEDDING_MODEL=test-embed-model\n" +
                        "EMBEDDING_DIMENSIONS=1024\n" +
                        "EMBEDDING_MODEL_DIR=data/models/local-test\n");

        var envMap = ConfigLoader.loadDotEnvFile(envFile);
        assertEquals("external", envMap.get("EMBEDDING_PROVIDER"));
        assertEquals("https://example.com/v1", envMap.get("EMBEDDING_BASE_URL"));
        assertEquals("sk-embed-test-key", envMap.get("EMBEDDING_API_KEY"));
        assertEquals("test-embed-model", envMap.get("EMBEDDING_MODEL"));
        assertEquals("1024", envMap.get("EMBEDDING_DIMENSIONS"));
        assertEquals("data/models/local-test", envMap.get("EMBEDDING_MODEL_DIR"));
    }

    @Test
    @DisplayName("embedding config from properties file")
    void shouldLoadEmbeddingFromProperties() throws IOException {
        Path propsFile = tempDir.resolve("test.properties");
        writeFile(propsFile,
                "embedding.provider=external\n" +
                        "embedding.base_url=https://api.example.com/v1\n" +
                        "embedding.api_key=sk-props-embed\n" +
                        "embedding.model=props-embed-model\n" +
                        "embedding.dimensions=1536\n" +
                        "embedding.model_dir=data/models/props-test\n");

        var props = ConfigLoader.loadPropertiesFile(propsFile);
        assertEquals("external", props.getProperty("embedding.provider"));
        assertEquals("https://api.example.com/v1", props.getProperty("embedding.base_url"));
        assertEquals("sk-props-embed", props.getProperty("embedding.api_key"));
        assertEquals("props-embed-model", props.getProperty("embedding.model"));
        assertEquals("1536", props.getProperty("embedding.dimensions"));
        assertEquals("data/models/props-test", props.getProperty("embedding.model_dir"));
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
