package com.gsim.root.tool;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RootTool 读取与写入")
class RootToolTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        // 写入足够的测试内容
        var sb = new StringBuilder();
        sb.append("# 测试世界观\n\n");
        sb.append("## 世界名称\n\nTest World\n\n");
        for (int i = 0; i < 200; i++) {
            sb.append("Line ").append(i).append(": padding content for truncation testing.\n");
        }
        Files.writeString(dm.worldFilePath(), sb.toString());
        Files.writeString(dm.entitiesFilePath(), "# Entities\n\n" + "Entity ".repeat(500));
        Files.writeString(dm.rulesFilePath(), "# Rules\n\n" + "Rule ".repeat(500));
        Files.writeString(dm.getPlayersPath(), "# Players\n\n" + "Player ".repeat(500));

        registry = new ToolRegistry();
        var factory = new RootToolFactory(dm, null);
        for (var tool : factory.createAll()) {
            registry.register(tool);
        }
    }

    @Nested
    @DisplayName("root_players_get 已注册")
    class RootPlayersGetRegistered {
        @Test
        @DisplayName("RootToolFactory.createAll 包含 root_players_get")
        void rootPlayersGetIsRegistered() {
            assertTrue(registry.has("root_players_get"), "root_players_get should be registered");
        }

        @Test
        @DisplayName("root_players_get 返回内容（非空 world）")
        void rootPlayersGetReturnsContent() {
            var call = new ToolCall("root_players_get", java.util.Map.of());
            ToolResult result = registry.call(call);
            assertTrue(result.success(), "root_players_get should succeed");
            assertTrue(result.items().get(0).snippet().contains("Players"));
        }
    }

    @Nested
    @DisplayName("分页读取")
    class Pagination {
        @Test
        @DisplayName("root_world_get 支持默认 offset/limit")
        void rootWorldGetDefaultPagination() {
            var call = new ToolCall("root_world_get", java.util.Map.of());
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("originalLength:"), "should include originalLength");
            assertTrue(content.contains("truncated:"), "should include truncated");
            assertTrue(content.contains("returnedRange:"), "should include returnedRange");
        }

        @Test
        @DisplayName("root_world_get 支持自定义 offset/limit")
        void rootWorldGetCustomOffsetLimit() {
            var call = new ToolCall("root_world_get", java.util.Map.of("offset", "0", "limit", "100"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("returnedRange: 0-100"));
        }

        @Test
        @DisplayName("root_entities_get 支持分页")
        void rootEntitiesGetPagination() {
            var call = new ToolCall("root_entities_get", java.util.Map.of("offset", "0", "limit", "500"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("truncated:"));
        }

        @Test
        @DisplayName("root_rules_get 支持分页")
        void rootRulesGetPagination() {
            var call = new ToolCall("root_rules_get", java.util.Map.of());
            ToolResult result = registry.call(call);
            assertTrue(result.success());
        }

        @Test
        @DisplayName("root_initial_info_get 支持分页")
        void rootInitialInfoGetPagination() {
            var call = new ToolCall("root_initial_info_get", java.util.Map.of());
            ToolResult result = registry.call(call);
            assertTrue(result.success());
        }

        @Test
        @DisplayName("truncated=true 时返回 nextOffset")
        void truncatedReturnsNextOffset() {
            // Use a small limit to force truncation
            var call = new ToolCall("root_world_get", java.util.Map.of("limit", "50"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("truncated: true"));
            assertTrue(content.contains("已截断"), "should include continuation hint");
        }

        @Test
        @DisplayName("full=true 返回全文（受安全上限限制）")
        void fullReturnsFullText() {
            var call = new ToolCall("root_world_get", java.util.Map.of("full", "true"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String content = result.items().get(0).snippet();
            assertTrue(content.contains("returnedRange: 0-"));
        }
    }

    @Nested
    @DisplayName("root update 默认 append")
    class DefaultAppend {
        @Test
        @DisplayName("root_world_update 默认 mode=append")
        void rootWorldUpdateDefaultsToAppend() throws Exception {
            String original = Files.readString(dm.worldFilePath());
            var call = new ToolCall("root_world_update", java.util.Map.of(
                    "content", "追加内容"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String updated = Files.readString(dm.worldFilePath());
            assertTrue(updated.contains("追加内容"));
            assertTrue(updated.length() > original.length(), "append should not reduce content");
        }

        @Test
        @DisplayName("root_world_update mode=replace 需要 confirmReplace=true")
        void rootWorldReplaceRequiresConfirm() {
            var call = new ToolCall("root_world_update", java.util.Map.of(
                    "mode", "replace", "content", "覆盖内容"));
            ToolResult result = registry.call(call);
            assertFalse(result.success());
            assertTrue(result.error().contains("REPLACE_REQUIRES_CONFIRM"));
        }

        @Test
        @DisplayName("root_world_update mode=replace + confirmReplace=true 允许覆盖")
        void rootWorldReplaceWithConfirm() throws Exception {
            var call = new ToolCall("root_world_update", java.util.Map.of(
                    "mode", "replace", "confirmReplace", "true", "content", "覆盖内容"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            assertEquals("覆盖内容", Files.readString(dm.worldFilePath()));
        }

        @Test
        @DisplayName("root_entities_update 默认 mode=append")
        void rootEntitiesUpdateDefaultsToAppend() throws Exception {
            String original = Files.readString(dm.entitiesFilePath());
            var call = new ToolCall("root_entities_update", java.util.Map.of(
                    "content", "追加实体"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String updated = Files.readString(dm.entitiesFilePath());
            assertTrue(updated.length() > original.length());
        }

        @Test
        @DisplayName("root_entities_update mode=replace 需要 confirmReplace=true")
        void rootEntitiesReplaceRequiresConfirm() {
            var call = new ToolCall("root_entities_update", java.util.Map.of(
                    "mode", "replace", "content", "覆盖"));
            ToolResult result = registry.call(call);
            assertFalse(result.success());
            assertTrue(result.error().contains("REPLACE_REQUIRES_CONFIRM"));
        }

        @Test
        @DisplayName("root_rules_update 默认 mode=append")
        void rootRulesUpdateDefaultsToAppend() throws Exception {
            String original = Files.readString(dm.rulesFilePath());
            var call = new ToolCall("root_rules_update", java.util.Map.of(
                    "content", "追加规则"));
            ToolResult result = registry.call(call);
            assertTrue(result.success());
            String updated = Files.readString(dm.rulesFilePath());
            assertTrue(updated.length() > original.length());
        }

        @Test
        @DisplayName("root_rules_update mode=replace 需要 confirmReplace=true")
        void rootRulesReplaceRequiresConfirm() {
            var call = new ToolCall("root_rules_update", java.util.Map.of(
                    "mode", "replace", "content", "覆盖"));
            ToolResult result = registry.call(call);
            assertFalse(result.success());
            assertTrue(result.error().contains("REPLACE_REQUIRES_CONFIRM"));
        }
    }
}
