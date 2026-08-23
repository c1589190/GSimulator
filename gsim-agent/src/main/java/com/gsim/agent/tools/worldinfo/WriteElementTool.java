package com.gsim.agent.tools.worldinfo;

import com.gsim.agentlib.tool.AgentTool;
import com.gsim.agentlib.tool.AgentTool.Permission;
import com.gsim.agentlib.tool.ToolCall;
import com.gsim.agentlib.tool.ToolResult;
import com.gsim.core.doc.DocCacheManager;
import com.gsim.core.ref.InlineRefResolver;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.NodeLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * write_element -- LLM 向节点检查点写入信息元素。
 *
 * <p>引用格式：{@code nodeId:checkpointId:key}（如 {@code n0002:characters:曹操}）
 * 或 {@code checkpointId:key}（默认写入当前活跃节点）。
 * 如果检查点不存在，会自动创建（type='misc'）。
 *
 * <p>支持两种写入模式：
 * <ul>
 *   <li>{@code replace}（默认）-- key 已存在时更新值，保留原始 createdAt</li>
 *   <li>{@code append} -- 始终添加新元素，即使 key 已存在</li>
 * </ul>
 *
 * <p>value 参数支持 {@code @cache:id} 引用，自动解析为缓存全文；也支持
 * {@code @doc:"docId"} / {@code @import:"file"} 内嵌引用，写入前展开为文档全文，
 * 引用无法解析时调用失败（[@DOC_REF_FAILED]）。
 *
 * <p>value 长度不做限制 — 写入多少取决于调用方（Agent）能输入多少，全文直接写入元素。
 * links 应使用相同的 {@code nodeId:checkpointId:key} 格式以便交叉引用。
 */
