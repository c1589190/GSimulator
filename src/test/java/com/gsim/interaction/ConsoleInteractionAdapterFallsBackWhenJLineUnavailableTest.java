package com.gsim.interaction;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConsoleInteractionAdapter — JLine 不可用时 fallback")
class ConsoleInteractionAdapterFallsBackWhenJLineUnavailableTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("构造完成不抛异常（JLine 不可用时 fallback 到 BufferedReader）")
    void constructsWithoutExceptionWhenJLineUnavailable() {
        // 在无终端环境中（如 CI），JLine 初始化失败应静默 fallback
        assertDoesNotThrow(() -> {
            var adapter = new ConsoleInteractionAdapter(null, null, () -> null);
            // 验证 buildPrompt 仍可用
            String prompt = adapter.buildPrompt();
            assertNotNull(prompt);
        });
    }

    @Test
    @DisplayName("JLine fallback 后 buildPrompt 仍正常工作")
    void buildPromptWorksAfterFallback() {
        var adapter = new ConsoleInteractionAdapter(null, null, () -> null);
        String prompt = adapter.buildPrompt();
        assertEquals("gsim[no-root]> ", prompt);
    }

    @Test
    @DisplayName("JLine fallback 后动态 prompt 仍反映 root 状态")
    void dynamicPromptWorksAfterFallback() throws Exception {
        DataManager dm = TestWorldFactory.createWithDefaultRoot(tempDir);
        var adapter = new ConsoleInteractionAdapter(null, null, () -> dm);
        String prompt = adapter.buildPrompt();
        assertTrue(prompt.contains("gsim["));
        assertTrue(prompt.contains("default"));
        assertTrue(prompt.contains("b0000-start"));
    }

    @Test
    @DisplayName("shutdown 不抛异常")
    void shutdownDoesNotThrow() {
        var adapter = new ConsoleInteractionAdapter(null, null, () -> null);
        assertDoesNotThrow(adapter::shutdown);
    }
}
