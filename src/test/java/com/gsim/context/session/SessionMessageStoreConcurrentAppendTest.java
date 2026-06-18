package com.gsim.context.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SessionMessageStore concurrent append safety.
 */
class SessionMessageStoreConcurrentAppendTest {

    private Path worldDir;
    private SessionMessageStore store;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) {
        worldDir = tmpDir.resolve("worlds").resolve("default");
        store = new SessionMessageStore(worldDir, "test-session-1");
    }

    @Test
    void concurrentAppend100MessagesProducesValidJsonl() throws Exception {
        int threads = 4;
        int messagesPerThread = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Exception> errors = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < messagesPerThread; i++) {
                        var msg = new SessionMessage(
                                "msg-t" + threadId + "-" + i,
                                "test-session-1",
                                "branch.b0000-start",
                                "user",
                                "chat_user",
                                "Message from thread " + threadId + " index " + i,
                                Instant.now(), null);
                        store.append(msg);
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete");
        assertTrue(errors.isEmpty(), "No errors: " + errors);

        // Verify all messages are in memory
        assertEquals(threads * messagesPerThread, store.count(),
                "All messages should be appended in memory");

        // Verify JSONL file is valid
        Path file = worldDir.resolve("context").resolve("session_messages")
                .resolve("test-session-1.jsonl");
        assertTrue(Files.exists(file), "JSONL file should exist");

        List<String> lines = Files.readAllLines(file);
        assertEquals(threads * messagesPerThread, lines.size(),
                "JSONL should have exactly " + (threads * messagesPerThread) + " lines");

        // Each line must be parseable JSON
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            assertFalse(line.isBlank(), "Line " + i + " should not be blank");
            try {
                com.gsim.util.JsonUtils.fromJson(line, SessionMessage.class);
            } catch (Exception e) {
                fail("Line " + i + " is not valid JSON: " + line + " — " + e.getMessage());
            }
        }
    }
}
