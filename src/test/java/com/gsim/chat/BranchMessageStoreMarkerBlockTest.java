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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchMessageStore Marker Block")
class BranchMessageStoreMarkerBlockTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Nested
    @DisplayName("写入 marker block 内")
    class WritesInsideMarkerBlock {
        @Test
        @DisplayName("appendMessage 写入到 <!-- BRANCH_MESSAGES START --> ... <!-- BRANCH_MESSAGES END --> 内")
        void writesInsideMarkerBlock() throws Exception {
            String branchId = dm.getActiveBranch();
            Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                    .resolve("b0000-start.md");

            // First append a message
            var msg = BranchMessage.create("m0001", "user", "chat_user", "测试消息");
            store.appendMessage(branchId, msg);

            String content = Files.readString(branchFile);
            assertTrue(content.contains("<!-- BRANCH_MESSAGES START -->"),
                    "should have marker start");
            assertTrue(content.contains("<!-- BRANCH_MESSAGES END -->"),
                    "should have marker end");

            // Second append
            var msg2 = BranchMessage.create("m0002", "assistant", "chat_response", "回复消息");
            store.appendMessage(branchId, msg2);

            content = Files.readString(branchFile);
            // Both messages should be inside the marker block
            int start = content.indexOf("<!-- BRANCH_MESSAGES START -->");
            int end = content.indexOf("<!-- BRANCH_MESSAGES END -->");
            assertTrue(start >= 0 && end > start);

            String blockContent = content.substring(start, end);
            assertTrue(blockContent.contains("测试消息"));
            assertTrue(blockContent.contains("回复消息"));
        }

        @Test
        @DisplayName("appendMessage 不追加到 EOF")
        void doesNotAppendToEof() throws Exception {
            String branchId = dm.getActiveBranch();
            Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                    .resolve("b0000-start.md");

            String originalContent = Files.readString(branchFile);
            String originalEnding = originalContent.substring(Math.max(0, originalContent.length() - 50));

            var msg = BranchMessage.create("m0001", "user", "chat_user", "测试");
            store.appendMessage(branchId, msg);

            String newContent = Files.readString(branchFile);
            String newEnding = newContent.substring(Math.max(0, newContent.length() - 50));

            // The end of file should be the marker end, not the message
            assertTrue(newContent.contains("<!-- BRANCH_MESSAGES END -->"));
            // Message content should be inside marker block, not appended to very end
            int msgPos = newContent.indexOf("测试");
            int markerEndPos = newContent.lastIndexOf("<!-- BRANCH_MESSAGES END -->");
            assertTrue(msgPos < markerEndPos, "message should be before marker end");
        }
    }

    @Nested
    @DisplayName("迁移旧文件（缺少 marker block）")
    class MigratesLegacy {
        @Test
        @DisplayName("已有 ## 二、LLM 上下文记录 但无 marker block → 创建 marker block")
        void createsMarkerBlockUnderLlmSection() throws Exception {
            String branchId = dm.getActiveBranch();
            Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                    .resolve("b0000-start.md");

            // Replace with a legacy file that has the section but no markers
            String legacyContent = """
                    ## 一、本节点输入
                    世界初始化。

                    ## 二、LLM 上下文记录

                    ### user
                    旧的消息格式。

                    ## 三、推演结果
                    无。
                    """;
            Files.writeString(branchFile, legacyContent);

            var msg = BranchMessage.create("m0001", "user", "chat_user", "新消息");
            store.appendMessage(branchId, msg);

            String content = Files.readString(branchFile);
            assertTrue(content.contains("<!-- BRANCH_MESSAGES START -->"),
                    "should create marker start");
            assertTrue(content.contains("<!-- BRANCH_MESSAGES END -->"),
                    "should create marker end");
            assertTrue(content.contains("新消息"));
        }
    }

    @Nested
    @DisplayName("保留其他 section")
    class PreservesSections {
        @Test
        @DisplayName("写入不破坏 ## 九、等其他 section")
        void preservesSectionNine() throws Exception {
            String branchId = dm.getActiveBranch();
            Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                    .resolve("b0000-start.md");

            String contentWithNine = """
                    ## 一、本节点输入
                    测试。

                    ## 二、LLM 上下文记录

                    ## 三、推演结果
                    无。

                    ## 九、其他
                    额外信息。
                    """;
            Files.writeString(branchFile, contentWithNine);

            var msg = BranchMessage.create("m0001", "user", "chat_user", "测试消息");
            store.appendMessage(branchId, msg);

            String content = Files.readString(branchFile);
            assertTrue(content.contains("## 九、其他"), "section 9 should be preserved");
            assertTrue(content.contains("额外信息"), "section 9 content should be preserved");
            assertTrue(content.contains("<!-- BRANCH_MESSAGES START -->"));
        }
    }

    @Nested
    @DisplayName("listMessages 优先 marker block")
    class ListPrefersMarkerBlock {
        @Test
        @DisplayName("有 marker block 时 listMessages 从 marker 内读取")
        void readsFromMarkerBlock() throws Exception {
            String branchId = dm.getActiveBranch();

            var msg1 = BranchMessage.create("m0001", "user", "chat_user", "消息一");
            store.appendMessage(branchId, msg1);

            var msg2 = BranchMessage.create("m0002", "assistant", "chat_response", "消息二");
            store.appendMessage(branchId, msg2);

            List<BranchMessage> msgs = store.listMessages(branchId);
            assertFalse(msgs.isEmpty());
            assertTrue(msgs.stream().anyMatch(m -> "消息一".equals(m.content())));
            assertTrue(msgs.stream().anyMatch(m -> "消息二".equals(m.content())));
        }
    }
}
