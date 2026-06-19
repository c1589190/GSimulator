package com.gsim.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 log4j2.xml 配置文件存在于 classpath 中且可被 Log4j2 加载。
 */
@DisplayName("log4j2.xml 存在于 classpath resources")
class Log4j2ConfigResourceExistsTest {

    @Test
    @DisplayName("log4j2.xml 可被 ClassLoader 读取")
    void log4j2ConfigOnClasspath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        assertNotNull(cl, "ContextClassLoader should not be null");

        try (InputStream is = cl.getResourceAsStream("log4j2.xml")) {
            assertNotNull(is,
                    "log4j2.xml MUST be present in src/main/resources/");
        } catch (Exception e) {
            fail("Failed to read log4j2.xml from classpath: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("log4j2.xml 包含必要 Appender")
    void log4j2ConfigHasRequiredAppenders() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String content;
        try (InputStream is = cl.getResourceAsStream("log4j2.xml")) {
            assert is != null;
            content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertTrue(content.contains("CONSOLE"),
                "Should define CONSOLE appender");
        assertTrue(content.contains("MAIN_LOG"),
                "Should define MAIN_LOG appender for gsimulator.log");
        assertTrue(content.contains("TOOLLOOP_LOG"),
                "Should define TOOLLOOP_LOG appender for toolloop.log");
        assertTrue(content.contains("LLM_LOG"),
                "Should define LLM_LOG appender for llm.log");
    }

    @Test
    @DisplayName("log4j2.xml 为 com.gsim.agent 和 com.gsim.llm 配置了独立 Logger")
    void log4j2ConfigHasAgentAndLlmLoggers() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String content;
        try (InputStream is = cl.getResourceAsStream("log4j2.xml")) {
            assert is != null;
            content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertTrue(content.contains("com.gsim.agent"),
                "Should configure com.gsim.agent logger for ToolLoop");
        assertTrue(content.contains("com.gsim.llm"),
                "Should configure com.gsim.llm logger for LLM");
    }
}
