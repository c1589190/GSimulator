package com.gsim.agent.tools.worldinfo;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import com.gsim.docslib.importing.ImportDocumentService;
import com.gsim.agentsmanager.ref.InlineRefResolver;
import com.gsim.core.worldinfo.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteElementToolTest {

    @TempDir
    Path tmpDir;

    private WorldInformation wi;
    private DocStore docStore;
    private InlineRefResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        NodeSnapshot n0 = new NodeSnapshot(
                "n0000",
                null,
                0,
                "origin",
                "initial",
                "t0",
                new LinkedHashMap<>(Map.of("worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()))),
                new LinkedHashMap<>());
        wi = new WorldInformation("test-world", List.of(n0));

        Path docsDir = tmpDir.resolve("docs");
        docStore = new DocStore(docsDir);
        docStore.init();
        Path importDir = tmpDir.resolve("import");
        Files.createDirectories(importDir);
        resolver = new InlineRefResolver(docStore, new ImportDocumentService(importDir));
    }

    private WriteElementTool tool() {
        return new WriteElementTool(() -> wi, tmpDir, null, resolver);
    }

    @Test
    void writeElementAppendsToCheckpoint() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱蝗灾四起",
                        "tags", "气候,灾害")));

        assertTrue(r.success());
        assertTrue(r.items().get(0).path().contains("n0000:worldview:气候.中原"));

        // verify in memory
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals("气候.中原", history.get(0).element().key());
        assertEquals("中原大旱蝗灾四起", history.get(0).element().value());
        assertTrue(history.get(0).element().tags().contains("气候"));
    }

    @Test
    void writeElementWithReplaceModeUpsertsExistingKey() {
        var tool = tool();

        // first write
        tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:气候.中原", "value", "中原大旱")));

        // second write with same ref — default mode=replace should upsert
        ToolResult r2 = tool.execute(
                new ToolCall("write_element", Map.of("ref", "n0000:worldview:气候.中原", "value", "中原大旱蝗灾四起民不聊生")));

        assertTrue(r2.success());
        assertTrue(r2.items().get(0).path().contains("replaced"));

        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size()); // still 1 element, not 2
        assertEquals("中原大旱蝗灾四起民不聊生", history.get(0).element().value());
    }

    @Test
    void writeElementWithAppendModeAlwaysAdds() {
        var tool = tool();

        // first write
        tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:气候.中原", "value", "中原大旱")));

        // second write with mode=append
        ToolResult r2 = tool.execute(new ToolCall(
                "write_element", Map.of("ref", "n0000:worldview:气候.中原", "value", "中原大雨", "mode", "append")));

        assertTrue(r2.success());
        assertTrue(r2.items().get(0).path().contains("appended"));

        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(2, history.size()); // 2 elements with same key
    }

    @Test
    void writeElementShortRefWithExplicitNodeId() {
        var tool = tool();
        // Use short ref with explicit nodeId
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "worldview:默认世界",
                        "nodeId", "n0000",
                        "value", "架空奇幻大陆")));

        assertTrue(r.success());
        assertTrue(r.items().get(0).path().contains("n0000:worldview:默认世界"));
    }

    @Test
    void longValueWritesDirectlyWithoutStaging() {
        String big = "灾".repeat(5000);
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:大事件", "value", big)));

        assertTrue(r.success());
        assertFalse(r.items().get(0).snippet().contains("暂存"));
        assertFalse(r.items().get(0).path().startsWith("wstg_"));

        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals(big, history.get(0).element().value());
        assertFalse(Files.exists(tmpDir.resolve("docs").resolve("tmp")));
    }

    @Test
    void longValueWritesDirectly() {
        String value = "中".repeat(5000);
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:长文", "value", value)));

        assertTrue(r.success());
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals(value, history.get(0).element().value());
    }

    @Test
    void inlineDocRefIsResolvedToSnapshotBeforeWrite() throws IOException {
        docStore.create("设定集", DocType.OTHER, "设定集", "唐朝背景架空设定", List.of("设定"));
        var tool = tool();
        ToolResult r = tool.execute(
                new ToolCall("write_element", Map.of("ref", "n0000:worldview:设定", "value", "@doc:\"设定集\"")));

        assertTrue(r.success());
        assertEquals(
                "唐朝背景架空设定", wi.checkpointHistory("worldview").get(0).element().value());
    }

    @Test
    void unresolvedDocRefFailsWithoutWriting() {
        var tool = tool();
        ToolResult r = tool.execute(
                new ToolCall("write_element", Map.of("ref", "n0000:worldview:设定", "value", "@doc:\"不存在的\"")));

        assertFalse(r.success());
        assertTrue(r.error().contains("[@DOC_REF_FAILED]"));
        assertTrue(wi.checkpointHistory("worldview").isEmpty());
    }

    @Test
    void docAndImportRefsResolveTogether() throws IOException {
        docStore.create("设定集", DocType.OTHER, "设定集", "唐朝设定", List.of());
        Files.writeString(tmpDir.resolve("import").resolve("附件.txt"), "山河辽阔", StandardCharsets.UTF_8);
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of("ref", "n0000:worldview:设定", "value", "前文 @doc:\"设定集\" 中段 @import:\"附件.txt\" 后文")));

        assertTrue(r.success());
        assertEquals(
                "前文 唐朝设定 中段 山河辽阔 后文",
                wi.checkpointHistory("worldview").get(0).element().value());
    }

    @Test
    void unquotedDocRefWrittenAsIs() {
        var tool = tool();
        ToolResult r = tool.execute(
                new ToolCall("write_element", Map.of("ref", "n0000:worldview:设定", "value", "引用 @doc:设定集 未加引号")));

        assertTrue(r.success());
        assertEquals(
                "引用 @doc:设定集 未加引号",
                wi.checkpointHistory("worldview").get(0).element().value());
    }
}
