# GSimulator — External Agent Access Guide

You are an external LLM agent connecting to a **GSimulator** server. GSimulator is a turn-based narrative simulation engine. It exposes World data (nodes, checkpoints, elements) and Document management through REST APIs.

## Connection

```
Base URL: http://127.0.0.1:8710
API Docs:  GET /api/documents/GSimulator-HTTP-API-Guide.md
Status:    GET /api/status
```

**First action**: Read the full API guide to understand available endpoints:
```
GET /api/documents/GSimulator-HTTP-API-Guide.md?full=true
```

## Core Concepts

- **World** — a self-contained narrative scenario. Has nodes, checkpoints, elements.
- **Node** (节点) — a turn/snapshot in the branch chain. n0000 is always the root.
- **Checkpoint** (检查点) — a named category container within a node (e.g., `worldview`, `characters`, `factions`).
- **Element** (元素) — a key-value pair inside a checkpoint. Addressed as `nodeId:checkpointId:key`.
- **Document** (文档) — imported reference materials (.txt/.md) searchable by keyword.

## Key Endpoints Quick Reference

| Operation | Method | Path |
|-----------|--------|------|
| List worlds | GET | `/api/world-manager` |
| Create world | POST | `/api/world-manager` |
| Delete world | DELETE | `/api/world-manager/{worldId}` |
| List nodes | GET | `/api/world-manager/{worldId}/nodes` |
| Create node (advance turn) | POST | `/api/world-manager/{worldId}/nodes` |
| Switch active node | POST | `/api/world-manager/{worldId}/nodes/active` |
| Write element | POST | `/api/world-manager-data/{worldId}/elements` |
| Query element by ref | GET | `/api/world-manager-data/{worldId}/elements?ref=...` |
| Search elements | GET | `/api/world-manager-data/{worldId}/elements/search?keywords=...` |
| Checkpoint history | GET | `/api/world-manager-data/{worldId}/checkpoints/{cpId}` |
| List documents | GET | `/api/documents` |
| Read document | GET | `/api/documents/{docId}` |
| Search documents | GET | `/api/documents/search?query=...` |

## General Workflow

### Starting a new scenario
1. `POST /api/world-manager` to create a world
2. `POST /api/world-manager-data/{worldId}/elements` to write settings (worldview, characters, factions)
3. Optionally `POST /api/documents` to upload reference materials

### Running a turn (simulating agent round)
1. Read current state: `GET /api/world-manager/{worldId}/nodes/active` and relevant checkpoints
2. Generate narrative/decisions
3. `POST /api/world-manager/{worldId}/nodes` to advance the turn
4. `POST /api/world-manager-data/{worldId}/elements` to write turn outcomes

### Researching a topic
1. `GET /api/world-manager-data/{worldId}/elements/search?keywords=...` for in-world data
2. `GET /api/documents/search?query=...` for reference materials

### Reading full API specification
```
GET /api/documents/GSimulator-HTTP-API-Guide.md?full=true
```

## Conventions

- All response bodies follow `{"success": bool, "data": {...}, "error": {...}}`
- Element ref format: `nodeId:checkpointId:key` (3-part) or `checkpointId:key` (2-part, defaults to active node)
- URL-encode Chinese characters in query parameters
- The `POST /api/world-manager/{worldId}/nodes` body requires `worldTime` (string, in-world time label)
- Checkpoints are auto-created when writing elements to non-existent checkpoints

## Important Notes

- All writes persist to disk immediately — no rollback
- Node navigation (switch/goto-parent) affects which node `checkpointId:key` refs resolve to
- Documents support `.txt`, `.md`, `.markdown` extensions only
- Path traversal attacks are rejected on document operations
