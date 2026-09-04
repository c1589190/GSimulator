package com.gsim.core.tools.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.agentsmanager.mcp.GsimRequestContext;
import com.gsim.core.worldinfo.Element;
import com.gsim.core.worldinfo.NodeSnapshot;
import com.gsim.core.worldinfo.WorldInformation;
import com.gsim.core.worldinfo.loader.WorldIndexManager;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CoreModuleTools.createWorldInfoSupplier 共享缓存回归测试（F3 实机缺陷 D2）。
 *
 * <p>缺陷：Main 组装的搜索上下文与 registerWorldInfoTools 的 worldinfo 工具各持一份按世界
 * 缓存——同一非活跃世界被加载两次，写入侧（write_element 原地 upsert）与读取侧（搜索/
 * 链接改写 findLink）各持一份实例，linkIndex 互相不可见。修复 = 两处共用
 * {@code AgentBridge.SHARED_WORLD_INFO_CACHE}。
 */
@DisplayName("AgentBridgeWorldInfoCacheTest — 共享按世界缓存")
class AgentBridgeWorldInfoCacheTest {

    private static final String SHARED_WORLD = "shared_cache_world";

    @TempDir
    Path tmpDir;

    @AfterEach
    void clearRequestContext() {
        GsimRequestContext.clear();
    }

    @Test
    @DisplayName("两个消费方对同一非活跃世界返回同一实例，写入对另一侧立即可见")
    void sharedCacheKeepsOneInstancePerWorldAcrossConsumers() {
        WorldIndexManager.createWorld(tmpDir, SHARED_WORLD, "共享缓存世界"); // n0000 + worldview 检查点

        // baseSupplier 代表应用侧活跃世界（与 SHARED_WORLD 不同）——SHARED_WORLD 必须走共享缓存
        WorldInformation active = new WorldInformation(
                "active_world",
                List.of(new NodeSnapshot("n0000", null, 0, "t0", "initial", "t0", Map.of(), new LinkedHashMap<>())));

        // 消费方 1（Main/搜索侧）与消费方 2（registerWorldInfoTools/write_element 侧）
        Supplier<WorldInformation> searchSide = CoreModuleTools.createWorldInfoSupplier(() -> active, tmpDir);
        Supplier<WorldInformation> writeSide = CoreModuleTools.createWorldInfoSupplier(() -> active, tmpDir);

        GsimRequestContext.setWorldId(SHARED_WORLD);
        try {
            WorldInformation wiSearch = searchSide.get(); // 触发磁盘加载进共享缓存
            WorldInformation wiWrite = writeSide.get();
            assertSame(wiSearch, wiWrite, "两个消费方必须共享同一 WorldInformation 实例");

            // 模拟 write_element：在 writeSide 实例上写入带 links 的元素
            Element element = new Element("都城", "text", "长安", List.of("都城"), List.of("gsimap:region:迷雾森林"), null, null);
            assertFalse(wiWrite.upsertElement("n0000", "worldview", element), "新元素应为追加");

            // 搜索侧立即看到该链接（同一实例 + LinkIndex 增量维护）——D2 分裂缺陷的回归断言
            var refs = wiSearch.linkIndex().findByLink("gsimap:region:迷雾森林");
            assertFalse(refs.isEmpty(), "searchSide 应能看到 writeSide 写入的链接");
            assertEquals("都城", refs.get(0).element().key());
        } finally {
            GsimRequestContext.clear();
        }
    }

    @Test
    @DisplayName("活跃世界返回 base 实例（应用侧可变实例语义不变）")
    void activeWorldReturnsBaseInstance() {
        WorldInformation active = new WorldInformation(
                "active_world",
                List.of(new NodeSnapshot("n0000", null, 0, "t0", "initial", "t0", Map.of(), new LinkedHashMap<>())));
        Supplier<WorldInformation> supplier = CoreModuleTools.createWorldInfoSupplier(() -> active, tmpDir);

        GsimRequestContext.setWorldId("active_world");
        try {
            assertSame(active, supplier.get(), "活跃世界必须返回 base 实例（write_element 原地更新）");
        } finally {
            GsimRequestContext.clear();
        }
    }
}
