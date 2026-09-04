package com.gsim.agent.tools.worldinfo;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.core.importing.ImportDocumentService;
import com.gsim.core.ref.InlineRefResolver;
import com.gsim.core.worldinfo.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WriteElementTool links 参数校验测试。
 *
 * <p>合法 key 形如 {@code ^(@world:|@doc:|@cache:|@import:|gsimap:|[a-z]+\d{4}:)[^\s]+$}
 * 或 {@code ^[^:\s]+:[^:\s]+$}（cpId:key）；任一条非法 → 整个调用失败且元素不写入；
 * links 参数缺失时不校验。
 */
class WriteElementToolLinksTest {

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

    // -- happy path --

    @Test
    void validLinksAreWrittenAndIndexed() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱蝗灾四起",
                        "links", "gsimap:region:迷雾森林,n0001:characters:曹操")));

        assertTrue(r.success());
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals(
                List.of("gsimap:region:迷雾森林", "n0001:characters:曹操"),
                history.get(0).element().links());

        // LinkIndex 反向命中
        assertEquals(1, wi.linkIndex().findByLink("gsimap:region:迷雾森林").size());
        assertEquals(1, wi.linkIndex().findByLink("n0001:characters:曹操").size());
        assertEquals(
                "气候.中原",
                wi.linkIndex().findByLink("gsimap:region:迷雾森林").get(0).element().key());
    }

    @Test
    void linksWithPrefixVariantsAreAccepted() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:设定",
                        "value", "设定内容",
                        "links", "@world:n0001:characters:曹操,@doc:char_guanyu,@cache:text_edit_1,@import:wiki_doc")));

        assertTrue(r.success());
        assertEquals(
                List.of("@world:n0001:characters:曹操", "@doc:char_guanyu", "@cache:text_edit_1", "@import:wiki_doc"),
                wi.checkpointHistory("worldview").get(0).element().links());
    }

    @Test
    void linksWithStraySpacesAreTrimmedAndAccepted() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", " gsimap:region:迷雾森林 , characters:曹操 ")));

        assertTrue(r.success());
        assertEquals(
                List.of("gsimap:region:迷雾森林", "characters:曹操"),
                wi.checkpointHistory("worldview").get(0).element().links());
        assertEquals(1, wi.linkIndex().findByLink("gsimap:region:迷雾森林").size());
        assertEquals(1, wi.linkIndex().findByLink("characters:曹操").size());
    }

    // -- failure path: any invalid entry fails the whole call, nothing written --

    @Test
    void invalidLinkWithWhitespaceFailsWholeCall() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", "not a key")));

        assertFalse(r.success());
        assertTrue(r.error().contains("not a key"));
        assertTrue(wi.checkpointHistory("worldview").isEmpty()); // 元素未写入
    }

    @Test
    void bareWordLinkFails() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", "裸词")));

        assertFalse(r.success());
        assertTrue(r.error().contains("裸词"));
        assertTrue(wi.checkpointHistory("worldview").isEmpty());
    }

    @Test
    void mixedLinksAnyInvalidFailsEverything() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", "n0001:characters:曹操, not a key")));

        assertFalse(r.success());
        assertTrue(r.error().contains("not a key"));
        assertTrue(wi.checkpointHistory("worldview").isEmpty());
    }

    @Test
    void emptyEntryAfterSplitFails() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", "n0001:characters:曹操, ")));

        assertFalse(r.success());
        assertTrue(wi.checkpointHistory("worldview").isEmpty());
    }

    @Test
    void invalidLinkFailsInAppendModeToo() {
        var tool = tool();
        ToolResult r = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", "bad key",
                        "mode", "append")));

        assertFalse(r.success());
        assertTrue(r.error().contains("bad key"));
        assertTrue(wi.checkpointHistory("worldview").isEmpty());
    }

    // -- links absent → no validation --

    @Test
    void linksParamAbsentSkipsValidation() {
        var tool = tool();
        ToolResult r =
                tool.execute(new ToolCall("write_element", Map.of("ref", "n0000:worldview:气候.中原", "value", "中原大旱")));

        assertTrue(r.success());
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertTrue(wi.checkpointHistory("worldview").get(0).element().links().isEmpty());
    }

    @Test
    void blankLinksParamSkipsValidation() {
        var tool = tool();
        ToolResult r = tool.execute(
                new ToolCall("write_element", Map.of("ref", "n0000:worldview:气候.中原", "value", "中原大旱", "links", "")));

        assertTrue(r.success());
        assertEquals(1, wi.checkpointHistory("worldview").size());
        assertTrue(wi.checkpointHistory("worldview").get(0).element().links().isEmpty());
    }

    // -- replace mode swaps links in the reverse index --

    @Test
    void replaceModeSwapsLinksInLinkIndex() {
        var tool = tool();
        ToolResult r1 = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱",
                        "links", "n0001:characters:曹操")));
        assertTrue(r1.success());
        assertEquals(1, wi.linkIndex().findByLink("n0001:characters:曹操").size());

        ToolResult r2 = tool.execute(new ToolCall(
                "write_element",
                Map.of(
                        "ref", "n0000:worldview:气候.中原",
                        "value", "中原大旱蝗灾四起",
                        "links", "gsimap:region:迷雾森林")));
        assertTrue(r2.success());
        assertTrue(r2.items().get(0).path().contains("replaced"));

        assertTrue(wi.linkIndex().findByLink("n0001:characters:曹操").isEmpty());
        assertEquals(1, wi.linkIndex().findByLink("gsimap:region:迷雾森林").size());
        assertEquals(1, wi.checkpointHistory("worldview").size());
    }
}
