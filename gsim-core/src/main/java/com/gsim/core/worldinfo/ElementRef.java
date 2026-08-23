package com.gsim.core.worldinfo;

/**
 * 元素引用 -- 附带来源节点信息的信息单元。
 *
 * <p>所有查询操作返回的结果单元，将 {@link Element} 与其所属的节点 ID、回合数、
 * 世界时间和检查点 ID 绑定在一起，便于追溯元素的来源。例如在关键词搜索结果中
 * 可通过元素引用定位到具体的节点和检查点。
 *
 * @param nodeId       来源节点 ID
 * @param turn         回合数
 * @param worldTime    世界时间
 * @param checkpointId 检查点 ID
 * @param element      信息单元
 */
public record ElementRef(String nodeId, int turn, String worldTime, String checkpointId, Element element) {
    /**
     * 创建元素引用。
     *
     * @param nodeId       来源节点 ID
     * @param turn         回合数
     * @param worldTime    世界时间
     * @param checkpointId 检查点 ID
     * @param element      信息单元
     * @return 元素引用实例
     */
    public static ElementRef from(String nodeId, int turn, String worldTime, String checkpointId, Element element) {
        return new ElementRef(nodeId, turn, worldTime, checkpointId, element);
    }
}
