package com.gsim.root;

import com.gsim.data.DataManager;
import com.gsim.root.tool.RootToolFactory;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies root tools' permission gates.
 */
class RootToolsPermissionTest {

    private DataManager dm;
    private RootToolFactory factory;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        Path dataRoot = tmp.resolve("data");
        dm = new DataManager(dataRoot);
        dm.createRoot("test-root", "## Test\n\nTest world.");
        dm.switchWorld("test-root");
        factory = new RootToolFactory(dm, id -> {});
    }

    // ===== root_create =====

    @Test
    void rootCreateAllowedAtRootBranch() {
        // We are at branch.b0000-start (just created world)
        assertTrue(dm.isAtRootBranch());
        var call = new ToolCall("root_create", Map.of("worldContent", "New world content"));
        var result = executeTool("root_create", call);
        assertTrue(result.success(), "root_create should succeed at root branch: " + result.error());
    }

    @Test
    void rootCreateDeniedAtNonRootBranch() throws Exception {
        dm.createBranch("child", "Child branch", "2099-01-01");
        assertFalse(dm.isAtRootBranch());
        var call = new ToolCall("root_create", Map.of("worldContent", "New world content"));
        var result = executeTool("root_create", call);
        assertFalse(result.success(), "root_create should fail at non-root branch");
        assertTrue(result.error().contains("NOT_AT_ROOT_BRANCH"), "Error should mention NOT_AT_ROOT_BRANCH: " + result.error());
    }

    // ===== root_world_update =====

    @Test
    void rootWorldUpdateAllowedAtRootBranch() {
        assertTrue(dm.isAtRootBranch());
        var call = new ToolCall("root_world_update", Map.of("content", "# Updated world"));
        var result = executeTool("root_world_update", call);
        assertTrue(result.success(), "root_world_update should succeed at root: " + result.error());
    }

    @Test
    void rootWorldUpdateDeniedAtNonRootBranch() throws Exception {
        dm.createBranch("child2", "Child", "2099-01-01");
        var call = new ToolCall("root_world_update", Map.of("content", "# Updated"));
        var result = executeTool("root_world_update", call);
        assertFalse(result.success());
        assertTrue(result.error().contains("NOT_AT_ROOT_BRANCH"));
    }

    // ===== root_entities_update =====

    @Test
    void rootEntitiesUpdateDeniedAtNonRootBranch() throws Exception {
        dm.createBranch("child3", "Child", "2099-01-01");
        var call = new ToolCall("root_entities_update", Map.of("content", "# Updated"));
        var result = executeTool("root_entities_update", call);
        assertFalse(result.success());
        assertTrue(result.error().contains("NOT_AT_ROOT_BRANCH"));
    }

    // ===== root_rules_update =====

    @Test
    void rootRulesUpdateDeniedAtNonRootBranch() throws Exception {
        dm.createBranch("child4", "Child", "2099-01-01");
        var call = new ToolCall("root_rules_update", Map.of("content", "# Updated"));
        var result = executeTool("root_rules_update", call);
        assertFalse(result.success());
        assertTrue(result.error().contains("NOT_AT_ROOT_BRANCH"));
    }

    // ===== root_initial_info_update =====

    @Test
    void rootInitialInfoUpdateDeniedAtNonRootBranch() throws Exception {
        dm.createBranch("child5", "Child", "2099-01-01");
        var call = new ToolCall("root_initial_info_update", Map.of("content", "updated info"));
        var result = executeTool("root_initial_info_update", call);
        assertFalse(result.success());
        assertTrue(result.error().contains("NOT_AT_ROOT_BRANCH"));
    }

    // ===== root_status =====

    @Test
    void rootStatusAllowedOnAnyBranch() throws Exception {
        var call = new ToolCall("root_status", Map.of());
        var result = executeTool("root_status", call);
        assertTrue(result.success(), "root_status should succeed at root: " + result.error());

        dm.createBranch("child6", "Child", "2099-01-01");
        var result2 = executeTool("root_status", call);
        assertTrue(result2.success(), "root_status should succeed at non-root: " + result2.error());
    }

    // ===== helper =====

    private ToolResult executeTool(String name, ToolCall call) {
        for (var tool : factory.createAll()) {
            if (tool.name().equals(name)) {
                return tool.execute(call);
            }
        }
        return ToolResult.fail(name, "TOOL_NOT_FOUND");
    }
}
