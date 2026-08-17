package com.gsim.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新增配置键（工具结果溢出 / 文档暂存 / 嵌入超时 / 地图限制等）的默认值与环境变量映射验证。
 *
 * <p>默认值必须与历史硬编码值一致 — 零行为变化是本测试的不变量。</p>
 */
@DisplayName("新增配置键 — 默认值与环境变量映射")
class NewConfigKeysDefaultTest {

    /** 配置键 → 期望默认值。 */
    private static final Map<String, String> EXPECTED_DEFAULTS = new LinkedHashMap<>();

    static {
        EXPECTED_DEFAULTS.put("agent.tool_loop.result_inline_max_chars", "4000");
        EXPECTED_DEFAULTS.put("agent.tool_loop.result_staging.enabled", "true");
        EXPECTED_DEFAULTS.put("mcp.response.max_json_bytes", "50000");
        EXPECTED_DEFAULTS.put("mcp.response.snippet_max_chars", "300");
        EXPECTED_DEFAULTS.put("mcp.response.default_page_size", "20");
        EXPECTED_DEFAULTS.put("mcp.response.max_page_size", "100");
        EXPECTED_DEFAULTS.put("mcp.response.overflow_staging.enabled", "true");
        EXPECTED_DEFAULTS.put("core.doc.staging.threshold", "500");
        EXPECTED_DEFAULTS.put("core.doc.query.staging.threshold", "3000");
        EXPECTED_DEFAULTS.put("core.doc.tmp.max_age_hours", "168");
        EXPECTED_DEFAULTS.put("core.doc.tmp.cleanup_enabled", "true");
        EXPECTED_DEFAULTS.put("docs.dir", "");
        EXPECTED_DEFAULTS.put("caches.dir", "");
        EXPECTED_DEFAULTS.put("knowledge.db.path", "data/gsim.db");
        EXPECTED_DEFAULTS.put("embedding.timeout_connect_seconds", "30");
        EXPECTED_DEFAULTS.put("embedding.timeout_read_seconds", "60");
        EXPECTED_DEFAULTS.put("embedding.timeout_write_seconds", "30");
        EXPECTED_DEFAULTS.put("agent.subagent.collect.timeout_seconds", "300");
        EXPECTED_DEFAULTS.put("agent.subagent.max_completed", "100");
        EXPECTED_DEFAULTS.put("import.doc.max_full_read_chars", "30000");
        EXPECTED_DEFAULTS.put("import.doc.default_limit", "8000");
        EXPECTED_DEFAULTS.put("web_research.wiki.url", "https://en.wikipedia.org/w/api.php");
        EXPECTED_DEFAULTS.put("map.radius.default", "80");
        EXPECTED_DEFAULTS.put("map.cache.max_entries", "32");
        EXPECTED_DEFAULTS.put("map.contour.cache.max", "5000");
        EXPECTED_DEFAULTS.put("map.lasso.max_radius", "200");
        EXPECTED_DEFAULTS.put("map.lasso.max_fill", "30000");
        EXPECTED_DEFAULTS.put("map.compression.min_region_size", "100");
        EXPECTED_DEFAULTS.put("map.resolver.max_chain_depth", "200");
    }

    /** 环境变量名 → 配置键。 */
    private static final Map<String, String> EXPECTED_ENV_MAPPINGS = new LinkedHashMap<>();

