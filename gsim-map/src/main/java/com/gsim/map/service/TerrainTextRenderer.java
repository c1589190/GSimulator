package com.gsim.map.service;

import com.gsim.map.map.MapData;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a hex map region as a compact ASCII character grid.
 *
 * <p>Each terrain type is mapped to a single visible character. Flat-top hex
 * orientation is rendered with offset rows — odd-r rows are indented one
 * space to simulate the staggered hex grid layout.
 *
 * <h3>Character mapping</h3>
 * <table>
 *   <tr><th>Terrain</th><th>Char</th><th>Meaning</th></tr>
 *   <tr><td>water</td><td>{@code ~}</td><td>海洋/水域</td></tr>
 *   <tr><td>lowland</td><td>{@code ,}</td><td>低地</td></tr>
 *   <tr><td>plains</td><td>{@code .}</td><td>平原/山区</td></tr>
 *   <tr><td>hills</td><td>{@code ^}</td><td>丘陵</td></tr>
 *   <tr><td>mountain</td><td>{@code @}</td><td>山地/高山</td></tr>
 *   <tr><td>forest</td><td>{@code F}</td><td>森林</td></tr>
 *   <tr><td>swamp</td><td>{@code S}</td><td>沼泽</td></tr>
 *   <tr><td>desert</td><td>{@code D}</td><td>沙漠</td></tr>
 *   <tr><td>tundra</td><td>{@code T}</td><td>冻土</td></tr>
 * </table>
 *
 * <p>Unknown terrains render as {@code ?}.
 */
public final class TerrainTextRenderer {

    private TerrainTextRenderer() {}

    // ── Terrain → character mapping ──────────────────────────

    private static final Map<String, String> TERRAIN_CHAR = new LinkedHashMap<>();

    static {
        TERRAIN_CHAR.put("water", "~");
        TERRAIN_CHAR.put("lowland", ",");
        TERRAIN_CHAR.put("plains", ".");
        TERRAIN_CHAR.put("hills", "^");
        TERRAIN_CHAR.put("mountain", "@");
        TERRAIN_CHAR.put("forest", "F");
        TERRAIN_CHAR.put("swamp", "S");
        TERRAIN_CHAR.put("desert", "D");
        TERRAIN_CHAR.put("tundra", "T");
    }

    /**
     * Returns the display character for a terrain key, or {@code "?"} for
     * unknown types.
     */
    public static String charFor(String terrainKey) {
        return TERRAIN_CHAR.getOrDefault(terrainKey, "?");
    }

    /**
     * Returns the terrain→character legend as a compact multiline string.
     */
    public static String legend(Map<String, MapData.TerrainType> terrainTypes) {
        StringBuilder sb = new StringBuilder();
        for (var entry : TERRAIN_CHAR.entrySet()) {
            String key = entry.getKey();
            String ch = entry.getValue();
            String label = key;
            if (terrainTypes != null) {
                MapData.TerrainType tt = terrainTypes.get(key);
                if (tt != null) label = tt.name() + "(" + key + ")";
            }
            sb.append("  ").append(ch).append(" = ").append(label).append("\n");
        }
        return sb.toString();
    }

    // ── Rendering ─────────────────────────────────────────────

    /**
     * Renders a character-grid view of the hexes within {@code radius} steps
     * of center ({@code cq}, {@code cr}).
     *
     * @param map    the resolved map data (must not be null)
     * @param cq     center q coordinate
     * @param cr     center r coordinate
     * @param radius search radius in hex steps (1–10)
     * @return the rendered text block
     */
    public static String render(MapData map, int cq, int cr, int radius) {
        // Phase 1: collect hex terrain chars within radius
        // We store terrain char per (q,r); null means no hex at that position
        int diameter = 2 * radius + 1;
        String[][] grid = new String[diameter][diameter]; // [dr + radius][dq + radius]

        int minR = cr + radius;
        int maxR = cr - radius;

        for (int dr = -radius; dr <= radius; dr++) {
            int r = cr + dr;
            int ri = dr + radius; // array row index
            for (int dq = -radius; dq <= radius; dq++) {
                int ds = -dq - dr;
                // Hex distance check using cube coordinates
                if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > 2 * radius) continue;

                int q = cq + dq;
                int qi = dq + radius; // array col index
                MapData.HexCell cell = map.hexes().get(MapData.hexKey(q, r));
                if (cell != null) {
                    grid[ri][qi] = charFor(cell.terrain());
                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                }
            }
        }

        if (minR > maxR) return "(no hexes in range)";

        // Phase 2: build row-by-row output
        StringBuilder sb = new StringBuilder();
        for (int r = minR; r <= maxR; r++) {
            int dr = r - cr;
            int ri = dr + radius;

            // Find min/max q for this row within the radius
            int minQ = cq + radius;
            int maxQ = cq - radius;
            for (int dq = -radius; dq <= radius; dq++) {
                int ds = -dq - dr;
                if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > 2 * radius) continue;
                int q = cq + dq;
                if (q < minQ) minQ = q;
                if (q > maxQ) maxQ = q;
            }

            // Flat-top hex: odd-r rows are indented one space
            if ((r & 1) != 0) sb.append(' ');

            for (int q = minQ; q <= maxQ; q++) {
                int dq = q - cq;
                int qi = dq + radius;
                String ch = grid[ri][qi];
                if (ch != null) {
                    sb.append(ch);
                } else {
                    sb.append(' ');
                }
                // Space separator between hexes (skip after last)
                if (q < maxQ) sb.append(' ');
            }
            sb.append('\n');
        }

        return sb.toString();
    }
}
