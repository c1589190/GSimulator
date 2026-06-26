package com.gsim.cache;

import com.gsim.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes CacheSession JSON files.
 */
public final class CacheStore {

    private CacheStore() {}

    /** Caches directory for a world. */
    public static Path cachesDir(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("caches");
    }

    /** Full path to a specific cache file. */
    public static Path cacheFile(Path worldsDir, String worldId, String sessionId) {
        return cachesDir(worldsDir, worldId).resolve(sessionId);
    }

    /** Load a cache session from disk. Returns null if not found. */
    public static CacheSession load(Path worldsDir, String worldId, String sessionId) {
        Path file = cacheFile(worldsDir, worldId, sessionId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), CacheSession.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load cache session: " + sessionId, e);
        }
    }

    /** Save a cache session to disk. Creates caches/ dir if needed. */
    public static void save(Path worldsDir, String worldId, CacheSession session) {
        Path file = cacheFile(worldsDir, worldId, session.sessionId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonUtils.toJson(session));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save cache session: " + session.sessionId(), e);
        }
    }

    /** Create a new empty session with timestamp-based sessionId. */
    public static CacheSession createNew(Path worldsDir, String worldId,
                                          String agentName, String nodeId) {
        String now = Instant.now().toString();
        // use agent-timestamp format for the session ID
        String sessionId = agentName + "_" + now.replace(":", "-").substring(0, 19) + ".json";
        String finalNow = now;

        CacheSession session = new CacheSession(agentName, worldId, nodeId, sessionId, finalNow);
        save(worldsDir, worldId, session);
        return session;
    }

    /** Append messages and persist. Used for streaming incremental save. */
    public static void appendAndSave(Path worldsDir, String worldId,
                                      CacheSession session, Map<String, Object> message) {
        session.addMessage(message);
        save(worldsDir, worldId, session);
    }
}
