package com.gsim.root.tool;

import com.gsim.data.DataManager;
import com.gsim.root.BootstrapIntentParser;
import com.gsim.root.RootBootstrapPolicy;
import com.gsim.root.RootIdGenerator;
import com.gsim.tool.AgentTool;
import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Root Tool 工厂 — 创建带权限门禁的根节点管理工具。
 */
public class RootToolFactory {

    private static final Logger log = LoggerFactory.getLogger(RootToolFactory.class);

    private final DataManager dm;
    private final Consumer<String> onRootChanged;

    public RootToolFactory(DataManager dm, Consumer<String> onRootChanged) {
        this.dm = dm;
        this.onRootChanged = onRootChanged;
    }

    public List<AgentTool> createAll() {
        return List.of(
                new RootStatusTool(),
                new RootCreateTool(),
                new RootWorldGetTool(),
                new RootEntitiesGetTool(),
                new RootRulesGetTool(),
                new RootInitialInfoGetTool(),
                new RootWorldUpdateTool(),
                new RootEntitiesUpdateTool(),
                new RootRulesUpdateTool(),
                new RootInitialInfoUpdateTool(),
                new RootPlayersUpdateTool()
        );
    }

    // ===== root_status (always allowed, read-only) =====

    private class RootStatusTool implements AgentTool {
        @Override public String name() { return "root_status"; }
        @Override public String description() {
            return "查询当前 root 状态: active root ID, active branch ID, 是否在根节点, knowledge db path。只读，任意节点可用。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            StringBuilder sb = new StringBuilder();
            sb.append("activeRoot: ").append(dm.getActiveRootId()).append("\n");
            sb.append("activeBranch: ").append(dm.getActiveBranchId()).append("\n");
            sb.append("isAtRootBranch: ").append(dm.isAtRootBranch()).append("\n");
            sb.append("knowledgeDbPath: ").append(dm.getActiveKnowledgeDbPath()).append("\n");
            sb.append("emptyData: ").append(RootBootstrapPolicy.isStrictlyEmptyDataRoot(dm.getDataRoot()));
            return ToolResult.ok(name(), List.of(
                    new ToolResult.Item("root_status", dm.getActiveRootId() != null ? dm.getActiveRootId() : "none",
                            sb.toString(), 1.0)));
        }
    }

    // ===== root_create =====