    static {
        EXPECTED_ENV_MAPPINGS.put(
                "GSIM_AGENT_TOOL_LOOP_RESULT_INLINE_MAX_CHARS", "agent.tool_loop.result_inline_max_chars");
        EXPECTED_ENV_MAPPINGS.put(
                "GSIM_AGENT_TOOL_LOOP_RESULT_STAGING_ENABLED", "agent.tool_loop.result_staging.enabled");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MCP_RESPONSE_MAX_JSON_BYTES", "mcp.response.max_json_bytes");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MCP_RESPONSE_SNIPPET_MAX_CHARS", "mcp.response.snippet_max_chars");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MCP_RESPONSE_DEFAULT_PAGE_SIZE", "mcp.response.default_page_size");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MCP_RESPONSE_MAX_PAGE_SIZE", "mcp.response.max_page_size");
        EXPECTED_ENV_MAPPINGS.put(
                "GSIM_MCP_RESPONSE_OVERFLOW_STAGING_ENABLED", "mcp.response.overflow_staging.enabled");
        EXPECTED_ENV_MAPPINGS.put("GSIM_CORE_DOC_STAGING_THRESHOLD", "core.doc.staging.threshold");
        EXPECTED_ENV_MAPPINGS.put("GSIM_CORE_DOC_QUERY_STAGING_THRESHOLD", "core.doc.query.staging.threshold");
        EXPECTED_ENV_MAPPINGS.put("GSIM_CORE_DOC_TMP_MAX_AGE_HOURS", "core.doc.tmp.max_age_hours");
        EXPECTED_ENV_MAPPINGS.put("GSIM_CORE_DOC_TMP_CLEANUP_ENABLED", "core.doc.tmp.cleanup_enabled");
        EXPECTED_ENV_MAPPINGS.put("GSIM_DOCS_DIR", "docs.dir");
        EXPECTED_ENV_MAPPINGS.put("GSIM_CACHES_DIR", "caches.dir");
        EXPECTED_ENV_MAPPINGS.put("GSIM_KNOWLEDGE_DB_PATH", "knowledge.db.path");
        EXPECTED_ENV_MAPPINGS.put("GSIM_EMBEDDING_TIMEOUT_CONNECT_SECONDS", "embedding.timeout_connect_seconds");
        EXPECTED_ENV_MAPPINGS.put("GSIM_EMBEDDING_TIMEOUT_READ_SECONDS", "embedding.timeout_read_seconds");
        EXPECTED_ENV_MAPPINGS.put("GSIM_EMBEDDING_TIMEOUT_WRITE_SECONDS", "embedding.timeout_write_seconds");
        EXPECTED_ENV_MAPPINGS.put(
                "GSIM_AGENT_SUBAGENT_COLLECT_TIMEOUT_SECONDS", "agent.subagent.collect.timeout_seconds");
        EXPECTED_ENV_MAPPINGS.put("GSIM_AGENT_SUBAGENT_MAX_COMPLETED", "agent.subagent.max_completed");
        EXPECTED_ENV_MAPPINGS.put("GSIM_IMPORT_DOC_MAX_FULL_READ_CHARS", "import.doc.max_full_read_chars");
        EXPECTED_ENV_MAPPINGS.put("GSIM_IMPORT_DOC_DEFAULT_LIMIT", "import.doc.default_limit");
        EXPECTED_ENV_MAPPINGS.put("GSIM_WEB_RESEARCH_WIKI_URL", "web_research.wiki.url");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_RADIUS_DEFAULT", "map.radius.default");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_CACHE_MAX_ENTRIES", "map.cache.max_entries");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_CONTOUR_CACHE_MAX", "map.contour.cache.max");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_LASSO_MAX_RADIUS", "map.lasso.max_radius");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_LASSO_MAX_FILL", "map.lasso.max_fill");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_COMPRESSION_MIN_REGION_SIZE", "map.compression.min_region_size");
        EXPECTED_ENV_MAPPINGS.put("GSIM_MAP_RESOLVER_MAX_CHAIN_DEPTH", "map.resolver.max_chain_depth");
    }

    @Test
    @DisplayName("buildDefaults 提供全部 29 个新键的精确默认值")
    void defaultsArePresentWithExactValues() {
        ConfigLoader loader = new ConfigLoader(new String[] {});
        ConfigLoader.ConfigResult result = loader.load();

        assertEquals(29, EXPECTED_DEFAULTS.size());
        for (var expected : EXPECTED_DEFAULTS.entrySet()) {
            String key = expected.getKey();
            assertNotNull(result.entries().get(key), "缺少默认键: " + key);
            assertEquals(expected.getValue(), result.get(key), "默认值不匹配: " + key);
        }
    }

    @Test
    @DisplayName("29 个 GSIM_* 环境变量映射到对应配置键")
    void envKeysMapToConfigKeys() {
        assertEquals(29, EXPECTED_ENV_MAPPINGS.size());
        for (var expected : EXPECTED_ENV_MAPPINGS.entrySet()) {
            assertEquals(
                    expected.getValue(), ConfigLoader.mapEnvKey(expected.getKey()), "环境变量映射不匹配: " + expected.getKey());
        }
    }

    @Test
    @DisplayName("新增配置键不覆盖既有键的默认值")
    void existingKeysUntouched() {
        ConfigLoader.ConfigResult result = new ConfigLoader(new String[] {}).load();

        assertEquals("8710", result.get("webui.port"));
        assertEquals("8711", result.get("map.port"));
        assertEquals("8712", result.get("cli.ws.port"));
        assertEquals("8720", result.get("mcp.http.port"));
        assertEquals("64", result.get("agent.tool_loop.max_rounds"));
        assertEquals("worlds", result.get("worlds.dir"));
    }
}
