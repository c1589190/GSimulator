package com.gsim.worldinfo;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    @Test
    void searchFindsMatchingElements() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("worldview", new Checkpoint("世界观", "worldview", List.of(
                new Element("k0", "text", "中原大旱蝗灾四起", List.of("气候", "灾害"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var result = idx.search("中原", 10, 0);

        assertEquals(1, result.totalHits());
        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).snippet().contains("中原"));
        assertEquals("n0000", result.items().get(0).elementRef().nodeId());
    }

    @Test
    void searchMultipleKeywordsOrSemantics() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("k1", "action", "曹操自陈留起兵", List.of("曹操", "军事"), List.of()),
                new Element("k2", "action", "皇甫嵩固守长社", List.of("皇甫嵩", "军事"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var result = idx.search("曹操", 10, 0);

        assertEquals(1, result.totalHits());
        assertEquals("k1", result.items().get(0).elementRef().element().key());
    }

    @Test
    void paginationWithOffset() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("a", "action", "曹操起兵", List.of("曹操"), List.of()),
                new Element("b", "action", "曹操会合", List.of("曹操"), List.of()),
                new Element("c", "action", "曹操大破", List.of("曹操"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var page1 = idx.search("曹操", 2, 0);
        assertEquals(3, page1.totalHits());
        assertEquals(2, page1.items().size());

        var page2 = idx.search("曹操", 2, 2);
        assertEquals(1, page2.items().size());
    }

    @Test
    void searchByTag() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("k1", "action", "some text", List.of("曹操", "军事"), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        assertEquals(1, idx.search("军事", 10, 0).totalHits());
        assertEquals(1, idx.search("曹操", 10, 0).totalHits());
    }

    @Test
    void noMatchReturnsEmpty() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            Map.of("p", new Checkpoint("p", "player", List.of(
                new Element("k1", "action", "hello world", List.of(), List.of())
            ))));

        KeywordIndex idx = KeywordIndex.build(List.of(n0));
        var result = idx.search("不存在", 10, 0);
        assertEquals(0, result.totalHits());
        assertTrue(result.items().isEmpty());
    }
}
