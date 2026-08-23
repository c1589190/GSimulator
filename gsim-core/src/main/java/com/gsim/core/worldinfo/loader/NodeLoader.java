package com.gsim.core.worldinfo.loader;

import com.gsim.core.util.JsonUtils;
import com.gsim.core.worldinfo.NodeSnapshot;
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
     * Returns the path for an independent attachment file.
     * Files are stored as {@code worlds/{worldId}/nodes/{nodeId}_{key}.json}.
     *
     * @param worldsDir worlds root directory
     * @param worldId   world ID
     * @param nodeId    node ID (e.g. "n0000")
     * @param key       attachment key (e.g. "map")
     * @return path to the attachment file
     */
    public static Path attachmentFilePath(Path worldsDir, String worldId, String nodeId, String key) {
        return nodesDir(worldsDir, worldId).resolve(nodeId + "_" + key + ".json");
    }

    /**
     * Save an attachment as an independent file alongside the node JSON.
     *
     * <p>Writes data to {@code nXXXX_{key}.json} and updates the node JSON's
     * {@code attachments} map with a light reference: {@code {"_file": "...", "_type": "external"}}.
     *
     * <p>If the node JSON update fails after the attachment file has been written,
     * the attachment file is deleted (best-effort rollback) to prevent orphan files.
     *
     * @param worldsDir worlds root directory
     * @param worldId   world ID
     * @param nodeId    node ID
     * @param key       attachment key (e.g. "map", "contour")
     * @param data      the data to persist (will be serialized to JSON)
     * @throws RuntimeException if the attachment cannot be saved or the node JSON update fails
     */
    public static void saveAttachmentFile(Path worldsDir, String worldId, String nodeId, String key, Object data) {
        Path attachFile = attachmentFilePath(worldsDir, worldId, nodeId, key);
        try {
            Files.createDirectories(attachFile.getParent());
            Files.writeString(attachFile, JsonUtils.toJson(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save attachment file: " + attachFile, e);
        }

        // Update the node JSON with a light reference
        Path nodeFile = nodeFile(worldsDir, worldId, nodeId);
        if (Files.exists(nodeFile)) {
            try {
                NodeSnapshot node = load(nodeFile);
                Map<String, Object> att = new LinkedHashMap<>(node.attachments());
                Map<String, String> ref = new LinkedHashMap<>();
                ref.put("_file", attachFile.getFileName().toString());
                ref.put("_type", "external");
                att.put(key, ref);
                NodeSnapshot updated = new NodeSnapshot(
                        node.nodeId(),
                        node.parentId(),
                        node.turn(),
                        node.worldTime(),
                        node.status(),
                        node.createdAt(),
                        node.checkpoints(),
                        att);
                save(nodeFile, updated);
            } catch (RuntimeException e) {
                // Rollback: delete the attachment file to prevent orphan files
                // when the node JSON is corrupt (e.g. empty file from partial write)
                try {
                    Files.deleteIfExists(attachFile);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                throw new RuntimeException(
                        "Failed to update node JSON for attachment '" + key + "', rolled back attachment file: "
                                + attachFile,
                        e);
            }
        }
    }

    /**
     * Load an attachment, preferring independent file over inline data.
     *
     * <p>If the node's attachments map has a reference with {@code _type: "external"},
     * reads from the independent file. Otherwise falls back to inline data (backward compat).
     *
     * @param worldsDir worlds root directory
     * @param worldId   world ID
     * @param nodeId    node ID
     * @param key       attachment key
     * @param type      expected type class
     * @param <T>       return type
     * @return the attachment data, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static <T> T loadAttachmentFile(Path worldsDir, String worldId, String nodeId, String key, Class<T> type) {
        Path nodeFile = nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) {
            // Map may be generated for a world with no GSim node yet — read the standalone attachment file.
            return loadStandaloneAttachment(worldsDir, worldId, nodeId, key, type);
        }

        NodeSnapshot node = load(nodeFile);
        Object raw = node.attachments().get(key);

        // Backward compat: check for external file by naming convention
        // even when no attachment reference exists in the node JSON.
        // Also falls back from "map_diff" → "map" key for legacy data
        // where diffs were stored as nXXXX_map.json alongside full maps.
        if (raw == null) {
            return loadStandaloneAttachment(worldsDir, worldId, nodeId, key, type);
        }

        // Check if it's an external file reference
        if (raw instanceof Map<?, ?> ref && "external".equals(ref.get("_type"))) {
            String fileName = (String) ref.get("_file");
            if (fileName != null) {
                Path attachFile = nodesDir(worldsDir, worldId).resolve(fileName);
                if (Files.exists(attachFile)) {
                    try {
                        String json = Files.readString(attachFile);
                        return JsonUtils.fromJson(json, type);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load attachment file: " + attachFile, e);
                    }
                }
            }
            return null;
        }

        // Backward compat: inline data
        return JsonUtils.MAPPER.convertValue(raw, type);
    }

    /** Read an attachment via the {@code nXXXX_<key>.json} naming convention (legacy/standalone). */
    private static <T> T loadStandaloneAttachment(
            Path worldsDir, String worldId, String nodeId, String key, Class<T> type) {
        Path legacyFile = attachmentFilePath(worldsDir, worldId, nodeId, key);
        if (!Files.exists(legacyFile) && "map_diff".equals(key)) {
            legacyFile = attachmentFilePath(worldsDir, worldId, nodeId, "map");
        }
        if (Files.exists(legacyFile)) {
            try {
                String json = Files.readString(legacyFile);
                return JsonUtils.fromJson(json, type);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load legacy attachment file: " + legacyFile, e);
            }
        }
        return null;
    }

    /**
     * List all attachment file references for a node.
     *
     * @param worldsDir worlds root directory
     * @param worldId   world ID
     * @param nodeId    node ID
     * @return list of attachment keys that have external file references
     */
    public static java.util.List<String> listAttachments(Path worldsDir, String worldId, String nodeId) {
        Path nodeFile = nodeFile(worldsDir, worldId, nodeId);
        if (!Files.exists(nodeFile)) return java.util.List.of();

        NodeSnapshot node = load(nodeFile);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (var entry : node.attachments().entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> ref && "external".equals(ref.get("_type"))) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

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
