package com.gsim.api;

/**
 * HTTP API 配置。
 *
 * <p>可从环境变量/属性文件或代码直接设置。
 */
public class ApiConfig {

    private final String host;
    private final int port;
    private final boolean enabled;

    public ApiConfig(String host, int port, boolean enabled) {
        this.host = host;
        this.port = port;
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取基础 URL（http://host:port 格式）。
     *
     * @return 完整的基础 URL 字符串
     */
    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }

    /**
     * 默认配置：127.0.0.1:8710，禁用。
     */
    public static ApiConfig defaultConfig() {
        return new ApiConfig("127.0.0.1", 8710, false);
    }

    @Override
    public String toString() {
        return "ApiConfig{host=" + host + ", port=" + port + ", enabled=" + enabled + "}";
    }
}
