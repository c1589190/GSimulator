package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/**
 * 信息单元 -- 节点检查点内的最小数据单元。
 *
 * <p>每个元素通过 key 唯一标识，包含类型、值、标签和链接等元数据。
 * 元素可以被写入、更新和查询，支持标签分类和跨元素链接。标签用于分类筛选，
 * 链接用于在元素之间建立引用关系。
 *
 * @param key       元素键（必填，不可为空）
 * @param type      元素类型（默认为 "text"）
 * @param value     元素值（默认为空字符串）
 * @param tags      标签列表（默认为空列表）
 * @param links     链接列表（默认为空列表）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
@JsonDeserialize
public record Element(
        @JsonProperty("key") String key,
        @JsonProperty("type") String type,
        @JsonProperty("value") String value,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("links") List<String> links,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt) {
    public Element {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        if (type == null) type = "text";
        if (value == null) value = "";
        if (tags == null) tags = List.of();
        if (links == null) links = List.of();
    }

    /**
     * 创建一个既无标签也无链接的简化元素。
     *
     * @param key   元素键
     * @param type  元素类型
     * @param value 元素值
     * @return 简化元素实例
     */
    public static Element simple(String key, String type, String value) {
        return new Element(key, type, value, List.of(), List.of(), null, null);
    }
}
