package com.gsim.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gsim.app.AppConfig;
import com.gsim.storage.DataPaths;
import com.gsim.util.TimeProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PlayerActionService thread safety.
 */
class PlayerActionServiceConcurrencyTest {

    private PlayerActionService service;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) {
        var config = AppConfig.forTesting();
        // Override data dir to use temp
        var dp = new DataPaths(config);
        service = new PlayerActionService(dp, new TimeProvider());
    }

    @Test
    void concurrentAddAndReadDoesNotThrow() throws Exception {
        int threads = 4;
        int actionsPerThread = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Exception> errors = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < actionsPerThread; i++) {
                        service.addAction("c1", "t1", "Player" + threadId, "Action " + i);
                        // Also read during writes
                        if (i % 5 == 0) {
                            service.getActions();
                            service.getActionCount();
                            service.hasActions();
                        }
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
        assertEquals(threads * actionsPerThread, service.getActionCount());
    }

    @Test
    void concurrentClearAndReadDoesNotThrow() throws Exception {
        // Populate actions
        for (int i = 0; i < 100; i++) {
            service.addAction("c1", "t1", "P", "Action " + i);
        }
        assertEquals(100, service.getActionCount());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Exception> errors = new ArrayList<>();

        // Thread 1: repeatedly clear
        new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 20; i++) {
                    service.clearActions();
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                errors.add(e);
            } finally {
                doneLatch.countDown();
            }
        }).start();

        // Thread 2: repeatedly read
        new Thread(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 100; i++) {
                    service.getActions();
                    service.getActionCount();
                }
            } catch (Exception e) {
                errors.add(e);
            } finally {
                doneLatch.countDown();
            }
        }).start();

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "No errors during concurrent clear/read: " + errors);
    }
}
