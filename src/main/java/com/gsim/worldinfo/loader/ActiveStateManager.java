package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads/writes active.json inside a world directory.
 */
public final class ActiveStateManager {

    private ActiveStateManager() {}

    public record ActiveState(
        String nodeId,
        Map<String, String> sessions  // agentName → sessionFileName
    ) {
        public ActiveState {
            if (sessions == null) sessions = new LinkedHashMap<>();
        }
    }

    public static Path activeFile(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("active.json");
    }

    public static ActiveState load(Path worldsDir, String worldId) {
        Path file = activeFile(worldsDir, worldId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), ActiveState.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load active.json for world: " + worldId, e);
        }
    }

    public static void save(Path worldsDir, String worldId, ActiveState state) {
        Path file = activeFile(worldsDir, worldId);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(state));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save active.json", e);
        }
    }

    /** Convenience: get the Orchestrator session filename. */
    public static String orchestratorSession(ActiveState state) {
        return state != null ? state.sessions().get("Orchestrator") : null;
    }
}
