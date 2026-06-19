package com.gsim.root;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BootstrapWorldDraftGenerator Fallback")
class BootstrapFallbackTest {

    private final BootstrapWorldDraftGenerator generator =
            new BootstrapWorldDraftGenerator(null, "test-model");

    @Nested
    @DisplayName("明日方舟/泰拉关键词检测")
    class ArknightsDetection {
        @Test
        @DisplayName("明日方舟 → Arknights fallback")
        void arknightsKeywordsTriggerArknightsFallback() {
            var intent = BootstrapIntentParser.parse("明日方舟世界观推演", true);
            var draft = generator.generateFallback(intent);
            String worldMd = draft.worldMarkdown();
            // 必须包含泰拉专有名词
            assertTrue(worldMd.contains("泰拉"), "world.md should mention 泰拉");
            assertTrue(worldMd.contains("源石"), "world.md should mention 源石");
            assertTrue(worldMd.contains("矿石病"), "world.md should mention 矿石病");
            assertTrue(worldMd.contains("天灾"), "world.md should mention 天灾");
            assertTrue(worldMd.contains("移动城市"), "world.md should mention 移动城市");
            assertTrue(worldMd.contains("感染者"), "world.md should mention 感染者");
            assertTrue(worldMd.contains("源石技艺"), "world.md should mention 源石技艺");
            assertTrue(worldMd.contains("罗德岛"), "world.md should mention 罗德岛");
            assertTrue(worldMd.contains("整合运动"), "world.md should mention 整合运动");
            assertTrue(worldMd.contains("乌萨斯"), "world.md should mention 乌萨斯");
            assertTrue(worldMd.contains("龙门"), "world.md should mention 龙门");
            assertTrue(worldMd.contains("炎国"), "world.md should mention 炎国");
            assertTrue(worldMd.contains("维多利亚"), "world.md should mention 维多利亚");
            assertTrue(worldMd.contains("莱塔尼亚"), "world.md should mention 莱塔尼亚");
            assertTrue(worldMd.contains("卡西米尔"), "world.md should mention 卡西米尔");
            assertTrue(worldMd.contains("哥伦比亚"), "world.md should mention 哥伦比亚");
            assertTrue(worldMd.contains("拉特兰"), "world.md should mention 拉特兰");
            assertTrue(worldMd.contains("核验"), "world.md should mark content as pending verification");
        }

        @Test
        @DisplayName("泰拉 → Arknights fallback")
        void terraTriggersArknightsFallback() {
            var intent = BootstrapIntentParser.parse("泰拉大陆推演", true);
            var draft = generator.generateFallback(intent);
            assertTrue(draft.worldMarkdown().contains("泰拉"));
            assertTrue(draft.worldMarkdown().contains("源石"));
        }

        @Test
        @DisplayName("罗德岛 → Arknights fallback")
        void rhodesIslandTriggersArknightsFallback() {
            var intent = BootstrapIntentParser.parse("罗德岛制药公司", true);
            var draft = generator.generateFallback(intent);
            assertTrue(draft.worldMarkdown().contains("罗德岛"));
            assertTrue(draft.worldMarkdown().contains("源石"));
        }

        @Test
        @DisplayName("源石 → Arknights fallback")
        void originiumTriggersArknightsFallback() {
            var intent = BootstrapIntentParser.parse("源石能源系统", true);
            var draft = generator.generateFallback(intent);
            assertTrue(draft.worldMarkdown().contains("源石"));
            assertTrue(draft.worldMarkdown().contains("矿石病"));
        }

        @Test
        @DisplayName("矿石病 → Arknights fallback")
        void oripathyTriggersArknightsFallback() {
            var intent = BootstrapIntentParser.parse("矿石病治疗方案", true);
            var draft = generator.generateFallback(intent);
            assertTrue(draft.worldMarkdown().contains("矿石病"));
            assertTrue(draft.worldMarkdown().contains("感染者"));
        }

