package com.gsim.worldinfo.loader;

import com.gsim.worldinfo.NodeSnapshot;
import com.gsim.worldinfo.WorldInformation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
