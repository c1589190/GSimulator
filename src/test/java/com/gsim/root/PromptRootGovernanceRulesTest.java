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
        assertTrue(content.contains("root_create"),
                "Prompt must mention root_create tool");
        assertTrue(content.contains("root_world_update"),
                "Prompt must mention root_world_update tool");
        assertTrue(content.contains("root_status"),
                "Prompt must mention root_status tool");
        assertTrue(content.contains("branch.b0000-start"),
                "Prompt must mention root branch");
        assertTrue(content.contains("不要把不同 root 的资料混用"),
                "Prompt must forbid cross-root contamination");
        assertTrue(content.contains("player_profile_update"),
                "Prompt must mention player profile tools are always available");
        assertTrue(content.contains("初始化根节点"),
                "Prompt must mention bootstrap prefix format");
        assertTrue(content.contains("不在根节点时的限制"),
                "Prompt must mention non-root-branch restrictions");
    }
}
