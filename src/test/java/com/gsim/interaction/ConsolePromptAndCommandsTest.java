package com.gsim.interaction;

import com.gsim.TestWorldFactory;
import com.gsim.app.ApplicationContext;
import com.gsim.app.AppConfig;
import com.gsim.data.DataManager;
import com.gsim.interaction.commands.HelpCommand;
import com.gsim.interaction.commands.WhereCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Console Prompt & Commands")
class ConsolePromptAndCommandsTest {

    @Nested
    @DisplayName("动态 Prompt")
    class DynamicPrompt {
        @TempDir Path tempDir;
        private DataManager dm;

        @BeforeEach
        void setUp() throws Exception {
            dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        }

        @Test
        @DisplayName("无 root 显示 [no-root]")
        void noRootPrompt() {
            var adapter = new ConsoleInteractionAdapter(null, null, () -> null);
            String prompt = adapter.buildPrompt();
            assertEquals("gsim[no-root]> ", prompt);
        }

        @Test
        @DisplayName("有 root 时显示 rootId 和 branch")
        void withRootPrompt() {
            var adapter = new ConsoleInteractionAdapter(null, null, () -> dm);
            String prompt = adapter.buildPrompt();
            assertTrue(prompt.contains("gsim["));
            assertTrue(prompt.contains("default")); // rootId
            assertTrue(prompt.contains("b0000-start")); // branch (shortened)
        }

        @Test
        @DisplayName("Prompt 动态反映 root 变化")
        void promptChangesWithRoot() throws Exception {
            var adapter = new ConsoleInteractionAdapter(null, null, () -> dm);
            String prompt1 = adapter.buildPrompt();
            assertTrue(prompt1.contains("default"));

            dm.createRoot("test-root", "# 测试\n\n测试内容");
            dm.switchWorld("test-root");
            String prompt2 = adapter.buildPrompt();
            assertTrue(prompt2.contains("test-root"));
        }
    }

    @Nested
    @DisplayName("/where 命令")
    class WhereCommandTests {
        @TempDir Path tempDir;

        @Test
        @DisplayName("/where 输出 dataRoot, activeRoot, activeBranch")
        void whereShowsLocation() throws Exception {
            DataManager dm = TestWorldFactory.createWithDefaultRoot(tempDir);
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);
            ctx.setDataManager(dm);

            var cmd = new WhereCommand(ctx);
            var session = new InteractionSession(null, config, null, null, null, null, null);
            InteractionResult result = cmd.execute(new String[]{}, session);

            assertTrue(result.success());
            String text = result.displayText();
            assertTrue(text.contains("dataRoot:"));
            assertTrue(text.contains("activeRoot: default"));
            assertTrue(text.contains("activeBranch:"));
            assertTrue(text.contains("branch file:"));
        }

        @Test
        @DisplayName("/where 显示 LLM 配置状态（不含 API key）")
        void whereShowsLlmConfig() throws Exception {
            DataManager dm = TestWorldFactory.createWithDefaultRoot(tempDir);
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);
            ctx.setDataManager(dm);

            var cmd = new WhereCommand(ctx);
            var session = new InteractionSession(null, config, null, null, null, null, null);
            InteractionResult result = cmd.execute(new String[]{}, session);

            assertTrue(result.success());
            String text = result.displayText();
            assertTrue(text.contains("llmConfigured:"));
            // 不应包含 API key
            assertFalse(text.contains("sk-"));
        }
    }

    @Nested
    @DisplayName("/help 命令")
    class HelpCommandTests {
        @Test
        @DisplayName("/help 包含 /where 和直接对话说明")
        void helpIncludesWhereAndDirectChat() {
            Map<String, InteractionCommand> commands = new LinkedHashMap<>();
            commands.put("help", new HelpCommand(() -> commands));
            commands.put("where", new com.gsim.interaction.commands.WhereCommand(null));
            commands.put("status", new com.gsim.interaction.commands.StatusCommand());

            var help = new HelpCommand(() -> commands);
            InteractionResult result = help.execute(new String[]{}, null);

            assertTrue(result.success());
            String text = result.displayText();
            assertTrue(text.contains("/where"), "help should include /where");
            assertTrue(text.contains("非 / 开头的输入"), "help should mention direct chat");
            assertTrue(text.contains("/messages"), "help should mention /messages");
            assertTrue(text.contains("/root status"), "help should mention /root status");
        }
    }
}
