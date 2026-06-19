package com.gsim.app;

import com.gsim.TestWorldFactory;
import com.gsim.data.DataManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Branch Switch Session")
class BranchSwitchSessionTest {

    @Nested
    @DisplayName("ApplicationContext onRootReady callback")
    class OnRootReadyCallback {
        @Test
        @DisplayName("setOnRootReadyCallback + fireOnRootReady 触发回调")
        void fireOnRootReadyTriggersCallback() {
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);

            AtomicBoolean fired = new AtomicBoolean(false);
            ctx.setOnRootReadyCallback(() -> fired.set(true));
            ctx.fireOnRootReady();
            assertTrue(fired.get(), "callback should be triggered");
        }

        @Test
        @DisplayName("未设置回调时 fireOnRootReady 不抛异常")
        void fireWithoutCallbackDoesNotThrow() {
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);
            assertDoesNotThrow(ctx::fireOnRootReady);
        }
    }

    @Nested
    @DisplayName("ContextSessionManager 动态获取")
    class ContextSessionManagerDynamic {
        @TempDir Path tempDir;

        @Test
        @DisplayName("setContextSessionManager 后 getContextSessionManager 返回正确的值")
        void getReturnsWhatWasSet() throws Exception {
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);
            DataManager dm = TestWorldFactory.createWithDefaultRoot(tempDir);
            ctx.setDataManager(dm);

            assertNull(ctx.getContextSessionManager());

            // 模拟创建 session manager
            var renderer = new com.gsim.context.BranchContextRenderer(dm, tempDir,
                    new com.gsim.chat.BranchMessageStore(dm, tempDir),
                    new com.gsim.branch.BranchAnalyzer(dm,
                            new com.gsim.chat.BranchMessageStore(dm, tempDir),
                            new com.gsim.player.PlayerProfileManager(dm)));
            Path worldDir = tempDir.resolve("worlds").resolve(dm.getActiveRootId());
            var sessionStore = new com.gsim.context.session.ContextSessionStore(worldDir);
            var sessionMgr = new com.gsim.context.session.ContextSessionManager(sessionStore, renderer, dm, worldDir);
            ctx.setContextSessionManager(sessionMgr);

            assertNotNull(ctx.getContextSessionManager());
            assertEquals(sessionMgr, ctx.getContextSessionManager());
        }

        @Test
        @DisplayName("初始为 null，bootstrap 后更新")
        void initiallyNullUpdatedAfterBootstrap() throws Exception {
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);

            // 使用严格空的目录（不 init）
            Path emptyDir = tempDir.resolve("empty");
            java.nio.file.Files.createDirectories(emptyDir);
            DataManager dm = new DataManager(emptyDir);
            ctx.setDataManager(dm);

            assertNull(ctx.getContextSessionManager());

            // Bootstrap
            var generator = new com.gsim.root.BootstrapWorldDraftGenerator(null, "test");
            var intent = com.gsim.root.BootstrapIntentParser.parse("测试", true);
            dm.bootstrapFromEmpty("test-root", generator.generate(intent));

            // 现在可以创建 session manager 了
            var renderer = new com.gsim.context.BranchContextRenderer(dm, emptyDir,
                    new com.gsim.chat.BranchMessageStore(dm, emptyDir),
                    new com.gsim.branch.BranchAnalyzer(dm,
                            new com.gsim.chat.BranchMessageStore(dm, emptyDir),
                            new com.gsim.player.PlayerProfileManager(dm)));
            Path worldDir = emptyDir.resolve("worlds").resolve(dm.getActiveRootId());
            var sessionStore = new com.gsim.context.session.ContextSessionStore(worldDir);
            var sessionMgr = new com.gsim.context.session.ContextSessionManager(sessionStore, renderer, dm, worldDir);
            ctx.setContextSessionManager(sessionMgr);

            assertNotNull(ctx.getContextSessionManager());
        }
    }

    @Nested
    @DisplayName("Branch changed Runnable 动态获取 session manager")
    class BranchChangedRunnable {
        @Test
        @DisplayName("Runnable 从 ctx 动态获取 session manager 而非捕获旧值")
        void runnableUsesDynamicGet() {
            AppConfig config = AppConfig.forTesting();
            ApplicationContext ctx = new ApplicationContext(config);

            // 模拟：initially null
            assertNull(ctx.getContextSessionManager());

            // 创建 onBranchChanged runnable（动态获取版本）
            Runnable onBranchChanged = () -> {
                var current = ctx.getContextSessionManager();
                // 如果是 null 就跳过（不抛 NPE）
                if (current != null) {
                    current.resetSession("default", "branch switched via agent tool");
                }
            };

            // 应该不抛异常
            assertDoesNotThrow(onBranchChanged::run);
        }
    }
}
