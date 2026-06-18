package com.gsim.root;

import com.gsim.data.DataManager;
import com.gsim.player.PlayerProfileManager;
import com.gsim.player.PlayerProfileUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies PlayerProfileManager is root-aware after root switch.
 */
class PlayerProfileRootAwareTest {

    private Path dataRoot;
    private DataManager dm;
    private PlayerProfileManager pm;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        dataRoot = tmp.resolve("data");
        dm = new DataManager(dataRoot);
        // Create two roots
        dm.createRoot("root-a", "## World A\n\nRoot A content.");
        dm.createRoot("root-b", "## World B\n\nRoot B content.");
        pm = new PlayerProfileManager(dm);
    }

    @Test
    void profileUpdateUsesCurrentRootAfterSwitch() throws Exception {
        dm.switchWorld("root-a");
        pm.updatePlayerField("Doctor", "identity", "rootA doctor");
        String playersA = dm.readPlayers();
        assertTrue(playersA.contains("rootA doctor"), "root-a players.md should contain rootA doctor");

        dm.switchWorld("root-b");
        pm.updatePlayerField("Doctor", "identity", "rootB doctor");
        String playersB = dm.readPlayers();
        assertTrue(playersB.contains("rootB doctor"), "root-b players.md should contain rootB doctor");

        // Switch back to root-a — should see original
        dm.switchWorld("root-a");
        String playersA2 = dm.readPlayers();
        assertTrue(playersA2.contains("rootA doctor"), "root-a should still have rootA doctor");
        assertFalse(playersA2.contains("rootB doctor"), "root-a should NOT have rootB doctor");
    }

    @Test
    void profileNoteUsesCurrentRootAfterSwitch() throws Exception {
        dm.switchWorld("root-a");
        pm.appendPlayerNote("Amiya", "Leader of Rhodes Island, root-a");
        assertTrue(dm.readPlayers().contains("root-a"));

        dm.switchWorld("root-b");
        pm.appendPlayerNote("Amiya", "Cautious operator, root-b");
        assertTrue(dm.readPlayers().contains("root-b"));

        dm.switchWorld("root-a");
        assertFalse(dm.readPlayers().contains("root-b"), "root-a should not leak root-b data");
    }

    @Test
    void listPlayersReturnsCurrentRootPlayers() throws Exception {
        dm.switchWorld("root-a");
        int countBeforeA = pm.listPlayers().size();
        pm.updatePlayerField("UniquePlayerA", "role", "root-a role");
        assertTrue(pm.exists("UniquePlayerA"), "UniquePlayerA should exist in root-a");

        dm.switchWorld("root-b");
        // root-b should NOT have UniquePlayerA
        assertFalse(pm.exists("UniquePlayerA"), "root-b should not have UniquePlayerA from root-a");

        pm.updatePlayerField("UniquePlayerB", "role", "root-b role");
        assertTrue(pm.exists("UniquePlayerB"), "UniquePlayerB should exist in root-b");

        dm.switchWorld("root-a");
        assertTrue(pm.exists("UniquePlayerA"), "root-a should still have UniquePlayerA");
        assertFalse(pm.exists("UniquePlayerB"), "root-a should NOT have UniquePlayerB from root-b");
    }

    @Test
    void noActiveRootReturnsEmptyGracefully() throws Exception {
        // dm has no active root after construction without init()
        DataManager emptyDm = new DataManager(tmpDir());
        PlayerProfileManager emptyPm = new PlayerProfileManager(emptyDm);
        // When no active root, worldDir() throws. But listPlayers calls ensurePlayersFile() which calls getPlayersPath() -> worldDir().
        // Should get IllegalStateException, not NPE.
        assertThrows(IllegalStateException.class, () -> emptyPm.listPlayers(),
                "Should throw when no active root, not NPE");
    }

    private Path tmpDir() throws Exception {
        Path p = Files.createTempDirectory("gsim-empty");
        p.toFile().deleteOnExit();
        return p;
    }
}