public final class WriteElementTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WriteElementTool.class);

    private final Supplier<WorldInformation> worldInfo;
    private final Path worldsDir;
    private final DocCacheManager cacheManager;
    private final InlineRefResolver inlineRefResolver;

    public WriteElementTool(
            Supplier<WorldInformation> worldInfo,
            Path worldsDir,
            DocCacheManager cacheManager,
            InlineRefResolver inlineRefResolver) {
        this.worldInfo = worldInfo;
        this.worldsDir = worldsDir;
        this.cacheManager = cacheManager;
        this.inlineRefResolver = inlineRefResolver;
    }

    @Override
    public String name() {
        return "write_element";
    }

    @Override
    public String description() {
        return """
            Write an information element to a checkpoint.
            ref format: nodeId:checkpointId:key (e.g. 'n0002:characters:曹操')
            or checkpointId:key to default to the current active node.
            If the checkpoint does not exist, it will be auto-created (type='misc').
            By default (mode='replace'), if the key already exists it will be upserted.
            Use mode='append' to always add a new element.
            links should use the same nodeId:checkpointId:key format for cross-references.
            """;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String ref = call.param("ref");
        if (ref == null || ref.isBlank()) {
            return ToolResult.fail(
                    "write_element", "ref is required (format: nodeId:checkpointId:key or checkpointId:key)");
        }

        // Parse ref: nodeId:checkpointId:key  or  checkpointId:key
        WorldInformation wi = worldInfo.get();
        String[] parts = ref.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            nodeId = call.param("nodeId");
            if (nodeId == null || nodeId.isBlank())
                return ToolResult.fail(name(), "[NODE_ID_REQUIRED] nodeId is required");
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            return ToolResult.fail(
                    "write_element",
                    "Invalid ref format: '" + ref + "'. Expected nodeId:checkpointId:key or checkpointId:key");
        }

        if (checkpointId.isEmpty() || key.isEmpty()) {
            return ToolResult.fail("write_element", "checkpointId and key must not be empty");
        }

        // Lazily load node from disk if not in the in-memory chain
        lazyLoadNode(wi, nodeId);

        String type = call.param("type");
        String value = call.param("value");
        String tagsStr = call.param("tags");
        String linksStr = call.param("links");
        String mode = call.param("mode");

        if (value == null || value.isBlank()) {
            return ToolResult.fail("write_element", "value is required");
        }

        // 解析 @cache: 引用
        if (cacheManager != null) {
            value = cacheManager.resolve(value);
        }

        // 内嵌引用解析：@doc:"id" / @import:"file" 展开为全文，未解析则失败
        InlineRefResolver.ResolveResult rr = inlineRefResolver.resolve(value);
        if (!rr.unresolved().isEmpty()) {
            return ToolResult.fail(name(), "[@DOC_REF_FAILED] 以下引用无法解析: " + String.join("; ", rr.unresolved()));
        }
        value = rr.text();

        List<String> tags = tagsStr != null && !tagsStr.isBlank() ? Arrays.asList(tagsStr.split(",")) : List.of();
        List<String> links = linksStr != null && !linksStr.isBlank()
                ? Arrays.stream(linksStr.split(",")).map(String::trim).toList()
                : List.of();

        // links 存在时逐条校验 — 任一条非法则整个调用失败且元素不写入
        for (String link : links) {
            if (!isValidLink(link)) {
                return ToolResult.fail(
                        name(),
                        "[INVALID_LINK] 非法链接格式: '"
                                + link
                                + "' — 期望 @world:|@doc:|@cache:|@import:|gsimap:|nodeId:checkpointId:key 或 checkpointId:key");
            }
        }

        String now = java.time.Instant.now().toString();
        String createdAt = now;

        // replace 模式时保留原始 createdAt
        if (!"append".equalsIgnoreCase(mode)) {
            var node = wi.nodeById(nodeId);
            if (node != null) {
                var cp = node.checkpoint(checkpointId);
                if (cp != null) {
                    for (var el : cp.elements()) {
                        if (el.key().equals(key) && el.createdAt() != null) {
                            createdAt = el.createdAt();
                            break;
                        }
                    }
                }
            }
        }

        Element element = new Element(key, type != null ? type : "text", value, tags, links, createdAt, now);

        boolean replaced;
        if ("append".equalsIgnoreCase(mode)) {
            wi.appendElement(nodeId, checkpointId, element);
            replaced = false;
        } else {
            replaced = wi.upsertElement(nodeId, checkpointId, element);
        }

        // persist
        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        NodeLoader.save(nodeFile, wi.nodeById(nodeId));

        String unifiedId = nodeId + ":" + checkpointId + ":" + key;
        String action = replaced ? "replaced" : "appended";
        return ToolResult.ok(
                "write_element", List.of(new ToolResult.Item(key, unifiedId + " (" + action + ")", value, 1.0)));
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "ref",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Target element reference: nodeId:checkpointId:key (e.g. 'n0002:characters:曹操') "
                                                        + "or checkpointId:key to write to the current active node"),
                                "type",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Element type: text, action, effect, narrative, character_state, etc. (default 'text')"),
                                "value", Map.of("type", "string", "description", "Element content"),
                                "tags", Map.of("type", "string", "description", "Comma-separated tags"),
                                "links",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Comma-separated cross-references in nodeId:checkpointId:key format"),
                                "mode",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "'replace' (default, upsert by key) or 'append' (always add new element)")),
                "required", List.of("ref", "value"));
    }

    @Override
    public boolean requiresWorldId() {
        return true;
    }

    @Override
    public Permission permission() {
        return Permission.WRITE;
    }

    /**
     * 校验单条链接格式 — {@code @world:|@doc:|@cache:|@import:|gsimap:|nodeId:checkpointId:key}
     * 前缀形式，或 {@code checkpointId:key} 两段形式。
     */
    private static boolean isValidLink(String link) {
        return link.matches("^(@world:|@doc:|@cache:|@import:|gsimap:|[a-z]+\\d{4}:)[^\\s]+$")
                || link.matches("^[^:\\s]+:[^:\\s]+$");
    }

    /**
     * Lazily load a node from disk into the WorldInformation if it is not already
     * in the in-memory chain. This allows writing to nodes that were created or
     * renamed on disk after the session started.
     */
    private void lazyLoadNode(WorldInformation wi, String nodeId) {
        if (wi.nodeById(nodeId) != null) return; // already loaded

        Path nodeFile = NodeLoader.nodeFile(worldsDir, wi.worldId(), nodeId);
        if (!Files.exists(nodeFile)) {
            throw new IllegalArgumentException("Unknown node: " + nodeId + " (not in chain and not on disk)");
        }
        try {
            NodeSnapshot node = NodeLoader.load(nodeFile);
            wi.ensureNode(node);
            log.info("Lazy-loaded node {} from disk into WorldInformation", nodeId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Failed to load node " + nodeId + " from disk: " + e.getMessage(), e);
        }
    }
}
