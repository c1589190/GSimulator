package com.gsim.map.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gsim.map.map.MapData;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapServiceTracePathwayTest {

    private static MapData mapWithEdges(Map<String, Map<String, Map<String, Object>>> edges) {
        return new MapData(
                30,
                false,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                MapData.TerrainType.defaults(),
                List.of(),
                MapData.defaultPathwayGroups(),
                edges);
    }

    private static String edge(int q1, int r1, int q2, int r2) {
        return MapData.edgeKey(q1, r1, q2, r2);
    }

    private static Map<String, Map<String, Map<String, Object>>> riverEdges(String... edgeKeys) {
        var edges = new LinkedHashMap<String, Map<String, Map<String, Object>>>();
        for (String k : edgeKeys) {
            edges.put(k, Map.of("river", Map.of()));
        }
        return edges;
    }

    @Test
    void simpleChainIsOnePathFromEndToEnd() {
        var edges = riverEdges(edge(0, 0, 1, 0), edge(1, 0, 2, 0));
        var chains = MapService.traceChains(mapWithEdges(edges), "river");
        assertEquals(1, chains.size());
        assertEquals(Set.of("0_0", "1_0", "2_0"), new HashSet<>(chains.get(0)));
        assertEquals(3, chains.get(0).size());
    }

    @Test
    void branchPointSplitsIntoPerEdgeChains() {
        var edges = riverEdges(edge(0, 0, 1, 0), edge(1, 0, 2, 0), edge(1, 0, 3, 0));
        var chains = MapService.traceChains(mapWithEdges(edges), "river");
        assertEquals(3, chains.size());
        for (var chain : chains) {
            assertEquals(2, chain.size());
            assertTrue(chain.contains("1_0"));
        }
    }

    @Test
    void closedCycleEmittedAsLoop() {
        var edges = riverEdges(edge(0, 0, 1, 0), edge(1, 0, 2, 0), edge(2, 0, 0, 0));
        var chains = MapService.traceChains(mapWithEdges(edges), "river");
        assertEquals(1, chains.size());
        var chain = chains.get(0);
        assertEquals(chain.get(0), chain.get(chain.size() - 1));
        assertEquals(3, new HashSet<>(chain).size());
    }

    @Test
    void typeFilterIgnoresOtherPathwayTypes() {
        var edges = riverEdges(edge(0, 0, 1, 0));
        assertTrue(MapService.traceChains(mapWithEdges(edges), "road").isEmpty());
    }

    @Test
    void emptyMapYieldsNoChains() {
        assertTrue(MapService.traceChains(mapWithEdges(Map.of()), "river").isEmpty());
    }
}
