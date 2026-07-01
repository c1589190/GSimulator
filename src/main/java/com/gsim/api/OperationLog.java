package com.gsim.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化操作日志 — 环形缓冲区，记录所有 HTTP API 写操作。
 * 供外部 LLM 通过 /api/logs/operations 查询"刚才发生了什么"。
 */
public final class OperationLog {

    private static final int MAX_ENTRIES = 2000;
    private static final OperationLog INSTANCE = new OperationLog();

    private final List<Map<String, Object>> entries = new ArrayList<>(MAX_ENTRIES);

    private OperationLog() {}

    public static OperationLog get() { return INSTANCE; }

    /**
     * 记录一条操作。
     *
     * @param worldId  关联的 world（可为 null）
     * @param action   操作类型，如 "world.create", "element.write", "document.upload"
     * @param method   如 "POST"
     * @param path     如 "/api/world-manager-data/api-test/elements"
     * @param summary  人类可读摘要
     * @param detail   额外详情（可为 null）
     * @param success  是否成功
     */
    public synchronized void record(String worldId, String action,
                                     String method, String path,
                                     String summary, Map<String, Object> detail,
                                     boolean success) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.remove(0);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("worldId", worldId);
        entry.put("action", action);
        entry.put("method", method);
        entry.put("path", path);
        entry.put("summary", summary);
        entry.put("detail", detail != null ? detail : Map.of());
        entry.put("success", success);
        entries.add(entry);
    }

    /** 查询最近的操作日志。 */
    public synchronized List<Map<String, Object>> query(String worldId, String since, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        int effectiveLimit = Math.max(1, Math.min(limit, 500));
        for (int i = entries.size() - 1; i >= 0 && result.size() < effectiveLimit; i--) {
            Map<String, Object> e = entries.get(i);
            if (worldId != null && !worldId.equals(e.get("worldId"))) continue;
            if (since != null && !since.isBlank()) {
                String ts = (String) e.get("timestamp");
                if (ts.compareTo(since) <= 0) continue;
            }
            result.add(e);
        }
        return result;
    }

    public synchronized int size() { return entries.size(); }
}
