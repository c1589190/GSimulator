package com.gsim.context.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gsim.context.BranchContextRenderer;
import com.gsim.data.DataManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ContextSessionManager close concurrent safety.
 */
class ContextSessionCloseConcurrentSafetyTest {

    private Path worldDir;
    private ContextSessionManager manager;
    private ContextSessionStore sessionStore;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) throws Exception {
        worldDir = tmpDir.resolve("worlds").resolve("default");
        Files.createDirectories(worldDir.resolve("context").resolve("session_messages"));
        Files.createDirectories(worldDir.resolve("context").resolve("base_contexts"));

        // Need a minimal DataManager for the manager
        Path dataRoot = tmpDir.resolve("data");
        DataManager dm = new DataManager(dataRoot);
        dm.init();

        sessionStore = new ContextSessionStore(worldDir);

        // Minimal BranchContextRenderer stub
        BranchContextRenderer renderer = new BranchContextRenderer(
                dm, dataRoot,
                new com.gsim.chat.BranchMessageStore(dm, dataRoot),
                null // branchAnalyzer can be null for this test
        );

        manager = new ContextSessionManager(sessionStore, renderer, dm, worldDir);
    }

    @Test
    void concurrentCloseAndSaveDoesNotCorruptFile() throws Exception {
        // Create several sessions first
        List<String> sessionIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var session = manager.createSession("api-test", "branch.b0000-start", "branch.b0000-start");
            sessionIds.add(session.sessionId());
        }

        int threads = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Exception> errors = new ArrayList<>();

        // Threads 0-1: close sessions
        for (int t = 0; t < 2; t++) {
            final int idx = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = idx; i < sessionIds.size(); i += 2) {
                        manager.closeSession(sessionIds.get(i), "test close " + i);
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        // Threads 2-3: create new sessions
        for (int t = 0; t < 2; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 5; i++) {
                        manager.createSession("api-new-" + Thread.currentThread().getName(),
                                "branch.b0000-start", "branch.b0000-start");
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "No errors: " + errors);

        // Verify JSONL is readable
        Path file = sessionStore.getSessionsFile();
        assertTrue(Files.exists(file));
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            try {
                com.gsim.util.JsonUtils.fromJson(line, ContextSession.class);
            } catch (Exception e) {
                fail("Corrupt JSONL line after concurrent access: " + line);
            }
        }
    }
}
