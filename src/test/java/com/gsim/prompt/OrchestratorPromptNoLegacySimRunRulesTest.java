package com.gsim.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 orchestrator-system.md 不再包含与统一 Agent 入口冲突的旧 /sim /run 规则。
 */
@DisplayName("Orchestrator Prompt No Legacy /sim /run Rules")
class OrchestratorPromptNoLegacySimRunRulesTest {

    private static String promptContent;

    static {
        try {
            // 通过 classpath 定位文件
            Path promptPath = Path.of("src/main/resources/gsim/prompts/orchestrator-system.md");
            promptContent = Files.readString(promptPath);
        } catch (IOException e) {
            promptContent = null;
        }
    }

    @Test
    @DisplayName("prompt 文件存在且可读")
    void promptFileExists() {
        assertNotNull(promptContent, "orchestrator-system.md 应存在且可读");
        assertFalse(promptContent.isBlank(), "prompt 内容不应为空");
    }

    @Test
    @DisplayName("不包含旧 /sim 独占指令")
    void noLegacySimInstruction() {
        // "/sim 时……" 这种独占指令不应出现
        assertFalse(promptContent.contains("/sim 时"),
                "不应有 /sim 独占指令，已改为统一 Agent 入口");
    }

    @Test
    @DisplayName("不包含旧 /run 独占指令")
    void noLegacyRunInstruction() {
        assertFalse(promptContent.contains("/run 时"),
                "不应有 /run 独占指令，已改为统一 Agent 入口");
    }

    @Test
    @DisplayName("声明 /sim 和 /run 已废弃")
    void declaresSimAndRunDeprecated() {
        assertTrue(promptContent.contains("/sim") || promptContent.contains("/run"),
                "应提到 /sim /run（以声明已废弃）");
        assertTrue(promptContent.contains("废弃"),
                "应明确声明 /sim 和 /run 已废弃");
    }

    @Test
    @DisplayName("不应要求只输出 JSON 工具调用（与统一 Agent 冲突）")
    void noJsonOnlyOutputRule() {
        assertFalse(promptContent.contains("不要输出其他文本，只输出 JSON 工具调用"),
                "不应有只输出 JSON 工具调用的独占规则");
    }

    @Test
    @DisplayName("应包含统一自然语言入口说明")
    void hasUnifiedNaturalLanguageEntry() {
        assertTrue(promptContent.contains("统一自然语言"),
                "应说明通过统一自然语言 Agent 入口工作");
    }

    @Test
    @DisplayName("应说明不需要工具时直接自然语言回答")
    void hasNaturalLanguageFallbackRule() {
        assertTrue(
                promptContent.contains("不需要工具时") || promptContent.contains("直接自然语言"),
                "应说明不需要工具时直接自然语言回答");
    }

    @Test
    @DisplayName("应包含工具调用规则不冲突")
    void hasConsistentToolCallingRules() {
        // "当需要调用工具时" (旧) 或 "API tool_calls 调用工具" (新)
        boolean hasWhenNeeded = promptContent.contains("当需要调用工具时")
                || promptContent.contains("API tool_calls 调用工具")
                || promptContent.contains("工具调用 JSON")
                || promptContent.contains("工具调用规则");
        boolean hasNaturalLanguage = promptContent.contains("自然语言");
        assertTrue(hasWhenNeeded, "应说明何时使用工具调用");
        assertTrue(hasNaturalLanguage, "应说明何时自然语言回答");
    }

    @Test
    @DisplayName("应说明区分 facts / inferences / hypotheses")
    void hasFactInferenceHypothesisRule() {
        assertTrue(promptContent.contains("facts") || promptContent.contains("已知事实"),
                "应要求区分已知事实");
        assertTrue(promptContent.contains("inferences") || promptContent.contains("推断"),
                "应要求区分推断");
        assertTrue(promptContent.contains("hypotheses") || promptContent.contains("假设"),
                "应要求区分假设");
    }

    @Test
    @DisplayName("应说明不要要求用户改用 /sim 或 /run")
    void noAskUserToSwitchSimRun() {
        assertTrue(
                promptContent.contains("不要要求用户改用 /sim") ||
                        promptContent.contains("不要要求用户改用/sim"),
                "应说明不要要求用户改用 /sim 或 /run");
    }

    @Test
    @DisplayName("应说明 knowledge_search 无 embedding 时改用 keyword_search")
    void hasKnowledgeFallbackInstruction() {
        assertTrue(
                promptContent.contains("NO_ACTIVE_EMBEDDING_PROFILE") ||
                        promptContent.contains("NO_EMBEDDINGS_FOR_PROFILE"),
                "应提到 NO_ACTIVE_EMBEDDING_PROFILE / NO_EMBEDDINGS_FOR_PROFILE 错误码");
        assertTrue(
                promptContent.contains("改用 keyword_search") ||
                        promptContent.contains("keyword_search"),
                "应说明无 embedding 时可改用 keyword_search");
    }
}
