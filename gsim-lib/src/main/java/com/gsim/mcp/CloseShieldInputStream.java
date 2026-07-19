package com.gsim.mcp;

import java.io.IOException;
import java.io.InputStream;

/**
 * 装饰 {@link InputStream}，阻止 {@link #close()} 调用传播到底层流。
 *
 * <p>用于 {@link StdioMcpTransport}，防止 {@link System#in} 被关闭
 * 从而破坏测试框架（如 Surefire）的 fork 进程通信通道。
 * JVM 退出时会自动关闭底层流。
 */
final class CloseShieldInputStream extends InputStream {

    private final InputStream delegate;

    CloseShieldInputStream(InputStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public void close() {
        // 阻止关闭底层流
    }
}
