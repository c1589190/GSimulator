package com.gsimap.config;

import java.nio.file.Path;

/**
 * Gsimap configuration — loaded from system properties and defaults.
 *
 * @param worldsDir path to the GSim worlds directory
 * @param importDir path to import/docs directory, may be null
 * @param httpPort  port for the HTTP server
 * @param gsimPort  port for the embedded GSim HTTP API
 * @param httpMode  whether the HTTP server is enabled
 * @param mcpMode   whether the MCP stdio server is enabled
 */
public record GsimapConfig(Path worldsDir, Path importDir, int httpPort, int gsimPort,
        boolean httpMode, boolean mcpMode) {

    /**
     * Loads configuration from system properties and environment variables.
     *
     * @return resolved configuration instance
     */
    public static GsimapConfig load() {
        String worldsDir = System.getProperty(
                "gsimap.worldsDir", System.getenv().getOrDefault("GSIMAP_WORLDS_DIR", "worlds"));
        String importDir =
                System.getProperty("gsimap.importDir", System.getenv().getOrDefault("GSIMAP_IMPORT_DIR", null));
        int port = Integer.parseInt(
                System.getProperty("gsimap.port", System.getenv().getOrDefault("GSIMAP_PORT", "8711")));
        int gsimPort = Integer.parseInt(System.getProperty(
                "gsimap.gsimPort", System.getenv().getOrDefault("GSIMAP_GSIM_PORT", "8709")));
        boolean httpMode = !Boolean.parseBoolean(System.getProperty("gsimap.mcpOnly", "false"));
        boolean mcpMode = !Boolean.parseBoolean(System.getProperty("gsimap.httpOnly", "false"));

        return new GsimapConfig(
                Path.of(worldsDir), importDir != null ? Path.of(importDir) : null, port, gsimPort, httpMode, mcpMode);
    }
}
