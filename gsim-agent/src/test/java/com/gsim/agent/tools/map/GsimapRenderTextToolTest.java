package com.gsim.map.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import com.gsim.map.map.MapData;
import com.gsim.map.map.MapStore;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GsimapRenderTextTool — {@code gsimap_render_text} 三模式渲染测试。
 *
 * <p>覆盖：默认 terrain 模式回归、region 模式 legend、region + tag 白名单、
 * tag + tagKey 存在性渲染、tagKey 缺失报错、非法 mode 报错。
 */
@DisplayName("GsimapRenderTextTool — 三模式渲染")
class GsimapRenderTextToolTest {

    private static final String WORLD = "rendertextworld";

    @TempDir
    Path tmpDir;

    private GsimapRenderTextTool tool;

    @BeforeEach
    void setUp() {
        WorldIndexManager.createWorld(tmpDir, WORLD, "渲染世界");
        MapData mapData = new MapData(
                30,
                false,
                Map.of(
                        // 0_0：王国A 属地，带"资源"tag
                        "0_0",
                        new MapData.HexCell("#6CC261", "plains", null, null, "", 0, Map.of(), Map.of("资源", "金")),
                        // 1_0：王国A 属地，无 tag
                        "1_0",
                        new MapData.HexCell("#6CC261", "plains", null, null, "", 0, Map.of(), Map.of()),
                        // 0_1：王国B 属地，无 tag
                        "0_1",
                        new MapData.HexCell("#228B22", "forest", null, null, "", 0, Map.of(), Map.of())),
                List.of(),
                Map.of(
                        "王国A",
                        new MapData.Province(List.of("0_0", "1_0"), "#ff0000", "王国A", "东境"),
                        "王国B",
                        new MapData.Province(List.of("0_1"), "#00ff00", "王国B", "西境")),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                Map.of(),
                Map.of());
        MapStore.saveFull(tmpDir, WORLD, "n0000", mapData);

        tool = new GsimapRenderTextTool(new MapService(tmpDir));
    }

    private ToolResult render(Map<String, String> params) {
        return tool.execute(new ToolCall("gsimap_render_text", params));
    }

    private ToolResult renderAt(Map<String, String> extra) {
        Map<String, String> params =
                new java.util.LinkedHashMap<>(Map.of("worldId", WORLD, "q", "0", "r", "0", "radius", "1"));
        params.putAll(extra);
        return render(params);
    }

    private String snippet(ToolResult result) {
        assertTrue(result.success(), "error: " + result.error());
        assertEquals(1, result.items().size());
        return result.items().get(0).snippet();
    }

    @Test
    @DisplayName("默认模式（无 mode 参数）：terrain 输出含 Text Map（回归）")
    void defaultModeIsTerrain() {
        String snippet = snippet(renderAt(Map.of()));
        assertTrue(snippet.contains("## Text Map: center (0,0), radius 1"), "snippet: " + snippet);
        assertTrue(snippet.contains("### Legend"), "snippet: " + snippet);
        assertTrue(snippet.contains("address: gsimap:hex:0_0"), "snippet: " + snippet);
    }

    @Test
    @DisplayName("mode=region：输出 Region Map 且 legend 含 tag= 标注")
    void regionMode() {
        String snippet = snippet(renderAt(Map.of("mode", "region")));
        assertTrue(snippet.contains("## Region Map: center (0,0), radius 1"), "snippet: " + snippet);
        assertTrue(snippet.contains("### Legend"), "snippet: " + snippet);
        assertTrue(snippet.contains("A = 王国A (tag=王国A, 2格)"), "snippet: " + snippet);
        assertTrue(snippet.contains("B = 王国B (tag=王国B, 1格)"), "snippet: " + snippet);
        assertTrue(snippet.contains("address: gsimap:region:王国A"), "snippet: " + snippet);
    }

    @Test
    @DisplayName("mode=region + tag 白名单：只渲染匹配 tag 的 province")
    void regionModeWithTagFilter() {
        String snippet = snippet(renderAt(Map.of("mode", "region", "tag", "王国A")));
        assertTrue(snippet.contains("A = 王国A (tag=王国A, 2格)"), "snippet: " + snippet);
        assertFalse(snippet.contains("王国B"), "snippet: " + snippet);
        assertTrue(snippet.contains("·"), "snippet: " + snippet);
    }

    @Test
    @DisplayName("mode=tag + tagKey：输出 Tag Map 且含 # 与 ·")
    void tagModeWithTagKey() {
        String snippet = snippet(renderAt(Map.of("mode", "tag", "tagKey", "资源")));
        assertTrue(snippet.contains("## Tag Map: 资源 center (0,0), radius 1"), "snippet: " + snippet);
        assertTrue(snippet.contains("# = 有标签 资源"), "snippet: " + snippet);
        assertTrue(snippet.contains("· = 无标签 资源"), "snippet: " + snippet);
        assertTrue(snippet.contains("#"), "snippet: " + snippet);
        assertTrue(snippet.contains("·"), "snippet: " + snippet);
    }

    @Test
    @DisplayName("mode=tag 缺 tagKey：fail 且错误信息含 tagKey")
    void tagModeMissingTagKeyFails() {
        ToolResult result = renderAt(Map.of("mode", "tag"));
        assertFalse(result.success());
        assertTrue(result.error().contains("tagKey"), "error: " + result.error());
    }

    @Test
    @DisplayName("非法 mode：fail 且错误信息提示可选值")
    void invalidModeFails() {
        ToolResult result = renderAt(Map.of("mode", "bogus"));
        assertFalse(result.success());
        assertTrue(result.error().contains("terrain, region, tag, or pathway"), "error: " + result.error());
    }
}
