package com.gsim.map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.*;

/**
 * Complete hex map data for a world node.
 * Stored as worlds/{id}/nodes/{nid}_map.json.
 *
 * <p>Coordinate system: axial (q, r), pointy-top by default.
 * Hex keys use format "q_r" (e.g., "0_0", "1_-2").
 */
@JsonDeserialize
public record MapData(
    @JsonProperty("gridSize") int gridSize,
    @JsonProperty("hexOrientation") boolean hexOrientation,
    @JsonProperty("hexes") Map<String, HexCell> hexes,
    @JsonProperty("provinces") Map<String, Province> provinces,
    @JsonProperty("cities") Map<String, City> cities,
    @JsonProperty("rivers") List<River> rivers,
    @JsonProperty("roads") List<Road> roads
) {
    public MapData {
        if (gridSize < 1 || gridSize > 1000) throw new IllegalArgumentException("gridSize must be 1-1000, got: " + gridSize);
        if (hexes == null) hexes = new LinkedHashMap<>();
        if (provinces == null) provinces = new LinkedHashMap<>();
        if (cities == null) cities = new LinkedHashMap<>();
        if (rivers == null) rivers = List.of();
        if (roads == null) roads = List.of();
    }

    /** Default map: empty, gridSize=30, pointy-top. */
    public static MapData empty() {
        return new MapData(30, false, Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    /** Hex key format: "q_r" */
    public static String hexKey(int q, int r) {
        return q + "_" + r;
    }

    /** Parse hex key to (q, r). */
    public static int[] parseHexKey(String key) {
        String[] parts = key.split("_");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    // ── HexCell ──────────────────────────────────────────

    @JsonDeserialize
    public record HexCell(
        @JsonProperty("color") String color,
        @JsonProperty("terrain") String terrain,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("symbolColor") String symbolColor
    ) {
        public HexCell {
            if (color == null) color = "#808080";
            if (terrain == null) terrain = "unknown";
        }

        public static HexCell of(String color) { return new HexCell(color, "unknown", null, null); }
        public static HexCell of(String color, String terrain) { return new HexCell(color, terrain, null, null); }
    }

    // ── Province ─────────────────────────────────────────

    @JsonDeserialize
    public record Province(
        @JsonProperty("hexes") List<String> hexes,
        @JsonProperty("color") String color
    ) {
        public Province {
            if (hexes == null) hexes = List.of();
            if (color == null) color = "#FF0000";
        }
    }

    // ── City ─────────────────────────────────────────────

    @JsonDeserialize
    public record City(
        @JsonProperty("q") int q,
        @JsonProperty("r") int r,
        @JsonProperty("name") String name
    ) {}

    // ── River ────────────────────────────────────────────

    @JsonDeserialize
    public record River(
        @JsonProperty("points") List<Point> points,
        @JsonProperty("width") int width,
        @JsonProperty("color") String color,
        @JsonProperty("taper") boolean taper
    ) {
        @JsonDeserialize
        public record Point(@JsonProperty("x") double x, @JsonProperty("y") double y) {}
    }

    // ── Road ─────────────────────────────────────────────

    @JsonDeserialize
    public record Road(
        @JsonProperty("points") List<River.Point> points,
        @JsonProperty("width") int width,
        @JsonProperty("color") String color
    ) {}
}
