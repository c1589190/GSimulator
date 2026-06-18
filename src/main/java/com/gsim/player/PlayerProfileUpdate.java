package com.gsim.player;

/**
 * 玩家档案更新操作记录。
 */
public record PlayerProfileUpdate(
        String playerName,
        String field,
        String content,
        boolean created
) {
    public static PlayerProfileUpdate created(String playerName, String field, String content) {
        return new PlayerProfileUpdate(playerName, field, content, true);
    }

    public static PlayerProfileUpdate updated(String playerName, String field, String content) {
        return new PlayerProfileUpdate(playerName, field, content, false);
    }
}
