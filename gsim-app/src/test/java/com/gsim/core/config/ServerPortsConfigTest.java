package com.gsim.core.config;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.app.AppConfig;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地服务端口（WebUI / Map / CLI WS / MCP HTTP）配置读取测试。
 */
@DisplayName("本地服务端口配置")
class ServerPortsConfigTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ConfigLoader 提供端口默认值")
    void defaultsArePresent() {
        ConfigLoader loader = new ConfigLoader(new String[] {});
        ConfigLoader.ConfigResult result = loader.load();

        assertNotNull(result.entries().get("webui.port"));
        assertNotNull(result.entries().get("map.port"));
        assertNotNull(result.entries().get("cli.ws.port"));
        assertNotNull(result.entries().get("mcp.http.port"));
    }

    @Test
    @DisplayName("环境变量 key 映射到 properties 端口配置")
    void envKeysMapToPorts() {
        assertEquals("map.port", ConfigLoader.mapEnvKey("GSIMAP_PORT"));
        assertEquals("map.port", ConfigLoader.mapEnvKey("GSIM_MAP_PORT"));
        assertEquals("cli.ws.port", ConfigLoader.mapEnvKey("CLI_WS_PORT"));
        assertEquals("mcp.http.port", ConfigLoader.mapEnvKey("MCP_HTTP_PORT"));
    }

    @Test
    @DisplayName("gsim.properties 中的端口配置被 AppConfig 读取")
    void propertiesFileOverridesPorts() throws IOException {
        Path propsFile = tempDir.resolve("ports.properties");
        writeFile(
                propsFile,
                "llm.base_url=https://api.example.com/v1\n"
                        + "llm.api_key=sk-test\n"
                        + "llm.model=m\n"
                        + "webui.port=9000\n"
                        + "map.port=9001\n"
                        + "cli.ws.port=9002\n"
                        + "mcp.http.port=9003\n");

        ConfigLoader loader = new ConfigLoader(new String[] {"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(9000, config.getWebUiPort());
        assertEquals(9001, config.getMapPort());
        assertEquals(9002, config.getCliWsPort());
        assertEquals(9003, config.getMcpHttpPort());
    }

    @Test
    @DisplayName("非法端口 clamp 到 1..65535")
    void invalidPortsAreClamped() throws IOException {
        Path propsFile = tempDir.resolve("bad-ports.properties");
        writeFile(
                propsFile,
                "llm.base_url=https://api.example.com/v1\n"
                        + "llm.api_key=sk-test\n"
                        + "llm.model=m\n"
                        + "map.port=0\n"
                        + "cli.ws.port=99999\n");

        ConfigLoader loader = new ConfigLoader(new String[] {"--config", propsFile.toString()});
        AppConfig config = new AppConfig(loader.load());

        assertEquals(1, config.getMapPort(), "0 应 clamp 到 1");
        assertEquals(65535, config.getCliWsPort(), "99999 应 clamp 到 65535");
    }

    private static void writeFile(Path file, String content) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(content);
        }
    }
}
