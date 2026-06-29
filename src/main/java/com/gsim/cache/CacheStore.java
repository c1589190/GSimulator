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

    /** Caches root directory — peer to worldsDir (e.g. ./caches/ instead of ./worlds/{id}/caches/). */
    private static volatile Path cachesRoot = null;

    /** Set the caches root directory explicitly (called at startup). */
    public static void setCachesRoot(Path root) {
        cachesRoot = root;
    }

    /** Caches root directory — flat, no world subdirectories. */
    public static Path cachesDir(Path worldsDir) {
        return cachesRoot != null ? cachesRoot : worldsDir.resolveSibling("caches");
    }

    /** Full path to a specific cache file (flat: caches/{sessionId}). */
    public static Path cacheFile(Path worldsDir, String sessionId) {
        return cachesDir(worldsDir).resolve(sessionId);
    }

    /** Load a cache session from disk. Returns null if not found. */
    public static CacheSession load(Path worldsDir, String sessionId) {
        Path file = cacheFile(worldsDir, sessionId);
        if (!Files.exists(file)) return null;
        try {
            return JsonUtils.fromJson(Files.readString(file), CacheSession.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load cache session: " + sessionId, e);
        }
    }

    /** Save a cache session to disk. Creates caches/ dir if needed. */
    public static void save(Path worldsDir, CacheSession session) {
        Path file = cacheFile(worldsDir, session.sessionId());
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
        save(worldsDir, session);
        return session;
    }

    /** Append messages and persist. Used for streaming incremental save. */
    public static void appendAndSave(Path worldsDir,
                                      CacheSession session, Map<String, Object> message) {
        session.addMessage(message);
        save(worldsDir, session);
    }
}
