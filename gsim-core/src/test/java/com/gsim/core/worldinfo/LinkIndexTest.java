package com.gsim.core.worldinfo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * LinkIndex 反向索引测试 -- Element.links 倒排 + WorldInformation 增量维护集成。
 */
class LinkIndexTest {

    private static NodeSnapshot node(String id, int turn, Map<String, Checkpoint> cps) {
        return new NodeSnapshot(id, null, turn, "t" + turn, "origin", "initial", cps, new LinkedHashMap<>());
    }

    private static Element el(String key, List<String> links) {
        return new Element(key, "text", "值-" + key, List.of(), links, null, null);
    }

    private static Checkpoint cp(String id, Element... elements) {
        // mutable copy — appendElement/upsertElement mutate cp.elements() in place
        return new Checkpoint(id, id, new ArrayList<>(List.of(elements)));
    }

    // -- build --

    @Test
    void buildIndexesAllLinksFromWholeChain() {
        NodeSnapshot n0 = node(
                "n0000",
                0,
                Map.of(
                        "worldview",
                        cp("worldview", el("k0", List.of("gsimap:region:迷雾森林", "n0001:characters:曹操"))),
                        "p",
                        cp("p", el("k1", List.of("gsimap:region:迷雾森林", "characters:刘备")))));
        NodeSnapshot n1 = node("n0001", 1, Map.of("p", cp("p", el("k2", List.of("n0001:characters:曹操")))));

        LinkIndex idx = LinkIndex.build(List.of(n0, n1));

        List<ElementRef> forest = idx.findByLink("gsimap:region:迷雾森林");
        assertEquals(2, forest.size());
        assertTrue(forest.stream().anyMatch(r -> r.element().key().equals("k0")));
        assertTrue(forest.stream().anyMatch(r -> r.element().key().equals("k1")));

        List<ElementRef> cao = idx.findByLink("n0001:characters:曹操");
        assertEquals(2, cao.size());
        assertTrue(cao.stream().anyMatch(r -> r.nodeId().equals("n0000")));
        assertTrue(cao.stream()
                .anyMatch(r -> r.nodeId().equals("n0001") && r.element().key().equals("k2")));

        assertEquals(1, idx.findByLink("characters:刘备").size());
        assertEquals("k1", idx.findByLink("characters:刘备").get(0).element().key());
    }

    @Test
    void buildWithEmptyChainProducesEmptyIndex() {
        LinkIndex idx = LinkIndex.build(List.of());
        assertTrue(idx.findByLink("anything").isEmpty());
    }

    @Test
    void elementWithoutLinksNotIndexed() {
        NodeSnapshot n0 = node("n0000", 0, Map.of("p", cp("p", el("k0", List.of()))));
        LinkIndex idx = LinkIndex.build(List.of(n0));
        assertTrue(idx.findByLink("n0001:characters:曹操").isEmpty());
    }

    // -- findByLink --

    @Test
    void findByLinkMissReturnsEmptyList() {
        LinkIndex idx = LinkIndex.build(List.of(node("n0000", 0, Map.of("p", cp("p", el("k0", List.of("a:b")))))));
        assertTrue(idx.findByLink("不存在").isEmpty());
        assertTrue(idx.findByLink("a:bb").isEmpty());
    }

    @Test
    void findByLinkReturnsImmutableCopy() {
        LinkIndex idx = LinkIndex.build(List.of(node("n0000", 0, Map.of("p", cp("p", el("k0", List.of("a:b")))))));
        List<ElementRef> copy = idx.findByLink("a:b");
        assertEquals(1, copy.size());
        assertThrows(UnsupportedOperationException.class, () -> copy.add(copy.get(0)));
    }

    // -- incremental maintenance --

    @Test
    void addElementIncremental() {
        LinkIndex idx = LinkIndex.build(List.of());
        ElementRef ref = ElementRef.from("n0000", 0, "t0", "p", el("k0", List.of("a:b", "c:d")));
        idx.addElement(ref);
        assertEquals(1, idx.findByLink("a:b").size());
        assertEquals(1, idx.findByLink("c:d").size());
        assertEquals("k0", idx.findByLink("a:b").get(0).element().key());
    }

    @Test
    void replaceElementRemovesOldKeysAndAddsNew() {
        LinkIndex idx = LinkIndex.build(List.of());
        ElementRef oldRef = ElementRef.from("n0000", 0, "t0", "p", el("k0", List.of("a:b")));
        idx.addElement(oldRef);
        // upsert-style replace: new ref shares the address triple but differs in payload
        ElementRef newRef = ElementRef.from("n0000", 0, "t0", "p", el("k0", List.of("c:d", "e:f")));
        idx.replaceElement(newRef, List.of("a:b"), List.of("c:d", "e:f"));
        assertTrue(idx.findByLink("a:b").isEmpty()); // stale ref removed by address, not by equality
        assertEquals(1, idx.findByLink("c:d").size());
        assertEquals(1, idx.findByLink("e:f").size());
        assertEquals("k0", idx.findByLink("c:d").get(0).element().key());
    }

