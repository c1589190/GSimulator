package com.gsim.core.worldinfo.loader;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.worldinfo.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorldInfoBuilder.discover() 宽松加载语义测试：
 * 完整链、断链保留、孤立节点、多分支合并、空世界。
 */
class WorldInfoBuilderDiscoverTest {

    @TempDir
    Path tmpDir;

    private static NodeSnapshot node(String id, String parentId, int turn) {
        return new NodeSnapshot(
                id,
                parentId,
                turn,
                "t" + turn,
                "active",
                "t" + turn,
                Map.of("cp" + turn, new Checkpoint("cp" + turn, "player", List.of(Element.simple("k", "text", "v")))),
                new LinkedHashMap<>());
    }

    private void save(String world, NodeSnapshot... nodes) throws Exception {
        for (NodeSnapshot n : nodes) {
            NodeLoader.save(NodeLoader.nodeFile(tmpDir, world, n.nodeId()), n);
        }
    }

    private List<String> ids(WorldInformation wi) {
        return wi.branchChain().stream().map(NodeSnapshot::nodeId).toList();
    }

    @Test
    void discoverFindsFullChainFromLeaf() throws Exception {
        save("w", node("n0000", null, 0), node("n0001", "n0000", 1), node("n0002", "n0001", 2));

        WorldInformation wi = WorldInfoBuilder.discover(tmpDir, "w");

        assertNotNull(wi);
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0002", wi.activeNodeId());
        assertEquals(List.of("n0000", "n0001", "n0002"), ids(wi));
    }

    @Test
    void discoverKeepsNodesOnBrokenLink() throws Exception {
        // n0001.json 缺失 → 从 n0002 回溯断链,但 n0000 仍应保留（有啥取啥）
        save("w", node("n0000", null, 0), node("n0002", "n0001", 2));

        WorldInformation wi = WorldInfoBuilder.discover(tmpDir, "w");

        assertNotNull(wi);
        assertEquals(2, wi.branchChain().size());
        assertEquals("n0000", wi.rootNodeId()); // 最小 turn 为根
        assertEquals("n0002", wi.activeNodeId()); // 最大 turn 为活跃
        assertNotNull(wi.nodeById("n0000"));
        assertNotNull(wi.nodeById("n0002"));
        assertEquals(List.of("n0000", "n0002"), ids(wi));
    }

    @Test
    void discoverIncludesOrphanNodes() throws Exception {
        // n0009 的 parent n0008 不存在 → 孤立节点,仍应纳入
        save("w", node("n0000", null, 0), node("n0001", "n0000", 1), node("n0009", "n0008", 9));

        WorldInformation wi = WorldInfoBuilder.discover(tmpDir, "w");

        assertNotNull(wi);
        assertEquals(3, wi.branchChain().size());
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0009", wi.activeNodeId());
        assertNotNull(wi.nodeById("n0000"));
        assertNotNull(wi.nodeById("n0001"));
        assertNotNull(wi.nodeById("n0009"));
        assertEquals(List.of("n0000", "n0001", "n0009"), ids(wi));
    }

    @Test
    void discoverMergesBranches() throws Exception {
        // 分支 n0000→n0001→n0003 (turn 3) 与 n0000→n0001→n0002 (turn 2) 全部保留
        save(
                "w",
                node("n0000", null, 0),
                node("n0001", "n0000", 1),
                node("n0002", "n0001", 2),
                node("n0003", "n0001", 3));

        WorldInformation wi = WorldInfoBuilder.discover(tmpDir, "w");

        assertNotNull(wi);
        assertEquals(4, wi.branchChain().size());
        assertEquals("n0000", wi.rootNodeId());
        assertEquals("n0003", wi.activeNodeId()); // 最大 turn 为活跃
        assertNotNull(wi.nodeById("n0002"));
        assertEquals(List.of("n0000", "n0001", "n0002", "n0003"), ids(wi));
    }

    @Test
    void discoverEmptyWorldReturnsNull() {
        assertNull(WorldInfoBuilder.discover(tmpDir, "no-such-world"));
    }

    @Test
    void discoverEmptyNodesDirReturnsNull() throws Exception {
        Files.createDirectories(NodeLoader.nodesDir(tmpDir, "w"));
        assertNull(WorldInfoBuilder.discover(tmpDir, "w"));
    }

    @Test
    void discoverIgnoresNonNodeFiles() throws Exception {
        // 目录里混入非节点文件（如 contour.json / map diff）不应影响结果
        save("w", node("n0000", null, 0));
        Files.writeString(NodeLoader.nodesDir(tmpDir, "w").resolve("contour.json"), "{}");
        Files.writeString(NodeLoader.nodesDir(tmpDir, "w").resolve("n0000_map.json"), "{}");

        WorldInformation wi = WorldInfoBuilder.discover(tmpDir, "w");

        assertNotNull(wi);
        assertEquals(List.of("n0000"), ids(wi));
    }
}
