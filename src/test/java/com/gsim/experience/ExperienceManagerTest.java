package com.gsim.experience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExperienceManager")
class ExperienceManagerTest {
    @TempDir Path tempDir;
    private ExperienceManager em;

    @BeforeEach void setUp() throws Exception {
        Path expDir = tempDir.resolve("experience");
        Files.createDirectories(expDir);
        Files.writeString(expDir.resolve("e0001.md"),
                "id: experience.e0001\ntype: experience\nname: Test Exp\nsource: test\ntags: [test]\nupdated: 2026-06-18\n-------------------\n\n# Test\nTest experience.\n");
        em = new ExperienceManager(tempDir);
    }

    @Test @DisplayName("list experiences")
    void testList() { assertFalse(em.listExperiences().isEmpty()); }

    @Test @DisplayName("read experience")
    void testRead() {
        var e = em.readExperience("experience.e0001");
        assertNotNull(e);
        assertEquals("Test Exp", e.name());
    }

    @Test @DisplayName("search experiences")
    void testSearch() {
        var r = em.searchExperiences("Test", 5);
        assertFalse(r.isEmpty());
    }

    @Test @DisplayName("write new experience")
    void testWrite() throws Exception {
        var e = em.writeExperience("新经验", "内容测试");
        assertNotNull(e);
        assertTrue(e.body().contains("内容测试"));
    }
}
