# Changelog

All notable changes to GSimulator.

## Unreleased

### Added

- Added SQLite-backed KnowledgeStore with documents, chunks, FTS, embedding profiles, and chunk embeddings.
- Added external OpenAI-compatible embedding support.
- Added deterministic EmbeddingProfile IDs.
- Added Agent knowledge tools: `knowledge_upsert`, `knowledge_update`, `knowledge_delete`, `knowledge_search`, `keyword_search`, `knowledge_get_chunk`, `knowledge_get_document`, and `knowledge_embed_missing`.
- Added `/knowledge` and `/embedding` management commands.
- Unified natural language Agent entry with `/chat`; `/sim` and `/run` are deprecated wrappers.

### Verified

- Verified first end-to-end knowledge flow with external embeddings, Agent upsert, keyword search, semantic search, update, and delete.
