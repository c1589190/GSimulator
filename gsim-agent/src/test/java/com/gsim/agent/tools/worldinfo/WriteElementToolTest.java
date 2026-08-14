package com.gsim.agent.tools.worldinfo;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.config.CoreConfig;
import com.gsim.core.doc.DocStore;
import com.gsim.core.doc.DocType;
import com.gsim.core.doc.Document;
import com.gsim.core.importing.ImportDocumentService;
import com.gsim.core.ref.InlineRefResolver;
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
        return new WriteElementTool(() -> wi, tmpDir, null, docStore, resolver, CoreConfig.load());
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
    void oversizedValueIsStagedToDocInsteadOfWriting() {
        String big = "灾".repeat(501);
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:大事件", "value", big)));

        assertTrue(r.success());
        String message = r.items().get(0).snippet();
        assertTrue(message.contains("wstg_write_"));
        assertTrue(message.contains("@doc:\""));

        // 未写入 world
        assertTrue(wi.checkpointHistory("worldview").isEmpty());

        // docs/tmp/ 下存在 wstg_*.md，DocStore 中 get 该 docId 内容 = value
        String docId = r.items().get(0).path();
        assertTrue(docId.startsWith("wstg_write_"));
        assertTrue(Files.exists(tmpDir.resolve("docs").resolve("tmp").resolve(docId + ".md")));
        Document doc = docStore.get(docId);
        assertNotNull(doc);
        assertEquals(DocType.TMP, doc.type());
        assertEquals("n0000:worldview:大事件", doc.title());
        assertEquals(big, doc.content());
    }

    @Test
    void valueWithinThresholdWritesDirectly() {
        String value = "中".repeat(500);
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:长文", "value", value)));

        assertTrue(r.success());
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals(value, history.get(0).element().value());
    }

    @Test
    void thresholdBoundaryExact500Writes501Stages() {
        var tool = tool();

        // 恰 500 字符 → 直接写入
        String atLimit = "字".repeat(500);
        ToolResult r1 =
                tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:边界一", "value", atLimit)));
        assertTrue(r1.success());
        assertEquals(1, wi.checkpointHistory("worldview").size());

        // 501 字符 → 暂存
        String over = "字".repeat(501);
        ToolResult r2 =
                tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:边界二", "value", over)));
        assertTrue(r2.success());
        assertTrue(r2.items().get(0).path().startsWith("wstg_write_"));
        assertEquals(1, wi.checkpointHistory("worldview").size()); // 未写入
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
    void stagedDocCanBeCommittedInSecondCall() {
        String big = "灾".repeat(501);
        var tool = tool();

        // 第一次调用：超阈值 → 暂存
        ToolResult r1 = tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:大事件", "value", big)));
        assertTrue(r1.success());
        String docId = r1.items().get(0).path();
        assertTrue(docId.startsWith("wstg_write_"));
        assertTrue(wi.checkpointHistory("worldview").isEmpty());

        // 第二次调用：@doc: 引用暂存文档 → 正常写入全文
        ToolResult r2 = tool.execute(
                new ToolCall("write_element", Map.of("ref", "n0000:worldview:大事件", "value", "@doc:\"" + docId + "\"")));
        assertTrue(r2.success());
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals(big, history.get(0).element().value());
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
