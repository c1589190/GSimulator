package com.gsim.chat;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchMessageStore — 旧消息迁移进 marker block")
class BranchMessageStoreMigratesLegacyMessagesIntoMarkerBlockTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Test
    @DisplayName("无 marker 但存在旧 message blocks 时 appendMessage 自动迁移")
    void migratesLegacyBlocksIntoNewMarker() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        // Old file has message blocks at EOF but no marker
        String oldContent = """
                ## 一、本节点输入

                初始输入内容。

                ## 二、LLM 上下文记录

                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                old message 1
                <!-- message:end -->
                <!-- message:start id=m0002 role=assistant type=chat_response created=2025-01-01T00:00:01Z -->
                old message 2
                <!-- message:end -->
                """;
        Files.writeString(branchFile, oldContent);

        // Now append a new message — should trigger migration
        BranchMessage newMsg = new BranchMessage("m0003", "user", "chat_user", null,
                Instant.now(), "new message 3");
        store.appendMessage("branch.b0000-start", newMsg);

        // After migration, the file should have a marker block with all 3 messages
        String updated = Files.readString(branchFile);
        assertTrue(updated.contains("<!-- BRANCH_MESSAGES START -->"), "should create marker block");
        assertTrue(updated.contains("<!-- BRANCH_MESSAGES END -->"), "should have marker end");
        assertTrue(updated.contains("old message 1"), "should include migrated message 1");
        assertTrue(updated.contains("old message 2"), "should include migrated message 2");
        assertTrue(updated.contains("new message 3"), "should include new message 3");

        // Old blocks outside marker should be removed
        String afterMarkerEnd = updated.substring(updated.indexOf("<!-- BRANCH_MESSAGES END -->"));
        assertFalse(afterMarkerEnd.contains("<!-- message:start id=m0001"),
                "old message blocks should not remain outside marker");

        // listMessages should return all 3
        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(3, msgs.size(), "should find all 3 messages after migration");
    }

    @Test
    @DisplayName("已有 marker 时不触发迁移，只追加")
    void noMigrationWhenMarkerExists() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        String content = """
                ## 二、LLM 上下文记录

                <!-- BRANCH_MESSAGES START -->
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                existing marker msg
                <!-- message:end -->
                <!-- BRANCH_MESSAGES END -->
                """;
        Files.writeString(branchFile, content);

        BranchMessage newMsg = new BranchMessage("m0002", "assistant", "chat_response", null,
                Instant.now(), "new appended msg");
        store.appendMessage("branch.b0000-start", newMsg);

        String updated = Files.readString(branchFile);

        // Should still have exactly one marker block
        int startCount = countOccurrences(updated, "<!-- BRANCH_MESSAGES START -->");
        assertEquals(1, startCount, "should not create duplicate marker");

        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(2, msgs.size());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
