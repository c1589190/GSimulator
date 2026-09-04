package com.gsim.core.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.doc.DocStore;
import com.gsim.docslib.doc.DocType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimSearchDocTool 测试。
 *
 * <p>核心性质：<b>新鲜度</b>——doc_create 后<em>不调用 doc_index</em> 立即搜索即可命中
 * （本工具直读 DocStore，不走 SkillIndex；测试中根本不构造 SkillIndex，新鲜度由构造保证）。
 */
class GsimSearchDocToolTest {

    @TempDir
    Path tempDir;

    private DocStore store;
    private GsimSearchDocTool tool;

    @BeforeEach
    void setUp() throws IOException {
        store = new DocStore(tempDir.resolve("docs"));
        store.init();
        tool = new GsimSearchDocTool(new SearchToolContext(null, null, store, null));
    }

    @Test
    void freshDocIsSearchableWithoutDocIndex() throws IOException {
        // 文档域全局：defaultNodeId 返回 null，worldId 传入但被忽略
        store.create("mcp_search_doc", DocType.OTHER, "迷雾森林考察", "浓雾与流水声交织的密林深处。", List.of("地理"));

        ToolResult r = tool.execute(new ToolCall("gsim_search_doc", Map.of("worldId", "w_test", "keywords", "迷雾")));

        assertTrue(r.success());
        assertEquals(1, r.items().size());
        assertEquals("@doc:mcp_search_doc", r.items().get(0).path());
        assertEquals("@doc:mcp_search_doc", r.items().get(0).title());
        assertTrue(r.items().get(0).snippet().startsWith("type=doc | "), "snippet 应含 type=doc 前缀");
        assertTrue(r.items().get(0).score() > 0);
    }

    @Test
    void titleSummaryAndTagsAreEachMatchable() throws IOException {
        // "考察" 只出现在 title；"流水" 只出现在 content（→ summary 语料）；
        // "探险" 只出现在 tags —— 三者互斥，证明语料 = title + summary + tags
        store.create("corpus_doc", DocType.OTHER, "迷雾森林考察", "浓雾与流水声交织的密林深处，树冠遮天蔽日。", List.of("地理", "探险"));

        assertHitKey(keywords("考察"), "corpus_doc"); // title 命中
        assertHitKey(keywords("流水"), "corpus_doc"); // summary 命中
        assertHitKey(keywords("探险"), "corpus_doc"); // tags 命中
    }

    @Test
    void contentBeyondSummaryCutoffIsNotSearchable() throws IOException {
        // 200 字摘要截断：正文尾部词不得进入语料（仅 title+summary+tags）
        String longBody = "静。".repeat(120) + "深渊密语" + "静。".repeat(120);
        store.create("long_doc", DocType.OTHER, "长文", longBody, List.of());

        assertEmpty(keywords("深渊密语"));
    }

    @Test
    void typeFilterRestrictsResults() throws IOException {
        store.create("doc_other", DocType.OTHER, "迷雾森林考察", "浓雾密林。", List.of("地理"));
        store.create("doc_char", DocType.CHARACTER, "迷雾剑客", "雾中剑客。", List.of("角色"));

        // 无过滤 → 两篇都命中
        ToolResult all = tool.execute(keywords("迷雾"));
        assertTrue(all.success());
        assertEquals(2, all.items().size());

        // type=character → 仅 character 篇
        ToolResult chars = tool.execute(
                new ToolCall("gsim_search_doc", Map.of("worldId", "w", "keywords", "迷雾", "type", "character")));
        assertTrue(chars.success());
        assertEquals(1, chars.items().size());
        assertEquals("@doc:doc_char", chars.items().get(0).path());

        // type=skill → 无该类型 → 空
        assertEmpty(new ToolCall("gsim_search_doc", Map.of("worldId", "w", "keywords", "迷雾", "type", "skill")));
    }

    @Test
    void tagFilterRestrictsResults() throws IOException {
        store.create("tag_doc", DocType.OTHER, "迷雾森林考察", "浓雾密林。", List.of("地理", "探险"));

        ToolResult hit =
                tool.execute(new ToolCall("gsim_search_doc", Map.of("worldId", "w", "keywords", "迷雾", "tag", "探险")));
        assertTrue(hit.success());
        assertEquals(1, hit.items().size());
        assertEquals("@doc:tag_doc", hit.items().get(0).path());

        assertEmpty(new ToolCall("gsim_search_doc", Map.of("worldId", "w", "keywords", "迷雾", "tag", "不存在的标签")));
    }

    @Test
    void noMatchReturnsEmptyItems() throws IOException {
        store.create("empty_doc", DocType.OTHER, "迷雾森林考察", "浓雾密林。", List.of("地理"));

        assertEmpty(keywords("千里之外没有的词"));
    }

    @Test
    void missingKeywordsFails() {
        ToolResult r = tool.execute(new ToolCall("gsim_search_doc", Map.of("worldId", "w")));

        assertFalse(r.success());
        assertTrue(r.error().contains("keywords"));
    }

    // ── 辅助 ──

    private static ToolCall keywords(String kw) {
        return new ToolCall("gsim_search_doc", Map.of("worldId", "w", "keywords", kw));
    }

    private void assertHitKey(ToolCall call, String docId) {
        ToolResult r = tool.execute(call);
        assertTrue(r.success());
        assertEquals(1, r.items().size(), () -> "应命中 1 条: " + r.items());
        assertEquals("@doc:" + docId, r.items().get(0).path());
    }

    private void assertEmpty(ToolCall call) {
        ToolResult r = tool.execute(call);
        assertTrue(r.success());
        assertTrue(r.items().isEmpty(), () -> "应无命中: " + r.items());
    }
}
