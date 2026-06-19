package com.gsim.branch.tool;

import com.gsim.data.DataDocument;
import com.gsim.data.DataManager;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * branch_list — 列出当前 root 下所有 branch 节点的结构化清单。
 *
 * <p>只读，不切换 active branch。返回每个节点的 branchId、name、parent、children、
 * turn、status、world_time、actionCount、simContentCount、settlementCount、nodeOverview preview。
 */
public class BranchListTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BranchListTool.class);
    public static final String NAME = "branch_list";

    private final DataManager dm;

    public BranchListTool(DataManager dm) {
        this.dm = dm;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public String description() {
        return "列出当前 root 下所有 branch 节点的结构化清单。只读，不切换 active branch。"
                + "每个节点返回：branchId、name、parent、children、turn、status、world_time、"
                + "actionCount、simContentCount、settlementCount、nodeOverview preview。"
                + "参数: mode (可选, flat|tree, 默认flat)。flat 模式按 branchId 排序平铺，"
                + "tree 模式标记 active 节点并缩进显示父子关系。";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        if (!dm.hasActiveRoot()) {
            return ToolResult.fail(NAME, "NO_ACTIVE_ROOT");
        }

        String mode = call.param("mode", "flat");
        String activeBranch = dm.getActiveBranchId();

        try {
            List<DataDocument> allBranches = dm.listBranches();

            if (allBranches.isEmpty()) {
                return ToolResult.ok(NAME, List.of(
                        new ToolResult.Item("Branches", dm.getActiveRootId(),
                                "rootId: " + dm.getActiveRootId() + "\n"
                                + "（当前 root 下没有 branch 节点）\n",
                                1.0)));
            }

            // 收集所有 parent 关系以计算 children
            Map<String, List<DataDocument>> childrenMap = new java.util.LinkedHashMap<>();
            Map<String, DataDocument> docMap = new java.util.LinkedHashMap<>();
            for (DataDocument doc : allBranches) {
                docMap.put(doc.id(), doc);
                String parent = doc.frontMatter().getOrDefault("parent", "none");
                childrenMap.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(doc);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("rootId: ").append(dm.getActiveRootId()).append("\n");
            sb.append("activeBranch: ").append(activeBranch).append("\n");
            sb.append("totalBranches: ").append(allBranches.size()).append("\n\n");

            if ("tree".equalsIgnoreCase(mode)) {
                renderTree(sb, allBranches, docMap, childrenMap, activeBranch);
            } else {
                renderFlat(sb, allBranches, docMap, childrenMap, activeBranch);
            }

            return ToolResult.ok(NAME, List.of(
                    new ToolResult.Item("Branches in " + dm.getActiveRootId(),
                            dm.getActiveRootId(), sb.toString().trim(), 1.0)));
        } catch (Exception e) {
            log.error("branch_list failed: {}", e.getMessage());
            return ToolResult.fail(NAME, "LIST_FAILED: " + e.getMessage());
        }
    }

    private void renderFlat(StringBuilder sb, List<DataDocument> allBranches,
                            Map<String, DataDocument> docMap,
                            Map<String, List<DataDocument>> childrenMap,
                            String activeBranch) {
        for (DataDocument doc : allBranches) {
            appendNodeEntry(sb, doc, docMap, childrenMap, activeBranch, 0);
        }
    }

    private void renderTree(StringBuilder sb, List<DataDocument> allBranches,
                            Map<String, DataDocument> docMap,
                            Map<String, List<DataDocument>> childrenMap,
                            String activeBranch) {
        // 找到根节点 (parent=none)
        Set<String> rendered = new HashSet<>();
        for (DataDocument doc : allBranches) {
            String parent = doc.frontMatter().getOrDefault("parent", "none");
            if ("none".equals(parent)) {
                renderTreeRecursive(sb, doc, docMap, childrenMap, activeBranch, rendered, 0);
            }
        }
        // 处理孤立的（没有 parent=none 但也没有被渲染的）
        for (DataDocument doc : allBranches) {
            if (!rendered.contains(doc.id())) {
                sb.append("[orphan] ");
                appendNodeEntry(sb, doc, docMap, childrenMap, activeBranch, 0);
                rendered.add(doc.id());
            }
        }
    }

    private void renderTreeRecursive(StringBuilder sb, DataDocument doc,
                                      Map<String, DataDocument> docMap,
                                      Map<String, List<DataDocument>> childrenMap,
                                      String activeBranch, Set<String> rendered, int depth) {
        if (!rendered.add(doc.id())) return; // 防止环
        appendNodeEntry(sb, doc, docMap, childrenMap, activeBranch, depth);
        List<DataDocument> children = childrenMap.getOrDefault(doc.id(), List.of());
        for (DataDocument child : children) {
            renderTreeRecursive(sb, child, docMap, childrenMap, activeBranch, rendered, depth + 1);
        }
    }

    private void appendNodeEntry(StringBuilder sb, DataDocument doc,
                                  Map<String, DataDocument> docMap,
                                  Map<String, List<DataDocument>> childrenMap,
                                  String activeBranch, int depth) {
        Map<String, String> fm = doc.frontMatter();
        String id = doc.id();
        boolean isActive = id.equals(activeBranch);

        if (depth > 0) {
            sb.append("  ".repeat(depth));
        }
        sb.append(isActive ? "▶ " : "  ");
        sb.append(id);
        if (isActive) sb.append(" [ACTIVE]");

        String name = fm.getOrDefault("name", "");
        if (!name.isBlank()) sb.append(" \"").append(name).append("\"");

        sb.append("\n");
        sb.append("  ".repeat(depth + 1));
        sb.append("parent: ").append(fm.getOrDefault("parent", "none"));

        List<DataDocument> children = childrenMap.getOrDefault(id, List.of());
        if (!children.isEmpty()) {
            sb.append("  children: [");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(children.get(i).id());
            }
            sb.append("]");
        }

        sb.append("  turn: ").append(fm.getOrDefault("turn", "?"));
        sb.append("  status: ").append(fm.getOrDefault("status", "?"));
        String wt = fm.getOrDefault("world_time", "");
        if (!wt.isBlank()) sb.append("  world_time: ").append(wt);

        // 读取轻量状态
        try {
            String markdown = dm.readBranchFile(id);
            BranchFileSimContent.NodeLightStatus status =
                    BranchFileSimContent.getNodeLightStatus(markdown);
            sb.append("\n");
            sb.append("  ".repeat(depth + 1));
            sb.append("actionCount: ").append(status.actionCount());
            sb.append("  simContentCount: ").append(status.simContentCount());
            sb.append("  settlementCount: ").append(status.settlementCount());
            if (!status.nodeOverview().isBlank()) {
                String preview = status.nodeOverview().replace("\n", " ");
                if (preview.length() > 120) preview = preview.substring(0, 117) + "...";
                sb.append("\n");
                sb.append("  ".repeat(depth + 1));
                sb.append("overview: ").append(preview);
            }
        } catch (Exception e) {
            sb.append("\n");
            sb.append("  ".repeat(depth + 1));
            sb.append("(无法读取状态: ").append(e.getMessage()).append(")");
        }

        sb.append("\n\n");
    }
}
