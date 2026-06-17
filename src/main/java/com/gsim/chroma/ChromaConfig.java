package com.gsim.chroma;

import java.util.List;

/**
 * ChromaDB 配置和 collection 定义。
 */
public final class ChromaConfig {

    private ChromaConfig() {
    }

    public static final String COLLECTION_WORLD_LORE = "world_lore";
    public static final String COLLECTION_PLAYER_ACTIONS = "player_actions";
    public static final String COLLECTION_TIMELINE_EVENTS = "timeline_events";
    public static final String COLLECTION_FACTIONS = "factions";
    public static final String COLLECTION_CHARACTERS = "characters";
    public static final String COLLECTION_RULES = "rules";
    public static final String COLLECTION_RESEARCH_CACHE = "research_cache";
    public static final String COLLECTION_PROMPT_CASES = "prompt_cases";

    public static final List<String> ALL_COLLECTIONS = List.of(
            COLLECTION_WORLD_LORE,
            COLLECTION_PLAYER_ACTIONS,
            COLLECTION_TIMELINE_EVENTS,
            COLLECTION_FACTIONS,
            COLLECTION_CHARACTERS,
            COLLECTION_RULES,
            COLLECTION_RESEARCH_CACHE,
            COLLECTION_PROMPT_CASES
    );

    public static final List<String> DEFAULT_SEARCH_COLLECTIONS = List.of(
            COLLECTION_WORLD_LORE,
            COLLECTION_RULES,
            COLLECTION_TIMELINE_EVENTS,
            COLLECTION_FACTIONS,
            COLLECTION_CHARACTERS,
            COLLECTION_RESEARCH_CACHE
    );
}
