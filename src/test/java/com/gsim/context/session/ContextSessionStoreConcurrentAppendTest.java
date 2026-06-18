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
 * Tests ContextSessionStore concurrent save safety.
 */
class ContextSessionStoreConcurrentAppendTest {

    private ContextSessionStore sessionStore;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) {
        Path worldDir = tmpDir.resolve("worlds").resolve("default");
        sessionStore = new ContextSessionStore(worldDir);
    }

    @Test
    void concurrentSave20SessionsProducesValidJsonl() throws Exception {
        int threads = 4;
        int sessionsPerThread = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Exception> errors = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < sessionsPerThread; i++) {
                        var session = new ContextSession(
                                "api-t" + threadId,
                                "sess-t" + threadId + "-" + i,
                                "branch.b0000-start",
                                "branch.b0000-start",
                                "base-001",
                                Instant.now(), Instant.now(),
                                ContextSessionStatus.ACTIVE, null);
                        sessionStore.save(session);
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "No errors: " + errors);

        // Verify all sessions can be loaded
        List<ContextSession> all = sessionStore.loadAll();
        assertEquals(threads * sessionsPerThread, all.size(),
                "Should load all saved sessions");

        // Verify JSONL file is valid
        Path file = sessionStore.getSessionsFile();
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                com.gsim.util.JsonUtils.fromJson(line, ContextSession.class);
            } catch (Exception e) {
                fail("Invalid JSONL line: " + line + " — " + e.getMessage());
            }
        }
    }
}
