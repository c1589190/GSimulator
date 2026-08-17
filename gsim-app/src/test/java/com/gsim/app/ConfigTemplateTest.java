package com.gsim.app;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规范 gsim.properties 模板（classpath 资源 /gsim/config/gsim.properties.template）治理测试。
 *
 * <p>模板是 Main.ensureConfigTemplate 首次运行落盘的唯一来源；此处锁定其存在性、
 * 活跃默认值与全部可配置键的文档覆盖（注释形式亦可）。
 */
@DisplayName("gsim.properties 规范模板（classpath 资源）")
class ConfigTemplateTest {

    private static final String TEMPLATE_RESOURCE = "/gsim/config/gsim.properties.template";

    /** ConfigLoader 新增的全部配置键 — 模板必须以注释形式完整记录（勿改默认值）。 */
    private static final String[] DOCUMENTED_KEYS = {
        "agent.tool_loop.result_inline_max_chars",
        "agent.tool_loop.result_staging.enabled",
        "mcp.response.max_json_bytes",
        "mcp.response.snippet_max_chars",
        "mcp.response.default_page_size",
        "mcp.response.max_page_size",
        "mcp.response.overflow_staging.enabled",
        "core.doc.staging.threshold",
        "core.doc.query.staging.threshold",
        "core.doc.tmp.max_age_hours",
        "core.doc.tmp.cleanup_enabled",
        "docs.dir",
        "caches.dir",
        "knowledge.db.path",
        "embedding.timeout_connect_seconds",
        "embedding.timeout_read_seconds",
        "embedding.timeout_write_seconds",
        "agent.subagent.collect.timeout_seconds",
        "agent.subagent.max_completed",
        "import.doc.max_full_read_chars",
        "import.doc.default_limit",
        "web_research.wiki.url",
        "map.radius.default",
        "map.cache.max_entries",
        "map.contour.cache.max",
        "map.lasso.max_radius",
        "map.lasso.max_fill",
        "map.compression.min_region_size",
        "map.resolver.max_chain_depth"
    };

    /** 模板必须保留的活跃默认键（与 ConfigLoader.buildDefaults() 一致）。 */
    private static final String[] ACTIVE_KEYS = {
        "webui.host=127.0.0.1",
        "webui.port=8710",
        "map.port=8711",
        "cli.ws.port=8712",
        "mcp.http.port=37201",
        "worlds.dir=worlds",
        "agent.tool_loop.max_rounds=64",
        "context.session.history.turns=12",
        "llm.stream.enabled=true"
    };

    private static String readTemplate() throws Exception {
        try (InputStream in = ConfigTemplateTest.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            assertNotNull(in, "classpath 资源必须存在: " + TEMPLATE_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("模板资源存在于 gsim-app classpath")
    void templateResourceExists() throws Exception {
        try (InputStream in = ConfigTemplateTest.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            assertNotNull(in, "classpath 资源必须存在: " + TEMPLATE_RESOURCE);
        }
    }

    @Test
    @DisplayName("模板包含活跃默认值 agent.tool_loop.max_rounds=64")
    void templateContainsActiveMaxRounds64() throws Exception {
        assertTrue(readTemplate().contains("agent.tool_loop.max_rounds=64"));
    }

    @Test
    @DisplayName("模板记录全部 ConfigLoader 配置键（注释形式亦可）")
    void templateDocumentsAllConfigKeys() throws Exception {
        String template = readTemplate();
        for (String key : DOCUMENTED_KEYS) {
            assertTrue(template.contains(key), "模板必须包含配置键: " + key);
        }
    }

    @Test
    @DisplayName("模板保留当前有效活跃键，生成结果与现有默认配置等价")
    void templateKeepsActiveDefaultKeys() throws Exception {
        String template = readTemplate();
        for (String active : ACTIVE_KEYS) {
            assertTrue(template.contains(active), "模板必须保留活跃键: " + active);
        }
    }
}
