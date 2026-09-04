package com.gsim.agentsmanager.mcp;

import java.io.Closeable;
import java.io.IOException;

/**
 * MCP 协议传输层抽象。
 *
 * <p>将 JSON-RPC 语义与具体传输方式解耦。
 * 每行是一个完整的 JSON-RPC 消息，传输层负责行分隔和编解码。
 *
 * <h3>内置实现</h3>
 * <ul>
 *   <li>{@link StdioMcpTransport} — 基于 {@code stdin}/{@code stdout} 的 stdio 传输</li>
 * </ul>
 *
 * <h3>自定义传输示例</h3>
 * <pre>{@code
 * // HTTP SSE 传输
 * class SseMcpTransport implements McpTransport { ... }
 *
 * // WebSocket 传输
 * class WebSocketMcpTransport implements McpTransport { ... }
 * }</pre>
 */
public interface McpTransport extends Closeable {

    /**
     * 从传输层读取一行 JSON-RPC 消息。
     * 阻塞直到有消息到达或传输层关闭。
     *
     * @return 一行 JSON-RPC 文本，EOF 时返回 {@code null}
     * @throws IOException 读取失败
     */
    String readLine() throws IOException;

    /**
     * 向传输层写入一行 JSON-RPC 消息。
     *
     * @param line JSON-RPC 响应文本（不含换行符）
     * @throws IOException 写入失败
     */
    void writeLine(String line) throws IOException;

    /**
     * 关闭传输层，释放底层资源并解除阻塞的读取操作。
     * 实现必须是幂等的（重复调用无副作用）。
     */
    @Override
    void close() throws IOException;
}
