package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeLoaderTest {

    @TempDir
    Path tmpDir;

    // ---- NodeLoader ----

    @Test
    void saveAndLoadRoundtrip() throws Exception {
        Element el = new Element("k", "text", "v", List.of("a"), List.of("b"), null, null);
        Checkpoint cp = new Checkpoint("cp1", "player", List.of(el));
        NodeSnapshot node = new NodeSnapshot("n0000", null, 0, "origin",
            "initial", "2026-01-01T00:00:00Z", Map.of("cp1", cp));

        Path file = NodeLoader.nodeFile(tmpDir, "test-world", "n0000");
        NodeLoader.save(file, node);

        assertTrue(Files.exists(file));
        NodeSnapshot loaded = NodeLoader.load(file);
        assertEquals("n0000", loaded.nodeId());
        assertEquals("initial", loaded.status());
        assertEquals(1, loaded.checkpoints().size());
        assertEquals("v", loaded.checkpoint("cp1").elements().get(0).value());
    }

    // ---- WorldInfoBuilder ----

    @Test
    void buildChainOfThreeNodes() throws Exception {
        // Create three nodes: n0000 → n0001 → n0002
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin",
            "initial", "t0", Map.of("worldview", new Checkpoint("世界观", "worldview",
                List.of(Element.simple("k0", "text", "v0")))));

        NodeSnapshot n1 = new NodeSnapshot("n0001", "n0000", 1, "t1",
            "simulated", "t1", Map.of("player.A", new Checkpoint("A", "player",
                List.of(Element.simple("act1", "action", "did something")))));

        NodeSnapshot n2 = new NodeSnapshot("n0002", "n0001", 2, "t2",
            "simulated", "t2", Map.of("narrative", new Checkpoint("推文", "narrative",
                List.of(Element.simple("main", "narrative", "A did something. The world changed.")))));

        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w", "n0000"), n0);
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w", "n0001"), n1);
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, "w", "n0002"), n2);

        WorldInformation wi = WorldInfoBuilder.build(tmpDir, "w", "n0002");

        assertNotNull(wi);
        assertEquals("w", wi.worldId());
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0002", wi.activeNodeId());
        assertEquals(3, wi.branchChain().size());

        // byCheckpoint: worldview accumulated from n0000
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertEquals("v0", wi.checkpointHistory("worldview").get(0).element().value());

        // player.A from n0001
        assertEquals(1, wi.checkpointHistory("player.A").size());

        // narrative from n0002
        assertEquals(1, wi.checkpointHistory("narrative").size());
    }

    @Test
    void emptyWorldReturnsNull() {
        WorldInformation wi = WorldInfoBuilder.build(tmpDir, "no-such-world", "n0000");
        assertNull(wi);
    }
}
