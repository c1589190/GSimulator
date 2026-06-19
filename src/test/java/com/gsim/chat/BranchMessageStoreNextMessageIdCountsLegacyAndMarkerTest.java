package com.gsim.chat;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchMessageStore — nextMessageId 计数覆盖 marker + legacy")
class BranchMessageStoreNextMessageIdCountsLegacyAndMarkerTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Test
    @DisplayName("nextMessageId 统计 marker 内和 marker 外所有消息")
    void nextMessageIdCountsAllSources() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        // Legacy messages outside marker: m0001..m0003
        // Marker messages: m0004..m0005
        String content = """
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                msg1
                <!-- message:end -->
                <!-- message:start id=m0002 role=user type=chat_user created=2025-01-01T00:00:01Z -->
                msg2
                <!-- message:end -->
                <!-- message:start id=m0003 role=user type=chat_user created=2025-01-01T00:00:02Z -->
                msg3
                <!-- message:end -->

                ## 二、LLM 上下文记录

                <!-- BRANCH_MESSAGES START -->
                <!-- message:start id=m0004 role=assistant type=chat_response created=2025-01-01T00:00:03Z -->
                msg4
                <!-- message:end -->
                <!-- message:start id=m0005 role=assistant type=chat_response created=2025-01-01T00:00:04Z -->
                msg5
                <!-- message:end -->
                <!-- BRANCH_MESSAGES END -->
                """;
        Files.writeString(branchFile, content);

        String nextId = store.nextMessageId("branch.b0000-start");
        assertEquals("m0006", nextId, "should count legacy + marker: max is m0005 → next is m0006");
    }

    @Test
    @DisplayName("nextMessageId 去重后计数（marker 覆盖旧 id）")
    void nextMessageIdDeduplicated() throws Exception {
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");

        // Same id outside and inside marker
        String content = """
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:00Z -->
                old version
                <!-- message:end -->

                ## 二、LLM 上下文记录

                <!-- BRANCH_MESSAGES START -->
                <!-- message:start id=m0001 role=user type=chat_user created=2025-01-01T00:00:01Z -->
                new version
                <!-- message:end -->
                <!-- message:start id=m0002 role=assistant type=chat_response created=2025-01-01T00:00:02Z -->
                msg2
                <!-- message:end -->
                <!-- BRANCH_MESSAGES END -->
                """;
        Files.writeString(branchFile, content);

        String nextId = store.nextMessageId("branch.b0000-start");
        assertEquals("m0003", nextId, "deduped max is m0002 → next is m0003");
    }
}
