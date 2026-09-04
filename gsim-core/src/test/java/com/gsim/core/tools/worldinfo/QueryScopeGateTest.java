package com.gsim.core.tools.worldinfo;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.QueryScope;
import com.gsim.agentsmanager.QueryScopeContext;
import com.gsim.agentsmanager.config.CoreConfig;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolRegistry;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.docslib.doc.DocStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryScopeGateTest {

    @TempDir
    Path tmpDir;

    private WorldInformation wi;
    private DocStore docStore;
    private CoreConfig coreConfig;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws java.io.IOException {
        coreConfig = CoreConfig.load();
        docStore = new DocStore(tmpDir.resolve("docs"));
        docStore.init();
        registry = new ToolRegistry();
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
                                List.of(
                                        new Element(
                                                "曹操.行动.起兵", "action", "曹操自陈留起兵", List.of("军事"), List.of(), null, null),
                                        new Element(
                                                "曹操.内政", "action", "曹操推行屯田", List.of("内政"), List.of(), null, null))),
                        "player.袁绍",
                        new Checkpoint(
                                "袁绍",
                                "player",
                                List.of(new Element(
                                        "袁绍.行动.观望", "action", "袁绍观望不前", List.of("外交"), List.of(), null, null)))),
                new LinkedHashMap<>());
        wi = new WorldInformation("test", List.of(n1));
    }

    @AfterEach
    void tearDown() {
        QueryScopeContext.clear();
    }

    @Test
    void elementQueryDeniesOutsideTagScope() {
        QueryScopeContext.set(new QueryScope("and", List.of("军事"), List.of()));
        var tool = new QueryElementTool(() -> wi, registry, docStore, coreConfig);
        ToolResult denied = tool.execute(new ToolCall("query_element", Map.of("ref", "n0001:player.曹操:曹操.内政")));
        assertFalse(denied.success());
        assertTrue(denied.error().contains("查询权限"), "error: " + denied.error());
        ToolResult allowed = tool.execute(new ToolCall("query_element", Map.of("ref", "n0001:player.曹操:曹操.行动.起兵")));
        assertTrue(allowed.success());
    }

    @Test
    void elementQueryDeniesOutsideAddressScope() {
        QueryScopeContext.set(new QueryScope("and", List.of(), List.of("n0001:player.曹操:曹操.行动.起兵")));
        var tool = new QueryElementTool(() -> wi, registry, docStore, coreConfig);
        assertTrue(tool.execute(new ToolCall("query_element", Map.of("ref", "n0001:player.曹操:曹操.行动.起兵")))
                .success());
        assertFalse(tool.execute(new ToolCall("query_element", Map.of("ref", "n0001:player.曹操:曹操.内政")))
                .success());
    }

    @Test
    void checkpointQueryFiltersDisallowedElements() {
        QueryScopeContext.set(new QueryScope("and", List.of("军事"), List.of()));
        var tool = new QueryCheckpointTool(() -> wi, docStore, coreConfig);
        ToolResult r = tool.execute(new ToolCall("query_checkpoint", Map.of("checkpointId", "player.曹操")));
        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertEquals("n0001:player.曹操:曹操.行动.起兵", r.items().get(0).path());
    }

    @Test
    void byTagQueryFiltersDisallowedElements() {
        QueryScopeContext.set(new QueryScope("and", List.of("内政"), List.of()));
        var tool = new QueryByTagTool(() -> wi, docStore, coreConfig);
        ToolResult r = tool.execute(new ToolCall("query_by_tag", Map.of("tag", "军事")));
        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).snippet().contains("(no results)")
                || r.items().get(0).snippet().contains("total: 0"));
    }

    @Test
    void internalElementsAreExcludedEvenWithoutScopeConfig() {
        // scope 为 null（queryScopeContext 未设置 = 未启用）时，internal 元素仍必须被过滤。
        // internal 是硬规则，不依赖 scope 配置。
        NodeSnapshot n = new NodeSnapshot(
                "n0001",
                "n0000",
                1,
                "t1",
                "simulated",
                "t1",
                Map.of(
                        "characters",
                        new Checkpoint(
                                "characters",
                                "character",
                                List.of(
                                        new Element(
                                                "密探", "text", "我方密探与敌国联络", List.of("internal"), List.of(), null, null),
                                        new Element("将军", "text", "我方将军", List.of("军事"), List.of(), null, null)))),
                new LinkedHashMap<>());
        WorldInformation wiInternal = new WorldInformation("test", List.of(n));

        QueryScopeContext.clear();
        var tool = new QueryByTagTool(() -> wiInternal, docStore, coreConfig);
        ToolResult r = tool.execute(new ToolCall("query_by_tag", Map.of("tag", "internal")));
        assertTrue(r.success());
        // 即使命中 internal tag，元素也应被硬规则过滤掉 → 0 结果
        assertTrue(r.items().get(0).snippet().contains("(no results)")
                || r.items().get(0).snippet().contains("total: 0"));
    }

    @Test
    void queryElementDeniesInternalWithoutScopeConfig() {
        NodeSnapshot n = new NodeSnapshot(
                "n0001",
                "n0000",
                1,
                "t1",
                "simulated",
                "t1",
                Map.of(
                        "characters",
                        new Checkpoint(
                                "characters",
                                "character",
                                List.of(new Element(
                                        "密探", "text", "我方密探与敌国联络", List.of("internal"), List.of(), null, null)))),
                new LinkedHashMap<>());
        WorldInformation wiInternal = new WorldInformation("test", List.of(n));

        QueryScopeContext.clear();
        var tool = new QueryElementTool(() -> wiInternal, registry, docStore, coreConfig);
        ToolResult denied = tool.execute(new ToolCall("query_element", Map.of("ref", "n0001:characters:密探")));
        assertFalse(denied.success());
        assertTrue(denied.error().contains("internal"), "error: " + denied.error());
    }
}
