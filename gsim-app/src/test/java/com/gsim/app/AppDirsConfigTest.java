package com.gsim.app;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.config.ConfigLoader;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证 docs.dir / caches.dir 配置键在 AppConfig 与 DocStore 组装层的接线：
 * 未配置时回退 worldsDir 同级目录，配置时全链路（AppConfig → DocStore）生效。
 */
@DisplayName("docs.dir / caches.dir 配置键接线")
class AppDirsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("未配置时 docsDir/cachesDir 回退 worldsDir 同级目录")
    void defaultsToWorldsSibling() {
        ConfigLoader loader = new ConfigLoader(new String[] {});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(config.worldsDir().resolveSibling("docs"), config.docsDir());
        assertEquals(config.worldsDir().resolveSibling("caches"), config.cachesDir());
    }

    @Test
    @DisplayName("配置文件设置 docs.dir / caches.dir 时 AppConfig 返回配置路径")
    void overridableViaConfigFile() throws IOException {
        Path propsFile = tempDir.resolve("dirs.properties");
        writeFile(
                propsFile,
                "llm.base_url=https://api.example.com/v1\n"
                        + "llm.api_key=sk-test\n"
                        + "llm.model=m\n"
                        + "docs.dir=/tmp/xyzdocs\n"
                        + "caches.dir=/tmp/xyzcaches\n");

        ConfigLoader loader = new ConfigLoader(new String[] {"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(Path.of("/tmp/xyzdocs"), config.docsDir());
        assertEquals(Path.of("/tmp/xyzcaches"), config.cachesDir());
    }

    @Test
    @DisplayName("DocStore 文件落在配置的 docs.dir 下（与 GSimulatorApplication 组装一致）")
    void docStoreWritesToConfiguredDir() throws IOException {
        Path docsRoot = tempDir.resolve("configured-docs");
        Path propsFile = tempDir.resolve("docstore.properties");
        writeFile(
                propsFile,
                "llm.base_url=https://api.example.com/v1\n"
                        + "llm.api_key=sk-test\n"
                        + "llm.model=m\n"
                        + "docs.dir=" + docsRoot + "\n");

        ConfigLoader loader = new ConfigLoader(new String[] {"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());
        assertEquals(docsRoot, config.docsDir());

        DocStore docStore = new DocStore(config.docsDir());
        docStore.init();
        docStore.create("t7-qa", DocType.OTHER, "QA", "content", List.of());

        assertTrue(Files.exists(docsRoot.resolve("other").resolve("t7-qa.md")));
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
