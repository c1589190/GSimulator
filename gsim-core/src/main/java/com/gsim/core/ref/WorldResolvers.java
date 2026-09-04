package com.gsim.core.ref;

import com.gsim.agentsmanager.ref.RefResolver.ResolvedRef;
import com.gsim.agentsmanager.ref.Resolver;
import com.gsim.agentsmanager.ref.ResolverContext;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.ActiveStateManager;
import com.gsim.core.worldinfo.loader.WorldManager;
import com.gsim.docslib.doc.Document;

/**
 * @world: / 裸引用解析器 — B 方案依赖反转：gsim-core 实现 agentsmanager 的 {@link Resolver} 接口，
 * app 装配时注册进 {@link com.gsim.agentsmanager.ref.ResolverRegistry}。
 *
 * <p>提供两个解析器：{@link WorldRefResolver}（prefix=@world）与 {@link BareRefResolver}（prefix=""，
 * nodeId:cpId:key / cpId:key 裸引用，按 @world 语义解析）。
 *
 * <p>两段式 {@code @world:cpId:key} 解析到活跃节点（turn 最大）。
 */
public final class WorldResolvers {

    private WorldResolvers() {}

    /** @world: 三段式 / 两段式（两段式解析到活跃节点）。 */
    public static final class WorldRefResolver implements Resolver {

        @Override
        public String prefix() {
            return "@world";
        }

        @Override
        public ResolvedRef resolve(String path, ResolverContext ctx) {
            return resolveWorldPath(path, ctx);
        }
    }

    /** 裸引用兜底：nodeId:cpId:key / cpId:key，按 @world 语义解析。 */
    public static final class BareRefResolver implements Resolver {

        @Override
        public String prefix() {
            return "";
        }

        @Override
        public ResolvedRef resolve(String path, ResolverContext ctx) {
            return resolveWorldPath(path, ctx);
        }
    }

    /**
     * @world 语义的路径解析（@world: 与裸引用共用）：三段式走显式节点，两段式走活跃节点。
     */
    private static ResolvedRef resolveWorldPath(String path, ResolverContext ctx) {
        if (path.isBlank()) throw new IllegalArgumentException("@world: path must not be blank");

        if (ctx.activeWorldId() == null || ctx.activeWorldId().isBlank()) {
            throw new IllegalStateException("No active world set");
        }

        String[] parts = path.split(":", 3);
        String nodeId, checkpointId, key;

        if (parts.length == 2) {
            nodeId = null; // 两段式 → 活跃节点
            checkpointId = parts[0].trim();
            key = parts[1].trim();
        } else if (parts.length == 3) {
            nodeId = parts[0].trim();
            checkpointId = parts[1].trim();
            key = parts[2].trim();
        } else {
            throw new IllegalArgumentException(
                    "@world: path must be <nodeId>:<cpId>:<key> or <cpId>:<key>. Got: " + path);
        }

        ActiveStateManager.ActiveState active = ActiveStateManager.load(ctx.worldsDir(), ctx.activeWorldId());
        if (active == null) {
            throw new IllegalStateException("World has no active state: " + ctx.activeWorldId());
        }

        WorldManager worldManager = new WorldManager(ctx.worldsDir());
        // 两段式解析到活跃节点（turn 最大），而非硬编码 "n0000"
        String resolveNodeId = nodeId != null ? nodeId : worldManager.activeNodeId(ctx.activeWorldId());
        if (resolveNodeId == null) {
            throw new IllegalStateException("Cannot determine active node for world: " + ctx.activeWorldId());
        }

        WorldInformation wi = worldManager.loadWorld(ctx.activeWorldId());
        if (wi == null) {
            throw new IllegalStateException("Cannot load world: " + ctx.activeWorldId());
        }

        NodeSnapshot node = wi.nodeById(resolveNodeId);
        if (node == null) {
            throw new IllegalArgumentException("Node not found: " + resolveNodeId);
        }

        var cp = node.checkpoint(checkpointId);
        if (cp == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + checkpointId + " in node " + resolveNodeId);
        }

        Element found = null;
        for (Element el : cp.elements()) {
            if (el.key().equals(key)) {
                found = el;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "Element not found: " + key + " in " + resolveNodeId + ":" + checkpointId);
        }

        String id = resolveNodeId + ":" + checkpointId + ":" + key;
        String title = key + " @" + resolveNodeId + " (turn " + node.turn() + ")";

        // route_to_doc：自动解析 @doc:xxx → Doc 全文
        String content = found.value();
        if ("route_to_doc".equals(found.type())
                && content != null
                && content.startsWith("@doc:")
                && ctx.docStore() != null) {
            String docId = content.substring(5).trim();
            if (!docId.isEmpty()) {
                Document doc = ctx.docStore().get(docId);
                if (doc != null) {
                    content = doc.content();
                    title = doc.title() + " (via " + id + ")";
                }
            }
        }

        return new ResolvedRef("world", id, title, content);
    }
}
