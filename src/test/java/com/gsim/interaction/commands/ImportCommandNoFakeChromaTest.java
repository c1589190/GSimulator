package com.gsim.interaction.commands;

import com.gsim.app.AppConfig;
import com.gsim.chroma.ChromaClient;
import com.gsim.importdata.ImportManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that ImportCommand does not silently succeed with FakeChromaClient.
 */
class ImportCommandNoFakeChromaTest {

    @Test
    void importCommandSourceDoesNotContainFakeChroma() throws Exception {
        Path f = Path.of("src/main/java/com/gsim/interaction/commands/ImportCommand.java");
        if (!Files.exists(f)) return;
        String content = Files.readString(f);
        assertFalse(content.contains("FakeChromaClient"),
                "ImportCommand.java must not import or use FakeChromaClient");
    }
}
