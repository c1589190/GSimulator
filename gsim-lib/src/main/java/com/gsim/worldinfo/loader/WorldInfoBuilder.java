package com.gsim.worldinfo.loader;

import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
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
 * 封装为 {@link com.gsim.worldinfo.WorldInformation} 返回。
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
     * 扫描 nodes 目录，自动发现当前活跃链（无需预先知道 activeNodeId）。
     *
     * <p>算法：加载所有节点 → 找到叶子节点（不被任何其他节点作为 parent 引用的节点）
     * → 从叶子向上回溯到根节点 → 反转得到 root→leaf 链。
     * 如果有多个叶子（分支），取 turn 最大的那条链。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return 完整的 WorldInformation，若 nodes 目录不存在或无节点则返回 null
     */
    public static WorldInformation discover(Path worldsDir, String worldId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.exists(nodesDir)) return null;

        // 1. Load all nodes
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
            return null;
        }

        if (allNodes.isEmpty()) return null;

        // 2. Find parentId set — nodeIds that are referenced as parents
        Set<String> referencedAsParent = new HashSet<>();
        for (NodeSnapshot n : allNodes.values()) {
            if (n.parentId() != null && !n.isRoot()) {
                referencedAsParent.add(n.parentId());
            }
        }

        // 3. Find leaf: node whose nodeId is NOT referenced as parent
        //    If multiple leaves, pick highest turn
        NodeSnapshot leaf = null;
        for (NodeSnapshot n : allNodes.values()) {
            if (!referencedAsParent.contains(n.nodeId())) {
                if (leaf == null || n.turn() > leaf.turn()) {
                    leaf = n;
                }
            }
        }

        if (leaf == null) {
            // All nodes are parents of some other node — circular reference?
            // Fall back to highest-turn node
            leaf = allNodes.values().stream()
                    .max((a, b) -> Integer.compare(a.turn(), b.turn()))
                    .orElse(null);
        }
        if (leaf == null) return null;

        // 4. Walk up from leaf to root
        List<NodeSnapshot> chain = new ArrayList<>();
        chain.add(leaf);
        NodeSnapshot cursor = leaf;
        while (!cursor.isRoot()) {
            Path parentFile = NodeLoader.nodeFile(worldsDir, worldId, cursor.parentId());
            if (!Files.exists(parentFile)) {
                log.warn(
                        "Parent node file missing during discover: {} (chain truncated at {})",
                        parentFile,
                        cursor.nodeId());
                break;
            }
            cursor = NodeLoader.load(parentFile);
            chain.add(cursor);
        }

        // 5. Reverse so root is first
        java.util.Collections.reverse(chain);

        return new WorldInformation(worldId, chain);
    }
}
