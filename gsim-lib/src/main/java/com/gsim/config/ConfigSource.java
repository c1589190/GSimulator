package com.gsim.config;

/**
 * 配置来源枚举。
 * 优先级从高到低排列。
 */
public enum ConfigSource {
    /** 命令行 --config 指定 */
    CLI("command-line --config"),
    /** 环境变量 GSIM_CONFIG */
    GSIM_CONFIG_ENV("GSIM_CONFIG env"),
    /** 当前目录 gsim.properties */
    CWD_PROPERTIES("./gsim.properties"),
    /** 当前目录 .env */
    CWD_DOTENV("./.env"),
    /** 用户目录 config.properties */
    USER_PROPERTIES("~/.gsimulator/config.properties"),
    /** 用户目录 .env */
    USER_DOTENV("~/.gsimulator/.env"),
    /** 系统环境变量 */
    SYSTEM_ENV("system environment"),
    /** 内置默认值 */
    DEFAULT("built-in default");

    private final String label;

    ConfigSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
