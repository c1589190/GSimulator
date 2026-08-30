package com.gsim.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryScopeTest {

    @Test
    void matchNullOrBlankDisablesScope() {
        assertFalse(new QueryScope(null, List.of("军事"), List.of("0_1")).isEnabled());
        assertFalse(new QueryScope("", List.of("军事"), List.of("0_1")).isEnabled());
        assertFalse(new QueryScope("none", List.of("军事"), List.of("0_1")).isEnabled());
    }

    @Test
    void matchAndOrWithoutAnyListDisables() {
        assertFalse(new QueryScope("and", List.of(), List.of()).isEnabled());
        assertFalse(new QueryScope("or", List.of(), List.of()).isEnabled());
    }

    @Test
    void tagOnlyScopeAllowsMatchingTag() {
        var scope = new QueryScope("and", List.of("军事"), List.of());
        assertTrue(scope.isEnabled());
        assertTrue(scope.allows("n0002", "player.曹操", "曹操.行动", List.of("军事")));
        assertFalse(scope.allows("n0002", "player.袁绍", "袁绍.行动", List.of("外交")));
    }

    @Test
    void addressOnlyScopeAllowsExactAddress() {
        var scope = new QueryScope("and", List.of(), List.of("n0002:player.曹操:曹操.行动"));
        assertTrue(scope.isEnabled());
        assertTrue(scope.allows("n0002", "player.曹操", "曹操.行动", List.of("军事")));
        assertFalse(scope.allows("n0002", "player.袁绍", "袁绍.行动", List.of("军事")));
    }

    @Test
    void andModeRequiresBothDimensions() {
        var scope = new QueryScope("and", List.of("军事"), List.of("n0002:player.曹操:曹操.行动"));
        assertTrue(scope.isEnabled());
        assertTrue(scope.allows("n0002", "player.曹操", "曹操.行动", List.of("军事")));
        assertFalse(scope.allows("n0002", "player.曹操", "曹操.行动", List.of("外交")));
        assertFalse(scope.allows("n0002", "player.袁绍", "袁绍.行动", List.of("军事")));
    }

    @Test
    void orModeAllowsEitherDimension() {
        var scope = new QueryScope("or", List.of("军事"), List.of("n0002:player.曹操:曹操.行动"));
        assertTrue(scope.isEnabled());
        assertTrue(scope.allows("n0002", "player.曹操", "曹操.行动", List.of("军事")));
        assertTrue(scope.allows("n0002", "player.曹操", "曹操.行动", List.of("外交")));
        assertTrue(scope.allows("n0002", "player.袁绍", "袁绍.行动", List.of("军事")));
        assertFalse(scope.allows("n0002", "player.袁绍", "袁绍.行动", List.of("外交")));
    }

    @Test
    void filterRefsKeepsOrderAndRemovesDisallowed() {
        var scope = new QueryScope("and", List.of("军事"), List.of());
        assertTrue(scope.filterRefs(List.of()).isEmpty());
        assertNull(scope.filterRefs(null));
        List<com.gsim.core.worldinfo.ElementRef> refs = List.of(
                com.gsim.core.worldinfo.ElementRef.from(
                        "n0002",
                        1,
                        "t1",
                        "player.曹操",
                        new com.gsim.core.worldinfo.Element(
                                "曹操.行动", "action", "陈留起兵", List.of("军事"), List.of(), null, null)),
                com.gsim.core.worldinfo.ElementRef.from(
                        "n0002",
                        1,
                        "t1",
                        "player.袁绍",
                        new com.gsim.core.worldinfo.Element(
                                "袁绍.行动", "action", "观望", List.of("外交"), List.of(), null, null)));
        List<com.gsim.core.worldinfo.ElementRef> filtered = scope.filterRefs(refs);
        assertEquals(1, filtered.size());
        assertEquals("曹操.行动", filtered.get(0).element().key());
    }
}
