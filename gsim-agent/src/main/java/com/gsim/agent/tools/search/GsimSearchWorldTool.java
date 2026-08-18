package com.gsim.agent.tools.search;

import com.gsim.agentlib.tool.ToolCall;
import com.gsim.core.search.SearchEntry;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * gsim_search_world — 世界元素域细化搜索工具。
 *
 * <p>将分支链（根 → 活跃节点）上所有检查点的全部元素（值 + 标签）作为搜索语料，
 * 使用共享的 {@link com.gsim.core.search.GenericSearchEngine} 做全文检索。
 * 每个命中条目 key 为 {@code nodeId:checkpointId:elementKey}，sortKey 为节点回合号。
 *
 * <p>参数：
 * <ul>
 *   <li>{@code keywords}（必填）— 搜索关键词</li>
 *   <li>{@code nodeId}（可选，默认活跃节点）— 指定节点时语料限定为该节点所在分支
 *       （根 → nodeId，即该节点的世界状态）</li>
 *   <li>{@code checkpointId}（可选）— 仅搜索指定检查点的元素</li>
 *   <li>{@code limit} / {@code offset}（可选）— 分页</li>
 * </ul>
 */
public final class GsimSearchWorldTool extends AbstractSearchTool {

    public GsimSearchWorldTool(SearchToolContext ctx) {
        super(ctx);
    }

    @Override
    public String name() {
        return "gsim_search_world";
    }

    @Override
    public String description() {
        return "Full-text search across all world information elements (values and tags). "
                + "Returns matching elements as nodeId:checkpointId:key references with relevance scores. "
                + "Optional nodeId scopes the search to that node's branch; optional checkpointId "
                + "restricts the search to a single checkpoint; supports pagination via limit/offset.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> base = super.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = new java.util.LinkedHashMap<>((Map<String, Object>) base.get("properties"));
        props.put(
                "checkpointId",
                Map.of("type", "string", "description", "Optional checkpoint ID to filter results (exact match)"));
        return Map.of("type", "object", "properties", props, "required", List.of("keywords"));
    }

    @Override
    protected String domain() {
        return "element";
    }

    @Override
    protected String defaultNodeId(String worldId) {
        return ctx.wiSupplier().get().activeNodeId();
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId, ToolCall call) {
        return buildCorpus(worldId, effectiveNodeId, call.param("checkpointId"));
    }

    @Override
    protected List<SearchEntry> buildEntries(String worldId, String effectiveNodeId) {
        return buildCorpus(worldId, effectiveNodeId, null);
    }

    /**
     * 核心语料构造：遍历分支链（根 → effectiveNodeId 所在节点，即该节点的世界状态），
     * 每个元素生成一条 {@link SearchEntry}。
     *
     * @param worldId         世界 ID
     * @param effectiveNodeId 生效节点 ID（null 或不存在时按空语料处理）
     * @param checkpointId    可选的检查点过滤（null/空白表示不过滤）
     * @return 搜索语料列表
     */
    private List<SearchEntry> buildCorpus(String worldId, String effectiveNodeId, String checkpointId) {
        WorldInformation wi = ctx.wiSupplier().get();
        List<NodeSnapshot> nodes = branchUpTo(wi, effectiveNodeId);

        List<SearchEntry> entries = new ArrayList<>();
        for (NodeSnapshot node : nodes) {
            for (Map.Entry<String, Checkpoint> cpEntry : node.checkpoints().entrySet()) {
                if (checkpointId != null
                        && !checkpointId.isBlank()
                        && !cpEntry.getKey().equals(checkpointId)) {
                    continue;
                }
                Checkpoint checkpoint = cpEntry.getValue();
                for (Element element : checkpoint.elements()) {
                    String text = element.value() + " " + String.join(" ", element.tags());
                    String key = node.nodeId() + ":" + cpEntry.getKey() + ":" + element.key();
                    entries.add(new SearchEntry(text, key, node.turn()));
                }
            }
        }
        return entries;
    }

    /**
     * 将语料限定到 {@code nodeId} 所在分支（根 → nodeId 包含）。
     *
     * <p>默认（effectiveNodeId 为活跃节点）时即为整条活跃分支链；nodeId 指定
     * 非活跃节点时，语料为该节点时刻可见的世界状态；nodeId 不存在时返回空列表
     * （调用方得到空结果，不抛异常）。
     */
    private static List<NodeSnapshot> branchUpTo(WorldInformation wi, String effectiveNodeId) {
        List<NodeSnapshot> chain = wi.branchChain();
        if (effectiveNodeId == null || effectiveNodeId.isBlank()) {
            return chain;
        }
        NodeSnapshot target = wi.nodeById(effectiveNodeId);
        if (target == null) {
            return List.of();
        }
        return chain.subList(0, chain.indexOf(target) + 1);
    }
}
