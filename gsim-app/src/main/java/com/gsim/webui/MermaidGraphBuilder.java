package com.gsim.webui;

import com.gsim.worldinfo.NodeSnapshot;

import java.util.List;

/**
 * 从节点链构建 Mermaid gitgraph DSL 字符串。
 */
public final class MermaidGraphBuilder {

    private MermaidGraphBuilder() {}

    /**
     * 构建 Mermaid gitgraph 定义。
     *
     * @param chain        从根到活跃节点的线性链（根在索引 0）
     * @param activeNodeId 当前活跃节点 ID（用于高亮）
     * @return Mermaid gitgraph DSL 字符串
     */
    public static String build(List<NodeSnapshot> chain, String activeNodeId) {
        if (chain == null || chain.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("gitGraph\n");

        for (int i = 0; i < chain.size(); i++) {
            NodeSnapshot node = chain.get(i);
            String id = node.nodeId();
            boolean isActive = id.equals(activeNodeId);

            sb.append("   commit id: \"").append(id)
              .append("\" tag: \"Turn ").append(node.turn())
              .append(" | ").append(escapeMermaidLabel(node.worldTime())).append("\"");
            if (isActive) sb.append(" type: HIGHLIGHT");
            sb.append("\n");
        }

        return sb.toString();
    }

    /** Mermaid 标签中的引号需要转义。 */
    private static String escapeMermaidLabel(String label) {
        if (label == null) return "";
        return label.replace("\"", "\\\"");
    }
}
