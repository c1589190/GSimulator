package com.gsim.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SkillManager")
class SkillManagerTest {
    @TempDir Path tempDir;
    private SkillManager sm;

    @BeforeEach void setUp() throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        Files.writeString(skillsDir.resolve("test-skill.md"),
                "id: skill.test\ntype: skill\nname: Test Skill\nscope: global\ntags: [test]\nupdated: 2026-06-18\n-------------------\n\n# Test\n\nA test skill.\n");
        sm = new SkillManager(tempDir);
    }

    @Test @DisplayName("list skills")
    void testList() { assertFalse(sm.listSkills().isEmpty()); }

    @Test @DisplayName("read skill by id")
    void testRead() {
        var s = sm.readSkill("skill.test");
        assertNotNull(s);
        assertEquals("Test Skill", s.name());
    }

    @Test @DisplayName("search skills")
    void testSearch() {
        var r = sm.searchSkills("Test", 5);
        assertFalse(r.isEmpty());
    }

    @Test @DisplayName("skill context")
    void testContext() {
        String ctx = sm.getSkillContext();
        assertTrue(ctx.contains("Test Skill"));
    }
}
