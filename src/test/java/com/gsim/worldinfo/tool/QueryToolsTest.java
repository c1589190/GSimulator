package com.gsim.worldinfo.tool;

import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryToolsTest {

    private WorldInformation wi;

    @BeforeEach
    void setUp() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("worldview", new Checkpoint("世界观", "worldview", List.of(
                new Element("气候.中原", "text", "中原大旱", List.of("气候"), List.of(), null, null)
            ))));
        NodeSnapshot n1 = new NodeSnapshot("n0001", "n0000", 1, "t1", "simulated", "t1",
            Map.of("player.曹操", new Checkpoint("曹操", "player", List.of(
                new Element("曹操.行动.起兵", "action", "曹操自陈留起兵", List.of("曹操", "军事"),
                    List.of("narrative.main"), null, null)
            )),
            "narrative", new Checkpoint("推文", "narrative", List.of(
                new Element("narrative.main", "narrative", "曹操起兵，天下震动", List.of("推文"),
                    List.of("player.曹操.elements.0"), null, null)
            ))));

        wi = new WorldInformation("test", List.of(n0, n1));
    }

    @Test
    void queryCheckpointReturnsAllHistory() {
        var tool = new QueryCheckpointTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_checkpoint", Map.of("checkpointId", "player.曹操")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).snippet().contains("陈留"));
    }

    @Test
    void queryCheckpointWildcardReturnsAllMatching() {
        var tool = new QueryCheckpointTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_checkpoint", Map.of("checkpointId", "player.*")));

        assertTrue(r.success());
        assertEquals(1, r.items().size()); // player.曹操
        assertTrue(r.items().get(0).snippet().contains("陈留"));
    }

    @Test
    void queryCheckpointMissingIdFails() {
        var tool = new QueryCheckpointTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_checkpoint", Map.of()));
        assertFalse(r.success());
    }

    @Test
    void queryKeywordFindsText() {
        var tool = new QueryKeywordTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_keyword", Map.of("keywords", "中原")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).path().contains("n0000"));
    }

    @Test
    void queryKeywordWithPagination() {
        var tool = new QueryKeywordTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_keyword",
            Map.of("keywords", "曹操", "limit", "1", "offset", "0")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
    }

    @Test
    void queryKeywordWithCheckpointFilter() {
        var tool = new QueryKeywordTool(() -> wi);
        // "中原" keyword exists in "worldview" checkpoint
        ToolResult r = tool.execute(new ToolCall("query_keyword",
            Map.of("keywords", "中原", "checkpointId", "worldview")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).path().contains("n0000"));
    }

    @Test
    void queryKeywordCheckpointFilterExcludesNonMatching() {
        var tool = new QueryKeywordTool(() -> wi);
        // "曹操" keyword exists in "player.曹操" and "narrative" but not "worldview"
        ToolResult r = tool.execute(new ToolCall("query_keyword",
            Map.of("keywords", "曹操", "checkpointId", "worldview")));

        assertTrue(r.success());
        assertEquals(0, r.items().size());
    }

    @Test
    void queryNodeReturnsAllCheckpoints() {
        var tool = new QueryNodeTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_node", Map.of("nodeId", "n0001")));

        assertTrue(r.success());
        assertEquals(2, r.items().size()); // player.曹操 + narrative
    }

    @Test
    void queryNodeUnknownIdFails() {
        var tool = new QueryNodeTool(() -> wi);
        ToolResult r = tool.execute(new ToolCall("query_node", Map.of("nodeId", "n9999")));
        assertFalse(r.success());
    }
}
