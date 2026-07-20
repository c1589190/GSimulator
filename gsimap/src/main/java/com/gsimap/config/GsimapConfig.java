package com.gsimap.config;

import java.nio.file.Path;

/**
 * Gsimap configuration — loaded from system properties and defaults.
 *
 * @param worldsDir path to the GSim worlds directory
 * @param importDir path to import/docs directory, may be null
 * @param httpPort  port for the HTTP server
 */
public record GsimapConfig(Path worldsDir, Path importDir, int httpPort) {

    /**
     * Loads configuration from system properties and environment variables.
     *
     * @return resolved configuration instance
     */
    public static GsimapConfig load() {
        String worldsDir = System.getProperty(
                "gsimap.worldsDir", System.getenv().getOrDefault("GSIMAP_WORLDS_DIR", "worlds"));
        String importDir = System.getProperty(
                "gsimap.importDir", System.getenv().getOrDefault("GSIMAP_IMPORT_DIR", null));
        int port = Integer.parseInt(
                System.getProperty("gsimap.port", System.getenv().getOrDefault("GSIMAP_PORT", "8711")));

        return new GsimapConfig(
                Path.of(worldsDir), importDir != null ? Path.of(importDir) : null, port);
    }
}
