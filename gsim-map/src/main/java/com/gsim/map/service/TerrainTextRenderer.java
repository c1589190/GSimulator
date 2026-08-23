package com.gsim.map.service;

import com.gsim.map.map.MapData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ── Region rendering ─────────────────────────────────────

    /**
     * Result of a region-mode render: the rendered text grid, the
     * province→character map used for the legend, and the overlap detail
     * lines (one per hex shared by multiple rendered provinces).
     */
    public record RegionRenderResult(String text, Map<String, String> regionCharMap, List<String> overlapLines) {
        public RegionRenderResult {
            // Defensive copy + freeze (SpotBugs EI_EXPOSE_REP)
            if (regionCharMap == null) regionCharMap = Map.of();
            else regionCharMap = Map.copyOf(regionCharMap);
            if (overlapLines == null) overlapLines = List.of();
            else overlapLines = List.copyOf(overlapLines);
        }
    }

    /**
     * Builds the province→character map for region rendering, shared by
     * {@link #renderRegionsDetailed} and the legend so both agree on the
     * assigned characters.
     *
     * <p>Participating provinces are sorted by name and assigned characters
     * in order: {@code A}–{@code Z}, then {@code a}–{@code z}, then
     * {@code 1}–{@code 9}, then {@code 0} (62 characters maximum; provinces
     * beyond that are not rendered).
     *
     * @param tagFilter non-empty whitelist of province {@code tag()} values
     *     to render; null or empty renders all non-annexed provinces
     * @return an insertion-ordered map of province name → character
     */
    public static Map<String, String> buildRegionCharMap(
            MapData map, int cq, int cr, int radius, Set<String> tagFilter) {
        List<String> names = new ArrayList<>();
        for (var entry : map.provinces().entrySet()) {
            if (participatesInRegionRender(entry.getValue(), tagFilter)) {
                names.add(entry.getKey());
            }
        }
        names.sort(String::compareTo);
        Map<String, String> charMap = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String ch = regionCharForIndex(i);
            if (ch == null) break; // no more characters available
            charMap.put(names.get(i), ch);
        }
        return charMap;
    }

    private static boolean participatesInRegionRender(MapData.Province province, Set<String> tagFilter) {
        if (tagFilter != null && !tagFilter.isEmpty()) {
            return tagFilter.contains(province.tag());
        }
        return province.annexedBy() == null || province.annexedBy().isBlank();
    }

    private static String regionCharForIndex(int index) {
        if (index < 26) return String.valueOf((char) ('A' + index));
        if (index < 52) return String.valueOf((char) ('a' + index - 26));
        if (index < 61) return String.valueOf((char) ('1' + index - 52));
        if (index == 61) return "0";
        return null;
    }

    /**
     * Renders the hexes within {@code radius} steps of center
     * ({@code cq}, {@code cr}) grouped by province.
     *
     * <p>Each participating province is assigned a unique character (see
     * {@link #buildRegionCharMap}). Hexes not belonging to any participating
     * province render as {@code ·} (background marker; empty positions still
     * render as spaces). Hexes shared by multiple participating provinces
     * render as {@code ※} and are listed in the overlap details.
     *
     * @return the rendered text block, or {@code "(no regions in range)"} when
     *     no participating province has any hex in range
     */
    public static String renderRegions(MapData map, int cq, int cr, int radius, Set<String> tagFilter) {
        return renderRegionsDetailed(map, cq, cr, radius, tagFilter).text();
    }

    /**
     * Like {@link #renderRegions} but also returns the region character map
     * and the overlap detail lines, so callers can build the legend and list
     * shared hexes without duplicating province logic.
     */
    public static RegionRenderResult renderRegionsDetailed(
            MapData map, int cq, int cr, int radius, Set<String> tagFilter) {
        Map<String, String> regionCharMap = buildRegionCharMap(map, cq, cr, radius, tagFilter);
        if (regionCharMap.isEmpty()) {
            return new RegionRenderResult("(no regions in range)", Map.of(), List.of());
        }

        // hexKey → participating province names (sorted for deterministic output)
        Map<String, List<String>> hexToProvinces = new HashMap<>();
        for (var entry : map.provinces().entrySet()) {
            String name = entry.getKey();
            if (!regionCharMap.containsKey(name)) continue;
            for (String hexKey : entry.getValue().hexes()) {
                hexToProvinces.computeIfAbsent(hexKey, k -> new ArrayList<>()).add(name);
            }
        }
        for (List<String> owners : hexToProvinces.values()) {
            owners.sort(String::compareTo);
        }

        // Phase 1: collect per-hex characters within radius
        int diameter = 2 * radius + 1;
        String[][] grid = new String[diameter][diameter]; // [dr + radius][dq + radius]

        List<String> overlapLines = new ArrayList<>();
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
                String hexKey = MapData.hexKey(q, r);
                if (!map.hexes().containsKey(hexKey)) continue;

                List<String> owners = hexToProvinces.get(hexKey);
                if (owners == null || owners.isEmpty()) {
                    grid[ri][qi] = "·"; // background: not in any participating province
                } else if (owners.size() == 1) {
                    grid[ri][qi] = regionCharMap.get(owners.get(0));
                } else {
                    grid[ri][qi] = "※"; // shared by multiple provinces
                    overlapLines.add("重合 hex: " + hexKey + " ∈ {" + String.join(", ", owners) + "}");
                }
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
            }
        }

        if (minR > maxR) {
            return new RegionRenderResult("(no regions in range)", regionCharMap, List.of());
        }
        overlapLines.sort(String::compareTo);

        return new RegionRenderResult(renderGridRows(grid, cq, cr, radius, minR, maxR), regionCharMap, overlapLines);
    }

    /**
     * Renders the hexes within {@code radius} steps of center
     * ({@code cq}, {@code cr}) marking presence of a hex tag key:
     * {@code #} when {@code cell.tags().containsKey(tagKey)}, {@code ·}
     * otherwise. Empty positions still render as spaces.
     *
     * @param tagKey the tag key to check for (must not be null or blank)
     * @return the rendered text block
     */
    public static String renderTagPresence(MapData map, int cq, int cr, int radius, String tagKey) {
        if (tagKey == null || tagKey.isBlank()) {
            throw new IllegalArgumentException("tagKey must not be blank");
        }

        // Phase 1: collect per-hex presence characters within radius
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
                    grid[ri][qi] = cell.tags().containsKey(tagKey) ? "#" : "·";
                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                }
            }
        }

        if (minR > maxR) return "(no hexes in range)";

        return renderGridRows(grid, cq, cr, radius, minR, maxR);
    }

    /**
     * Renders the region-mode legend: one line per province in
     * {@code regionCharMap} ({@code char = name (tag=..., N格)}) plus, when
     * {@code overlapCount > 0}, the {@code ※ = 区域重合} marker line.
     */
    public static String legendRegions(Map<String, String> regionCharMap, MapData map, int overlapCount) {
        StringBuilder sb = new StringBuilder();
        if (regionCharMap != null) {
            for (var entry : regionCharMap.entrySet()) {
                String name = entry.getKey();
                String ch = entry.getValue();
                MapData.Province province = map == null ? null : map.provinces().get(name);
                if (province == null) {
                    sb.append("  ").append(ch).append(" = ").append(name).append("\n");
                } else {
                    sb.append("  ")
                            .append(ch)
                            .append(" = ")
                            .append(name)
                            .append(" (tag=")
                            .append(province.tag())
                            .append(", ")
                            .append(province.hexes().size())
                            .append("格)\n");
                }
            }
        }
        if (overlapCount > 0) {
            sb.append("  ※ = 区域重合 (").append(overlapCount).append(" 个 hex 属于多个区域)\n");
        }
        return sb.toString();
    }

    /**
     * Returns the tag-presence legend: {@code # = 有标签 <tagKey>} and
     * {@code · = 无标签 <tagKey>}.
     */
    public static String legendTagPresence(String tagKey) {
        return "  # = 有标签 " + tagKey + "\n  · = 无标签 " + tagKey + "\n";
    }

    /**
     * Phase 2 shared row builder: renders the collected grid row by row with
     * the flat-top hex odd-r indentation and single-space hex separators.
     * Mirrors the layout of {@link #render(MapData, int, int, int)}.
     */
    private static String renderGridRows(String[][] grid, int cq, int cr, int radius, int minR, int maxR) {
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
