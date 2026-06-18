package com.gsim.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlayerProfileParser")
class PlayerProfileParserTest {

    @Test
    @DisplayName("应正确解析单个玩家档案")
    void shouldParseSingleProfile() {
        String raw = """
                # 玩家档案

                ## 罗文·艾尔德
                * 类型：玩家角色
                * 阵营：市政议会
                * 身份：市政议会书记官
                * 控制资源：未设定
                * 公开目标：维持灰桥城贸易秩序
                * 隐藏倾向：未设定
                * 当前状态：未设定
                * 关系：
                  * 未设定：未设定
                * 备注：无
                """;

        List<PlayerProfile> profiles = PlayerProfileParser.parse(raw);
        assertEquals(1, profiles.size());

        PlayerProfile p = profiles.get(0);
        assertEquals("罗文·艾尔德", p.name());
        assertEquals("玩家角色", p.type());
        assertEquals("市政议会", p.faction());
        assertEquals("市政议会书记官", p.identity());
        assertEquals("维持灰桥城贸易秩序", p.publicGoal());
    }

    @Test
    @DisplayName("应正确解析多个玩家档案")
    void shouldParseMultipleProfiles() {
        String raw = """
                # 玩家档案

                ## 罗文·艾尔德
                * 类型：玩家角色
                * 阵营：市政议会
                * 身份：书记官
                * 控制资源：未设定
                * 公开目标：维持贸易秩序
                * 隐藏倾向：未设定
                * 当前状态：未设定
                * 关系：
                  * 未设定：未设定
                * 备注：无

                ## 玛拉·铁脉
                * 类型：玩家角色
                * 阵营：矿业行会
                * 身份：执事
                * 控制资源：铁矿矿脉
                * 公开目标：矿区自治
                * 隐藏倾向：未设定
                * 当前状态：未设定
                * 关系：
                  * 未设定：未设定
                * 备注：无
                """;

        List<PlayerProfile> profiles = PlayerProfileParser.parse(raw);
        assertEquals(2, profiles.size());
        assertEquals("罗文·艾尔德", profiles.get(0).name());
        assertEquals("玛拉·铁脉", profiles.get(1).name());
        assertEquals("矿业行会", profiles.get(1).faction());
        assertEquals("铁矿矿脉", profiles.get(1).resources());
    }

    @Test
    @DisplayName("空输入应返回空列表")
    void shouldReturnEmptyForBlankInput() {
        assertTrue(PlayerProfileParser.parse("").isEmpty());
        assertTrue(PlayerProfileParser.parse(null).isEmpty());
    }
}
