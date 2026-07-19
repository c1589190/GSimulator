package com.gsim.webui;

/**
 * WebUI 独立配置。
 */
public record WebUiConfig(String host, int port, boolean enabled) {
    /**
     * 返回默认的 WebUI 配置。
     *
     * @return 默认配置实例（127.0.0.1:8710，禁用状态）
     */
    public static WebUiConfig defaults() {
        return new WebUiConfig("127.0.0.1", 8710, false);
    }

    /**
     * 从 AppConfig 提取 WebUI 配置。
     *
     * @param appConfig 应用配置对象
     * @return 从 AppConfig 提取的 WebUI 配置实例
     */
    public static WebUiConfig from(com.gsim.app.AppConfig appConfig) {
        return new WebUiConfig(appConfig.getWebUiHost(), appConfig.getWebUiPort(), appConfig.isWebUiEnabled());
    }

    /**
     * 获取 WebUI 的基础 URL。
     *
     * @return 格式为 "http://{host}:{port}" 的基础 URL 字符串
     */
    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
