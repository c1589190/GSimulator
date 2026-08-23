package com.gsim.core.config;

import java.nio.file.Path;

/**
 * ConfigDoctor 诊断所需的配置快照（core 自有输入类型）。
 *
 * <p>gsim-core 不依赖 gsim-app 的 AppConfig；gsim-app 在调用诊断时
 * 从 AppConfig 构造本快照传入。
 */
public record ConfigSnapshot(
        Path configPath,
        boolean llmConfigured,
        String llmBaseUrl,
        String llmApiKey,
        String llmModel,
        int llmTimeoutSeconds,
        String maskedApiKey,
        Path dataDir,
        Path importDir,
        Path outputDir,
        Path logDir) {}
