package com.gsim.llm;

import java.util.Collections;
import java.util.Map;

/**
 * LLM Provider 配置 — 封装一个 API 的所有连接和能力参数。
 *
 * <p>三个预设工厂方法覆盖讯飞、SiliconFlow、DeepSeek 三种 API。
 */
public record ProviderConfig(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        int timeoutSeconds,

        /** 是否支持 forced tool_choice（某些模型会退化为 auto）。 */
        boolean supportsForcedToolChoice,

        /** Provider 是否原生返回 reasoning_content（如 DeepSeek V4-Flash 的思考模式）。 */
        boolean hasNativeReasoning,

        /** OpenAI chat/completions 请求体的额外顶层字段（null = 不注入）。 */
        Map<String, Object> extraBody,

        /** thinking 参数（null = 不注入，如 DeepSeek 的 {type: "disabled"}）。 */
        Map<String, Object> thinking
) {
    // ---- 三个预设 ----

    /** 讯飞 astron-code-latest。需要 extraBody: {enable_thinking: false}。 */
    public static ProviderConfig forXfyun(String apiKey, String model) {
        return new ProviderConfig(
                "xfyun",
                "https://maas-api.cn-huabei-1.xf-yun.com/v2",
                apiKey,
                model != null ? model : "astron-code-latest",
                0.3, 120,
                false,  // forced tool_choice 不稳定
                false,  // 无原生 reasoning
                Map.of("enable_thinking", false),
                null
        );
    }

    /** SiliconFlow deepseek-ai/DeepSeek-V4-Flash。支持 forced，有原生 reasoning。 */
    public static ProviderConfig forSiliconFlow(String apiKey, String model) {
        return new ProviderConfig(
                "siliconflow",
                "https://api.siliconflow.cn/v1",
                apiKey,
                model != null ? model : "deepseek-ai/DeepSeek-V4-Flash",
                0.3, 120,
                true,
                true,
                null,
                null
        );
    }

    /** DeepSeek deepseek-chat。支持 forced，但 thinking 模式下禁 forced tool_choice。 */
    public static ProviderConfig forDeepSeek(String apiKey, String model) {
        return new ProviderConfig(
                "deepseek",
                "https://api.deepseek.com",
                apiKey,
                model != null ? model : "deepseek-chat",
                0.3, 120,
                true,
                false,
                null,
                Map.of("type", "disabled")
        );
    }

    /** 通用配置（不注入任何扩展参数）。 */
    public static ProviderConfig generic(String name, String baseUrl, String apiKey,
                                          String model, double temperature, int timeoutSeconds) {
        return new ProviderConfig(name, baseUrl, apiKey, model, temperature, timeoutSeconds,
                true, false, null, null);
    }

    /** 返回不带敏感信息的描述。 */
    public String toSafeString() {
        return "ProviderConfig{name=" + name + ", baseUrl=" + baseUrl
                + ", model=" + model + ", temperature=" + temperature
                + ", timeout=" + timeoutSeconds + "s"
                + ", forcedToolChoice=" + supportsForcedToolChoice
                + ", nativeReasoning=" + hasNativeReasoning + "}";
    }
}
