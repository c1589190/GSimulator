package com.gsim.agent.tools.map;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 地图写工具必须显式指定目标节点，禁止默认写入 n0000。
 */
@DisplayName("地图写工具 nodeId 必填")
class MapWriteToolsRequireNodeIdTest {

    @TempDir
    Path tmpDir;

    private List<AgentTool> writeTools() {
        MapService mapService = new MapService(tmpDir);
        return List.of(
                new GsimapGenerateTool(mapService),
                new GsimapCreateRegionTool(mapService),
                new GsimapDeleteRegionTool(mapService),
                new GsimapAddHexToRegionTool(mapService),
                new GsimapRemoveHexFromRegionTool(mapService),
                new GsimapUpdateTerrainTypeTool(mapService),
                new GsimapEdgeSetTool(mapService),
                new GsimapEdgeRemoveTool(mapService),
                new GsimapSetHexTool(mapService),
                new GsimapRemoveHexTagTool(mapService));
    }

    @Test
    @DisplayName("缺少 nodeId 时所有写工具拒绝执行")
    void missingNodeIdIsRejected() {
        for (AgentTool tool : writeTools()) {
            ToolResult result = tool.execute(new ToolCall(tool.name(), Map.of("worldId", "w")));
            assertFalse(result.success(), tool.name() + " should reject a call without nodeId");
            assertTrue(result.error().contains("nodeId is required"), tool.name() + " error: " + result.error());
        }
    }

    @Test
    @DisplayName("getParameters 将 nodeId 声明为必填参数")
    void nodeIdIsRequiredInSchema() {
        for (AgentTool tool : writeTools()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = tool.getParameters();
            assertNotNull(params, tool.name() + " should provide a schema");
            @SuppressWarnings("unchecked")
            List<Object> required = (List<Object>) params.get("required");
            assertNotNull(required, tool.name() + " should declare required parameters");
            assertTrue(required.contains("nodeId"), tool.name() + " required=" + required);
        }
    }
}