    @Test
    void replaceElementPreservesOtherRefsOnSharedKey() {
        LinkIndex idx = LinkIndex.build(List.of());
        ElementRef ref1 = ElementRef.from("n0000", 0, "t0", "p", el("k1", List.of("a:b")));
        ElementRef ref2 = ElementRef.from("n0001", 1, "t1", "p", el("k2", List.of("a:b")));
        idx.addElement(ref1);
        idx.addElement(ref2);
        // new instance, same address as ref1, different payload — ref2 must survive
        ElementRef newRef1 = ElementRef.from("n0000", 0, "t0", "p", el("k1", List.of("c:d")));
        idx.replaceElement(newRef1, List.of("a:b"), List.of("c:d"));
        assertEquals(1, idx.findByLink("a:b").size());
        assertEquals("k2", idx.findByLink("a:b").get(0).element().key());
        assertEquals(1, idx.findByLink("c:d").size());
        assertEquals("k1", idx.findByLink("c:d").get(0).element().key());
    }

    @Test
    void removeElementDropsRefAndCleansEmptyKeys() {
        LinkIndex idx = LinkIndex.build(List.of());
        ElementRef ref1 = ElementRef.from("n0000", 0, "t0", "p", el("k1", List.of("a:b", "c:d")));
        ElementRef ref2 = ElementRef.from("n0001", 1, "t1", "p", el("k2", List.of("a:b")));
        idx.addElement(ref1);
        idx.addElement(ref2);
        idx.removeElement(ref1);
        // a:b still referenced by ref2; c:d's list is now empty → key dropped
        assertEquals(1, idx.findByLink("a:b").size());
        assertEquals("k2", idx.findByLink("a:b").get(0).element().key());
        assertTrue(idx.findByLink("c:d").isEmpty());
    }

    @Test
    void removeElementOnUnindexedRefIsNoop() {
        LinkIndex idx = LinkIndex.build(List.of());
        ElementRef ref = ElementRef.from("n0000", 0, "t0", "p", el("k1", List.of("a:b")));
        idx.removeElement(ref); // never added — must not throw
        assertTrue(idx.findByLink("a:b").isEmpty());
    }

    // -- WorldInformation integration --

    private static WorldInformation wiWithRoot() {
        NodeSnapshot n0 = node("n0000", 0, Map.of("worldview", cp("worldview")));
        return new WorldInformation("test-world", List.of(n0));
    }

    @Test
    void constructorBuildsLinkIndexFromChain() {
        NodeSnapshot n0 = node("n0000", 0, Map.of("p", cp("p", el("k0", List.of("a:b")))));
        WorldInformation wi = new WorldInformation("test-world", List.of(n0));
        assertEquals(1, wi.linkIndex().findByLink("a:b").size());
        assertEquals("n0000", wi.linkIndex().findByLink("a:b").get(0).nodeId());
    }

    @Test
    void appendElementMaintainsLinkIndex() {
        WorldInformation wi = wiWithRoot();
        wi.appendElement("n0000", "worldview", el("k1", List.of("gsimap:region:迷雾森林")));
        List<ElementRef> hits = wi.linkIndex().findByLink("gsimap:region:迷雾森林");
        assertEquals(1, hits.size());
        assertEquals("k1", hits.get(0).element().key());
        assertEquals("worldview", hits.get(0).checkpointId());
    }

    @Test
    void appendElementWithoutLinksNotIndexed() {
        WorldInformation wi = wiWithRoot();
        wi.appendElement("n0000", "worldview", el("k1", List.of()));
        assertTrue(wi.linkIndex().findByLink("gsimap:region:迷雾森林").isEmpty());
    }

    @Test
    void upsertElementReplaceSwapsLinksInIndex() {
        WorldInformation wi = wiWithRoot();
        wi.upsertElement("n0000", "worldview", el("k1", List.of("n0001:characters:曹操")));
        assertEquals(1, wi.linkIndex().findByLink("n0001:characters:曹操").size());

        // 同 key 二次写入（replace）→ 旧链接移除，新链接加入
        wi.upsertElement("n0000", "worldview", el("k1", List.of("gsimap:region:迷雾森林")));
        assertTrue(wi.linkIndex().findByLink("n0001:characters:曹操").isEmpty());
        assertEquals(1, wi.linkIndex().findByLink("gsimap:region:迷雾森林").size());
        assertEquals(1, wi.checkpointHistory("worldview").size()); // 仍是 1 个元素
    }

    @Test
    void upsertElementAppendPathMaintainsLinkIndex() {
        WorldInformation wi = wiWithRoot();
        wi.upsertElement("n0000", "worldview", el("k1", List.of("a:b")));
        assertEquals(1, wi.linkIndex().findByLink("a:b").size());
        assertEquals("k1", wi.linkIndex().findByLink("a:b").get(0).element().key());
    }

    @Test
    void ensureNodeIndexesLoadedNodeLinks() {
        NodeSnapshot n0 = node("n0000", 0, Map.of("worldview", cp("worldview")));
        WorldInformation wi = new WorldInformation("test-world", List.of(n0));
        assertTrue(wi.linkIndex().findByLink("n0001:characters:曹操").isEmpty());

        NodeSnapshot n1 = node("n0001", 1, Map.of("p", cp("p", el("k9", List.of("n0001:characters:曹操")))));
        wi.ensureNode(n1);
        assertEquals(1, wi.linkIndex().findByLink("n0001:characters:曹操").size());
        assertEquals(
                "n0001", wi.linkIndex().findByLink("n0001:characters:曹操").get(0).nodeId());
    }

    @Test
    void ensureNodeDuplicateIsNoop() {
        NodeSnapshot n0 = node("n0000", 0, Map.of("p", cp("p", el("k0", List.of("a:b")))));
        WorldInformation wi = new WorldInformation("test-world", List.of(n0));
        wi.ensureNode(n0); // already present — no-op
        assertEquals(1, wi.linkIndex().findByLink("a:b").size());
    }
}
