package com.gsim.core.worldinfo.loader;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorldManager — 统一世界读取入口的单元测试。
 */
@DisplayName("WorldManager 统一读取")
class WorldManagerTest {

    @TempDir
    Path tmpDir;

    private WorldManager worldManager;

    @BeforeEach
    void setUp() {
        worldManager = new WorldManager(tmpDir);
    }

    @Test
    @DisplayName("不存在的世界返回 null，activeNodeIdOr 使用 fallback")
    void missingWorldFallsBack() {
        assertFalse(worldManager.exists("missing"));
        assertNull(worldManager.loadWorld("missing"));
        assertNull(worldManager.activeNodeId("missing"));
        assertEquals("n0000", worldManager.activeNodeIdOr("missing", "n0000"));
    }

    @Test
    @DisplayName("可列出并读取 WorldIndexManager 创建的世界")
    void listsAndLoadsCreatedWorld() {
        WorldIndexManager.createWorld(tmpDir, "w1", "世界一");

        assertEquals(1, worldManager.listWorlds().size());
        assertTrue(worldManager.exists("w1"));
        assertEquals("世界一", worldManager.loadMeta("w1").name());

        WorldInformation wi = worldManager.loadWorld("w1");
        assertNotNull(wi);
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0000", wi.activeNodeId());
    }

    @Test
    @DisplayName("activeNodeId 取 turn 最大的节点，不依赖硬编码 n0000")
    void activeNodeComesFromHighestTurn() {
        WorldIndexManager.createWorld(tmpDir, "w1", "世界一");
        NodeSnapshot child = new NodeSnapshot(
                "n0002",
                "n0000",
                7,
                "第七回合",
                "active",
                java.time.Instant.now().toString(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>());
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w1", "n0002"), child);

        WorldInformation wi = worldManager.loadWorld("w1");
        assertNotNull(wi);
        assertEquals("n0002", wi.activeNodeId());
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0002", worldManager.activeNodeId("w1"));
    }

    @Test
    @DisplayName("nodesDir 和 worldFile 路径正确")
    void exposesUsefulPaths() {
        WorldIndexManager.createWorld(tmpDir, "w1", "世界一");
        assertEquals(tmpDir.resolve("w1").resolve("nodes"), worldManager.nodesDir("w1"));
        assertEquals(tmpDir.resolve("w1").resolve("world.json"), worldManager.worldFile("w1"));
    }
}
