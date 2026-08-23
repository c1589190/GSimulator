# GSimulator — External Agent Access Guide (MCP)

## Connection

GSimulator exposes 70+ tools via **MCP (Model Context Protocol)** over stdio:

- Start GSimulator with `--no-cli` to run in MCP mode
- Or use the `gsimap-mcp.sh` shell script
- The MCP server implements JSON-RPC 2.0, protocol version `"2024-11-05"`
- Tool names use two namespaces: `gsim_*` (core engine) and `gsimap_*` (hex map)
- The HTTP API is also available on port 8710 for REST-based access

## Core Concepts

| Concept | Description |
|---------|-------------|
| **World** | Self-contained narrative scenario. Contains nodes, checkpoints, elements. Managed via `world_list` / `world_create`. |
| **Node** | Turn or snapshot in a branch chain. `n0000` is the root node. Managed via `node_list` / `node_status` / `node_create`. |
| **Checkpoint** | Named category container (e.g. `worldview`, `characters`, `factions`, `player.*`). Managed via `query_checkpoint` / `create_checkpoint`. |
| **Element** | Key-value pair stored in a checkpoint. Addressed as `nodeId:checkpointId:key`. Managed via `query_element` / `write_element` / `query_keyword`. |
| **Document** | Reference materials (.txt / .md). Managed via `doc_list` / `doc_read` / `doc_create` / `doc_write` / `doc_search`. |
| **Map** | Hex grid map with terrain, provinces, cities, and edge pathways. Managed via `gsimap_*` tools. |

## Tool Namespace

- `gsim_*` — Core engine tools: WorldInfo, Node, Doc, Cache, Import, Search, Agent management
- `gsimap_*` — Hex map tools: terrain, province, city, edge pathway, generation

## worldId Requirement

Most tools require a `worldId` parameter to operate on world data. Exceptions: `doc_*`, `import_*`, LLM/agent config tools, `wiki_search`, `mediawiki_search`. The `_context` field in responses shows `{worldId, nodeId, address}`. Default tools (always available): `finish_action`, `activate_tool_groups`, `world_list`, `doc_list`, `doc_read`.

## Pagination

All list/search tools support `_page` (1-based) and `_pageSize` (default 20, max 100). Responses include a `_hasMore` boolean.

## Response Format

```
Success: {"success": true, "toolName": "...", "items": [...], "itemCount": N, "_page": 1, "_pageSize": 20, "_hasMore": false, "_context": {"worldId": "...", "nodeId": "...", "address": "..."}}
Error:   {"success": false, "toolName": "...", "error": "..."}
```

## @ Reference System

| Reference | Meaning | Example |
|-----------|---------|---------|
| `@world:nodeId:checkpoint:key` | World element (3-part) | `@world:n0002:characters:曹操` |
| `@world:checkpoint:key` | World element (2-part, active node) | `@world:characters:曹操` |
| `@doc:docId` | Document | `@doc:char_guanyu` |
| `@cache:cacheId` | Cached text | `@cache:text_edit_xxx` |
| `@import:documentId` | Imported document | `@import:wiki_doc` |

Use `resolve_ref` to resolve any `@` reference. Use `text_edit` for the text editing pipeline (source -> select/delete/insert/replace -> `@cache` -> `write_element`).

## Key Tools by Category

### World & Node
| Tool | Purpose |
|------|---------|
| `gsim_world_list` | List all worlds |
| `gsim_world_create` | Create new world |
| `gsim_node_status` | Current active node |
| `gsim_node_list` | List nodes (flat or tree) |
| `gsim_node_create` | Create child node (advance turn) |

### WorldInfo Elements
| Tool | Purpose |
|------|---------|
| `gsim_query_node` | View all checkpoints/elements in a node |
| `gsim_query_checkpoint` | Checkpoint history across chain |
| `gsim_query_keyword` | Full-text keyword search |
| `gsim_query_element` | Exact element lookup with link resolution |
| `gsim_write_element` | Write or update element (upsert or append) |
| `gsim_create_checkpoint` | Explicitly create a checkpoint |

### Documents
| Tool | Purpose |
|------|---------|
| `gsim_doc_list` | List documents |
| `gsim_doc_read` | Read document content |
| `gsim_doc_create` | Create a new document |
| `gsim_doc_write` | Write or update a document |
| `gsim_doc_search` | Search documents by keyword |

### GSimap Map
| Tool | Purpose |
|------|---------|
| `gsimap_generate` | Generate procedural terrain |
| `gsimap_get_hex` | Get hex details |
| `gsimap_query_radius` | Query hexes in a radius |
| `gsimap_edge_set` | Set a pathway tag on an edge |
| `gsimap_edge_get` | Get edge pathway tags |
| `gsimap_edge_list` | List edges by filter |
| `gsimap_render_text` | ASCII map rendering |

## General Workflow

**Starting a new scenario:**
1. `gsim_world_create` — create a world
2. `gsim_write_element` — write worldview, characters, faction settings
3. Optionally `gsim_doc_create` — upload reference materials

**Running a turn:**
1. `gsim_node_status` — check current state
2. `gsim_query_checkpoint` — read relevant checkpoints
3. Generate narrative and decisions
4. `gsim_node_create` — advance turn (requires `worldTime`)
5. `gsim_write_element` — write turn outcomes

**Researching:**
1. `gsim_query_keyword` — search in-world data
2. `gsim_doc_search` — search reference materials
3. `gsim_import_document_search` — search imported documents

## HTTP API (alternative access)

- Base URL: `http://127.0.0.1:8710`
- Full API guide: see `GSimulator-HTTP-API-Guide.md`
- SSE streaming: `POST /api/command/stream`
- Status: `GET /api/status`

## Important Notes

- All writes persist to disk immediately — no rollback.
- Node navigation affects `@world` reference resolution.
- Gsimap tools require the map service (gsim-map module) to be loaded.
- The map UI (gsim-map) is available at `http://127.0.0.1:8711`.

## Further Reading

- `docs/TOOL-CONTRACTS.md` — Complete tool interface reference
- `docs/DATA-MODEL.md` — Data structures
- `docs/RUNTIME-FLOWS.md` — Runtime sequences
- `GSimulator-HTTP-API-Guide.md` — HTTP API cookbook
- `CLAUDE.md` — AI agent development guide
