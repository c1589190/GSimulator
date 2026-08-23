package com.gsim.core.search;

/**
 * 搜索选项。
 *
 * @param limit    每页最大结果数（{@code <= 0} 返回空页）
 * @param offset   分页偏移量（{@code < 0} 按 0 处理）
 * @param sortMode 排序模式
 */
public record SearchOptions(int limit, int offset, SortMode sortMode) {

    /** 排序模式。 */
    public enum SortMode {
        /** 相关度优先：score 降序，再 sortKey 降序。 */
        RELEVANCE,
        /** sortKey 升序为主、score 降序次之；与 {@link #SORT_KEY_DESC} 互为精确反序。 */
        SORT_KEY_ASC,
        /** sortKey 降序为主、score 升序次之；与 {@link #SORT_KEY_ASC} 互为精确反序。 */
        SORT_KEY_DESC
    }
}
