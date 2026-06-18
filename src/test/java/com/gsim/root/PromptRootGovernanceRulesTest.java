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
        assertTrue(content.contains("你不能主动创建、切换、删除 root"),
                "Prompt must forbid agent from managing roots");
        assertTrue(content.contains("/root create"),
                "Prompt must mention /root create");
        assertTrue(content.contains("/root switch"),
                "Prompt must mention /root switch");
        assertTrue(content.contains("/root delete"),
                "Prompt must mention /root delete");
        assertTrue(content.contains("不要把不同 root 的资料混用"),
                "Prompt must forbid cross-root contamination");
    }
}
