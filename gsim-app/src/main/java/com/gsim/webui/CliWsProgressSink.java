package com.gsim.webui;

import com.gsim.agent.AgentProgressEvent;
import com.gsim.agent.AgentProgressSink;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * CLI WebSocket 进度 Sink — 将 AgentProgressEvent 格式化为终端文本，
 * 通过独立线程异步发送到 WebSocket。
 */
public class CliWsProgressSink implements AgentProgressSink {

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final OutputStream out;
    private volatile boolean running = true;

    public CliWsProgressSink(OutputStream out) {
        this.out = out;
        // 启动发送线程
        Thread.startVirtualThread(this::sendLoop);
    }

    @Override
    public void onProgress(AgentProgressEvent event) {
        String line = format(event);
        if (line != null && !line.isEmpty()) {
            queue.offer(line);
        }
    }

    public void shutdown() {
        running = false;
        queue.offer(""); // 唤醒 sendLoop
    }

    private void sendLoop() {
        while (running) {
            try {
                String line = queue.take();
                if (!running) break;
                if (line.isEmpty()) continue;
                sendText(out, line);
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                break;
            }
        }
    }

    // ══════════════════════════════════════════
    // 格式化
    // ══════════════════════════════════════════

    private static String format(AgentProgressEvent event) {
        String phase = event.phase();
        String detail = event.detail();
        var meta = event.meta();
        String tool = meta.getOrDefault("tool", "");
        String agentId = meta.getOrDefault("agentId", "");

        return switch (phase) {
            case AgentProgressEvent.LLM_STREAM_STARTED -> {
                if (!agentId.isEmpty()) yield "[" + agentId + "] ● 思考中...";
                yield "● 思考中...";
            }
            case AgentProgressEvent.TOOL_SELECTED, AgentProgressEvent.TOOL_EXECUTING -> {
                if (!agentId.isEmpty()) yield "[" + agentId + "] ⚙ " + tool;
                yield "⚙ " + tool;
            }
            case AgentProgressEvent.TOOL_SUCCESS -> {
                if (!agentId.isEmpty()) yield "[" + agentId + "] ✓ " + tool + " done";
                yield "✓ " + tool + " done";
            }
            case AgentProgressEvent.TOOL_FAILED -> {
                String err = meta.getOrDefault("error", "failed");
                if (!agentId.isEmpty()) yield "[" + agentId + "] ✖ " + tool + " " + err;
                yield "✖ " + tool + " " + err;
            }
            case AgentProgressEvent.AGENT_PUBLIC_MESSAGE -> {
                String subType = meta.get("subType");
                if ("simulation_content".equals(subType)) {
                    String title = meta.getOrDefault("title", "");
                    String body = meta.getOrDefault("body", detail);
                    body = stripAnsi(body);
                    yield "▐ 推文: " + title + "\n" + body;
                }
                yield detail != null && !detail.isBlank() ? stripAnsi(detail) : null;
            }
            case AgentProgressEvent.FINISH_ACTION_ACCEPTED -> null;
            case AgentProgressEvent.FINISH_ACTION_REJECTED -> "finish_action 被拒: " + detail;
            default -> null;
        };
    }

    private static String stripAnsi(String s) {
        return s != null ? s.replaceAll("\\[[0-9;]*m", "").trim() : "";
    }

    // ══════════════════════════════════════════
    // WebSocket 发送（最小化，复用 CliWebSocketServer 的方法）
    // ══════════════════════════════════════════

    static void sendText(OutputStream out, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;
        synchronized (out) {
            out.write(0x81);
            if (len < 126) {
                out.write(len);
            } else if (len < 65536) {
                out.write(126);
                out.write((len >> 8) & 0xff);
                out.write(len & 0xff);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) out.write((int) ((len >> (i * 8)) & 0xff));
            }
            out.write(payload);
            out.flush();
        }
    }
}
