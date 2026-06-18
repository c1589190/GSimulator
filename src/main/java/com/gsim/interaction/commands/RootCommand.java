package com.gsim.interaction.commands;

import com.gsim.data.DataManager;
import com.gsim.interaction.InteractionCommand;
import com.gsim.interaction.InteractionResult;
import com.gsim.interaction.InteractionSession;
import com.gsim.knowledge.scope.ScopedKnowledgeStoreFactory;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;

import java.util.List;
import java.util.function.Consumer;

/**
 * /root 命令 — 根节点工作区管理。
 *
 * <p>子命令：
 * <ul>
 *   <li>/root status — 显示当前 root 状态</li>
 *   <li>/root list — 列出所有 root</li>
 *   <li>/root create &lt;rootId&gt; &lt;初始设定&gt; — 创建新 root</li>
 *   <li>/root switch &lt;rootId&gt; — 切换 root</li>
 *   <li>/root delete &lt;rootId&gt; --confirm &lt;rootId&gt; — 删除 root</li>
 * </ul>
 */
public class RootCommand implements InteractionCommand {

    private final DataManager dataManager;
    private final ScopedKnowledgeStoreFactory storeFactory;
    private final Consumer<String> onRootChanged; // callback: 切换/创建 root 后通知

    public RootCommand(DataManager dataManager,
                       ScopedKnowledgeStoreFactory storeFactory,
                       Consumer<String> onRootChanged) {
        this.dataManager = dataManager;
        this.storeFactory = storeFactory;
        this.onRootChanged = onRootChanged;
    }

    @Override
    public String name() { return "root"; }

    @Override
    public String description() {
        return "根节点工作区管理：/root status|list|create|switch|delete";
    }

    @Override
    public String usage() {
        return """
                /root status
                /root list
                /root create <rootId> <初始设定文本>
                /root switch <rootId>
                /root delete <rootId> --confirm <rootId>
                """;
    }

    @Override
    public InteractionResult execute(String[] args, InteractionSession session) {
        if (args.length == 0) return InteractionResult.fail("用法: " + usage());

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "status" -> doStatus();
            case "list" -> doList();
            case "create" -> doCreate(args);
            case "switch" -> doSwitch(args);
            case "delete" -> doDelete(args);
            default -> InteractionResult.fail("未知子命令: " + sub + "\n用法: " + usage());
        };
    }

    private InteractionResult doStatus() {
        if (dataManager.needsRootBootstrap()) {
            return InteractionResult.ok("当前没有 root。使用 /root create 创建，或输入第一条世界观描述自动初始化。");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Active Root: ").append(dataManager.getActiveRootId()).append("\n");
        sb.append("Active Branch: ").append(dataManager.getActiveBranch()).append("\n");
        sb.append("Root Directory: data/worlds/").append(dataManager.getActiveRootId()).append("/\n");
        var dbPath = dataManager.getActiveKnowledgeDbPath();
        sb.append("Knowledge DB: ").append(dbPath != null ? dbPath : "(none)").append("\n");
        sb.append("Root Count: ").append(dataManager.listRootIds().size()).append("\n");
        sb.append("Roots: ").append(String.join(", ", dataManager.listRootIds())).append("\n");
        sb.append("Documents Loaded: ").append(dataManager.docCount()).append("\n");
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doList() {
        List<String> roots = dataManager.listRootIds();
        if (roots.isEmpty()) {
            return InteractionResult.ok("没有 root。使用 /root create 创建第一个 root。");
        }
        StringBuilder sb = new StringBuilder();
        for (String r : roots) {
            String marker = r.equals(dataManager.getActiveRootId()) ? " *" : "";
            sb.append("  ").append(r).append(marker).append("\n");
        }
        sb.append("\n* = active root");
        return InteractionResult.ok(sb.toString().trim());
    }

    private InteractionResult doCreate(String[] args) {
        if (args.length < 2) {
            return InteractionResult.fail("用法: /root create <rootId> <初始设定文本>");
        }
        String rootId = args[1];
        if (!rootId.matches("[a-zA-Z0-9._-]+")) {
            return InteractionResult.fail("rootId 只允许 [a-zA-Z0-9._-]，收到: " + rootId);
        }

        String worldContent = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";

        try {
            dataManager.createRoot(rootId, worldContent);
            dataManager.switchWorld(rootId);
            if (onRootChanged != null) onRootChanged.accept(rootId);
            return InteractionResult.ok("Root '" + rootId + "' 已创建并切换。");
        } catch (Exception e) {
            return InteractionResult.fail(e.getMessage());
        }
    }

    private InteractionResult doSwitch(String[] args) {
        if (args.length < 2) {
            return InteractionResult.fail("用法: /root switch <rootId>");
        }
        String rootId = args[1];
        try {
            dataManager.switchWorld(rootId);
            if (onRootChanged != null) onRootChanged.accept(rootId);
            return InteractionResult.ok("已切换到 root '" + rootId + "'。");
        } catch (Exception e) {
            return InteractionResult.fail(e.getMessage());
        }
    }

    private InteractionResult doDelete(String[] args) {
        if (args.length < 2) {
            return InteractionResult.fail("用法: /root delete <rootId> --confirm <rootId>");
        }
        String rootId = args[1];

        // 检查 --confirm
        if (args.length < 4 || !"--confirm".equals(args[2])) {
            return InteractionResult.fail("删除 root 需要确认。用法: /root delete " + rootId + " --confirm " + rootId);
        }
        if (!rootId.equals(args[3])) {
            return InteractionResult.fail("confirm 值不匹配。期望: " + rootId + " 收到: " + args[3]);
        }

        // 不能删除 active root
        if (rootId.equals(dataManager.getActiveRootId())) {
            return InteractionResult.fail("Cannot delete active root. Switch to another root first.");
        }

        try {
            dataManager.deleteRoot(rootId);
            return InteractionResult.ok("Root '" + rootId + "' 已删除。");
        } catch (Exception e) {
            return InteractionResult.fail(e.getMessage());
        }
    }
}