    private class RootCreateTool implements AgentTool {
        @Override public String name() { return "root_create"; }
        @Override public String description() {
            return "创建新 root。仅允许: (1) data 严格为空, 或 (2) 当前在根节点 branch.b0000-start。" +
                   "参数: rootId(可选, 不填则自动生成), title(可选), worldContent(必填), switchToNewRoot(可选, 默认true)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            // 权限检查
            boolean emptyData = RootBootstrapPolicy.isStrictlyEmptyDataRoot(dm.getDataRoot());
            if (!emptyData && !dm.isAtRootBranch()) {
                return ToolResult.fail(name(), "NOT_AT_ROOT_BRANCH: root_create 仅在根节点或空 data 时允许。当前 branch: " + dm.getActiveBranchId());
            }

            String explicitRootId = call.param("rootId", "");
            String worldContent = call.param("worldContent", "");
            if (worldContent.isBlank()) {
                return ToolResult.fail(name(), "worldContent is required");
            }

            String rootId = RootIdGenerator.resolve(
                    explicitRootId.isBlank() ? null : explicitRootId, worldContent);
            String title = call.param("title", RootIdGenerator.extractTitle(worldContent));
            boolean switchTo = !"false".equalsIgnoreCase(call.param("switchToNewRoot", "true"));

            try {
                if (emptyData) {
                    dm.bootstrapFromEmpty(rootId, RootIdGenerator.buildWorldMarkdown(title, worldContent));
                } else {
                    dm.createRoot(rootId, RootIdGenerator.buildWorldMarkdown(title, worldContent));
                    if (switchTo) {
                        dm.switchWorld(rootId);
                    }
                }
                if (onRootChanged != null && switchTo) onRootChanged.accept(rootId);

                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item(title, rootId,
                                "status=OK rootId=" + rootId + " title=" + title
                                + " activeBranch=" + dm.getActiveBranchId()
                                + " isAtRoot=" + dm.isAtRootBranch(), 1.0)));
            } catch (Exception e) {
                log.error("root_create failed: {}", e.getMessage());
                return ToolResult.fail(name(), "ROOT_CREATE_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_world_update =====

    private class RootWorldUpdateTool implements AgentTool {
        @Override public String name() { return "root_world_update"; }
        @Override public String description() {
            return "修改当前 root 的 world.md。仅允许在根节点 (branch.b0000-start)。" +
                   "参数: mode(replace|append, 默认replace), content(必填), reason(可选)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.isAtRootBranch()) {
                return ToolResult.fail(name(), "NOT_AT_ROOT_BRANCH: 只能在根节点修改 world.md。当前: " + dm.getActiveBranchId());
            }
            if (!dm.hasActiveRoot()) {
                return ToolResult.fail(name(), "NO_ACTIVE_ROOT");
            }

            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");
            String mode = call.param("mode", "replace");

            try {
                Path file = dm.worldFilePath();
                if (Files.exists(file)) {
                    String existing = Files.readString(file);
                    String updated = "append".equals(mode) ? existing + "\n" + content : content;
                    dm.updateWorldFile(updated);
                } else {
                    dm.updateWorldFile(content);
                }
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("world.md", dm.getActiveRootId(),
                                "mode=" + mode + " rootId=" + dm.getActiveRootId(), 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "UPDATE_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_entities_update =====

    private class RootEntitiesUpdateTool implements AgentTool {
        @Override public String name() { return "root_entities_update"; }
        @Override public String description() {
            return "修改当前 root 的 entities.md。仅允许在根节点。参数: content(必填), mode(replace|append)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.isAtRootBranch()) {
                return ToolResult.fail(name(), "NOT_AT_ROOT_BRANCH: 只能在根节点修改 entities.md。");
            }
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");

            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");
            String mode = call.param("mode", "replace");

            try {
                if ("append".equals(mode) && Files.exists(dm.entitiesFilePath())) {
                    String existing = Files.readString(dm.entitiesFilePath());
                    dm.updateEntitiesFile(existing + "\n" + content);
                } else {
                    dm.updateEntitiesFile(content);
                }
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("entities.md", dm.getActiveRootId(),
                                "mode=" + mode, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "UPDATE_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_rules_update =====

    private class RootRulesUpdateTool implements AgentTool {
        @Override public String name() { return "root_rules_update"; }
        @Override public String description() {
            return "修改当前 root 的 rules.md。仅允许在根节点。参数: content(必填), mode(replace|append)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.isAtRootBranch()) {
                return ToolResult.fail(name(), "NOT_AT_ROOT_BRANCH: 只能在根节点修改 rules.md。");
            }
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");

            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");
            String mode = call.param("mode", "replace");

            try {
                if ("append".equals(mode) && Files.exists(dm.rulesFilePath())) {
                    String existing = Files.readString(dm.rulesFilePath());
                    dm.updateRulesFile(existing + "\n" + content);
                } else {
                    dm.updateRulesFile(content);
                }
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("rules.md", dm.getActiveRootId(),
                                "mode=" + mode, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "UPDATE_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_initial_info_update =====

    private class RootInitialInfoUpdateTool implements AgentTool {
        @Override public String name() { return "root_initial_info_update"; }
        @Override public String description() {
            return "修改根节点 b0000-start.md 的初始信息。仅允许在根节点。参数: content(必填), section(可选, 默认 一、本节点输入)。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.isAtRootBranch()) {
                return ToolResult.fail(name(), "NOT_AT_ROOT_BRANCH: 只能在根节点修改根节点信息。");
            }
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");

            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");

            try {
                dm.updateRootBranchSection("一、本节点输入", content);
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("b0000-start.md", dm.getActiveRootId(),
                                "updated initial info", 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "UPDATE_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_world_get (read-only, any node) =====

    private class RootWorldGetTool implements AgentTool {
        @Override public String name() { return "root_world_get"; }
        @Override public String description() {
            return "读取当前 root 的 world.md 内容。任意节点可读。读到内容后再计划修改方案。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");
            try {
                Path f = dm.worldFilePath();
                if (!Files.exists(f)) return ToolResult.fail(name(), "WORLD_FILE_NOT_FOUND");
                String content = Files.readString(f);
                // Limit to reasonable size for LLM context
                if (content.length() > 4000) content = content.substring(0, 3997) + "...";
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("world.md", dm.getActiveRootId(), content, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "READ_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_entities_get (read-only, any node) =====

    private class RootEntitiesGetTool implements AgentTool {
        @Override public String name() { return "root_entities_get"; }
        @Override public String description() {
            return "读取当前 root 的 entities.md 内容。任意节点可读。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");
            try {
                Path f = dm.entitiesFilePath();
                if (!Files.exists(f)) return ToolResult.fail(name(), "ENTITIES_FILE_NOT_FOUND");
                String content = Files.readString(f);
                if (content.length() > 4000) content = content.substring(0, 3997) + "...";
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("entities.md", dm.getActiveRootId(), content, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "READ_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_rules_get (read-only, any node) =====

    private class RootRulesGetTool implements AgentTool {
        @Override public String name() { return "root_rules_get"; }
        @Override public String description() {
            return "读取当前 root 的 rules.md 内容。任意节点可读。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");
            try {
                Path f = dm.rulesFilePath();
                if (!Files.exists(f)) return ToolResult.fail(name(), "RULES_FILE_NOT_FOUND");
                String content = Files.readString(f);
                if (content.length() > 4000) content = content.substring(0, 3997) + "...";
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("rules.md", dm.getActiveRootId(), content, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "READ_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_players_update =====

    private class RootPlayersUpdateTool implements AgentTool {
        @Override public String name() { return "root_players_update"; }
        @Override public String description() {
            return "修改当前 root 的 players.md（玩家资料/人物卡/长期状态）。仅允许在根节点。"
                    + "参数: content(必填), mode(replace|append, 默认append)。"
                    + "本工具用于维护玩家长期档案，不是记录本回合行动。本回合行动请用 player_action_append。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.isAtRootBranch()) {
                return ToolResult.fail(name(), "NOT_AT_ROOT_BRANCH: 只能在根节点修改 players.md。");
            }
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");

            String content = call.param("content", "");
            if (content.isBlank()) return ToolResult.fail(name(), "content is required");
            String mode = call.param("mode", "append");

            try {
                Path f = dm.getPlayersPath();
                if ("append".equals(mode) && Files.exists(f)) {
                    String existing = Files.readString(f);
                    dm.writePlayers(existing + "\n" + content);
                } else {
                    dm.writePlayers(content);
                }
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("players.md", dm.getActiveRootId(),
                                "mode=" + mode, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "UPDATE_FAILED: " + e.getMessage());
            }
        }
    }

    // ===== root_initial_info_get (read-only, any node) =====

    private class RootInitialInfoGetTool implements AgentTool {
        @Override public String name() { return "root_initial_info_get"; }
        @Override public String description() {
            return "读取根节点 b0000-start.md 的全文。任意节点可读。";
        }
        @Override
        public ToolResult execute(ToolCall call) {
            if (!dm.hasActiveRoot()) return ToolResult.fail(name(), "NO_ACTIVE_ROOT");
            try {
                Path f = dm.rootBranchFilePath();
                if (!Files.exists(f)) return ToolResult.fail(name(), "ROOT_BRANCH_FILE_NOT_FOUND");
                String content = Files.readString(f);
                if (content.length() > 4000) content = content.substring(0, 3997) + "...";
                return ToolResult.ok(name(), List.of(
                        new ToolResult.Item("b0000-start.md", dm.getActiveRootId(), content, 1.0)));
            } catch (Exception e) {
                return ToolResult.fail(name(), "READ_FAILED: " + e.getMessage());
            }
        }
    }
}
