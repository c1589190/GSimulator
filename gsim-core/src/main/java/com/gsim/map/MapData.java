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
    @JsonProperty("roads") List<Road> roads,
    @JsonProperty("terrainTypes") Map<String, TerrainType> terrainTypes
) {
    public MapData {
        if (gridSize < 1 || gridSize > 1000) throw new IllegalArgumentException("gridSize must be 1-1000, got: " + gridSize);
        if (hexes == null) hexes = new LinkedHashMap<>();
        if (provinces == null) provinces = new LinkedHashMap<>();
        if (cities == null) cities = new LinkedHashMap<>();
        if (rivers == null) rivers = List.of();
        if (roads == null) roads = List.of();
        if (terrainTypes == null || terrainTypes.isEmpty()) terrainTypes = TerrainType.defaults();
    }

    /** Default map: empty, gridSize=30, pointy-top, standard terrain types. */
    public static MapData empty() {
        return new MapData(30, false, Map.of(), Map.of(), Map.of(), List.of(), List.of(), TerrainType.defaults());
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

    /** Look up terrain type by name. Returns null if not found. */
    public TerrainType terrain(String name) {
        return terrainTypes.get(name);
    }

    /** Resolve terrain name → color. Falls back to #808080. */
    public String terrainColor(String name) {
        TerrainType t = terrainTypes.get(name);
        return t != null ? t.color() : "#808080";
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

    // ── TerrainType ──────────────────────────────────────

    @JsonDeserialize
    public record TerrainType(
        @JsonProperty("name") String name,
        @JsonProperty("color") String color,
        @JsonProperty("food") double food,
        @JsonProperty("gold") double gold,
        @JsonProperty("stone") double stone,
        @JsonProperty("moveCost") int moveCost,
        @JsonProperty("description") String description
    ) {
        public TerrainType {
            if (color == null) color = "#808080";
            if (moveCost < 1) moveCost = 1;
            if (description == null) description = "";
        }

        /** Standard terrain types for grand-strategy wargames. */
        public static Map<String, TerrainType> defaults() {
            Map<String, TerrainType> m = new LinkedHashMap<>();
            m.put("plains",   new TerrainType("plains",   "#6CC261", 3, 1, 1, 1, "Flat open land, easy to traverse and farm."));
            m.put("forest",   new TerrainType("forest",   "#228B22", 2, 1, 3, 2, "Dense woodland, good for lumber and ambushes."));
            m.put("mountain", new TerrainType("mountain", "#808080", 0, 2, 5, 3, "Rocky highlands, rich in minerals but hard to cross."));
            m.put("water",    new TerrainType("water",    "#3295D2", 1, 0, 0, 99, "Impassable water — requires ships or bridges."));
            m.put("desert",   new TerrainType("desert",   "#DDC88D", 0, 1, 2, 2, "Arid wasteland, sparse resources and slow travel."));
            m.put("swamp",    new TerrainType("swamp",    "#556B2F", 2, 0, 1, 3, "Marshy wetland, difficult to march through."));
            m.put("tundra",   new TerrainType("tundra",   "#B0C4DE", 1, 0, 1, 2, "Cold frozen plains, limited agriculture."));
            m.put("hills",    new TerrainType("hills",    "#A0522D", 1, 2, 3, 2, "Rolling hills, defensible and mineral-rich."));
            return m;
        }
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
