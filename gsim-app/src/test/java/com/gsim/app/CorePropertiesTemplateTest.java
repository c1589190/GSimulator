package com.gsim.app;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.config.CoreConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GSimulatorApplication.ensureCorePropertiesTemplate 落盘行为测试（package-private static 方法）。 */
class CorePropertiesTemplateTest {

    @TempDir
    Path tmpDir;

    @Test
    void ensureWritesTemplateWithThreshold() throws Exception {
        GSimulatorApplication.ensureCorePropertiesTemplate(tmpDir);

        Path target = tmpDir.resolve("core.properties");
        assertTrue(Files.isRegularFile(target));
        String content = Files.readString(target);
        assertTrue(content.contains("core.doc.staging.threshold=500"));
    }

    @Test
    void ensureDoesNotOverwriteExisting() throws Exception {
        Path target = tmpDir.resolve("core.properties");
        Files.writeString(target, "custom=1");

        GSimulatorApplication.ensureCorePropertiesTemplate(tmpDir);

        assertEquals("custom=1", Files.readString(target));
    }

    @Test
    void writtenFileIsReadableByCoreConfig() throws Exception {
        GSimulatorApplication.ensureCorePropertiesTemplate(tmpDir);

        CoreConfig config = CoreConfig.load(tmpDir.resolve("core.properties"));
        assertEquals(500, config.getInt(CoreConfig.STAGING_THRESHOLD, -1));
    }
}
