package com.gsim.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CoreConfig 内置默认 + 外部文件覆盖测试。 */
class CoreConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadUsesClasspathDefaultThreshold() {
        CoreConfig config = CoreConfig.load();
        assertEquals(500, config.getInt(CoreConfig.STAGING_THRESHOLD, -1));
    }

    @Test
    void externalFileOverridesDefaultThreshold() throws IOException {
        writeExternal("core.doc.staging.threshold=100");
        CoreConfig config = CoreConfig.load(externalPath());
        assertEquals(100, config.getInt(CoreConfig.STAGING_THRESHOLD, -1));
    }

    @Test
    void missingExternalFileFallsBackToDefault() {
        CoreConfig config = CoreConfig.load(tempDir.resolve("no-such-file.properties"));
        assertEquals(500, config.getInt(CoreConfig.STAGING_THRESHOLD, -1));
    }

    @Test
    void externalFileWithoutThresholdKeyKeepsDefault() throws IOException {
        writeExternal("unrelated.key=1");
        CoreConfig config = CoreConfig.load(externalPath());
        assertEquals(500, config.getInt(CoreConfig.STAGING_THRESHOLD, -1));
    }

    @Test
    void invalidThresholdValueFallsBackToDefault() throws IOException {
        writeExternal("core.doc.staging.threshold=abc");
        CoreConfig config = CoreConfig.load(externalPath());
        assertEquals(500, config.getInt(CoreConfig.STAGING_THRESHOLD, -1));
    }

    @Test
    void getReturnsNullForUnknownKey() {
        CoreConfig config = CoreConfig.load();
        assertNull(config.get("no.such.key"));
    }

    private Path externalPath() {
        return tempDir.resolve("core.properties");
    }

    private void writeExternal(String content) throws IOException {
        Files.writeString(externalPath(), content + "\n", StandardCharsets.UTF_8);
    }
}
