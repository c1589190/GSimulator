package com.gsim.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 基于 {@code stdin}/{@code stdout} 的 MCP 传输实现。
 *
 * <p>读取 {@link System#in}（通过 {@link CloseShieldInputStream} 保护），
 * 写入 {@link System#out}。{@link #close()} 关闭包装的 reader/writer，
 * 但不关闭底层的 {@code System.in}/{@code System.out}。
 *
 * <p>支持构造函数注入自定义流（供测试使用）。
 */
public class StdioMcpTransport implements McpTransport {

    private final BufferedReader reader;
    private final PrintWriter writer;
    private volatile boolean closed;

    /** 使用 {@code System.in}/{@code System.out} 创建传输。 */
    public StdioMcpTransport() {
        this(new CloseShieldInputStream(System.in), System.out, false);
    }

    /**
     * 使用自定义流创建传输（供测试使用）。
     *
     * @param in  输入流
     * @param out 输出流
     */
    public StdioMcpTransport(InputStream in, OutputStream out) {
        this(in, out, true);
    }

    private StdioMcpTransport(InputStream in, OutputStream out, boolean ignored) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    }

    @Override
    public String readLine() throws IOException {
        return reader.readLine();
    }

    @Override
    public void writeLine(String line) throws IOException {
        writer.println(line);
        if (writer.checkError()) {
            throw new IOException("Write error in StdioMcpTransport");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        // 关闭包装的 reader 以解除阻塞的 readLine()。
        // 当 ownStreams=false 时（默认），底层 System.in 受 CloseShieldInputStream 保护，
        // 不会被关闭，从而不会破坏测试框架的 fork 通信。
        IOException first = null;
        try {
            reader.close();
        } catch (IOException e) {
            first = e;
        }
        writer.close();
        if (first != null) throw first;
    }
}
