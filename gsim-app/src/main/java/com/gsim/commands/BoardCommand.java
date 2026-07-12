package com.gsim.commands;

import com.gsim.doc.DocStore;
import com.gsim.doc.DocType;
import com.gsim.doc.Document;
import com.gsim.worldinfo.WorldInformation;

import java.io.IOException;
import java.util.List;

/**
 * /board — 与 World-Node 绑定的公开展示板管理。
 *
 * <pre>
 *   /board list              — 列出当前节点所有 board
 *   /board read &lt;id&gt;         — 读取指定 board 全文
 *   /board create &lt;title&gt;    — 在当前节点下创建新 board
 *   /board write &lt;id&gt; &lt;text&gt; — 写入 board（替换全文）
 *   /board append &lt;id&gt; &lt;text&gt; — 追加到 board
 * </pre>
 *
 * <p>Board 命名规则: {@code {worldId}-{nodeId}-{title}}
 * worldId-nodeId 前缀由系统自动拼接，用户只需提供 title。
 */
public final class BoardCommand {

    private final DocStore docStore;
    private final WorldInformation worldInfo;

    public BoardCommand(DocStore docStore, WorldInformation worldInfo) {
        this.docStore = docStore;
        this.worldInfo = worldInfo;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return usage();
        String sub = args.get(0);

        return switch (sub) {
            case "list" -> listBoards();
            case "read" -> args.size() < 2
                    ? "Usage: /board read <boardId>"
                    : readBoard(args.get(1));
            case "create" -> args.size() < 2
                    ? "Usage: /board create <title>"
                    : createBoard(joinArgs(args, 1));
            case "write" -> args.size() < 3
                    ? "Usage: /board write <boardId> <content>"
                    : writeBoard(args.get(1), joinArgs(args, 2));
            case "append" -> args.size() < 3
                    ? "Usage: /board append <boardId> <content>"
                    : appendBoard(args.get(1), joinArgs(args, 2));
            default -> "Unknown sub-command: " + sub + "\n" + usage();
        };
    }

    // ── 子命令 ──

    private String listBoards() {
        String prefix = boardPrefix();
        List<Document> all = docStore.list(DocType.BOARD, null);

        List<Document> matching = all.stream()
                .filter(d -> d.id().startsWith(prefix))
                .toList();

        if (matching.isEmpty()) {
            return "当前节点无 board。使用 /board create <title> 创建。\n"
                    + "  当前节点: " + currentWorld() + " / " + currentNode();
        }

        StringBuilder sb = new StringBuilder("## Boards — ")
                .append(currentWorld()).append(" / ").append(currentNode()).append("\n\n");
        sb.append("| # | boardId | 标题 | 大小 |\n");
        sb.append("|---|---------|------|------|\n");
        int idx = 1;
        for (Document d : matching) {
            String shortName = d.id().substring(prefix.length());
            int lines = d.content() != null ? d.content().split("\n").length : 0;
            sb.append("| ").append(idx++).append(" | `").append(d.id())
                    .append("` | ").append(d.title())
                    .append(" | ").append(lines).append(" 行 |\n");
        }
        return sb.toString();
    }

    private String readBoard(String boardId) {
        Document doc = docStore.get(boardId);
        if (doc == null) return "Board 不存在: " + boardId;
        return "## " + doc.title() + "\n\n" + doc.content();
    }

    private String createBoard(String title) {
        String docId = boardPrefix() + sanitize(title);
        try {
            Document doc = docStore.create(docId, DocType.BOARD, title, "# " + title + "\n", List.of("board"));
            if (doc == null) return "Board 已存在: " + docId;
            return "已创建 board: `" + docId + "`\n"
                    + "  Agent 可通过 doc_write(docId=\"" + docId + "\", content=\"...\") 写入内容。";
        } catch (IOException e) {
            return "创建失败: " + e.getMessage();
        }
    }

    private String writeBoard(String boardId, String content) {
        Document doc = docStore.get(boardId);
        if (doc == null) return "Board 不存在: " + boardId + "。使用 /board create <title> 创建。";
        try {
            docStore.updateContent(boardId, content);
            return "已写入 board: " + boardId + " (" + content.lines().count() + " 行)";
        } catch (IOException e) {
            return "写入失败: " + e.getMessage();
        }
    }

    private String appendBoard(String boardId, String content) {
        Document doc = docStore.get(boardId);
        if (doc == null) return "Board 不存在: " + boardId + "。使用 /board create <title> 创建。";
        try {
            docStore.updateContent(boardId, doc.content() + "\n" + content);
            return "已追加到 board: " + boardId;
        } catch (IOException e) {
            return "追加失败: " + e.getMessage();
        }
    }

    // ── 辅助 ──

    private String boardPrefix() {
        return currentWorld() + "-" + currentNode() + "-";
    }

    private String currentWorld() {
        return worldInfo != null ? worldInfo.worldId() : "default";
    }

    private String currentNode() {
        if (worldInfo == null) return "n0000";
        String node = worldInfo.activeNodeId();
        return node != null ? node : "n0000";
    }

    private static String sanitize(String title) {
        return title.trim().replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]", "_");
    }

    private static String joinArgs(List<String> args, int from) {
        return String.join(" ", args.subList(from, args.size()));
    }

    private static String usage() {
        return """
                /board — 公开展示板管理

                /board list              — 列出当前节点所有 board
                /board read <boardId>    — 读取指定 board
                /board create <title>    — 创建新 board
                /board write <boardId> <text> — 写入 board
                /board append <boardId> <text> — 追加到 board
                """;
    }
}
