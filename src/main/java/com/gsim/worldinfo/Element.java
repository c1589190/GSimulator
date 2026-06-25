package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.List;

@JsonDeserialize
public record Element(
    @JsonProperty("key") String key,
    @JsonProperty("type") String type,
    @JsonProperty("value") String value,
    @JsonProperty("tags") List<String> tags,
    @JsonProperty("links") List<String> links
) {
    public Element {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        if (type == null) type = "text";
        if (value == null) value = "";
        if (tags == null) tags = List.of();
        if (links == null) links = List.of();
    }

    /** An element with neither tags nor links. */
    public static Element simple(String key, String type, String value) {
        return new Element(key, type, value, List.of(), List.of());
    }
}
