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

@DisplayName("BranchMessageStore — 首次 marker append 后不丢失旧消息")
class BranchMessageStoreDoesNotLoseOldMessagesAfterFirstMarkerAppendTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Test
    @DisplayName("有 EOF 旧消息 + 无 marker → 首次 append → listMessages 仍能看到旧消息")
    void oldMessagesStillReadableAfterFirstAppend() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        // File with old message blocks at EOF, no marker, no LLM section heading
        String oldContent = """
                ## 一、本节点输入

                Some input.

                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                first user message
                <!-- message:end -->
                <!-- message:start id=m0002 role=assistant type=chat_response created=2025-01-01T00:00:01Z -->
                first assistant response
                <!-- message:end -->
                """;
        Files.writeString(branchFile, oldContent);

        // First append triggers marker creation + migration
        BranchMessage newMsg = new BranchMessage("m0003", "user", "chat_user", null,
                Instant.now(), "second user message");
        store.appendMessage("branch.b0000-start", newMsg);

        // Verify all 3 messages are readable
        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(3, msgs.size(), "should not lose old messages after first marker append");

        assertTrue(msgs.stream().anyMatch(m -> m.id().equals("m0001") && m.content().contains("first user")),
                "m0001 should still be readable");
        assertTrue(msgs.stream().anyMatch(m -> m.id().equals("m0002") && m.content().contains("first assistant")),
                "m0002 should still be readable");
        assertTrue(msgs.stream().anyMatch(m -> m.id().equals("m0003") && m.content().contains("second user")),
                "m0003 should be readable");
    }

    @Test
    @DisplayName("多次 append 后旧消息不丢失")
    void oldMessagesNotLostAfterMultipleAppends() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        String oldContent = """
                ## 二、LLM 上下文记录

                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                initial message
                <!-- message:end -->
                """;
        Files.writeString(branchFile, oldContent);

        // First append (triggers migration from raw)
        store.appendMessage("branch.b0000-start", new BranchMessage("m0002", "assistant",
                "chat_response", null, Instant.now(), "reply 1"));

        // Second append (marker already exists, no migration)
        store.appendMessage("branch.b0000-start", new BranchMessage("m0003", "user",
                "chat_user", null, Instant.now(), "reply 2"));

        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(3, msgs.size(), "should have all 3 messages after 2 appends");
        assertTrue(msgs.stream().anyMatch(m -> m.content().contains("initial message")),
                "initial message should not be lost");
        assertTrue(msgs.stream().anyMatch(m -> m.content().contains("reply 1")),
                "reply 1 should not be lost");
        assertTrue(msgs.stream().anyMatch(m -> m.content().contains("reply 2")),
                "reply 2 should not be lost");
    }
}
