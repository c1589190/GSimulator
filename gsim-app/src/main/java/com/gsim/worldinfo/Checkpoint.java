package com.gsim.worldinfo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.Collections;
import java.util.List;

@JsonDeserialize
public record Checkpoint(
    @JsonProperty("label") String label,
    @JsonProperty("type") String type,
    @JsonProperty("elements") List<Element> elements
) {
    public Checkpoint {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label must not be blank");
        if (type == null) type = "misc";
        if (elements == null) elements = List.of();
    }
}
