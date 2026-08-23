package com.gsim.core.worldinfo.loader;

import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorldInformation 构建器 -- 从活跃节点出发沿父节点链回溯，构建完整的 WorldInformation。
 *
 * <p>从 activeNodeId 开始，通过 {@link NodeSnapshot#parentId()} 逐级向上遍历，
 * 直到根节点（parentId 为 null），然后将所有节点反转排序（根节点在前），
 * 封装为 {@link com.gsim.core.worldinfo.WorldInformation} 返回。
 *
 * <p>如果某级父节点文件缺失，链会被截断并记录警告日志。
 * 如果 world 的 nodes 目录不存在，返回 null。
 *
 * <p>此类为纯静态工具类，不可实例化。
 */
public final class WorldInfoBuilder {

    private static final Logger log = LoggerFactory.getLogger(WorldInfoBuilder.class);

    private WorldInfoBuilder() {}

    /**
     * 从活跃节点出发沿父链回溯，加载所有节点，构建 WorldInformation。
     *
     * <p>遍历过程：从 activeNodeId 开始加载节点，重复通过 parentId 加载父节点，
     * 直到根节点（parentId 为 null）。将收集到的节点反转，使根节点位于列表首位。
     *
     * @param worldsDir    worlds 根目录
     * @param worldId      世界 ID
     * @param activeNodeId 当前活跃节点 ID
     * @return 完整的 WorldInformation，若 nodes 目录不存在则返回 null
     */
    public static WorldInformation build(Path worldsDir, String worldId, String activeNodeId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.exists(nodesDir)) {
            return null;
        }

        // 1. Load active node
        NodeSnapshot current = NodeLoader.load(NodeLoader.nodeFile(worldsDir, worldId, activeNodeId));

        // 2. Walk up parent chain
        List<NodeSnapshot> chain = new ArrayList<>();
        chain.add(current);
        NodeSnapshot cursor = current;
        while (!cursor.isRoot()) {
            Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, cursor.parentId());
            if (!Files.exists(parentFile)) {
                log.warn("Parent node file missing: {} (chain truncated at {})", parentFile, cursor.nodeId());
                break;
            }
            cursor = NodeLoader.load(parentFile);
            chain.add(cursor);
        }

        // 3. Reverse so root is first
        java.util.Collections.reverse(chain);

        // 4. Build
        return new WorldInformation(worldId, chain);
    }

    /**
     * 扫描 nodes 目录，自动发现完整节点集合（无需预先知道 activeNodeId）。
     *
     * <p>宽松模式（"有啥取啥"）：加载所有可读节点 → 从最高 turn 的叶子节点（不被任何
     * 其他节点作为 parent 引用的节点）出发沿父链向上回溯，仅用于断链诊断（父节点
     * 文件缺失时记录告警）；最终链为全部可读节点按 turn 升序（同 turn 按 nodeId），
     * 断链丢失的祖先、其他分支、孤立节点全部保留可见。
     * rootNodeId = 最小 turn 节点，activeNodeId = 最大 turn 节点。
     * 若所有节点都互为 parent（疑似成环），从最高 turn 节点出发回溯（visited 防环）。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return 完整 WorldInformation（全部节点按 turn 升序），
     *         若 nodes 目录不存在或无节点则返回 null
     */
    public static WorldInformation discover(Path worldsDir, String worldId) {
        // 1. Load all readable node metadata (unified via WorldManager callers)
        Map<String, NodeSnapshot> allNodes = loadAllNodes(worldsDir, worldId);
        if (allNodes.isEmpty()) return null;

        // 2. Find parentId set — nodeIds that are referenced as parents
        Set<String> referencedAsParent = new HashSet<>();
        for (NodeSnapshot n : allNodes.values()) {
            if (n.parentId() != null && !n.isRoot()) {
                referencedAsParent.add(n.parentId());
            }
        }

        // 3. Starting point: highest-turn leaf (node NOT referenced as parent).
        //    If every node is referenced as a parent (circular?), fall back to highest-turn node.
        NodeSnapshot start = allNodes.values().stream()
                .filter(n -> !referencedAsParent.contains(n.nodeId()))
                .max(java.util.Comparator.comparingInt(NodeSnapshot::turn))
                .orElseGet(() -> allNodes.values().stream()
                        .max(java.util.Comparator.comparingInt(NodeSnapshot::turn))
                        .orElse(null));
        if (start == null) return null;

        // 4. Walk up along parent links (visited guards against cycles) —
        //    diagnostics only, ordering below is by turn, so nothing is dropped.
        Set<String> visited = new HashSet<>();
        NodeSnapshot cursor = start;
        while (cursor != null && visited.add(cursor.nodeId())) {
            if (cursor.isRoot()) break;
            Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, cursor.parentId());
            if (!Files.exists(parentFile)) {
                log.warn(
                        "Parent node file missing during discover: {} (chain truncated at {})",
                        parentFile,
                        cursor.nodeId());
                break;
            }
            cursor = NodeLoader.load(parentFile);
        }

        // 5. 有啥取啥 — every readable node, ordered by turn then nodeId.
        //    Root = lowest turn, active = highest turn; branches/orphans stay visible.
        List<NodeSnapshot> chain = new ArrayList<>(allNodes.values());
        chain.sort(java.util.Comparator.comparingInt(NodeSnapshot::turn).thenComparing(NodeSnapshot::nodeId));

        return new WorldInformation(worldId, chain);
    }

    /**
     * 扫描并加载节点目录中所有可读的 {@code nXXXX.json} 节点。
     *
     * <p>仅供同包的 {@link WorldManager} 做轻量活跃/根节点推导使用；
     * 返回不可修改的 nodeId → NodeSnapshot 映射，目录不存在或扫描失败时返回空 Map。
     */
    static Map<String, NodeSnapshot> loadAllNodes(Path worldsDir, String worldId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.isDirectory(nodesDir)) return Map.of();

        Map<String, NodeSnapshot> allNodes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(nodesDir)) {
            files.filter(f -> f.getFileName().toString().matches("n\\d{4}\\.json"))
                    .forEach(f -> {
                        try {
                            NodeSnapshot n = NodeLoader.load(f);
                            allNodes.put(n.nodeId(), n);
                        } catch (RuntimeException e) {
                            log.warn("Skipping unreadable node file: {}", f, e);
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list nodes directory: {}", nodesDir, e);
            return Map.of();
        }
        return Map.copyOf(allNodes);
    }
}
