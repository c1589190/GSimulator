package com.gsim.core.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.mcp.GsimRequestContext;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * gsim_search_world 工具测试。
 *
 * <p>固定世界：n0000（根，回合 0）worldview 检查点含元素 {@code 都城="长安"}；
 * n0001（活跃，回合 1）含 {@code player.曹操} 检查点（元素 {@code 行动.起兵="曹操自陈留起兵"}）
 * 与 {@code narrative} 检查点（元素 {@code narrative.main="曹操起兵，天下震动"}）。
 */
class GsimSearchWorldToolTest {

    private final WorldInformation wi = newWorld();
    private final GsimSearchWorldTool tool = new GsimSearchWorldTool(new SearchToolContext(() -> wi, null, null));

    @AfterEach
    void clearRequestContext() {
        GsimRequestContext.clear();
    }

    private static WorldInformation newWorld() {
        NodeSnapshot n0 = new NodeSnapshot(
                "n0000",
                null,
                0,
                "origin",
                "initial",
                "t0",
                Map.of(
                        "worldview",
                        new Checkpoint(
                                "世界观",
                                "worldview",
                                List.of(new Element("都城", "text", "长安", List.of("都城", "地理"), List.of(), null, null)))),
                new LinkedHashMap<>());
        NodeSnapshot n1 = new NodeSnapshot(
                "n0001",
                "n0000",
                1,
                "t1",
                "simulated",
                "t1",
                Map.of(
                        "player.曹操",
                        new Checkpoint(
                                "曹操",
                                "player",
                                List.of(new Element(
                                        "行动.起兵", "action", "曹操自陈留起兵", List.of("曹操", "军事"), List.of(), null, null))),
                        "narrative",
                        new Checkpoint(
                                "推文",
                                "narrative",
                                List.of(new Element(
                                        "narrative.main",
                                        "narrative",
                                        "曹操起兵，天下震动",
                                        List.of("推文"),
                                        List.of(),
                                        null,
                                        null)))),
                new LinkedHashMap<>());
        return new WorldInformation("test", List.of(n0, n1));
    }

    private ToolResult run(String... kvPairs) {
        if (kvPairs.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            params.put(kvPairs[i], kvPairs[i + 1]);
        }
        return tool.execute(new ToolCall("gsim_search_world", params));
    }

    // -- happy path --

    @Test
    void searchFindsElementByValue() {
        ToolResult r = run("worldId", "test", "keywords", "长安");

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        ToolResult.Item item = r.items().get(0);
        assertEquals("n0000:worldview:都城", item.title());
        assertEquals("n0000:worldview:都城", item.path());
        assertTrue(item.snippet().startsWith("type=element | "));
        assertTrue(item.snippet().contains("长安"));
        assertTrue(item.score() > 0);
    }

    @Test
    void searchFindsElementByTag() {
        ToolResult r = run("worldId", "test", "keywords", "军事");

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertEquals("n0001:player.曹操:行动.起兵", r.items().get(0).path());
    }

    @Test
    void defaultSearchesWholeActiveChain() {
        // 不指定 nodeId：默认活跃节点（n0001），语料为整条分支链 → 命中 n0000 与 n0001 的元素
        ToolResult r = run("worldId", "test", "keywords", "曹操");

        assertTrue(r.success());
        assertEquals(2, r.items().size());
        assertEquals("n0001:player.曹操:行动.起兵", r.items().get(0).path());
        assertEquals("n0001:narrative:narrative.main", r.items().get(1).path());
    }

    // -- nodeId scoping --

    @Test
    void nodeIdTargetsNonActiveNode() {
        // nodeId=n0000（非活跃节点）：语料限定为 n0000 分支 → 只命中 n0000 的元素
        ToolResult r = run("worldId", "test", "keywords", "长安", "nodeId", "n0000");
        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertEquals("n0000:worldview:都城", r.items().get(0).path());

        // 非活跃节点上不存在的关键词 → 空
        ToolResult r2 = run("worldId", "test", "keywords", "曹操", "nodeId", "n0000");
        assertTrue(r2.success());
        assertEquals(0, r2.items().size());
    }

    @Test
    void nonexistentNodeIdReturnsEmptyItems() {
        ToolResult r = run("worldId", "test", "keywords", "长安", "nodeId", "n9999");

        assertTrue(r.success());
        assertEquals(0, r.items().size());
    }

    // -- checkpointId filter --

    @Test
    void checkpointIdFilterLimitsCorpus() {
        ToolResult r = run("worldId", "test", "keywords", "曹操", "checkpointId", "player.曹操");
        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertEquals("n0001:player.曹操:行动.起兵", r.items().get(0).path());

        ToolResult r2 = run("worldId", "test", "keywords", "曹操", "checkpointId", "worldview");
        assertTrue(r2.success());
        assertEquals(0, r2.items().size());
    }

    // -- failure & empty cases --

    @Test
    void missingKeywordsFails() {
        ToolResult r = run("worldId", "test");
        assertFalse(r.success());
        assertTrue(r.error().contains("keywords is required"));
    }

    @Test
    void blankKeywordsFails() {
        ToolResult r = run("worldId", "test", "keywords", "   ");
        assertFalse(r.success());
        assertTrue(r.error().contains("keywords is required"));
    }

    @Test
    void noMatchReturnsEmptyItems() {
        ToolResult r = run("worldId", "test", "keywords", "不存在的词");
        assertTrue(r.success());
        assertEquals(0, r.items().size());
    }

    // -- pagination --

    @Test
    void paginationLimitsAndOffsetsResults() {
        ToolResult page1 = run("worldId", "test", "keywords", "曹操", "limit", "1", "offset", "0");
        assertTrue(page1.success());
        assertEquals(1, page1.items().size());
        assertEquals("n0001:player.曹操:行动.起兵", page1.items().get(0).path());

        ToolResult page2 = run("worldId", "test", "keywords", "曹操", "limit", "1", "offset", "1");
        assertTrue(page2.success());
        assertEquals(1, page2.items().size());
        assertEquals("n0001:narrative:narrative.main", page2.items().get(0).path());
    }

    // -- worldId resolution --

    @Test
    void worldIdFromRequestContext() {
        GsimRequestContext.setWorldId("test");
        try {
            ToolResult r = tool.execute(new ToolCall("gsim_search_world", Map.of("keywords", "长安")));
            assertTrue(r.success());
            assertEquals(1, r.items().size());
        } finally {
            GsimRequestContext.clear();
        }
    }
}
