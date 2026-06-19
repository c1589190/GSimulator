package com.gsim.chat;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchMessageStore — Marker + Legacy 合并读取")
class BranchMessageStoreReadsMarkerAndLegacyTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Test
    @DisplayName("marker 内有消息时也返回 marker 外旧 message blocks")
    void markerAndLegacyBothReturned() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        // Legacy message block outside marker
        String legacyBlock = """
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                legacy message outside marker
                <!-- message:end -->

                ## 二、LLM 上下文记录

                <!-- BRANCH_MESSAGES START -->
                <!-- message:start id=m0002 role=assistant type=chat_response created=2025-01-01T00:00:01Z -->
                marker message inside
                <!-- message:end -->
                <!-- BRANCH_MESSAGES END -->
                """;
        Files.writeString(branchFile, legacyBlock);

        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(2, msgs.size(), "should return both legacy and marker messages");
        assertTrue(msgs.stream().anyMatch(m -> m.id().equals("m0001")), "should include legacy m0001");
        assertTrue(msgs.stream().anyMatch(m -> m.id().equals("m0002")), "should include marker m0002");
    }

    @Test
    @DisplayName("marker 内有同 id 消息时 marker 版本优先（覆盖旧消息）")
    void markerWinsOnDedup() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        String content = """
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                old content outside marker
                <!-- message:end -->

                ## 二、LLM 上下文记录

                <!-- BRANCH_MESSAGES START -->
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:01Z -->
                new content inside marker
                <!-- message:end -->
                <!-- BRANCH_MESSAGES END -->
                """;
        Files.writeString(branchFile, content);

        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(1, msgs.size(), "should deduplicate by id");
        assertTrue(msgs.get(0).content().contains("new content inside marker"),
                "marker version should win: " + msgs.get(0).content());
    }
}
