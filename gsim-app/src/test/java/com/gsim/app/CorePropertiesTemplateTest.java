package com.gsim.app;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.core.config.ConfigLoader;
import org.junit.jupiter.api.Test;

/**
 * core.* 阈值默认值已并入 ConfigLoader 主链（原 core.properties 模板机制删除后，
 * 主链 buildDefaults 承担默认值职责，此处锁定查询暂存阈值）。
 */
class CorePropertiesTemplateTest {

    @Test
    void mainChainCarriesCoreStagingDefaults() {
        ConfigLoader.ConfigResult result = new ConfigLoader(new String[0]).load();
        assertEquals("3000", result.get("core.doc.query.staging.threshold"));
    }
}
