package com.gsim.agent.tools.worldinfo;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.config.CoreConfig;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import com.gsim.docslib.doc.Document;
import com.gsim.core.worldinfo.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * query 侧大文本暂存机制测试：query_* 工具返回的元素超过
 * core.doc.query.staging.threshold（默认 3000）时暂存为 TMP 文档并返回提示。
 */
class QueryElementToolStagingTest {

    @TempDir
    Path tmpDir;

    private WorldInformation wi;
    private DocStore docStore;
    private CoreConfig coreConfig;

    @BeforeEach
    void setUp() throws IOException {
        coreConfig = CoreConfig.load();
        docStore = new DocStore(tmpDir.resolve("docs"));
        docStore.init();

        List<Element> elements = new ArrayList<>();
        elements.add(new Element("长文", "text", "灾".repeat(3001), List.of(), List.of(), null, null));
        elements.add(new Element("短文", "text", "中原大旱", List.of(), List.of(), null, null));

        NodeSnapshot n0 = new NodeSnapshot(
                "n0000",
                null,
                0,
                "origin",
                "initial",
                "t0",
                new LinkedHashMap<>(Map.of("worldview", new Checkpoint("世界观", "worldview", elements))),
                new LinkedHashMap<>());
        wi = new WorldInformation("test-world", List.of(n0));
    }

    private QueryElementTool queryElementTool() {
        return new QueryElementTool(() -> wi, null, docStore, coreConfig);
    }

    private static String extractDocId(String snippet) {
        Matcher m = Pattern.compile("docId=(wstg_query_[^，,\\s]+)").matcher(snippet);
        assertTrue(m.find(), "snippet should contain docId, was: " + snippet);
        return m.group(1);
    }

    @Test
    void oversizedElementIsStagedToDocInsteadOfInlined() {
        ToolResult r = queryElementTool().execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:长文")));

        assertTrue(r.success());
        ToolResult.Item item = r.items().get(0);
        assertEquals("长文", item.title());
        assertEquals("n0000:worldview:长文", item.path()); // path 保持元素 ref

        String docId = extractDocId(item.snippet());
        Document doc = docStore.get(docId);
        assertNotNull(doc);
        assertEquals(DocType.TMP, doc.type());
        assertEquals("n0000:worldview:长文", doc.title());
        assertEquals("灾".repeat(3001), doc.content());
    }

    @Test
    void elementAtThreshold3000IsInlined() {
        wi.upsertElement(
                "n0000", "worldview", new Element("边界", "text", "中".repeat(3000), List.of(), List.of(), null, null));
        ToolResult r = queryElementTool().execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:边界")));

        assertTrue(r.success());
        assertEquals("中".repeat(3000), r.items().get(0).snippet());
    }

    @Test
    void shortElementInlinedNormally() {
        ToolResult r = queryElementTool().execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:短文")));

        assertTrue(r.success());
        assertEquals("中原大旱", r.items().get(0).snippet());
    }

    @Test
    void queryCheckpointDetailMixedStagesOnlyOversized() {
        var tool = new QueryCheckpointTool(() -> wi, docStore, coreConfig);
        ToolResult r =
                tool.execute(new ToolCall("query_checkpoint", Map.of("checkpointId", "worldview", "detail", "true")));

        assertTrue(r.success());
        assertEquals(2, r.items().size());

        ToolResult.Item longItem = r.items().get(0);
        assertTrue(longItem.snippet().contains("wstg_query_"));
        assertTrue(longItem.path().contains("长文"));

        ToolResult.Item shortItem = r.items().get(1);
        assertEquals("中原大旱", shortItem.snippet());
    }

    @Test
    void duplicateContentReusesSameDocId() {
        var tool = queryElementTool();
        ToolResult r1 = tool.execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:长文")));
        ToolResult r2 = tool.execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:长文")));

        String docId1 = extractDocId(r1.items().get(0).snippet());
        String docId2 = extractDocId(r2.items().get(0).snippet());
        assertEquals(docId1, docId2);

        assertEquals(1, docStore.list(DocType.TMP, null).size());
    }

    @Test
    void differentContentCreatesSeparateDocs() {
        wi.upsertElement(
                "n0000", "worldview", new Element("长文二", "text", "灾".repeat(3100), List.of(), List.of(), null, null));

        var tool = queryElementTool();
        ToolResult r1 = tool.execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:长文")));
        ToolResult r2 = tool.execute(new ToolCall("query_element", Map.of("ref", "n0000:worldview:长文二")));

        String docId1 = extractDocId(r1.items().get(0).snippet());
        String docId2 = extractDocId(r2.items().get(0).snippet());
        assertNotEquals(docId1, docId2);

        assertEquals(2, docStore.list(DocType.TMP, null).size());
    }
}
