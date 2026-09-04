package com.gsim.agentsmanager.tool;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.cache.CacheSession;
import com.gsim.core.cache.CacheStore;
import com.gsim.core.cache.FileSystemCachesManager;
import com.gsim.core.config.CoreConfig;
import com.gsim.docslib.doc.DocStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubAgentCacheQueryToolTest {

    @TempDir
    Path tmpDir;

    private CoreConfig coreConfig;
    private DocStore docStore;
    private FileSystemCachesManager cachesManager;
    private String longCacheId;
    private String shortCacheId;

    @BeforeEach
    void setUp() throws java.io.IOException {
        coreConfig = CoreConfig.load();
        docStore = new DocStore(tmpDir.resolve("docs"));
        docStore.init();
        CacheStore.setCachesRoot(tmpDir.resolve("caches"));
        cachesManager = new FileSystemCachesManager();

        // 短缓存：两条消息，一条含"起兵"
        CacheSession shortSession = CacheStore.createNew("sim");
        shortCacheId = shortSession.sessionId();
        shortSession.setAgentName("sim");
        shortSession.addMessage(Map.of("role", "user", "content", "曹操起兵了吗？"));
        shortSession.addMessage(Map.of("role", "assistant", "content", "曹操自陈留起兵。"));
        CacheStore.save(shortSession);

        // 长缓存：一条超长消息（>3000 字符）
        CacheSession longSession = CacheStore.createNew("search");
        longCacheId = longSession.sessionId();
        longSession.setAgentName("search");
        longSession.addMessage(Map.of("role", "user", "content", "请分析曹操起兵的战略。".repeat(400))); // 约 4400 字符，超阈值
        CacheStore.save(longSession);
    }

    @Test
    void queryMatchesKeywordInSingleCache() {
        var tool = new QuerySubAgentCacheTool(cachesManager, docStore, coreConfig);
        ToolResult r =
                tool.execute(new ToolCall("query_sub_agent_cache", Map.of("cacheId", shortCacheId, "keywords", "起兵")));
        assertTrue(r.success());
        assertFalse(r.items().isEmpty());
        assertEquals(shortCacheId + "#0", r.items().get(0).path());
        assertTrue(r.items().get(0).snippet().contains("起兵"));
    }

    @Test
    void queryMatchesKeywordAcrossAllCachesAndStagesLong() {
        var tool = new QuerySubAgentCacheTool(cachesManager, docStore, coreConfig);
        ToolResult r = tool.execute(new ToolCall("query_sub_agent_cache", Map.of("keywords", "起兵", "detail", "true")));
        assertTrue(r.success());
        // 短缓存命中，长缓存命中且超长被暂存为 doc
        String sid = longCacheId;
        boolean sawStaged = r.items().stream()
                .anyMatch(item -> item.path().startsWith(sid) && item.snippet().contains("docId="));
        assertTrue(sawStaged, "长消息应被暂存为 doc: " + r.items());
    }

    @Test
    void viewReadsFullBreakpointAndStagesLong() {
        var tool = new ViewSubAgentCacheTool(cachesManager, docStore, coreConfig);
        ToolResult r = tool.execute(new ToolCall("view_sub_agent_cache", Map.of("cacheId", longCacheId)));
        assertTrue(r.success());
        String snippet = r.items().get(0).snippet();
        assertTrue(snippet.contains("docId="), "超长消息应暂存为 doc");
    }

    @Test
    void viewShortBreakpointShowsAllMessagesInline() {
        var tool = new ViewSubAgentCacheTool(cachesManager, docStore, coreConfig);
        ToolResult r = tool.execute(new ToolCall("view_sub_agent_cache", Map.of("cacheId", shortCacheId)));
        assertTrue(r.success());
        String snippet = r.items().get(0).snippet();
        assertTrue(snippet.contains("曹操自陈留起兵。"), "应内联显示短消息内容");
        assertFalse(snippet.contains("docId="));
    }
}
