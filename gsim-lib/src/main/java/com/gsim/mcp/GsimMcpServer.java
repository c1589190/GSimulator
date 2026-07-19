package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.gsim.app.ApplicationContext;
import java.nio.file.Path;
import java.util.List;

/**
 * GSimulator MCP (Model Context Protocol) JSON-RPC 2.0 server over stdio.
 *
 * <p>继承 {@link AbstractMcpServer}，复用 JSON-RPC 2.0 协议处理逻辑。
 * 工具注册表为 {@link GsimMcpToolRegistry}（前缀 {@code gsim_} 的工具集）。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   // 方式 1: 仅 GSim 工具
 *   GsimMcpServer server = new GsimMcpServer(worldsDir);
 *   server.start();  // 阻塞，从 stdin 读取，向 stdout 写入
 *
 *   // 方式 2: 注册自定义 MCP 工具（与 GSim 工具合并）
 *   var composite = new CompositeMcpToolRegistry(
 *       new MyCustomRegistry(),
 *       new GsimMcpToolRegistry(worldsDir).asMcpRegistry()
 *   );
 *   var server = new AbstractMcpServer(composite) {
 *       {@literal @}Override protected String getServerName() { return "MyApp"; }
 *       {@literal @}Override protected String getServerVersion() { return "1.0"; }
 *   };
 *   server.start();
 *
 *   // 方式 3: ApplicationContext 模式（Agent/LLM 工具直调 Java API）
 *   GsimMcpServer server = new GsimMcpServer(applicationContext);
 *   server.start();
 * }</pre>
 *
 * <h3>独立命令行启动</h3>
 * <pre>{@code
 *   java -cp gsim-lib.jar com.gsim.mcp.GsimMcpServer &lt;worldsDir&gt; [importDir] [httpBaseUrl]
 * }</pre>
 */
public class GsimMcpServer extends AbstractMcpServer {

    private final GsimMcpToolRegistry registry;

    // ── 构造函数 ─────────────────────────────────────────────

    /**
     * 使用指定的 worlds 目录创建 MCP 服务器。
     *
     * @param worldsDir 世界观数据目录路径
     */
    public GsimMcpServer(Path worldsDir) {
        this(worldsDir, null, null);
    }

    /**
     * 使用指定的 worlds 目录和导入目录创建 MCP 服务器。
     *
     * @param worldsDir 世界观数据目录路径
     * @param importDir 导入文档目录路径
     */
    public GsimMcpServer(Path worldsDir, Path importDir) {
        this(worldsDir, importDir, null);
    }

    /**
     * 创建 MCP 服务器，指定 worlds 目录、导入目录和 HTTP 基础 URL。
     *
     * @param worldsDir   世界观数据目录路径
     * @param importDir   导入文档目录路径（可为 null）
     * @param httpBaseUrl gsim-app HTTP API 的基础 URL（可为 null，默认 http://127.0.0.1:8710）
     */
    public GsimMcpServer(Path worldsDir, Path importDir, String httpBaseUrl) {
        this(new GsimMcpToolRegistry(worldsDir, importDir, httpBaseUrl));
    }

    /**
     * 使用 ApplicationContext 创建 MCP 服务器。
     * Agent/LLM 工具直接调用内部 Java API，不依赖 HTTP 服务器。
     *
     * @param ctx 应用上下文
     */
    public GsimMcpServer(ApplicationContext ctx) {
        this(new GsimMcpToolRegistry(ctx));
    }

    /**
     * 使用已配置好的工具注册表创建 MCP 服务器。
     *
     * <p>此构造函数允许外部调用者在传入前对注册表做额外配置
     *（如添加自定义工具），然后再创建 MCP 服务器。
     *
     * @param registry 已初始化的 GSim MCP 工具注册表
     */
    public GsimMcpServer(GsimMcpToolRegistry registry) {
        super();
        this.registry = registry;
    }

    // ── AbstractMcpServer 模板方法实现 ────────────────────────

    @Override
    protected String getServerName() {
        return "GSimulator-MCP";
    }

    @Override
    protected String getServerVersion() {
        return "0.1.0";
    }

    @Override
    protected List<com.gsim.mcp.ToolDef> getAllTools() {
        return registry.all().stream()
                .map(t -> new com.gsim.mcp.ToolDef(t.name(), t.description(), t.schema()))
                .toList();
    }

    @Override
    protected String executeTool(String name, JsonNode args) throws Exception {
        return registry.execute(name, args);
    }

    // ── 公共 API ─────────────────────────────────────────────

    /**
     * 获取 GSim 工具注册表。
     *
     * <p>返回的注册表可用于与其他工具注册表合并，以扩展 MCP 工具集。
     *
     * @return GSim MCP 工具注册表实例
     */
    public GsimMcpToolRegistry getRegistry() {
        return registry;
    }

    // ── 独立入口点 ───────────────────────────────────────────

    /**
     * 独立入口点，用于从命令行启动 MCP 服务器。
     *
     * <p>用法: {@code java com.gsim.mcp.GsimMcpServer &lt;worldsDir&gt; [importDir] [httpBaseUrl]}
     *
     * @param args 命令行参数：worldsDir（必需）、importDir（可选）、httpBaseUrl（可选）
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: gsim-mcp <worldsDir> [importDir] [httpBaseUrl]");
            System.exit(1);
        }
        Path worldsDir = Path.of(args[0]);
        Path importDir = args.length >= 2 ? Path.of(args[1]) : null;
        String httpBaseUrl = args.length >= 3 ? args[2] : null;
        GsimMcpServer server = new GsimMcpServer(worldsDir, importDir, httpBaseUrl);
        server.start();
    }
}
