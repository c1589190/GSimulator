package com.gsim.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ImportApiHandler does not use FakeChromaClient.
 */
class ImportApiLocalDoesNotUseFakeChromaTest {

    @Test
    void importApiHandlerSourceDoesNotContainFakeChroma() throws Exception {
        Path f = Path.of("src/main/java/com/gsim/api/handlers/ImportApiHandler.java");
        if (!Files.exists(f)) return; // not running from project root
        String content = Files.readString(f);
        assertFalse(content.contains("FakeChromaClient"),
                "ImportApiHandler.java must not import or use FakeChromaClient");
    }

    @Test
    void gsimulatorApplicationSourceDoesNotContainFakeChroma() throws Exception {
        Path f = Path.of("src/main/java/com/gsim/app/GSimulatorApplication.java");
        if (!Files.exists(f)) return;
        String content = Files.readString(f);
        assertFalse(content.contains("FakeChromaClient"),
                "GSimulatorApplication.java must not import or use FakeChromaClient");
    }
}
