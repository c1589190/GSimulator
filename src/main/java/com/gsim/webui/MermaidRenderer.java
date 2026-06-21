package com.gsim.webui;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import java.util.*;

public class MermaidRenderer {

    public static Map<String, Object> renderTimeline(DataManager dm) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (dm == null || dm.getActiveBranch() == null) {
            result.put("mermaid", "gitGraph\n    commit id: \"no-data\" tag: \"无数据\"");
            result.put("nodes", List.of());
            result.put("activeBranchId", "");
            return result;
        }

        String activeBranchId = dm.getActiveBranch();
        List<DataDocument> branches = dm.listBranches();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<DataDocument> chain = dm.getBranchChain(activeBranchId);

        StringBuilder sb = new StringBuilder("gitGraph\n");

        // Render main chain from root
        if (!chain.isEmpty()) {
            DataDocument root = chain.get(0);
            sb.append("    commit id: \"").append(shortId(root.id()))
                    .append("\" tag: \"").append(escapeTag(root.name())).append("\"\n");
            nodes.add(nodeInfo(root, activeBranchId));

            for (int i = 1; i < chain.size(); i++) {
                DataDocument node = chain.get(i);
                sb.append("    commit id: \"").append(shortId(node.id()))
                        .append("\" tag: \"").append(escapeTag(node.name())).append("\"\n");
                nodes.add(nodeInfo(node, activeBranchId));
            }
        }

        // Render side branches
        Set<String> chainIds = new HashSet<>();
        for (DataDocument c : chain) chainIds.add(c.id());
        for (DataDocument branch : branches) {
            if (!chainIds.contains(branch.id())) {
                Map<String, String> fm = branch.frontMatter();
                String parent = fm.getOrDefault("parent", "");
                if (!parent.isBlank()) {
                    sb.append("    branch ").append(shortId(branch.id())).append("\n");
                    sb.append("    checkout ").append(shortId(branch.id())).append("\n");
                    sb.append("    commit id: \"").append(shortId(branch.id()))
                            .append("\" tag: \"").append(escapeTag(branch.name())).append("\"\n");
                    sb.append("    checkout main\n");
                    nodes.add(nodeInfo(branch, activeBranchId));
                }
            }
        }

        result.put("mermaid", sb.toString());
        result.put("nodes", nodes);
        result.put("activeBranchId", activeBranchId);
        return result;
    }

    private static Map<String, Object> nodeInfo(DataDocument doc, String activeBranchId) {
        Map<String, String> fm = doc.frontMatter();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", doc.id());
        info.put("name", doc.name());
        info.put("turn", fm.getOrDefault("turn", "0"));
        info.put("worldTime", fm.getOrDefault("world_time", "?"));
        info.put("status", fm.getOrDefault("status", "active"));
        info.put("isActive", doc.id().equals(activeBranchId));
        info.put("updated", doc.updated());
        return info;
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        int dot = id.lastIndexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    private static String escapeTag(String name) {
        if (name == null || name.isBlank()) return "?";
        return name.replace("\"", "'").replace("#", "");
    }
}
