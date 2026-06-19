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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RootPlayersUpdate — append 默认 + replace 门禁")
class RootPlayersUpdateTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        Files.writeString(dm.getPlayersPath(), "初始玩家档案\n");
        registry = new ToolRegistry();
        var factory = new RootToolFactory(dm, null);
        for (var tool : factory.createAll()) {
            registry.register(tool);
        }
    }

    @Nested
    @DisplayName("root_players_update 默认 append")
    class DefaultsToAppend {
        @Test
        @DisplayName("不传 mode → 默认 append")
        void defaultsToAppend() throws Exception {
            String original = Files.readString(dm.getPlayersPath());
            var call = new ToolCall("root_players_update", Map.of(
                    "content", "新增玩家记录"));
            ToolResult result = registry.call(call);
            assertTrue(result.success(), "should succeed: " + result.error());
            String updated = Files.readString(dm.getPlayersPath());
            assertTrue(updated.contains("初始玩家档案"));
            assertTrue(updated.contains("新增玩家记录"));
            assertTrue(updated.length() > original.length(), "append should not reduce content");
        }

        @Test
        @DisplayName("mode=append → 追加")
        void explicitAppend() throws Exception {
            String original = Files.readString(dm.getPlayersPath());
            var call = new ToolCall("root_players_update", Map.of(
                    "mode", "append", "content", "追加条目"));
            ToolResult result = registry.call(call);
            assertTrue(result.success(), "should succeed: " + result.error());
            String updated = Files.readString(dm.getPlayersPath());
            assertTrue(updated.contains("初始玩家档案"));
            assertTrue(updated.contains("追加条目"));
            assertTrue(updated.length() > original.length());
        }
    }

    @Nested
    @DisplayName("mode=replace 需要 confirmReplace")
    class ReplaceRequiresConfirm {
        @Test
        @DisplayName("mode=replace 无 confirmReplace → 失败")
        void replaceWithoutConfirm() {
            var call = new ToolCall("root_players_update", Map.of(
                    "mode", "replace", "content", "覆盖档案"));
            ToolResult result = registry.call(call);
            assertFalse(result.success());
            assertTrue(result.error().contains("REPLACE_REQUIRES_CONFIRM"),
                    "should require confirmReplace: " + result.error());
        }

        @Test
        @DisplayName("mode=replace + confirmReplace=true → 允许覆盖")
        void replaceWithConfirm() throws Exception {
            var call = new ToolCall("root_players_update", Map.of(
                    "mode", "replace", "confirmReplace", "true",
                    "content", "完全替换的玩家档案"));
            ToolResult result = registry.call(call);
            assertTrue(result.success(), "should allow replace with confirm: " + result.error());
            String updated = Files.readString(dm.getPlayersPath());
            assertEquals("完全替换的玩家档案", updated.trim());
            assertFalse(updated.contains("初始玩家档案"), "replace should overwrite old content");
        }
    }
}
