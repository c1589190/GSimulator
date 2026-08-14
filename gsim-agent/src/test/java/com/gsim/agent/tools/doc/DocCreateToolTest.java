package com.gsim.agent.tools.doc;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.doc.DocCacheManager;
import com.gsim.core.doc.DocStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DocCreateTool 嵌套 docId（a/b/c）校验与创建测试。 */
class DocCreateToolTest {

    @TempDir
    Path tempDir;

    private DocStore store;
    private DocCreateTool tool;

    @BeforeEach
    void setUp() throws IOException {
        store = new DocStore(tempDir.resolve("docs"));
        store.init();
        // cacheManager 不能为 null：execute() 无条件调用 cacheManager.resolve()
        tool = new DocCreateTool(store, new DocCacheManager(tempDir.resolve("cache")), null);
    }

    @Test
    void nestedDocIdCreatesDocument() {
        ToolCall call = new ToolCall(
                "doc_create", Map.of("docId", "a/b/c", "title", "嵌套标题", "content", "嵌套正文"));
        ToolResult r = tool.execute(call);

        assertTrue(r.success());
        var doc = store.get("a/b/c");
        assertNotNull(doc);
        assertEquals("嵌套正文", doc.content());
    }

    @Test
    void nestedDocIdWithEmptySegmentFails() {
        ToolResult r = tool.execute(new ToolCall("doc_create", Map.of("docId", "a//b", "title", "t")));

        assertFalse(r.success());
        assertEquals("docId 只能包含字母、数字、连字符、下划线", r.error());
    }

    @Test
    void nestedDocIdWithTraversalFails() {
        ToolResult r = tool.execute(new ToolCall("doc_create", Map.of("docId", "a/../b", "title", "t")));

        assertFalse(r.success());
        assertEquals("docId 只能包含字母、数字、连字符、下划线", r.error());
    }

    @Test
    void nestedDocIdWithIllegalCharFails() {
        ToolResult r = tool.execute(new ToolCall("doc_create", Map.of("docId", "a/b.", "title", "t")));

        assertFalse(r.success());
        assertEquals("docId 只能包含字母、数字、连字符、下划线", r.error());
    }

    @Test
    void topLevelDocIdStillWorks() {
        ToolResult r = tool.execute(new ToolCall("doc_create", Map.of("docId", "abc", "title", "顶层")));

        assertTrue(r.success());
        assertNotNull(store.get("abc"));
    }
}
