package com.gsim.data;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataManager")
class DataManagerTest {
    @TempDir Path tempDir;
    private DataManager dm;

    @BeforeEach void setUp() { dm = new DataManager(tempDir); }

    @Test @DisplayName("auto init creates structure")
    void testAutoInit() {
        assertEquals("default", dm.getActiveWorld());
        assertEquals("branch.b0000-start", dm.getActiveBranch());
        assertTrue(dm.docCount() >= 5);
    }

    @Test @DisplayName("read base files")
    void testReadBaseFiles() {
        assertNotNull(dm.readById("world.base"));
        assertNotNull(dm.readById("entities.base"));
        assertNotNull(dm.readById("rules.base"));
        assertNotNull(dm.readById("input.current"));
        assertNotNull(dm.readById("branch.b0000-start"));
    }

    @Test @DisplayName("world create and switch")
    void testWorldCreateSwitch() throws Exception {
        dm.createWorld("testworld");
        dm.switchWorld("testworld");
        assertEquals("testworld", dm.getActiveWorld());
        assertEquals("branch.b0000-start", dm.getActiveBranch());
    }

    @Test @DisplayName("branch create appends to timeline")
    void testBranchCreate() throws Exception {
        dm.createBranch("branch.b0001-contact", "首次接触", "泰拉历1098年春");
        assertEquals("branch.b0001-contact", dm.getActiveBranch());
        assertNotNull(dm.readById("branch.b0001-contact"));
        assertTrue(dm.listBranches().size() >= 2);
    }

    @Test @DisplayName("branch chain follows parent")
    void testBranchChain() throws Exception {
        dm.createBranch("branch.b0001-a", "A", "T1");
        dm.createBranch("branch.b0002-b", "B", "T2");
        var chain = dm.getBranchChain("branch.b0002-b");
        assertEquals(3, chain.size()); // b0002 -> b0001 -> b0000
        assertEquals("branch.b0001-a", chain.get(1).id());
    }

    @Test @DisplayName("timeline tree")
    void testTimelineTree() throws Exception {
        dm.createBranch("branch.b0001-a", "A", "T1");
        var tree = dm.getTimelineTree();
        assertFalse(tree.isEmpty());
        assertEquals("branch.b0000-start", tree.get(0).id());
        assertEquals(1, tree.get(0).children().size());
        assertEquals("branch.b0001-a", tree.get(0).children().get(0).id());
    }

    @Test @DisplayName("append and clear input")
    void testInput() throws Exception {
        dm.appendInput("测试输入");
        dm.reload();
        DataDocument input = dm.readById("input.current");
        assertTrue(input.body().contains("测试输入"));
        dm.clearInput();
        dm.reload();
        input = dm.readById("input.current");
        assertFalse(input.body().contains("测试输入"));
    }

    @Test @DisplayName("effective context includes base + chain")
    void testEffectiveContext() throws Exception {
        dm.appendInput("玩家A行动：接触罗德岛");
        dm.createBranch("branch.b0001-test", "测试节点", "T1");
        String ctx = dm.getEffectiveContext();
        assertTrue(ctx.contains("世界观"));      // base world
        assertTrue(ctx.contains("实体资料"));   // base entities
        assertTrue(ctx.contains("测试节点"));    // branch name in current node info
        assertTrue(ctx.contains("branch.b0001-test")); // branch ID in context
    }

    @Test @DisplayName("search")
    void testSearch() {
        var r = dm.search("世界观", 5);
        assertFalse(r.isEmpty());
        assertTrue(r.get(0).id().contains("world"));
    }

    @Test @DisplayName("persistence across instances")
    void testPersistence() throws Exception {
        dm.createBranch("branch.b0001-test", "T", "T1");
        DataManager dm2 = new DataManager(tempDir);
        assertEquals("branch.b0001-test", dm2.getActiveBranch());
        assertNotNull(dm2.readById("branch.b0001-test"));
    }
}
