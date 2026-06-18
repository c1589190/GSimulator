package com.gsim.root;

import com.gsim.data.DataManager;
import com.gsim.interaction.commands.RootCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.app.AppConfig;
import com.gsim.interaction.InteractionContext;
import com.gsim.knowledge.scope.ScopedKnowledgeStoreFactory;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.campaign.TurnService;
import com.gsim.storage.DataPaths;
import com.gsim.tool.ToolRegistry;
import com.gsim.util.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RootCommandTest {
    private DataManager dm;
    private RootCommand cmd;
    private AtomicReference<String> lastChanged;
    private InteractionSession session;

    @BeforeEach
    void setUp(@TempDir Path tmpDir) throws Exception {
        Path dataRoot = tmpDir.resolve("data");
        dm = new DataManager(dataRoot);
        // Default world auto-created by constructor

        var factory = new ScopedKnowledgeStoreFactory(null);
        lastChanged = new AtomicReference<>();
        cmd = new RootCommand(dm, factory, lastChanged::set);

        AppConfig config = AppConfig.forTesting();
        DataPaths dp = new DataPaths(config);
        TimeProvider tp = new TimeProvider();
        CampaignService cs = new CampaignService(dp, tp);
        TurnService ts = new TurnService(dp, tp);
        PlayerActionService ps = new PlayerActionService(dp, tp);
        InteractionContext ctx = new InteractionContext();
        session = new InteractionSession(ctx, config, cs, ts, ps, new ToolRegistry(), null);
    }

    @Test
    void statusShowsActiveRoot() {
        InteractionResult r = cmd.execute(new String[]{"status"}, session);
        assertTrue(r.success(), "status should succeed: " + r.displayText());
        assertTrue(r.displayText().contains(dm.getActiveRootId()),
                "status should contain active root ID: " + r.displayText());
    }

    @Test
    void listShowsRoots() {
        InteractionResult r = cmd.execute(new String[]{"list"}, session);
        assertTrue(r.success(), "list should succeed: " + r.displayText());
        assertFalse(r.displayText().isEmpty());
    }

    @Test
    void createNewRoot() {
        InteractionResult r = cmd.execute(new String[]{"create", "test-world", "测试世界观内容"}, session);
        assertTrue(r.success(), r.message());
        assertNotNull(lastChanged.get(), "onRootChanged should be called");
        assertEquals("test-world", lastChanged.get());
    }

    @Test
    void createWithInvalidIdFails() {
        InteractionResult r = cmd.execute(new String[]{"create", "bad id!", "content"}, session);
        assertFalse(r.success());
        assertTrue(r.message().contains("只允许"));
    }

    @Test
    void createDuplicateFails() {
        cmd.execute(new String[]{"create", "dup-world", "content1"}, session);
        InteractionResult r = cmd.execute(new String[]{"create", "dup-world", "content2"}, session);
        assertFalse(r.success());
    }

    @Test
    void switchRoot() {
        cmd.execute(new String[]{"create", "world-a", "content A"}, session);
        cmd.execute(new String[]{"create", "world-b", "content B"}, session);
        lastChanged.set(null);
        InteractionResult r = cmd.execute(new String[]{"switch", "world-a"}, session);
        assertTrue(r.success(), r.message());
        assertEquals("world-a", lastChanged.get());
    }

    @Test
    void switchNonExistentFails() {
        InteractionResult r = cmd.execute(new String[]{"switch", "no-such-root"}, session);
        assertFalse(r.success());
    }

    @Test
    void deleteWithoutConfirmFails() {
        InteractionResult r = cmd.execute(new String[]{"delete", "some-root"}, session);
        assertFalse(r.success());
        assertTrue(r.message().contains("confirm"));
    }

    @Test
    void deleteActiveRootFails() {
        String active = dm.getActiveRootId();
        InteractionResult r = cmd.execute(new String[]{"delete", active, "--confirm", active}, session);
        assertFalse(r.success());
        assertTrue(r.message().contains("active root"));
    }

    @Test
    void deleteNonActiveRootSucceeds() throws Exception {
        // Create a new root, then switch back to original before deleting
        cmd.execute(new String[]{"create", "deletable", "content"}, session);
        String originalActive = dm.getActiveRootId(); // now "deletable"
        // Switch back to the default root
        dm.switchWorld("default");
        lastChanged.set(null);

        InteractionResult r = cmd.execute(new String[]{"delete", "deletable", "--confirm", "deletable"}, session);
        assertTrue(r.success(), "delete should succeed: " + r.message());
    }

    @Test
    void unknownSubCommand() {
        InteractionResult r = cmd.execute(new String[]{"unknown"}, session);
        assertFalse(r.success());
    }
}
