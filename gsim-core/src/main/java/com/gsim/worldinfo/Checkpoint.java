package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * 检查点 -- 节点内的分类容器。
 *
 * <p>每个检查点拥有唯一的 label 和可选的 type，包含一组 {@link Element} 列表。
 * 例如一个节点可以包含 "worldview" 检查点、"characters" 检查点、"factions" 检查点等，
 * 用于按类别组织信息单元。
 *
 * @param label    检查点标识符（必填，不可为空）
 * @param type     检查点类型（默认为 "misc"）
 * @param elements 检查点内的元素列表（默认为空列表）
 */
@JsonDeserialize
public record Checkpoint(
        @JsonProperty("label") String label,
        @JsonProperty("type") String type,
        @JsonProperty("elements") List<Element> elements) {
    public Checkpoint {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label must not be blank");
        if (type == null) type = "misc";
        if (elements == null) elements = List.of();
    }
}
