package com.gsim.core.ref;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.doc.DocStore;
import com.gsim.core.importing.ImportDocumentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("InlineRefResolver — @doc:/@import: 内嵌引用解析")
class InlineRefResolverTest {

    @TempDir
    Path tempDir;

    private InlineRefResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        Path docsDir = tempDir.resolve("docsDir");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("a.md"), "文档A正文");
        Files.writeString(docsDir.resolve("empty.md"), "");
        Files.createDirectories(docsDir.resolve("第一层").resolve("第二层"));
        Files.writeString(docsDir.resolve("第一层").resolve("第二层").resolve("文件名.md"), "嵌套文档正文");
        DocStore docStore = new DocStore(docsDir);
        docStore.init();

        Path importDir = tempDir.resolve("importDir");
        Files.createDirectories(importDir.resolve("path").resolve("to"));
        Files.writeString(importDir.resolve("b.md"), "导入B正文");
        Files.writeString(importDir.resolve("path").resolve("to").resolve("file.md"), "嵌套导入正文");
        ImportDocumentService importService = new ImportDocumentService(importDir);

        resolver = new InlineRefResolver(docStore, importService);
    }

    @Test
    @DisplayName("@doc:\"a\" 展开为文档全文")
    void docExpandsToFullContent() {
        var result = resolver.resolve("见 @doc:\"a\" 文档");

        assertEquals("见 文档A正文 文档", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("@doc:\"a.md\" 与 @doc:\"a.MD\" 等价于 @doc:\"a\"（剥 .md，含大写）")
    void docMdSuffixStripped() {
        assertEquals("文档A正文", resolver.resolve("@doc:\"a\"").text());
        assertEquals("文档A正文", resolver.resolve("@doc:\"a.md\"").text());
        assertEquals("文档A正文", resolver.resolve("@doc:\"a.MD\"").text());
        assertTrue(resolver.resolve("@doc:\"a.MD\"").unresolved().isEmpty());
    }

    @Test
    @DisplayName("@doc:\"第一层/第二层/文件名\" 按嵌套路径寻址")
    void docNestedPath() {
        var result = resolver.resolve("引用 @doc:\"第一层/第二层/文件名\" 结束");

        assertEquals("引用 嵌套文档正文 结束", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("@import:\"path/to/file.md\" 按完整相对路径展开")
    void importFullRelativePath() {
        var result = resolver.resolve("引用 @import:\"path/to/file.md\" 结束");

        assertEquals("引用 嵌套导入正文 结束", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("多引用混合文本原位替换，顺序正确")
    void mixedRefsReplacedInPlace() {
        var result = resolver.resolve("前文 @doc:\"a\" 中段 @import:\"b.md\" 后文");

        assertEquals("前文 文档A正文 中段 导入B正文 后文", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("引用的文档不存在 → unresolved 含引用原文，text 原样返回（不部分替换）")
    void missingDocUnresolvedKeepsOriginalText() {
        var result = resolver.resolve("前文 @doc:\"nope\" 后文");

        assertEquals("前文 @doc:\"nope\" 后文", result.text());
        assertEquals(List.of("@doc:\"nope\""), result.unresolved());
    }

    @Test
    @DisplayName("@import:\"../secret.md\" 路径逃逸 → unresolved（ImportDocumentException 归一）")
    void importTraversalUnresolved() {
        var result = resolver.resolve("前文 @import:\"../secret.md\" 后文");

        assertEquals("前文 @import:\"../secret.md\" 后文", result.text());
        assertEquals(List.of("@import:\"../secret.md\""), result.unresolved());
    }

    @Test
    @DisplayName("@doc:xxx 无引号形态 → 原样保留，unresolved 为空（不误伤）")
    void noQuotesKeptAsIs() {
        var result = resolver.resolve("正文 @doc:xxx 结尾");

        assertEquals("正文 @doc:xxx 结尾", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("@import:\"noext\" 无后缀 → unresolved；@doc:\"a.txt\" 查 store 为 null → unresolved")
    void unsupportedFormsUnresolved() {
        var importResult = resolver.resolve("@import:\"noext\"");
        assertEquals("@import:\"noext\"", importResult.text());
        assertEquals(List.of("@import:\"noext\""), importResult.unresolved());

        var docResult = resolver.resolve("@doc:\"a.txt\"");
        assertEquals("@doc:\"a.txt\"", docResult.text());
        assertEquals(List.of("@doc:\"a.txt\""), docResult.unresolved());
    }

    @Test
    @DisplayName("空文本 / 纯空白 / null → 原样返回，unresolved 空")
    void blankAndNullInputs() {
        var empty = resolver.resolve("");
        assertEquals("", empty.text());
        assertTrue(empty.unresolved().isEmpty());

        var blank = resolver.resolve("   ");
        assertEquals("   ", blank.text());
        assertTrue(blank.unresolved().isEmpty());

        var nil = resolver.resolve(null);
        assertNull(nil.text());
        assertTrue(nil.unresolved().isEmpty());
    }

    @Test
    @DisplayName("@doc:\".md\" 剥后缀后空 docId → unresolved")
    void docSuffixOnlyUnresolved() {
        var result = resolver.resolve("@doc:\".md\"");

        assertEquals("@doc:\".md\"", result.text());
        assertEquals(List.of("@doc:\".md\""), result.unresolved());
    }

    @Test
    @DisplayName("空内容文档展开为空串（合法展开，区别于未解析）")
    void emptyContentDocExpandsToEmptyString() {
        var result = resolver.resolve("前 @doc:\"empty\" 后");

        assertEquals("前  后", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("引号未闭合（无结束引号）→ 从 @ 起原样保留到末尾，unresolved 空")
    void unclosedQuoteKeptAsIs() {
        var result = resolver.resolve("前文 @doc:\"abc");

        assertEquals("前文 @doc:\"abc", result.text());
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    @DisplayName("引号内嵌套 @import: 字样 → 作为 inner 普通文本解析失败，unresolved 记引用原文，文本原样")
    void nestedQuoteInsideInnerUnresolved() {
        var result = resolver.resolve("@doc:\"unclosed @import:\"b.md\"");

        // 第一个引号对闭合，inner = "unclosed @import:"；解析失败 → unresolved 一条，原样追加
        assertEquals("@doc:\"unclosed @import:\"b.md\"", result.text());
        assertEquals(List.of("@doc:\"unclosed @import:\""), result.unresolved());
    }
}
