package com.gsim.agentsmanager;

import java.util.List;

/**
 * Query scope — 限制 Agent 查询 worldinfo 信息单元的范围。
 *
 * <p>两种限制维度（均可选，空列表 = 该维度不限制）：
 * <ul>
 *   <li>{@code tagAllowlist} — 只允许查询带这些 tag 之一的元素</li>
 *   <li>{@code addressAllowlist} — 只允许查询这些精确地址（{@code nodeId:checkpointId:key}）的元素</li>
 * </ul>
 *
 * <p>{@code match} 决定两个维度的组合关系："and" = 交集（两者都要满足）、"or" = 并集（满足其一）。
 * match 既不是 "and" 也不是 "or"（含缺省）时，本 scope 不启用，全部放行（兼容无限制配置）。
 */
public record QueryScope(String match, List<String> tagAllowlist, List<String> addressAllowlist) {

    /**
     * 可被 scope 判定的元素引用抽象 — 由 worldinfo 的 {@code ElementRef} 实现（core 依赖 agentsmanager）。
     */
    public interface ScopedRef {
        String nodeId();

        String checkpointId();

        String key();

        List<String> tags();
    }

    public static final String MATCH_AND = "and";
    public static final String MATCH_OR = "or";

    /** internal 标记 tag —— 带此 tag 的元素在任何 scope 下都对查询工具不可见（硬规则）。 */
    public static final String INTERNAL_TAG = "internal";

    /** 无限制 scope（match 为 null）。 */
    public static QueryScope none() {
        return new QueryScope(null, List.of(), List.of());
    }

    /**
     * 判断元素的 tags 是否包含 internal 标记（忽略大小写）。
     *
     * <p>internal 为无条件硬规则：无论 scope 是否启用、是否配置，带此标记的元素
     * 都不能被任何查询工具返回（如完整判定表、他国决策原文、军事情报等敏感数据）。
     */
    public static boolean isInternal(List<String> tags) {
        if (tags == null) return false;
        for (String t : tags) {
            if (t != null && t.equalsIgnoreCase(INTERNAL_TAG)) return true;
        }
        return false;
    }

    public QueryScope {
        if (match == null || match.isBlank()) match = null;
        if (tagAllowlist == null) tagAllowlist = List.of();
        else tagAllowlist = List.copyOf(tagAllowlist);
        if (addressAllowlist == null) addressAllowlist = List.of();
        else addressAllowlist = List.copyOf(addressAllowlist);
    }

    /** match 为 and/or 且至少一个列表非空时，限制才生效。 */
    public boolean isEnabled() {
        return (MATCH_AND.equals(match) || MATCH_OR.equals(match))
                && (!tagAllowlist.isEmpty() || !addressAllowlist.isEmpty());
    }

    /** 元素的 tags 中是否至少有一个命中 tag 白名单（白名单为空 = 放行）。 */
    public boolean allowsTag(List<String> tags) {
        if (tagAllowlist.isEmpty()) return true;
        if (tags == null) return false;
        for (String t : tags) {
            if (tagAllowlist.contains(t)) return true;
        }
        return false;
    }

    /** 精确地址是否命中地址白名单（白名单为空 = 放行）。 */
    public boolean allowsAddress(String nodeId, String checkpointId, String key) {
        if (addressAllowlist.isEmpty()) return true;
        return addressAllowlist.contains(nodeId + ":" + checkpointId + ":" + key);
    }

    /** 组合判定：match="or" 任一命中，否则（"and"）两者都要命中。 */
    public boolean allows(String nodeId, String checkpointId, String key, List<String> tags) {
        // internal 硬规则：带 internal 标记的元素无条件拒绝，不依赖 isEnabled()。
        if (isInternal(tags)) return false;
        if (!isEnabled()) return true;
        boolean tagOk = allowsTag(tags);
        boolean addrOk = allowsAddress(nodeId, checkpointId, key);
        return MATCH_OR.equals(match) ? (tagOk || addrOk) : (tagOk && addrOk);
    }

    /** 便捷重载：按 {@link ScopedRef} 判定。 */
    public boolean allows(ScopedRef ref) {
        if (ref == null) return true;
        return allows(ref.nodeId(), ref.checkpointId(), ref.key(), ref.tags());
    }

    /** 过滤元素引用列表，保留被允许的（保持顺序）。 */
    public <T extends ScopedRef> List<T> filterRefs(List<T> refs) {
        if (refs == null || refs.isEmpty()) return refs;
        return refs.stream().filter(this::allows).toList();
    }

    /** 人类可读摘要（不含敏感内容）。 */
    public String toSafeString() {
        if (!isEnabled()) return "queryScope: (未启用)";
        return "queryScope: match=" + match
                + ", tags=" + tagAllowlist.size()
                + ", addresses=" + addressAllowlist.size();
    }
}
