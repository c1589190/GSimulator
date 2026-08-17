package com.gsim.agent.tools.search;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MediaWikiSearchTool 配置注入测试 — 断言构造注入的 wiki URL 被使用（本地 stub 服务器，不访问外网）。
 */
@DisplayName("MediaWikiSearchTool 配置注入")
class MediaWikiSearchToolConfigTest {

    private static final String SEARCH_JSON =
            """
            {"query":{"search":[{"title":"Test Page","pageid":1,"snippet":"<span>a snippet</span>","wordcount":3}]}}
            """;

    private HttpServer server;
    private String serverUrl;
    private AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/w/api.php", exchange -> {
            lastPath.set(exchange.getRequestURI().toString());
            byte[] body = SEARCH_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/w/api.php";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("构造注入的 wiki URL 在未传 wiki_url 参数时生效")
    void configuredWikiUrlUsedAsDefault() {
        var tool = new MediaWikiSearchTool(serverUrl, "TestAgent/1.0");

        ToolResult result = tool.execute(new ToolCall("mediawiki_search", Map.of("query", "test")));

        assertTrue(result.success(), "search should succeed against configured URL");
        assertTrue(lastPath.get().contains("action=query"), "request should hit configured API: " + lastPath.get());
        assertTrue(lastPath.get().contains("list=search"));
        assertEquals("Test Page", result.items().get(0).title());
        assertTrue(result.items().get(0).path().startsWith(serverUrl), "item path should use configured URL");
    }

    @Test
    @DisplayName("调用方显式 wiki_url 参数仍优先于构造注入值")
    void explicitWikiUrlParamWins() {
        var tool = new MediaWikiSearchTool(serverUrl, "TestAgent/1.0");

        ToolResult result = tool.execute(
                new ToolCall("mediawiki_search", Map.of("query", "test", "wiki_url", serverUrl)));

        assertTrue(result.success());
        assertTrue(lastPath.get().contains("list=search"));
    }

    @Test
    @DisplayName("默认构造保留 DEFAULT_WIKI_URL / DEFAULT_USER_AGENT")
    void defaultConstructorKeepsConstants() {
        var tool = new MediaWikiSearchTool();

        assertEquals("https://en.wikipedia.org/w/api.php", MediaWikiSearchTool.DEFAULT_WIKI_URL);
        assertEquals("GSimulator/1.0 (research tool)", MediaWikiSearchTool.DEFAULT_USER_AGENT);
        assertTrue(tool.description().contains(MediaWikiSearchTool.DEFAULT_WIKI_URL));
        // 空 query 直接失败，不触网
        ToolResult result = tool.execute(new ToolCall("mediawiki_search", Map.of("query", "")));
        assertFalse(result.success());
    }
}
