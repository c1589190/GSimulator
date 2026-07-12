package com.gsim.worldinfo;

/**
 * An element with its source node attached — the unit returned by all queries.
 */
public record ElementRef(
    String nodeId,
    int turn,
    String worldTime,
    String checkpointId,
    Element element
) {
    public static ElementRef from(String nodeId, int turn, String worldTime,
                                   String checkpointId, Element element) {
        return new ElementRef(nodeId, turn, worldTime, checkpointId, element);
    }
}
