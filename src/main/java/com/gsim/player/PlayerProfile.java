package com.gsim.player;

/**
 * 玩家档案 — 记录当前世界中一个玩家角色的设定信息。
 * 不可变 record，修改通过 PlayerProfileManager 写回 players.md。
 */
public record PlayerProfile(
        String name,
        String type,
        String faction,
        String identity,
        String resources,
        String publicGoal,
        String hiddenTendency,
        String currentStatus,
        String relationships,
        String notes,
        String rawSection
) {
    public static final String DEFAULT_TYPE = "玩家角色";
    public static final String UNSET = "未设定";

    /** 创建新玩家默认模板。 */
    public static PlayerProfile createTemplate(String name) {
        return new PlayerProfile(name, DEFAULT_TYPE, UNSET, UNSET,
                UNSET, UNSET, UNSET, UNSET, UNSET, UNSET, "");
    }

    /** 序列化为 Markdown ## 二级标题段。 */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(name).append("\n");
        sb.append("* 类型：").append(blankToDefault(type)).append("\n");
        sb.append("* 阵营：").append(blankToDefault(faction)).append("\n");
        sb.append("* 身份：").append(blankToDefault(identity)).append("\n");
        sb.append("* 控制资源：").append(blankToDefault(resources)).append("\n");
        sb.append("* 公开目标：").append(blankToDefault(publicGoal)).append("\n");
        sb.append("* 隐藏倾向：").append(blankToDefault(hiddenTendency)).append("\n");
        sb.append("* 当前状态：").append(blankToDefault(currentStatus)).append("\n");
        sb.append("* 关系：\n");
        if (relationships == null || relationships.isBlank() || UNSET.equals(relationships)) {
            sb.append("  * ").append(UNSET).append("：").append(UNSET).append("\n");
        } else {
            for (String line : relationships.split("\n")) {
                sb.append("  ").append(line.trim().isEmpty() ? "" : line).append("\n");
            }
        }
        sb.append("* 备注：").append(notes != null && !notes.isBlank() ? notes : "无").append("\n");
        return sb.toString();
    }

    /** 复制并替换指定字段。 */
    public PlayerProfile withField(String field, String value) {
        String actualValue = value != null ? value : UNSET;
        return switch (field) {
            case "type", "类型" -> new PlayerProfile(name, actualValue, faction, identity, resources,
                    publicGoal, hiddenTendency, currentStatus, relationships, notes, rawSection);
            case "faction", "阵营" -> new PlayerProfile(name, type, actualValue, identity, resources,
                    publicGoal, hiddenTendency, currentStatus, relationships, notes, rawSection);
            case "identity", "身份" -> new PlayerProfile(name, type, faction, actualValue, resources,
                    publicGoal, hiddenTendency, currentStatus, relationships, notes, rawSection);
            case "resources", "资源" -> new PlayerProfile(name, type, faction, identity, actualValue,
                    publicGoal, hiddenTendency, currentStatus, relationships, notes, rawSection);
            case "publicGoal", "公开目标" -> new PlayerProfile(name, type, faction, identity, resources,
                    actualValue, hiddenTendency, currentStatus, relationships, notes, rawSection);
            case "hiddenTendency", "隐藏倾向" -> new PlayerProfile(name, type, faction, identity, resources,
                    publicGoal, actualValue, currentStatus, relationships, notes, rawSection);
            case "currentStatus", "当前状态", "status" -> new PlayerProfile(name, type, faction, identity, resources,
                    publicGoal, hiddenTendency, actualValue, relationships, notes, rawSection);
            case "relationships", "关系" -> new PlayerProfile(name, type, faction, identity, resources,
                    publicGoal, hiddenTendency, currentStatus, actualValue, notes, rawSection);
            case "notes", "备注" -> new PlayerProfile(name, type, faction, identity, resources,
                    publicGoal, hiddenTendency, currentStatus, relationships, actualValue, rawSection);
            default -> this;
        };
    }

    /** 追加备注。 */
    public PlayerProfile withAppendedNote(String note) {
        String newNotes;
        if (notes == null || notes.isBlank() || "无".equals(notes)) {
            newNotes = note;
        } else {
            newNotes = notes + "\n" + note;
        }
        return new PlayerProfile(name, type, faction, identity, resources,
                publicGoal, hiddenTendency, currentStatus, relationships, newNotes, rawSection);
    }

    private static String blankToDefault(String s) {
        return (s == null || s.isBlank()) ? UNSET : s;
    }
}
