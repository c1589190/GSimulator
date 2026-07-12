package com.gsim.interaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandParser 测试。
 */
@DisplayName("CommandParser")
class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @Test
    @DisplayName("应正确解析 /help 命令（无参数）")
    void shouldParseHelpCommand() {
        var parsed = parser.parse("/help");
        assertTrue(parsed.isCommand());
        assertEquals("help", parsed.commandName());
        assertEquals(0, parsed.args().length);
    }

    @Test
    @DisplayName("应正确解析 /status 命令（无参数）")
    void shouldParseStatusCommand() {
        var parsed = parser.parse("/status");
        assertTrue(parsed.isCommand());
        assertEquals("status", parsed.commandName());
    }

    @Test
    @DisplayName("应正确解析 /exit 命令")
    void shouldParseExitCommand() {
        var parsed = parser.parse("/exit");
        assertTrue(parsed.isCommand());
        assertEquals("exit", parsed.commandName());
    }

    @Test
    @DisplayName("应正确解析 /player 命令 — 玩家名和内容分离")
    void shouldParsePlayerCommand() {
        var parsed = parser.parse("/player 张三 向北方边境派出三个侦察连");
        assertTrue(parsed.isCommand());
        assertEquals("player", parsed.commandName());
        assertEquals(2, parsed.args().length);
        assertEquals("张三", parsed.args()[0]);
        assertEquals("向北方边境派出三个侦察连", parsed.args()[1]);
    }

    @Test
    @DisplayName("应正确解析 /player 命令 — 仅有玩家名")
    void shouldParsePlayerCommandWithoutContent() {
        var parsed = parser.parse("/player 张三");
        assertEquals("player", parsed.commandName());
        assertEquals(1, parsed.args().length);
        assertEquals("张三", parsed.args()[0]);
    }

    @Test
    @DisplayName("应正确解析 /run 命令 — 全部后续文本作为强制要求")
    void shouldParseRunCommandWithInstruction() {
        var parsed = parser.parse("/run 本回合请重点考虑补给线");
        assertEquals("run", parsed.commandName());
        assertEquals(1, parsed.args().length);
        assertEquals("本回合请重点考虑补给线", parsed.args()[0]);
    }

    @Test
    @DisplayName("应正确解析 /run 命令 — 无强制要求")
    void shouldParseRunCommandWithoutInstruction() {
        var parsed = parser.parse("/run");
        assertEquals("run", parsed.commandName());
        assertEquals(0, parsed.args().length);
    }

    @Test
    @DisplayName("应正确解析 /searchdb 命令")
    void shouldParseSearchdbCommand() {
        var parsed = parser.parse("/searchdb 塔拉第一大陆军的组织结构");
        assertEquals("searchdb", parsed.commandName());
        assertEquals(1, parsed.args().length);
        assertEquals("塔拉第一大陆军的组织结构", parsed.args()[0]);
    }

    @Test
    @DisplayName("应正确解析 /newturn 命令")
    void shouldParseNewturnCommand() {
        var parsed = parser.parse("/newturn");
        assertEquals("newturn", parsed.commandName());
    }

    @Test
    @DisplayName("应正确解析 /import 命令")
    void shouldParseImportCommand() {
        var parsed = parser.parse("/import");
        assertEquals("import", parsed.commandName());
    }

    @Test
    @DisplayName("应正确解析 /save 命令")
    void shouldParseSaveCommand() {
        var parsed = parser.parse("/save");
        assertEquals("save", parsed.commandName());
    }

    @Test
    @DisplayName("应正确解析 /load 命令")
    void shouldParseLoadCommand() {
        var parsed = parser.parse("/load my-campaign");
        assertEquals("load", parsed.commandName());
        assertEquals("my-campaign", parsed.args()[0]);
    }

    @Test
    @DisplayName("非命令输入应返回空命令名")
    void shouldHandleNonCommandInput() {
        var parsed = parser.parse("hello world");
        assertFalse(parsed.isCommand());
        assertEquals("", parsed.commandName());
    }

    @Test
    @DisplayName("空输入应返回空命令名")
    void shouldHandleEmptyInput() {
        var parsed = parser.parse("");
        assertFalse(parsed.isCommand());
    }

    @Test
    @DisplayName("null 输入应返回空命令名")
    void shouldHandleNullInput() {
        var parsed = parser.parse(null);
        assertFalse(parsed.isCommand());
    }

    @Test
    @DisplayName("命令名应转为小写")
    void shouldNormalizeCommandNameToLowerCase() {
        var parsed = parser.parse("/HELP");
        assertEquals("help", parsed.commandName());
    }

    @Test
    @DisplayName("/player 长内容应完整保留")
    void shouldPreserveLongPlayerContent() {
        String longContent = "塔拉第一大陆军宣布整编旧砖厂民兵，并向北部边境派出侦察队，" +
                "同时要求地方商会提供粮食补给。";
        var parsed = parser.parse("/player 约翰 " + longContent);
        assertEquals("约翰", parsed.args()[0]);
        assertEquals(longContent, parsed.args()[1]);
    }
}
