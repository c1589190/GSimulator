package com.gsim.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolPollutionFilter")
class ToolPollutionFilterTest {

    @Test
    @DisplayName("检测到已知污染片段返回 true")
    void testIsPolluted_Positive() {
        assertTrue(ToolPollutionFilter.isPolluted(
                "* test: Run tests with the given coverage strategy. Use when asked to run tests, check coverage, or verify test results."));
        assertTrue(ToolPollutionFilter.isPolluted(
                "mvn test with optional coverage (JaCoCo) and reporting (Surefire)"));
        assertTrue(ToolPollutionFilter.isPolluted(
                "Use when asked to run tests, check coverage"));
        // 大小写不敏感
        assertTrue(ToolPollutionFilter.isPolluted(
                "RUN TESTS WITH THE GIVEN COVERAGE STRATEGY"));
    }

    @Test
    @DisplayName("正常文本返回 false")
    void testIsPolluted_Negative() {
        assertFalse(ToolPollutionFilter.isPolluted("罗德岛是一家制药公司。"));
        assertFalse(ToolPollutionFilter.isPolluted(""));
        assertFalse(ToolPollutionFilter.isPolluted(null));
        assertFalse(ToolPollutionFilter.isPolluted("工具 wiki_search 返回:\n- 罗德岛 (rhodes-island.txt)"));
    }

    @Test
    @DisplayName("sanitize 移除污染行")
    void testSanitize_RemovesPollutedLines() {
        String input = """
                正常文本行
                * test: Run tests with the given coverage strategy. Use when asked to run tests.
                另一行正常文本
                mvn test with optional coverage""";
        String result = ToolPollutionFilter.sanitize(input);
        assertTrue(result.contains("正常文本行"));
        assertTrue(result.contains("另一行正常文本"));
        assertFalse(result.contains("Run tests with the given coverage strategy"));
        assertFalse(result.contains("mvn test with optional coverage"));
    }

    @Test
    @DisplayName("sanitize 无污染时原样返回")
    void testSanitize_NoPollution() {
        String input = "罗德岛是泰拉大陆主要的感染者救助组织。";
        String result = ToolPollutionFilter.sanitize(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("sanitize null 返回空字符串")
    void testSanitize_Null() {
        assertEquals("", ToolPollutionFilter.sanitize(null));
    }

    @Test
    @DisplayName("deduplicateToolDefinitions 去除重复工具定义行")
    void testDeduplicateToolDefinitions() {
        String input = """
                * wiki_search: Search local PRTS Wiki files
                * data_search: Search world data files
                * wiki_search: Search local PRTS Wiki files
                * data_search: Search world data files
                正常推演文本""";
        String result = ToolPollutionFilter.deduplicateToolDefinitions(input);
        // 应该包含去重标记
        assertTrue(result.contains("deduplicated"));
        // 正常文本应该保留
        assertTrue(result.contains("正常推演文本"));
        // 第一个出现的 wiki_search 和 data_search 应该保留
        long wikiCount = result.lines()
                .filter(l -> l.contains("wiki_search:") && !l.contains("deduplicated"))
                .count();
        assertEquals(1, wikiCount);
    }

    @Test
    @DisplayName("deduplicateToolDefinitions 无工具列表时原样返回")
    void testDeduplicateToolDefinitions_NoToolList() {
        String input = "罗德岛是泰拉大陆主要的感染者救助组织。";
        String result = ToolPollutionFilter.deduplicateToolDefinitions(input);
        assertEquals(input, result);
    }
}
