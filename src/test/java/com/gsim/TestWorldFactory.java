package com.gsim;

import com.gsim.data.DataManager;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 测试辅助 — 创建带默认 root 的 DataManager。
 * 所有需要已初始化世界的测试应使用此工厂。
 */
public final class TestWorldFactory {

    private TestWorldFactory() {}

    /** 创建并初始化一个带默认 root 的 DataManager。 */
    public static DataManager createWithDefaultRoot(Path dataRoot) throws IOException {
        DataManager dm = new DataManager(dataRoot);
        dm.init();
        return dm;
    }
}
