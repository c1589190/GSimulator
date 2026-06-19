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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BranchMessageStore — 并发 append 不丢消息")
class BranchMessageStoreConcurrentAppendDoesNotLoseMessagesTest {

    @TempDir Path tempDir;
    private DataManager dm;
    private BranchMessageStore store;

    @BeforeEach
    void setUp() throws Exception {
        dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        store = new BranchMessageStore(dm, tempDir);
    }

    @Test
    @DisplayName("多线程并发 append 50 条消息，listMessages 数量正确且 id 不重复")
    void concurrentAppendDoesNotLoseMessages() throws Exception {
        int numThreads = 5;
        int msgsPerThread = 10;
        int totalMsgs = numThreads * msgsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger counter = new AtomicInteger(0); // 全局 id 分配器

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < msgsPerThread; i++) {
                        int idx = counter.incrementAndGet();
                        String msgId = String.format("m%04d", idx);
                        BranchMessage msg = new BranchMessage(msgId, "user", "chat_user", null,
                                Instant.now(), "concurrent message " + idx);
                        store.appendMessage("branch.b0000-start", msg);
                    }
                } catch (Exception e) {
                    fail("Concurrent append should not throw: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "executor should finish in time");

        // 验证消息数量和 id 唯一性
        List<BranchMessage> msgs = store.listMessages("branch.b0000-start");
        assertEquals(totalMsgs, msgs.size(),
                "should have all " + totalMsgs + " messages, got " + msgs.size());

        Set<String> ids = new HashSet<>();
        for (BranchMessage m : msgs) {
            assertTrue(ids.add(m.id()), "duplicate id: " + m.id());
        }
    }

    @Test
    @DisplayName("并发 append 后文件中消息都在 marker block 内")
    void concurrentAppendMessagesStayInMarker() throws Exception {
        int numThreads = 4;
        int msgsPerThread = 5;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger counter = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < msgsPerThread; i++) {
                        int idx = counter.incrementAndGet();
                        BranchMessage msg = new BranchMessage(String.format("m%04d", idx),
                                "user", "chat_user", null, Instant.now(),
                                "content " + idx);
                        store.appendMessage("branch.b0000-start", msg);
                    }
                } catch (Exception e) {
                    fail("Concurrent append should not throw: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        // 检查文件结构 — 只有一个 marker block
        Path branchFile = tempDir.resolve("worlds").resolve("default").resolve("branches")
                .resolve("b0000-start.md");
        String content = Files.readString(branchFile);
        int startCount = countOccurrences(content, "<!-- BRANCH_MESSAGES START -->");
        assertEquals(1, startCount, "should have exactly one marker block start");

        // 所有消息应在 marker block 内
        int markerStart = content.indexOf("<!-- BRANCH_MESSAGES START -->");
        int markerEnd = content.indexOf("<!-- BRANCH_MESSAGES END -->");
        assertTrue(markerStart > 0 && markerEnd > markerStart);
        String markerContent = content.substring(markerStart, markerEnd);

        int msgCount = 0;
        int idx = 0;
        while ((idx = markerContent.indexOf("<!-- message:start", idx)) != -1) {
            msgCount++;
            idx++;
        }
        assertEquals(numThreads * msgsPerThread, msgCount,
                "all messages should be inside marker block");
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
