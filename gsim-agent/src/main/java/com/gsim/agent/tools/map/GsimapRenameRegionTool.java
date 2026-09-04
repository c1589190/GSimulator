package com.gsim.agent.tools.map;

import com.gsim.agent.tools.search.SearchToolContext;
import com.gsim.agentsmanager.tool.AgentTool.Permission;
import com.gsim.agentsmanager.tool.ToolCall;
import com.gsim.agentsmanager.tool.ToolResult;
import com.gsim.docslib.util.JsonUtils;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.ElementRef;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.NodeLoader;
import com.gsim.map.service.MapService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gsimap_rename_region — Rename a region across all data stores:
 * MapData provinces + all GSim checkpoint references (factions, narrative, map, etc.).
 * Updates keys, tags, and text references. Auto-saves.
 *
 * <p>区域名变更后，通过 {@link WorldInformation#linkIndex()} 反向查询引用
 * {@code gsimap:region:旧名} 的所有元素，将其 links 中该条目改写为新名（仅 links，
 * 不触碰元素 value），并经 {@link NodeLoader#save} 逐节点落盘——否则改写仅内存生效，
 * 重启即回滚。
 */
public final class GsimapRenameRegionTool extends AbstractGsimapTool {

    private static final Logger log = LoggerFactory.getLogger(GsimapRenameRegionTool.class);

    private final SearchToolContext searchCtx;

    public GsimapRenameRegionTool(MapService mapService, SearchToolContext searchCtx) {
        super(mapService);
        this.searchCtx = searchCtx;
    }

    @Override
    public String name() {
        return "gsimap_rename_region";
    }

    @Override
    public String description() {
        return "Rename a region across all data stores: provinces, checkpoint references, keys, and tags. Auto-saves.";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String worldId = com.gsim.agentsmanager.mcp.GsimRequestContext.worldId();
        if (worldId == null) {
            worldId = call.param("worldId");
            if (worldId == null || worldId.isBlank()) {
                return ToolResult.fail(name(), "worldId is required");
            }
        }
        String nodeId = call.param("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = mapService.readActiveNodeId(worldId);
        }
        String oldName = call.param("oldName");
        if (oldName == null || oldName.isBlank()) {
            return ToolResult.fail(name(), "oldName is required");
        }
        String newName = call.param("newName");
        if (newName == null || newName.isBlank()) {
            return ToolResult.fail(name(), "newName is required");
        }

        Map<String, Object> result = new LinkedHashMap<>(mapService.renameRegion(worldId, nodeId, oldName, newName));
        if (Boolean.TRUE.equals(result.get("ok"))) {
            propagateLinkRewrite(worldId, oldName, newName);
        }
        result.put("address", "gsimap:region:" + newName);
        return ToolResult.ok(
                name(), List.of(new ToolResult.Item(newName, "gsimap_rename_region", JsonUtils.toJson(result), 1.0)));
    }

    /**
     * 将 {@code gsimap:region:旧名} → {@code gsimap:region:新名} 传播到所有引用该区域的元素：
     * 读原元素 → 仅改写 links 中对应条目（保留顺序与其他链接）→ 重建 Element（保留
     * createdAt/updatedAt/value/tags/type）→ {@link WorldInformation#upsertElement} 写回
     * （LinkIndex 增量更新：移除旧 key、加入新 key）→ 每个受影响节点 {@link NodeLoader#save}
     * 落盘。无命中时静默返回（renameRegion 原语义不变）。
     */
    private void propagateLinkRewrite(String worldId, String oldName, String newName) {
        if (searchCtx == null || searchCtx.wiSupplier() == null) {
            log.warn("[renameRegion] no WorldInformation supplier wired — skipping link rewrite propagation");
            return;
        }
        WorldInformation wi = searchCtx.wiSupplier().get();
        if (wi == null) {
            log.warn(
                    "[renameRegion] WorldInformation unavailable for world={} — skipping link rewrite propagation",
                    worldId);
            return;
        }

        String oldLink = "gsimap:region:" + oldName;
        String newLink = "gsimap:region:" + newName;
        List<ElementRef> affected = wi.linkIndex().findByLink(oldLink);
        if (affected.isEmpty()) {
            return; // silent success — no linked elements
        }

        LinkedHashSet<String> affectedNodes = new LinkedHashSet<>();
        for (ElementRef ref : affected) {
            Element old = ref.element();
            List<String> newLinks = old.links().stream()
                    .map(l -> l.equals(oldLink) ? newLink : l)
                    .toList();
            Element rewritten = new Element(
                    old.key(), old.type(), old.value(), old.tags(), newLinks, old.createdAt(), old.updatedAt());
            wi.upsertElement(ref.nodeId(), ref.checkpointId(), rewritten);
            affectedNodes.add(ref.nodeId());
        }

        // 落盘：改写仅内存生效会随重启回滚 — 与 WriteElementTool 一致按节点保存
        Path worldsDir = searchCtx.worldsDir();
        if (worldsDir == null) {
            log.warn("[renameRegion] worldsDir not wired — link rewrites not persisted for world={}", worldId);
            return;
        }
        for (String affectedNode : affectedNodes) {
            NodeSnapshot node = wi.nodeById(affectedNode);
            if (node == null) continue;
            NodeLoader.save(NodeLoader.nodeFile(worldsDir, wi.worldId(), affectedNode), node);
        }
        log.info(
                "Renamed gsimap:region link '{}' -> '{}' for {} element(s) in world={}",
                oldName,
                newName,
                affected.size(),
                worldId);
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "worldId", Map.of("type", "string", "description", "GSim world ID"),
                                "nodeId",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Node ID (optional, defaults to active node)"),
                                "oldName", Map.of("type", "string", "description", "Current region name"),
                                "newName", Map.of("type", "string", "description", "New region name")),
                "required", List.of("worldId", "oldName", "newName"));
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }
}