        @Test
        @DisplayName("entities.md 包含 Arknights 人物/势力")
        void entitiesMdContainsArknightsContent() {
            var intent = BootstrapIntentParser.parse("明日方舟", true);
            var draft = generator.generateFallback(intent);
            String entitiesMd = draft.entitiesMarkdown();
            assertTrue(entitiesMd.contains("罗德岛"));
            assertTrue(entitiesMd.contains("阿米娅"));
            assertTrue(entitiesMd.contains("博士"));
            assertTrue(entitiesMd.contains("凯尔希"));
            assertTrue(entitiesMd.contains("整合运动"));
            assertTrue(entitiesMd.contains("塔露拉"));
        }

        @Test
        @DisplayName("warnings 不包含【所有设定均为占位】")
        void warningsDoNotSayAllPlaceholder() {
            var intent = BootstrapIntentParser.parse("明日方舟", true);
            var draft = generator.generateFallback(intent);
            for (String w : draft.warnings()) {
                assertFalse(w.contains("所有设定均为占位"),
                        "warnings should not say '所有设定均为占位': " + w);
            }
        }
    }

    @Nested
    @DisplayName("通用 fallback")
    class GeneralFallback {
        @Test
        @DisplayName("非 Arknights 输入使用通用模板且不含占位警告")
        void generalInputNoPlaceholderWarning() {
            var intent = BootstrapIntentParser.parse("架空科幻世界", true);
            var draft = generator.generateFallback(intent);
            String worldMd = draft.worldMarkdown();
            assertTrue(worldMd.contains("待补全") || worldMd.contains("待用户补充"));
            // 不包含 Arknights 专有名词
            assertFalse(worldMd.contains("罗德岛"));

            // warnings 不包含"所有设定均为占位"
            for (String w : draft.warnings()) {
                assertFalse(w.contains("所有设定均为占位"),
                        "warnings should not say '所有设定均为占位': " + w);
            }
        }

        @Test
        @DisplayName("通用 fallback rules.md 包含可信度分级和写入规则")
        void generalRulesContainsCredibilityGrading() {
            var intent = BootstrapIntentParser.parse("架空世界", true);
            var draft = generator.generateFallback(intent);
            String rulesMd = draft.rulesMarkdown();
            assertTrue(rulesMd.contains("已核验"));
            assertTrue(rulesMd.contains("待核验"));
            assertTrue(rulesMd.contains("推演假设"));
            assertTrue(rulesMd.contains("设定可信度分级"));
            assertTrue(rulesMd.contains("player_action_append"));
            assertTrue(rulesMd.contains("simulation_content_append"));
            assertTrue(rulesMd.contains("turn_settlement_save"));
            assertTrue(rulesMd.contains("本回合行动不得写入 players.md"));
        }

        @Test
        @DisplayName("通用 fallback players.md 包含四个分区")
        void generalPlayersContainsFourSections() {
            var intent = BootstrapIntentParser.parse("架空世界", true);
            var draft = generator.generateFallback(intent);
            String playersMd = draft.playersMarkdown();
            assertTrue(playersMd.contains("玩家资料区"));
            assertTrue(playersMd.contains("人物卡区"));
            assertTrue(playersMd.contains("长期状态区"));
            assertTrue(playersMd.contains("关系与资源区"));
        }
    }

    @Nested
    @DisplayName("Generate full draft")
    class FullDraft {
        @Test
        @DisplayName("generate (no LLM) → fallback: warn about local fallback only")
        void generateNoLlmReturnsFallback() {
            var intent = BootstrapIntentParser.parse("测试世界", true);
            var draft = generator.generate(intent); // LLM is null, falls back
            assertNotNull(draft);
            assertNotNull(draft.rootIdSuggestion());
            assertFalse(draft.rootIdSuggestion().isBlank());
            assertNotNull(draft.title());
            assertFalse(draft.title().isBlank());
            // One warning about local fallback
            assertEquals(1, draft.warnings().size());
            String warning = draft.warnings().get(0);
            assertTrue(warning.contains("本地 fallback"));
            assertFalse(warning.contains("所有设定均为占位"));
        }
    }
}
