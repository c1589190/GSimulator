package com.gsim.webui;

/**
 * WebUI 独立配置。
 */
public record WebUiConfig(
        String host,
        int port,
        boolean enabled
) {
    public static WebUiConfig defaults() {
        return new WebUiConfig("127.0.0.1", 8711, false);
    }

    /**
     * 从 AppConfig 提取 WebUI 配置。
     */
    public static WebUiConfig from(com.gsim.app.AppConfig appConfig) {
        return new WebUiConfig(
                appConfig.getWebUiHost(),
                appConfig.getWebUiPort(),
                appConfig.isWebUiEnabled()
        );
    }

    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }
}
