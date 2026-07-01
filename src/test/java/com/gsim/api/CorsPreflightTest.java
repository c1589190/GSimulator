package com.gsim.api;

import com.gsim.app.AppConfig;
import com.gsim.app.ApplicationContext;
import com.gsim.event.EventBus;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORS 预检测试 — 验证 OPTIONS 请求返回 204 + CORS headers。
 */
@DisplayName("CORS Preflight")
class CorsPreflightTest {

    private static ApplicationContext ctx;
    private static ApiManager apiManager;
    private static int port;

    private HttpClient client;

    @BeforeAll
    static void startServer() throws Exception {
        AppConfig config = AppConfig.forTesting();
        config.getDataDir().toFile().mkdirs();
        config.getLogDir().toFile().mkdirs();

        ctx = new ApplicationContext(config);
        ctx.initialize();
        ctx.getApiManager().stop();

        ApiConfig apiConfig = new ApiConfig("127.0.0.1", 0, true);
        apiManager = new ApiManager(apiConfig, ctx, ctx.getEventBus(),
                config.worldsDir(), config.getImportDir(), () -> "default");
        apiManager.forceEnable();
        apiManager.start();

        port = apiManager.getPort();
        System.out.println("[CorsPreflightTest] API server on port " + port);
    }

    @AfterAll
    static void stopServer() {
        if (apiManager != null) apiManager.stop();
        if (ctx != null) ctx.shutdown();
    }

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    @DisplayName("OPTIONS /api/tasks 应返回 204 + CORS headers")
    void shouldReturn204ForOptionsRequest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/tasks"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertEquals(204, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("")
                .contains("OPTIONS"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                .contains("Content-Type"));
    }

    @Test
    @DisplayName("GET /api/status 响应应包含 CORS headers")
    void shouldReturnCorsHeadersOnNormalResponse() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    @DisplayName("OPTIONS /api/status 应返回 204 + CORS headers")
    void shouldReturn204ForOptionsOnStatus() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        assertEquals(204, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }
}
