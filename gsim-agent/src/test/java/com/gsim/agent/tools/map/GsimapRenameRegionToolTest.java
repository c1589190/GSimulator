package com.gsim.agent.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agent.tools.search.SearchToolContext;
import com.gsim.agent.tools.worldinfo.WriteElementTool;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.importing.ImportDocumentService;
import com.gsim.agentsmanager.ref.InlineRefResolver;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.ElementRef;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.NodeLoader;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.core.worldinfo.loader.WorldManager;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimapRenameRegionTool — {@code gsimap_rename_region} 成功后向 LinkIndex 传播关联改写：
 * 引用被改名区域（links 含 {@code gsimap:region:旧名}）的元素，其 links 中被替换为新名，
 * 且每个受影响节点落盘持久化（重启/重载后仍为新名）。
 */
@DisplayName("GsimapRenameRegionTool — renameRegion 后 LinkIndex 关联改写传播")
class GsimapRenameRegionToolTest {

    private static final String WORLD = "rename-propagation-world";

    @TempDir
    Path tmpDir;

    private WorldInformation wi;
    private MapService mapService;
    private GsimapRenameRegionTool tool;

    @BeforeEach
    void setUp() throws IOException {
        // 临时世界：world.json + n0000（空根节点，含 worldview 检查点）
        WorldIndexManager.createWorld(tmpDir, WORLD, "改名传播世界");

        // 地图：一格平原 + 迷雾森林区域（供 renameRegion 操作）
        MapData mapData = new MapData(
                30,
                false,
                Map.of(MapData.hexKey(0, 0), MapData.HexCell.of("#6CC261", "plains")),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
        MapStore.saveFull(tmpDir, WORLD, "n0000", mapData);
        mapService = new MapService(tmpDir);
        mapService.createRegion(WORLD, "n0000", "迷雾森林", "forest", "#228B22", "浓雾笼罩", List.of());

        // 第二个节点 n0001（turn=1，成为活跃节点）—— 多节点传播用例
        NodeSnapshot n1 = new NodeSnapshot(
                "n0001",
                "n0000",
                1,
                "time+1",
                "running",
                "t1",
                // 可变元素列表（Checkpoint 紧凑构造不拷贝，写入需 ArrayList）
                new LinkedHashMap<>(Map.of("worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()))),
                new LinkedHashMap<>());
        NodeLoader.save(NodeLoader.nodeFile(tmpDir, WORLD, "n0001"), n1);

        // WorldInformation：从磁盘构建（含 LinkIndex）
        wi = new WorldManager(tmpDir).loadWorld(WORLD);
        assertNotNull(wi, "world should load from disk");

        SearchToolContext searchCtx = new SearchToolContext(() -> wi, mapService, null, null, tmpDir, null, null);
        tool = new GsimapRenameRegionTool(mapService, searchCtx);
    }

    /** write_element 工具（真实写入路径：links 校验 + LinkIndex 增量 + 落盘）。 */
    private WriteElementTool writeTool() throws IOException {
        Path docsDir = tmpDir.resolve("docs");
        DocStore docStore = new DocStore(docsDir);
        docStore.init();
        Path importDir = tmpDir.resolve("import");
        Files.createDirectories(importDir);
        return new WriteElementTool(
                () -> wi, tmpDir, null, new InlineRefResolver(docStore, new ImportDocumentService(importDir)));
    }

    private void writeElement(String nodeId, String key, String value, String links) throws IOException {
        ToolResult r = writeTool()
                .execute(new ToolCall(
                        "write_element", Map.of("ref", nodeId + ":worldview:" + key, "value", value, "links", links)));
        assertTrue(r.success(), "write_element should succeed: " + r.error());
    }

    private ToolResult rename(String oldName, String newName) {
        return tool.execute(new ToolCall(
                "gsimap_rename_region",
                Map.of("worldId", WORLD, "nodeId", "n0000", "oldName", oldName, "newName", newName)));
    }

    private static Element elementIn(WorldInformation w, String nodeId, String key) {
        NodeSnapshot node = w.nodeById(nodeId);
        assertNotNull(node, "node " + nodeId + " should be present");
        for (var el : node.checkpoint("worldview").elements()) {
            if (el.key().equals(key)) return el;
        }
        return fail("element '" + key + "' not found in node " + nodeId);
    }

    // -- happy path --

    @Test
    @DisplayName("rename 后元素 links 改写、LinkIndex 同步、createdAt/value 保留")
    void renamePropagatesLinkRewriteElementAndIndex() throws IOException {
        writeElement("n0000", "气候.迷雾森林", "浓雾笼罩，野兽出没", "gsimap:region:迷雾森林,n0001:characters:曹操");
        Element before = elementIn(wi, "n0000", "气候.迷雾森林");

        ToolResult r = rename("迷雾森林", "幽暗森林");
        assertTrue(r.success(), "rename should succeed: " + r.error());

        Element after = elementIn(wi, "n0000", "气候.迷雾森林");
        assertEquals(
                List.of("gsimap:region:幽暗森林", "n0001:characters:曹操"),
                after.links(),
                "links rewritten, other links order preserved");
        assertEquals(before.createdAt(), after.createdAt(), "createdAt preserved");
        assertEquals(before.updatedAt(), after.updatedAt(), "updatedAt preserved");
        assertEquals(before.value(), after.value(), "element value untouched (only links rewritten)");

        // LinkIndex 反向索引同步
        assertTrue(wi.linkIndex().findByLink("gsimap:region:迷雾森林").isEmpty(), "old key must not be indexed");
        List<ElementRef> hits = wi.linkIndex().findByLink("gsimap:region:幽暗森林");
        assertEquals(1, hits.size(), "new key must be indexed");
        assertEquals("气候.迷雾森林", hits.get(0).element().key());
        assertEquals("n0000", hits.get(0).nodeId());
    }

    @Test
    @DisplayName("无关联元素时 rename 静默成功，其他元素不受影响")
    void renameWithoutLinkedElementsSucceedsSilently() throws IOException {
        writeElement("n0000", "气候.南境", "干燥少雨", "n0001:characters:曹操"); // 无区域链接

        ToolResult r = rename("迷雾森林", "幽暗森林");
        assertTrue(r.success(), "rename with no linked elements must succeed: " + r.error());

        // 无关元素未被触碰
        assertEquals(
                List.of("n0001:characters:曹操"), elementIn(wi, "n0000", "气候.南境").links());
    }

    @Test
    @DisplayName("MapService 失败（区域名冲突）时不传播改写")
    void failedRenameDoesNotRewriteLinks() throws IOException {
        writeElement("n0000", "设定.迷雾森林", "传说之地", "gsimap:region:迷雾森林");
        mapService.createRegion(WORLD, "n0000", "幽暗森林", "forest", "#000000", "", List.of());

        ToolResult r = rename("迷雾森林", "幽暗森林");
        assertTrue(r.success(), "tool keeps its result shape");

        // MapService 校验失败（Region already exists）→ 不改写
        assertEquals(
                List.of("gsimap:region:迷雾森林"), elementIn(wi, "n0000", "设定.迷雾森林").links());
        assertEquals(1, wi.linkIndex().findByLink("gsimap:region:迷雾森林").size(), "old key still indexed");
        assertTrue(wi.linkIndex().findByLink("gsimap:region:幽暗森林").isEmpty(), "new key not indexed");
    }

    // -- persistence --

    @Test
    @DisplayName("改写落盘：重载世界后 links 仍为新名")
    void linksSurviveWorldReloadFromDisk() throws IOException {
        writeElement("n0000", "设定.迷雾森林", "传说之地", "gsimap:region:迷雾森林");

        ToolResult r = rename("迷雾森林", "幽暗森林");
        assertTrue(r.success(), "rename should succeed: " + r.error());

        // 从磁盘重新加载世界（模拟重启）— 链接改写必须已落盘
        WorldInformation fresh = new WorldManager(tmpDir).loadWorld(WORLD);
        assertNotNull(fresh, "world reload should succeed");
        assertTrue(fresh.linkIndex().findByLink("gsimap:region:迷雾森林").isEmpty(), "old link must not survive reload");
        List<ElementRef> hits = fresh.linkIndex().findByLink("gsimap:region:幽暗森林");
        assertEquals(1, hits.size(), "rewritten link must survive reload");
        assertEquals("设定.迷雾森林", hits.get(0).element().key());
        assertEquals(List.of("gsimap:region:幽暗森林"), hits.get(0).element().links());
    }

    @Test
    @DisplayName("多个受影响节点全部改写并各自落盘")
    void multipleAffectedNodesAllRewrittenAndPersisted() throws IOException {
        writeElement("n0000", "设定.北境迷雾", "北部边境", "gsimap:region:迷雾森林");
        writeElement("n0001", "设定.南境迷雾", "南部边境", "gsimap:region:迷雾森林");

        ToolResult r = rename("迷雾森林", "幽暗森林");
        assertTrue(r.success(), "rename should succeed: " + r.error());

        // 内存：两个节点均改写
        assertEquals(2, wi.linkIndex().findByLink("gsimap:region:幽暗森林").size(), "both nodes rewritten in memory");

        // 磁盘：每个受影响节点都落盘
        WorldInformation fresh = new WorldManager(tmpDir).loadWorld(WORLD);
        assertEquals(2, fresh.linkIndex().findByLink("gsimap:region:幽暗森林").size(), "both nodes persisted");
        assertEquals(
                List.of("gsimap:region:幽暗森林"),
                elementIn(fresh, "n0000", "设定.北境迷雾").links());
        assertEquals(
                List.of("gsimap:region:幽暗森林"),
                elementIn(fresh, "n0001", "设定.南境迷雾").links());
    }
}
