package com.gsim.interaction.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WhereCommand — root title 解析")
class WhereCommandTitleParsingTest {

    @Nested
    @DisplayName("从下一行读取 title")
    class TitleFromNextLine {
        @Test
        @DisplayName("## 世界名称\\nTitle → 取下一行")
        void titleFromImmediateNextLine() {
            String content = """
                    ## 世界名称
                    明日方舟/泰拉
                    """;
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }

        @Test
        @DisplayName("## 世界名称\\n\\nTitle → 跳过空行取下一行")
        void titleFromNextLineWithBlank() {
            String content = """
                    ## 世界名称

                    明日方舟/泰拉
                    """;
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }
    }

    @Nested
    @DisplayName("从冒号分隔 heading 读取 title")
    class TitleFromColonHeading {
        @Test
        @DisplayName("## 世界名称：Title → 取冒号后")
        void titleFromColonHeading() {
            String content = "## 世界名称：明日方舟/泰拉\n";
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }

        @Test
        @DisplayName("## 世界名称:Title → 取冒号后（半角）")
        void titleFromHalfWidthColon() {
            String content = "## 世界名称:明日方舟/泰拉\n";
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }

        @Test
        @DisplayName("## 世界名称 ： Title → 中文冒号+空格")
        void titleFromChineseColonWithSpaces() {
            String content = "## 世界名称 ： 明日方舟/泰拉\n";
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }

        @Test
        @DisplayName("## 世界名称 : Title → 英文冒号+空格")
        void titleFromEnglishColonWithSpaces() {
            String content = "## 世界名称 : 明日方舟/泰拉\n";
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }

        @Test
        @DisplayName("## 世界名称  Title → 仅有空格分隔")
        void titleFromSpaceOnly() {
            String content = "## 世界名称 明日方舟/泰拉\n";
            String title = WhereCommand.extractTitle(content);
            assertEquals("明日方舟/泰拉", title);
        }
    }

    @Nested
    @DisplayName("fallback 到 H1 标题")
    class FallbackToH1 {
        @Test
        @DisplayName("# Title → H1 fallback")
        void h1Fallback() {
            String content = """
                    # 架空科幻世界

                    一些描述...
                    """;
            String title = WhereCommand.extractTitle(content);
            assertEquals("架空科幻世界", title);
        }

        @Test
        @DisplayName("# 世界观 不匹配（太通用）")
        void h1GenericWorldViewIgnored() {
            String content = """
                    # 世界观

                    ## 世界名称
                    测试世界
                    """;
            String title = WhereCommand.extractTitle(content);
            assertEquals("测试世界", title, "should skip generic '# 世界观' and use ## 世界名称");
        }
    }

    @Nested
    @DisplayName("无法解析时返回 null")
    class TitleNotFound {
        @Test
        @DisplayName("空内容返回 null")
        void emptyContent() {
            assertNull(WhereCommand.extractTitle(""));
            assertNull(WhereCommand.extractTitle(null));
        }

        @Test
        @DisplayName("无标题结构返回 null")
        void noHeadingStructure() {
            String content = "Some text without any headings\nMore text here\n";
            assertNull(WhereCommand.extractTitle(content));
        }
    }
}
