package com.gsim.worldinfo.loader;

import com.gsim.util.JsonUtils;
import com.gsim.worldinfo.NodeSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点加载器 -- 读写 worlds/{worldId}/nodes/ 下的单个节点 JSON 文件。
 *
 * <p>提供节点的序列化与反序列化，以及节点附件（attachments）的管理。
 * 附件是外部应用可存储在节点旁的任意键值数据，与 WorldInfo 元素体系独立。
 *
 * <p>此类为纯静态工具类，不可实例化。
 */
public final class NodeLoader {

    private NodeLoader() {}

    /**
     * 从 JSON 文件加载单个节点快照。
     *
     * @param nodeFile 节点 JSON 文件路径
     * @return 解析后的 NodeSnapshot 记录
     * @throws IllegalArgumentException 文件不存在时抛出
     * @throws RuntimeException         读取或反序列化失败时抛出
     */
    public static NodeSnapshot load(Path nodeFile) {
        if (!Files.exists(nodeFile)) {
            throw new IllegalArgumentException("Node file not found: " + nodeFile);
        }
        try {
            String json = Files.readString(nodeFile);
            return JsonUtils.fromJson(json, NodeSnapshot.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load node: " + nodeFile, e);
        }
    }

    /**
     * 将节点快照保存到 JSON 文件。自动创建父目录。
     *
     * @param nodeFile 目标 JSON 文件路径
     * @param node     要保存的节点快照
     * @throws RuntimeException 写入失败时抛出
     */
    public static void save(Path nodeFile, NodeSnapshot node) {
        try {
            Files.createDirectories(nodeFile.getParent());
            String json = JsonUtils.toJson(node);
            Files.writeString(nodeFile, json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save node: " + nodeFile, e);
        }
    }

    /**
     * 获取指定 world 的节点文件存放目录。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @return {@code worldsDir/worldId/nodes} 目录路径
     */
    public static Path nodesDir(Path worldsDir, String worldId) {
        return worldsDir.resolve(worldId).resolve("nodes");
    }

    /**
     * 获取指定节点的 JSON 文件路径。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @param nodeId    节点 ID（如 "n0000"）
     * @return 节点 JSON 文件的完整路径
     */
    public static Path nodeFile(Path worldsDir, String worldId, String nodeId) {
        return nodesDir(worldsDir, worldId).resolve(nodeId + ".json");
    }

    // ── Attachment helpers ──────────────────────────────

    /**
     * 为节点保存附件值。读取当前节点，合并附件后写回。
     * 附件是外部应用可存储的任意键值数据，与节点状态关联但不属于 WorldInfo 元素体系。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @param nodeId    节点 ID
     * @param key       附件键名
     * @param data      附件数据（将被序列化为 JSON）
     */
    public static void saveAttachment(Path worldsDir, String worldId, String nodeId, String key, Object data) {
        Path file = nodeFile(worldsDir, worldId, nodeId);
        NodeSnapshot node = load(file);
        Map<String, Object> att = new LinkedHashMap<>(node.attachments());
        att.put(key, data);
        NodeSnapshot updated = new NodeSnapshot(
                node.nodeId(),
                node.parentId(),
                node.turn(),
                node.worldTime(),
                node.status(),
                node.createdAt(),
                node.checkpoints(),
                att);
        save(file, updated);
    }

    /**
     * 从节点中按键名加载附件值。
     * 如果键不存在或节点文件不存在，返回 null。
     *
     * @param worldsDir worlds 根目录
     * @param worldId   世界 ID
     * @param nodeId    节点 ID
     * @param key       附件键名
     * @param type      期望的类型 Class
     * @param <T>       返回值的类型
     * @return 附件值，不存在时返回 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T loadAttachment(Path worldsDir, String worldId, String nodeId, String key, Class<T> type) {
        Path file = nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(file)) return null;
        NodeSnapshot node = load(file);
        Object data = node.attachments().get(key);
        if (data == null) return null;
        return JsonUtils.MAPPER.convertValue(data, type);
    }
}
