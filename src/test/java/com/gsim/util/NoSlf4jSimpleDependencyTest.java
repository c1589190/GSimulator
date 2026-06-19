package com.gsim.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 pom.xml 中不存在 slf4j-simple（会和 Log4j2 冲突）。
 */
@DisplayName("无 slf4j-simple 依赖冲突")
class NoSlf4jSimpleDependencyTest {

    @Test
    @DisplayName("pom.xml 中不声明 slf4j-simple 依赖")
    void noSlf4jSimpleInPom() throws Exception {
        Path pomPath = Paths.get("pom.xml");
        assertTrue(Files.exists(pomPath),
                "pom.xml should exist at project root (working directory)");

        String content = Files.readString(pomPath);

        // slf4j-simple (both 1.x and 2.x) would conflict with log4j-slf4j2-impl
        assertFalse(content.contains("slf4j-simple"),
                "pom.xml must NOT contain slf4j-simple dependency. "
                        + "Only log4j-slf4j2-impl should be the SLF4J binding.");
    }

    @Test
    @DisplayName("仅 log4j-slf4j2-impl 作为 SLF4J 绑定存在")
    void onlyLog4jSlf4j2ImplAsBinding() throws Exception {
        Path pomPath = Paths.get("pom.xml");
        String content = Files.readString(pomPath);

        // The only SLF4J binding should be log4j-slf4j2-impl
        assertTrue(content.contains("log4j-slf4j2-impl"),
                "pom.xml must contain log4j-slf4j2-impl as the SLF4J 2.x binding");

        // No other SLF4J bindings
        assertFalse(content.contains("log4j-slf4j-impl"),
                "Must use log4j-slf4j2-impl (SLF4J 2.x compatible), "
                        + "NOT log4j-slf4j-impl (SLF4J 1.x)");
        assertFalse(content.contains("logback-classic"),
                "logback-classic must be removed");
    }

    @Test
    @DisplayName("无 slf4j-nop、slf4j-jdk14 等其他 SLF4J 绑定")
    void noOtherSlf4jBindings() throws Exception {
        Path pomPath = Paths.get("pom.xml");
        String content = Files.readString(pomPath);

        assertFalse(content.contains("slf4j-nop"),
                "Must not contain slf4j-nop (no-op binding)");
        assertFalse(content.contains("slf4j-jdk14"),
                "Must not contain slf4j-jdk14 (java.util.logging binding)");
        assertFalse(content.contains("slf4j-jcl"),
                "Must not contain slf4j-jcl (commons-logging binding)");
    }
}
