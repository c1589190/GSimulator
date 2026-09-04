package com.gsim.agent.tools.search;

import com.gsim.agentsmanager.QueryScope;
import com.gsim.agentsmanager.QueryScopeContext;
import com.gsim.core.search.SearchEntry;
import com.gsim.core.worldinfo.Checkpoint;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 世界元素域共享语料源（gsim_search_world 与 gsim_search 聚合器共用）。
 *
 * <p>语料语义（从 {@link GsimSearchWorldTool} 抽取，行为完全一致）：遍历分支链
 * （根 → effectiveNodeId 所在节点，即该节点的世界状态），每个元素的
 * {@code value + tags} 作为文本，key 为 {@code nodeId:checkpointId:elementKey}，
 * sortKey 为节点回合号。
 *
 * <p>包可见：仅限 search 包内工具/聚合器直接调用，避免 ToolRegistry 往返解析。
 */
final class WorldSearchSource {

    private final SearchToolContext ctx;

    WorldSearchSource(SearchToolContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 构建世界元素搜索语料。
     *
     * @param worldId         世界 ID（本域不直接使用，语料来自 wiSupplier）
     * @param effectiveNodeId 生效节点 ID（null/空白 → 整条活跃分支链；不存在的节点 → 空语料）
     * @param checkpointId    可选的检查点过滤（null/空白表示不过滤）
     * @return 搜索语料列表
     */
    List<SearchEntry> build(String worldId, String effectiveNodeId, String checkpointId) {
        WorldInformation wi = ctx.wiSupplier().get();
        List<NodeSnapshot> nodes = branchUpTo(wi, effectiveNodeId);

        QueryScope scope = QueryScopeContext.get();
        if (scope == null) scope = QueryScope.none();

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
                    if (!scope.allows(node.nodeId(), cpEntry.getKey(), element.key(), element.tags())) {
                        continue;
                    }
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
