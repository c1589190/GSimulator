package com.gsim.root;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies orchestrator-system.md contains root governance rules.
 */
class PromptRootGovernanceRulesTest {

    @Test
    void promptContainsRootGovernanceRules() throws Exception {
        Path f = Path.of("src/main/resources/gsim/prompts/orchestrator-system.md");
        if (!Files.exists(f)) return;
        String content = Files.readString(f);

        assertTrue(content.contains("根节点 / Root Workspace 管理规则"),
                "Prompt must contain root governance section");
        assertTrue(content.contains("n0000"),
                "Prompt must reference root node n0000");
        assertTrue(content.contains("不在根节点时的限制"),
                "Prompt must mention non-root restrictions");
        assertTrue(content.contains("第一条自然语言视为创建第一个 root 的需求")
                || content.contains("任意自然语言输入都允许 bootstrap")
                || content.contains("不得把原始用户消息直接作为 world.md"),
                "Prompt must contain new any-text bootstrap rules");
        assertTrue(content.contains("worldview"),
                "Prompt must reference worldview checkpoint");
        assertTrue(content.contains("narrative"),
                "Prompt must reference narrative checkpoint");
    }
}
