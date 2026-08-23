package com.gsim.core.worldinfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 链接反向索引 -- {@link Element#links()} 的倒排索引。
 *
 * <p>key 为元素 links 列表中的单个字符串（如 {@code n0001:characters:曹操} 或
 * {@code gsimap:region:迷雾森林}），值为引用该链接的元素引用列表。用于回答
 * "谁引用了这个地址" 的反向查询。纯内存索引，随 {@link WorldInformation}
 * 重建，不做持久化。
 */
public final class LinkIndex {

    // link string → element refs whose element.links() contains it
    private final Map<String, List<ElementRef>> byLink = new HashMap<>();

    private LinkIndex() {}

    /**
     * 从完整节点链构建反向索引。
     *
     * <p>遍历节点链中所有节点的所有检查点元素，为每个元素的每条链接建立
     * 链接到元素引用的映射。
     *
     * @param chain 节点链（根节点到活跃节点）
     * @return 构建完成的反向索引实例
     */
    public static LinkIndex build(List<NodeSnapshot> chain) {
        LinkIndex idx = new LinkIndex();
        for (NodeSnapshot node : chain) {
            for (var entry : node.checkpoints().entrySet()) {
                String cpId = entry.getKey();
                for (Element el : entry.getValue().elements()) {
                    idx.addElement(ElementRef.from(node.nodeId(), node.turn(), node.worldTime(), cpId, el));
                }
            }
        }
        return idx;
    }

    /**
     * 向索引中添加单个元素引用（用于实时更新）。
     *
     * @param ref 要添加的元素引用
     */
    public void addElement(ElementRef ref) {
        for (String link : ref.element().links()) {
            byLink.computeIfAbsent(link, k -> new ArrayList<>()).add(ref);
        }
    }

    /**
     * 替换单个元素引用的链接集合 -- 先移除旧链接条目，再加入新链接条目。
     *
     * <p>upsert 按 key 替换元素：新引用与索引中的旧引用共享同一元素地址
     * （nodeId、checkpointId、key）但元素载荷不同，值相等比较会失效，因此
     * 按地址三元组移除旧条目。
     *
     * @param ref      元素引用（携带新链接）
     * @param oldLinks 旧链接集合
     * @param newLinks 新链接集合
     */
    public void replaceElement(ElementRef ref, List<String> oldLinks, List<String> newLinks) {
        for (String link : oldLinks) {
            List<ElementRef> list = byLink.get(link);
            if (list == null) continue;
            list.removeIf(r -> r.nodeId().equals(ref.nodeId())
                    && r.checkpointId().equals(ref.checkpointId())
                    && r.element().key().equals(ref.element().key()));
            if (list.isEmpty()) byLink.remove(link);
        }
        for (String link : newLinks) {
            byLink.computeIfAbsent(link, k -> new ArrayList<>()).add(ref);
        }
    }

    /**
     * 从索引中移除元素引用 -- 将该引用从它引用的每条链接的列表中移除，
     * 列表清空的链接键一并删除。
     *
     * @param ref 要移除的元素引用
     */
    public void removeElement(ElementRef ref) {
        for (String link : ref.element().links()) {
            removeLinkEntry(link, ref);
        }
    }

    /**
     * 按链接字符串反向查询引用它的所有元素。
     *
     * @param key 链接字符串（如 {@code n0001:characters:曹操}）
     * @return 引用该链接的元素引用列表（不可变副本；不存在时返回空列表）
     */
    public List<ElementRef> findByLink(String key) {
        List<ElementRef> list = byLink.get(key);
        return list == null ? List.of() : List.copyOf(list);
    }

    private void removeLinkEntry(String link, ElementRef ref) {
        List<ElementRef> list = byLink.get(link);
        if (list == null) return;
        list.remove(ref);
        if (list.isEmpty()) byLink.remove(link);
    }
}
