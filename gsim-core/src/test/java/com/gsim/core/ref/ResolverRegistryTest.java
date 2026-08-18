package com.gsim.core.ref;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.doc.DocStore;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.loader.NodeLoader;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ResolverRegistry — 统一地址解析（@world/@doc/@cache/@import/裸引用）与
 * {@code @world} 两段式解析到活跃节点（而非硬编码 n0000）的回归测试。
 */
@DisplayName("ResolverRegistry — 统一地址解析")
class ResolverRegistryTest {

    @TempDir
    Path tmpDir;

    private Path worldsDir;
    private Path importDir;
    private Path cacheDir;
    private DocStore docStore;
    private ResolverRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        // 世界 w1：n0000（turn 0，含 worldview:old）+ n0001（turn 1 = 活跃节点，含 worldview:flag）
        worldsDir = tmpDir.resolve("worlds");
        WorldIndexManager.createWorld(worldsDir, "w1", "世界一"); // world.json + n0000 + active.json
        String t = "2026-01-01T00:00:00Z";
        NodeSnapshot root = new NodeSnapshot(
                "n0000",
                null,
                0,
                "t0",
                "simulated",
                t,
                Map.of(
                        "worldview",
                        new Checkpoint(
                                "世界观",
                                "misc",
                                List.of(new Element("old", "text", "from-n0000", List.of(), List.of(), t, t)))),
                new LinkedHashMap<>());
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, "w1", "n0000"), root);
        NodeSnapshot active = new NodeSnapshot(
                "n0001",
                "n0000",
                1,
                "t1",
                "simulated",
                t,
                Map.of(
                        "worldview",
                        new Checkpoint(
                                "世界观",
                                "misc",
                                List.of(new Element("flag", "text", "from-n0001", List.of(), List.of(), t, t)))),
                new LinkedHashMap<>());
        NodeLoader.save(NodeLoader.nodeFile(worldsDir, "w1", "n0001"), active);

        importDir = tmpDir.resolve("import");
        Files.createDirectories(importDir);
        Files.writeString(importDir.resolve("imp1.md"), "import-content");

        cacheDir = tmpDir.resolve("cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("mycache.txt"), "cache-content");

        Path docsDir = tmpDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("doc1.md"), "doc-content");
        docStore = new DocStore(docsDir);
        docStore.init();

        registry = ResolverRegistry.createWithBuiltins();
    }

    private ResolverContext ctx(String worldId) {
        return ResolverContext.of(worldsDir, worldId, importDir, docStore, cacheDir);
    }

    @Test
    @DisplayName("@world 三段式解析到显式节点")
    void worldThreePartResolvesExplicitNode() {
        var result = registry.resolve("@world:n0000:worldview:old", ctx("w1"));
        assertEquals("world", result.source());
        assertEquals("n0000:worldview:old", result.id());
        assertEquals("from-n0000", result.content());
    }

    @Test
    @DisplayName("@world 两段式解析到活跃节点 n0001（而非硬编码 n0000）")
    void worldTwoPartResolvesActiveNode() {
        var result = registry.resolve("@world:worldview:flag", ctx("w1"));
        assertEquals("world", result.source());
        assertEquals("n0001:worldview:flag", result.id());
        assertEquals("from-n0001", result.content());
    }

    @Test
    @DisplayName("@doc 解析")
    void docResolves() {
        var result = registry.resolve("@doc:doc1", ctx("w1"));
        assertEquals("doc", result.source());
        assertEquals("doc1", result.id());
        assertEquals("doc-content", result.content());
    }

    @Test
    @DisplayName("@cache 解析")
    void cacheResolves() {
        var result = registry.resolve("@cache:mycache", ctx("w1"));
        assertEquals("cache", result.source());
        assertEquals("cache-content", result.content());
    }

    @Test
    @DisplayName("@import 解析")
    void importResolves() {
        var result = registry.resolve("@import:imp1.md", ctx("w1"));
        assertEquals("import", result.source());
        assertEquals("import-content", result.content());
    }

    @Test
    @DisplayName("裸 nodeId:cpId:key 按 @world 语义解析")
    void bareNodeCpKeyResolves() {
        var result = registry.resolve("n0000:worldview:old", ctx("w1"));
        assertEquals("world", result.source());
        assertEquals("from-n0000", result.content());
    }

    @Test
    @DisplayName("裸 cpId:key 解析到活跃节点")
    void bareCpKeyResolvesToActiveNode() {
        var result = registry.resolve("worldview:flag", ctx("w1"));
        assertEquals("world", result.source());
        assertEquals("n0001:worldview:flag", result.id());
        assertEquals("from-n0001", result.content());
    }

    @Test
    @DisplayName("未知 @ 前缀抛 IllegalArgumentException")
    void unknownAtPrefixThrows() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("@foo:bar", ctx("w1")));
        assertTrue(e.getMessage().contains("Unknown ref prefix"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("gsimap: 未注册 resolver 时抛 IllegalArgumentException（应用层需注册 GsimapResolver）")
    void gsimapWithoutRegisteredResolverThrows() {
        var e = assertThrows(IllegalArgumentException.class, () -> registry.resolve("gsimap:region:x", ctx("w1")));
        assertTrue(e.getMessage().contains("gsimap"), "msg: " + e.getMessage());
    }

    @Test
    @DisplayName("@world 不存在的节点抛 IllegalArgumentException")
    void worldMissingNodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("@world:unknown:worldview:old", ctx("w1")));
    }

    @Test
    @DisplayName("空 worldId 抛 IllegalStateException（沿用 RefResolver 既有错误语义）")
    void blankWorldIdThrows() {
        assertThrows(IllegalStateException.class, () -> registry.resolve("@world:worldview:flag", ctx(" ")));
    }

    @Test
    @DisplayName("畸形引用抛 IllegalArgumentException")
    void malformedRefsThrow() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("@world:", ctx("w1")));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("@world:a:b:c:d", ctx("w1")));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve(null, ctx("w1")));
    }
}
