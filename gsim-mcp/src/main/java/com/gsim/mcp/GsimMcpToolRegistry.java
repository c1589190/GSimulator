package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gsim.agent.AgentConfig;
import com.gsim.agent.AgentConfigStore;
import com.gsim.agent.ToolFilterConfig;
import com.gsim.llm.LlmConfig;
import com.gsim.llm.LlmConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Registry of MCP tools for GSimulator world data and document management.
 * All tools prefixed "gsim_" for coexistence with GoatMosire's "goatmosire_" tools.
 *
 * <p>Directly reads GSim node JSON files — no dependency on gsim-app's HTTP layer.
 */
public class GsimMcpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(GsimMcpToolRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newHttpClient();

    private final Path worldsDir;
    private final Path importDir;
    private final String httpBaseUrl;
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    public GsimMcpToolRegistry(Path worldsDir) {
        this(worldsDir, null, null);
    }

    public GsimMcpToolRegistry(Path worldsDir, Path importDir) {
        this(worldsDir, importDir, null);
    }

    public GsimMcpToolRegistry(Path worldsDir, Path importDir, String httpBaseUrl) {
        this.worldsDir = worldsDir;
        this.importDir = importDir != null ? importDir : Path.of("import");
        this.httpBaseUrl = httpBaseUrl != null ? httpBaseUrl : "http://127.0.0.1:8710";
        registerAll();
    }

    public record ToolDef(String name, String description, String schema) {}

    public List<ToolDef> all() { return List.copyOf(tools.values()); }

    public String execute(String name, JsonNode args) throws Exception {
        ToolDef tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return switch (name) {
            case "gsim_list_worlds"               -> handleListWorlds(args);
            case "gsim_get_world_info"            -> handleGetWorldInfo(args);
            case "gsim_get_node_info"             -> handleGetNodeInfo(args);
            case "gsim_create_node"              -> handleCreateNode(args);
            case "gsim_list_checkpoints"          -> handleListCheckpoints(args);
            case "gsim_get_checkpoint"            -> handleGetCheckpoint(args);
            case "gsim_add_checkpoint_element"    -> handleAddCheckpointElement(args);
            case "gsim_update_checkpoint_element" -> handleUpdateCheckpointElement(args);
            case "gsim_delete_checkpoint_element" -> handleDeleteCheckpointElement(args);
            case "gsim_search"                    -> handleSearch(args);
            case "gsim_resolve_ref"               -> handleResolveRef(args);
            case "gsim_list_docs"                 -> handleListDocs(args);
            case "gsim_get_doc"                   -> handleGetDoc(args);
            case "gsim_llm_list"                  -> handleLlmList(args);
            case "gsim_llm_get"                   -> handleLlmGet(args);
            case "gsim_llm_add"                   -> handleLlmAdd(args);
            case "gsim_llm_update"                -> handleLlmUpdate(args);
            case "gsim_llm_delete"                -> handleLlmDelete(args);
            case "gsim_llm_test" -> {
                var result = httpPostBody("/api/llm/" + args.get("id").asText() + "/test", MAPPER.createObjectNode());
                try {
                    var json = MAPPER.readTree(result);
                    yield toJson(Map.of("connected",
                        json.has("data") && json.get("data").has("connected")
                            && json.get("data").get("connected").asBoolean(),
                        "detail", json.has("data") && json.get("data").has("detail")
                            ? json.get("data").get("detail").asText() : ""));
                } catch (Exception e) {
                    yield toJson(Map.of("connected", false, "detail", result));
                }
            }
            case "gsim_agent_list"               -> httpGet("/api/agents", args);
            case "gsim_agent_get"                -> httpGet("/api/agents/" + args.get("instanceId").asText(), args);
            case "gsim_agent_run"                -> httpPost("/api/agents/run", args);
            case "gsim_agent_cancel"             -> httpPost("/api/agents/" + args.get("instanceId").asText() + "/cancel", args);
            case "gsim_agent_output"             -> httpGet("/api/agents/" + args.get("instanceId").asText() + "/output", args);
            case "gsim_agent_config_list"        -> handleAgentConfigList(args);
            case "gsim_agent_config_get"         -> handleAgentConfigGet(args);
            case "gsim_agent_config_create"      -> handleAgentConfigCreate(args);
            case "gsim_agent_config_update"      -> handleAgentConfigUpdate(args);
            case "gsim_agent_config_delete"      -> handleAgentConfigDelete(args);
            case "gsim_create_world"             -> handleCreateWorld(args);
            case "gsim_delete_world"             -> handleDeleteWorld(args);
            case "gsim_create_doc"               -> handleCreateDoc(args);
            case "gsim_update_doc"               -> handleUpdateDoc(args);
            case "gsim_delete_doc"               -> handleDeleteDoc(args);
            case "gsim_doc_store_list"           -> handleDocStoreList(args);
            case "gsim_doc_store_get"            -> handleDocStoreGet(args);
            case "gsim_doc_store_create"         -> handleDocStoreCreate(args);
            case "gsim_agent_cache_list"         -> handleAgentCacheList(args);
            case "gsim_agent_cache_get"          -> handleAgentCacheGet(args);
            case "gsim_agent_cache_create"       -> handleAgentCacheCreate(args);
            case "gsim_agent_cache_delete"       -> handleAgentCacheDelete(args);
            case "gsim_skill_list"               -> handleSkillList(args);
            case "gsim_skill_get"                -> handleSkillGet(args);
            case "gsim_skill_search"             -> handleSkillSearch(args);
            case "gsim_cache_list"               -> handleCacheList(args);
            case "gsim_cache_get"                -> handleCacheGet(args);
            case "gsim_cache_edit"               -> handleCacheEdit(args);
            case "gsim_get_status"               -> handleGetStatus(args);
            case "gsim_list_tools" -> {
                try {
                    yield httpGet("/api/tools", MAPPER.createObjectNode());
                } catch (Exception e) {
                    yield toJson(Map.of("tools", tools.keySet().stream().toList(), "count", tools.size(),
                        "note", "Using static tool list (gsim-app HTTP API unavailable)"));
                }
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    // ── Registration ──────────────────────────────────────

    private void registerAll() {
        register("gsim_list_worlds",
            "List all available GSim worlds with their metadata.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_get_world_info",
            "Get overview info for a GSim world: name, node chain, active node, total nodes.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"}
            },"required":["worldId"]}""");

        register("gsim_get_node_info",
            "Get metadata for a GSim node: turn number, worldTime, parentId, checkpoints list.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000)"}
            },"required":["worldId","nodeId"]}""");

        register("gsim_create_node",
            "Create a new child node in a GSim world (advance turn). Creates a new branch node with auto-incremented turn number. Uses direct file I/O — no HTTP dependency.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"GSim world ID"},
              "parentId":{"type":"string","description":"Parent node ID to branch from"},
              "worldTime":{"type":"string","description":"In-game time label (e.g. '星历502年春')"},
              "title":{"type":"string","description":"Optional node title"}
            },"required":["worldId","parentId","worldTime"]}""");

        register("gsim_list_checkpoints",
            "List all checkpoints in a GSim node with element counts.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000)"}
            },"required":["worldId","nodeId"]}""");

        register("gsim_get_checkpoint",
            "Get elements from a GSim checkpoint. Filter by key or tags (all must match).",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000)"},
              "checkpoint":{"type":"string","description":"Checkpoint name: narrative, factions, worldview, characters, map"},
              "key":{"type":"string","description":"Filter by specific element key (optional)"},
              "tags":{"type":"array","items":{"type":"string"},"description":"Filter by tags — element must have ALL specified tags (optional)"},
              "limit":{"type":"integer","description":"Max elements to return (default 50)"}
            },"required":["worldId","nodeId","checkpoint"]}""");

        register("gsim_add_checkpoint_element",
            "Add a new element to a GSim checkpoint.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000)"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Unique element key"},
              "value":{"type":"string","description":"Full text content"},
              "type":{"type":"string","description":"Element type: text, character_state, map-region, map-city (default: text)"},
              "tags":{"type":"array","items":{"type":"string"},"description":"Tags for categorization"}
            },"required":["worldId","nodeId","checkpoint","key","value"]}""");

        register("gsim_update_checkpoint_element",
            "Update an existing element in a GSim checkpoint. Only provided fields are changed.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000)"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to update"},
              "value":{"type":"string","description":"New text content (optional)"},
              "type":{"type":"string","description":"New element type (optional)"},
              "tags":{"type":"array","items":{"type":"string"},"description":"New tags list (optional, replaces all existing tags)"}
            },"required":["worldId","nodeId","checkpoint","key"]}""");

        register("gsim_delete_checkpoint_element",
            "Delete an element from a GSim checkpoint by key.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000)"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to delete"}
            },"required":["worldId","nodeId","checkpoint","key"]}""");

        register("gsim_search",
            "Keyword search across GSim world data: checkpoints (all worlds) plus imported documents. Returns matching elements with surrounding context.",
            """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Search keyword(s)"},
              "scope":{"type":"string","description":"Search scope: world, docs, or all (default: all)"},
              "worldId":{"type":"string","description":"Limit search to specific world (optional)"},
              "limit":{"type":"integer","description":"Max results (default 20)"}
            },"required":["query"]}""");

        register("gsim_resolve_ref",
            "Resolve a GSim @ reference to full element content. Supports explicit @world:worldId:nodeId:checkpoint:key (5-segment) and @world:worldId:checkpoint:key (4-segment, requires nodeId param). Also supports @doc:docId.",
            """
            {"type":"object","properties":{
              "ref":{"type":"string","description":"Reference: @world:worldId:nodeId:checkpoint:key, @world:worldId:checkpoint:key (needs nodeId param), or @doc:docId"},
              "worldId":{"type":"string","description":"World ID (required for @world refs)"},
              "nodeId":{"type":"string","description":"Node ID (required for 4-segment @world refs without nodeId in the ref)"}
            },"required":["ref"]}""");

        register("gsim_list_docs",
            "List all imported documents (reference materials) available in GSim.",
            """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Optional keyword filter"}
            },"required":[]}""");

        register("gsim_get_doc",
            "Read a GSim document by ID, with optional pagination.",
            """
            {"type":"object","properties":{
              "docId":{"type":"string","description":"Document ID"},
              "offset":{"type":"integer","description":"Line offset for pagination (default 0)"},
              "limit":{"type":"integer","description":"Max lines to return (default 200)"}
             },"required":["docId"]}""");

        // ── LLM Provider Management ────────────────────────
        register("gsim_llm_list",
            "List all configured LLM providers (base URL, model, API key status).",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_llm_get",
            "Get details for a specific LLM provider by ID.",
            """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Provider ID"}
            },"required":["id"]}""");

        register("gsim_llm_add",
            "Add a new LLM provider configuration.",
            """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Provider ID (unique)"},
              "name":{"type":"string","description":"Display name (optional)"},
              "baseUrl":{"type":"string","description":"API base URL"},
              "model":{"type":"string","description":"Default model name"},
              "apiKey":{"type":"string","description":"API key (optional, can be set later)"}
            },"required":["id","baseUrl","model"]}""");

        register("gsim_llm_update",
            "Update a field of an existing LLM provider.",
            """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Provider ID"},
              "field":{"type":"string","description":"Field to update: name, baseUrl, model, apiKey"},
              "value":{"type":"string","description":"New value for the field"}
            },"required":["id","field","value"]}""");

        register("gsim_llm_delete",
            "Delete an LLM provider configuration by ID.",
            """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Provider ID to delete"}
            },"required":["id"]}""");

        register("gsim_llm_test",
            "Test connectivity to an LLM provider.",
            """
            {"type":"object","properties":{
              "id":{"type":"string","description":"Provider ID to test"}
            },"required":["id"]}""");

        // ── Agent Lifecycle Management ──────────────────────
        register("gsim_agent_list",
            "List all agent instances and their statuses.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_agent_get",
            "Get status and details for a specific agent instance.",
            """
            {"type":"object","properties":{
              "instanceId":{"type":"string","description":"Agent instance ID"}
            },"required":["instanceId"]}""");

        register("gsim_agent_run",
            "Launch a new agent with given prompt. Returns instanceId for tracking.",
            """
            {"type":"object","properties":{
              "sessionId":{"type":"string","description":"Session ID (default: 'default')"},
              "prompt":{"type":"string","description":"Agent prompt/input text"},
              "configId":{"type":"string","description":"Agent config ID to use (optional)"},
              "cacheId":{"type":"string","description":"Cache ID for resumed context (optional)"},
              "parentInstanceId":{"type":"string","description":"Parent instance ID for chaining (optional)"}
            },"required":["sessionId","prompt"]}""");

        register("gsim_agent_cancel",
            "Cancel a running agent by instance ID.",
            """
            {"type":"object","properties":{
              "instanceId":{"type":"string","description":"Agent instance ID to cancel"}
            },"required":["instanceId"]}""");

        register("gsim_agent_output",
            "Get the output of a completed agent run.",
            """
            {"type":"object","properties":{
              "instanceId":{"type":"string","description":"Agent instance ID"}
            },"required":["instanceId"]}""");

        // ── Agent Configuration Management ──────────────────
        register("gsim_agent_config_list",
            "List all saved agent configurations.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_agent_config_get",
            "Get details of a specific agent configuration.",
            """
            {"type":"object","properties":{
              "configId":{"type":"string","description":"Config ID"}
            },"required":["configId"]}""");

        register("gsim_agent_config_create",
            "Create a new agent configuration. Fields match AgentConfig record.",
            """
            {"type":"object","properties":{
              "agentId":{"type":"string","description":"Unique agent config ID"},
              "llmProvider":{"type":"string","description":"LLM provider ID to use (default: 'base')"},
              "staticSystemPrompt":{"type":"string","description":"System prompt (static)"},
              "systemPrompt":{"type":"string","description":"Legacy system prompt field"},
              "userTemplate":{"type":"string","description":"User message template (optional)"},
              "toolGroups":{"type":"array","items":{"type":"string"},"description":"Tool group keys to enable (optional)"},
              "maxToolRounds":{"type":"integer","description":"Max tool call rounds (default: 32)"},
              "temperature":{"type":"number","description":"LLM temperature (default: 0.3)"},
              "maxTokens":{"type":"integer","description":"Max tokens per response (default: 2048)"}
            },"required":["agentId","staticSystemPrompt"]}""");

        register("gsim_agent_config_update",
            "Update fields of an existing agent configuration.",
            """
            {"type":"object","properties":{
              "configId":{"type":"string","description":"Config ID to update"},
              "llmProvider":{"type":"string","description":"New provider (optional)"},
              "staticSystemPrompt":{"type":"string","description":"New system prompt (optional)"},
              "systemPrompt":{"type":"string","description":"New legacy prompt (optional)"},
              "userTemplate":{"type":"string","description":"New user template (optional)"},
              "toolGroups":{"type":"array","items":{"type":"string"},"description":"New tool groups (optional)"},
              "maxToolRounds":{"type":"integer","description":"New max rounds (optional)"},
              "temperature":{"type":"number","description":"New temperature (optional)"},
              "maxTokens":{"type":"integer","description":"New max tokens (optional)"}
            },"required":["configId"]}""");

        register("gsim_agent_config_delete",
            "Delete an agent configuration by ID.",
            """
            {"type":"object","properties":{
              "configId":{"type":"string","description":"Config ID to delete"}
            },"required":["configId"]}""");

        // ── World CRUD ─────────────────────────────────────────
        register("gsim_create_world",
            "Create a new GSim world. Creates world.json, root node n0000 (with worldview + narrative checkpoints), active.json, and updates _index.json.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"Unique world ID (alphanumeric, dash, underscore)"},
              "name":{"type":"string","description":"Display name (defaults to worldId)"}
            },"required":["worldId"]}""");

        register("gsim_delete_world",
            "Delete a GSim world. Recursively removes the world directory and updates _index.json.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"World ID to delete"}
            },"required":["worldId"]}""");

        // ── Import doc CRUD ───────────────────────────────────
        register("gsim_create_doc",
            "Create a new document in the import directory (.txt or .md).",
            """
            {"type":"object","properties":{
              "name":{"type":"string","description":"Filename (e.g. 'research.txt'). Auto-appends .txt if no extension."},
              "content":{"type":"string","description":"Full document text content"}
            },"required":["name","content"]}""");

        register("gsim_update_doc",
            "Update an existing import document by docId.",
            """
            {"type":"object","properties":{
              "docId":{"type":"string","description":"Document ID (relative path in import dir)"},
              "content":{"type":"string","description":"New full text content"}
            },"required":["docId","content"]}""");

        register("gsim_delete_doc",
            "Delete an import document by docId.",
            """
            {"type":"object","properties":{
              "docId":{"type":"string","description":"Document ID to delete"}
            },"required":["docId"]}""");

        // ── DocStore CRUD ─────────────────────────────────────
        register("gsim_doc_store_list",
            "List documents in the DocStore (data/docs/). Filter by type (skill, character, world_state, template, context, rule, board, other) or tag.",
            """
            {"type":"object","properties":{
              "type":{"type":"string","description":"Document type filter (optional): skill, character, world_state, ..."},
              "tag":{"type":"string","description":"Tag filter (optional, case-sensitive substring match)"}
            },"required":[]}""");

        register("gsim_doc_store_get",
            "Read a DocStore document by docId. Auto-discovers the type subdirectory. Supports line-level offset/limit pagination.",
            """
            {"type":"object","properties":{
              "docId":{"type":"string","description":"Document ID (e.g. 'my-skill')"},
              "offset":{"type":"integer","description":"Line offset for pagination (default 0)"},
              "limit":{"type":"integer","description":"Max lines to return (default 200)"}
            },"required":["docId"]}""");

        register("gsim_doc_store_create",
            "Create a new DocStore document (YAML frontmatter + Markdown). Stored at data/docs/{type}/{docId}.md.",
            """
            {"type":"object","properties":{
              "docId":{"type":"string","description":"Unique document ID"},
              "type":{"type":"string","description":"Document type: skill, character, world_state, template, context, rule, board, other"},
              "title":{"type":"string","description":"Document title"},
              "content":{"type":"string","description":"Markdown body content"},
              "tags":{"type":"string","description":"Comma-separated tags (optional)"}
            },"required":["docId","type","title","content"]}""");

        // ── Agent Caches ───────────────────────────────────────
        register("gsim_agent_cache_list",
            "List agent conversation caches. Filter by worldId or agentType (orchestrator/sim/search).",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"Filter by world ID (optional)"},
              "agentType":{"type":"string","description":"Filter by agent type (optional): orchestrator, sim, search"}
            },"required":[]}""");

        register("gsim_agent_cache_get",
            "Read an agent cache by sessionId. Supports message pagination via offset/limit. Use summary=true for metadata only.",
            """
            {"type":"object","properties":{
              "cacheId":{"type":"string","description":"Cache session ID (filename, e.g. 'orchestrator_2026-07-06T12-04-23.json')"},
              "summary":{"type":"boolean","description":"Return metadata only, omit messages (default false)"},
              "offset":{"type":"integer","description":"Message offset for pagination (default 0)"},
              "limit":{"type":"integer","description":"Max messages to return (default 50)"}
            },"required":["cacheId"]}""");

        register("gsim_agent_cache_create",
            "Create a new agent conversation cache. Auto-injects system prompt from agent config if available.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string","description":"World ID for the cache"},
              "agentName":{"type":"string","description":"Agent name (e.g. 'orchestrator', 'sim-1'). Default: 'orchestrator'."},
              "nodeId":{"type":"string","description":"Node ID (default: 'n0000')"}
            },"required":["worldId"]}""");

        register("gsim_agent_cache_delete",
            "Delete an agent cache by session ID.",
            """
            {"type":"object","properties":{
              "cacheId":{"type":"string","description":"Cache session ID to delete"}
            },"required":["cacheId"]}""");

        // ── Skills ─────────────────────────────────────────────
        register("gsim_skill_list",
            "List all skill documents (from data/docs/skill/). Returns id, title, type, tags, summary for each.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_skill_get",
            "Read a skill document by ID. Supports line-level offset/limit pagination.",
            """
            {"type":"object","properties":{
              "skillId":{"type":"string","description":"Skill ID (filename without .md extension)"},
              "offset":{"type":"integer","description":"Line offset (default 0)"},
              "limit":{"type":"integer","description":"Max lines (default 200)"}
            },"required":["skillId"]}""");

        register("gsim_skill_search",
            "Search skill documents by keyword. Matches against title and content. Returns up to 20 results with excerpts.",
            """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Search keyword(s)"}
            },"required":["query"]}""");

        // ── Text Caches ────────────────────────────────────────
        register("gsim_cache_list",
            "List all text caches (from data/docs/.cache/). Returns id and file size for each.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_cache_get",
            "Read a text cache by ID. Supports line-level offset/limit pagination.",
            """
            {"type":"object","properties":{
              "cacheId":{"type":"string","description":"Cache ID (filename, e.g. 'crop_20260701_120000_a1b2c3d4.txt')"},
              "offset":{"type":"integer","description":"Line offset (default 0)"},
              "limit":{"type":"integer","description":"Max lines (default 200)"}
            },"required":["cacheId"]}""");

        register("gsim_cache_edit",
            "Edit a text cache. Supports keyword replacement (replace_from → replace_to) and text appending (insert_text). Result saved as a new cache file.",
            """
            {"type":"object","properties":{
              "cacheId":{"type":"string","description":"Source cache ID to edit"},
              "replace_from":{"type":"string","description":"Text to find and replace (optional)"},
              "replace_to":{"type":"string","description":"Replacement text (optional, used with replace_from)"},
              "insert_text":{"type":"string","description":"Text to append at end (optional)"}
            },"required":["cacheId"]}""");

        // ── Status ─────────────────────────────────────────────
        register("gsim_get_status",
            "Get MCP server status: version, directories, world count, tool count.",
            """
            {"type":"object","properties":{},"required":[]}""");

        register("gsim_list_tools",
            "List GSimulator agent tools. Delegates to gsim-app HTTP API if running, otherwise returns a static tool summary.",
            """
            {"type":"object","properties":{},"required":[]}""");
    }

    private void register(String name, String description, String schema) {
        tools.put(name, new ToolDef(name, description, schema));
    }

    // ── JSON helpers ───────────────────────────────────────

    private static String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    // ── Resolve helpers ─────────────────────────────────────

    private ObjectNode readNodeFile(String worldId, String nodeId) throws Exception {
        Path path = worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
        if (!Files.exists(path)) throw new IllegalArgumentException("Node not found: " + worldId + "/" + nodeId);
        return (ObjectNode) MAPPER.readTree(path.toFile());
    }

    private void writeNodeFile(String worldId, String nodeId, ObjectNode node) throws IOException {
        Path path = worldsDir.resolve(worldId).resolve("nodes").resolve(nodeId + ".json");
        Files.createDirectories(path.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), node);
    }

    private String now() {
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC));
    }

    // ── Tool: gsim_list_worlds ──────────────────────────────

    private String handleListWorlds(JsonNode args) {
        List<Map<String, Object>> worlds = new ArrayList<>();
        java.io.File[] dirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
        if (dirs == null) return toJson(Map.of("worlds", List.of(), "count", 0));

        for (java.io.File wf : dirs) {
            Path w = wf.toPath();
            Path worldJson = w.resolve("world.json");
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("worldId", w.getFileName().toString());
            try {
                if (Files.exists(worldJson)) {
                    JsonNode meta = MAPPER.readTree(worldJson.toFile());
                    info.put("name", meta.has("name") ? meta.get("name").asText() : "");
                    info.put("createdAt", meta.has("createdAt") ? meta.get("createdAt").asText() : "");
                }
                Path nodesDir = w.resolve("nodes");
                info.put("nodeCount", Files.isDirectory(nodesDir) ?
                    Files.list(nodesDir).filter(f -> f.toString().endsWith(".json") && !f.toString().contains("_map")).count() : 0);
            } catch (Exception ignored) {}
            worlds.add(info);
        }
        return toJson(Map.of("worlds", worlds, "count", worlds.size()));
    }

    // ── Tool: gsim_get_world_info ───────────────────────────

    private String handleGetWorldInfo(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        Path w = worldsDir.resolve(worldId);
        if (!Files.isDirectory(w)) return toJson(Map.of("error", "World not found: " + worldId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", worldId);

        // World metadata
        Path worldJson = w.resolve("world.json");
        if (Files.exists(worldJson)) {
            JsonNode meta = MAPPER.readTree(worldJson.toFile());
            result.put("name", meta.has("name") ? meta.get("name").asText() : "");
            result.put("createdAt", meta.has("createdAt") ? meta.get("createdAt").asText() : "");
        }

        // Active node — informational only (read from active.json for display)
        Path activeFile = w.resolve("active.json");
        String activeNode = null;
        if (Files.exists(activeFile)) {
            try {
                JsonNode an = MAPPER.readTree(activeFile.toFile());
                if (an.has("nodeId") && !an.get("nodeId").isNull())
                    activeNode = an.get("nodeId").asText();
            } catch (Exception ignored) {}
        }
        if (activeNode != null) result.put("activeNode", activeNode);

        // Node chain
        Path nodesDir = w.resolve("nodes");
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (Files.isDirectory(nodesDir)) {
            var nodeFiles = Files.list(nodesDir)
                .filter(f -> f.getFileName().toString().matches("n\\d+\\.json"))
                .sorted().toList();
            for (Path nf : nodeFiles) {
                try {
                    String nid = nf.getFileName().toString().replace(".json", "");
                    JsonNode nd = MAPPER.readTree(nf.toFile());
                    Map<String, Object> ni = new LinkedHashMap<>();
                    ni.put("nodeId", nid);
                    ni.put("turn", nd.has("turn") ? nd.get("turn").asInt() : -1);
                    ni.put("worldTime", nd.has("worldTime") ? nd.get("worldTime").asText() : "");
                    ni.put("parentId", nd.has("parentId") && !nd.get("parentId").isNull()
                        ? nd.get("parentId").asText() : null);
                    ni.put("hasMap", Files.exists(nodesDir.resolve(nid + "_map.json")));
                    nodes.add(ni);
                } catch (Exception ignored) {}
            }
        }
        result.put("nodes", nodes);
        result.put("nodeCount", nodes.size());
        return toJson(result);
    }

    // ── Tool: gsim_get_node_info ────────────────────────────

    private String handleGetNodeInfo(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        ObjectNode node = readNodeFile(worldId, nodeId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("worldId", worldId);
        result.put("nodeId", nodeId);
        result.put("turn", node.has("turn") ? node.get("turn").asInt() : -1);
        result.put("worldTime", node.has("worldTime") ? node.get("worldTime").asText() : "");
        result.put("parentId", node.has("parentId") && !node.get("parentId").isNull()
            ? node.get("parentId").asText() : null);
        result.put("status", node.has("status") ? node.get("status").asText() : "");
        result.put("isRoot", node.has("root") && node.get("root").asBoolean());

        // Checkpoint summary
        List<Map<String, Object>> cps = new ArrayList<>();
        if (node.has("checkpoints")) {
            var fields = node.get("checkpoints").fields();
            while (fields.hasNext()) {
                var f = fields.next();
                Map<String, Object> cp = new LinkedHashMap<>();
                cp.put("name", f.getKey());
                var cpNode = f.getValue();
                cp.put("label", cpNode.has("label") ? cpNode.get("label").asText() : "");
                cp.put("type", cpNode.has("type") ? cpNode.get("type").asText() : "");
                cp.put("elementCount", cpNode.has("elements") ? cpNode.get("elements").size() : 0);
                cps.add(cp);
            }
        }
        result.put("checkpoints", cps);
        return toJson(result);
    }

    // ── Tool: gsim_list_checkpoints ─────────────────────────

    private String handleListCheckpoints(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        ObjectNode node = readNodeFile(worldId, nodeId);

        List<Map<String, Object>> cps = new ArrayList<>();
        if (node.has("checkpoints")) {
            var fields = node.get("checkpoints").fields();
            while (fields.hasNext()) {
                var f = fields.next();
                Map<String, Object> cp = new LinkedHashMap<>();
                cp.put("name", f.getKey());
                var cpNode = f.getValue();
                cp.put("label", cpNode.has("label") ? cpNode.get("label").asText() : "");
                cp.put("type", cpNode.has("type") ? cpNode.get("type").asText() : "");
                cp.put("elementCount", cpNode.has("elements") ? cpNode.get("elements").size() : 0);
                cps.add(cp);
            }
        }
        return toJson(Map.of("worldId", worldId, "nodeId", nodeId, "checkpoints", cps));
    }

    // ── Tool: gsim_get_checkpoint ───────────────────────────

    private String handleGetCheckpoint(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        String checkpointName = args.get("checkpoint").asText();
        String filterKey = args.has("key") ? args.get("key").asText() : null;
        int limit = args.has("limit") ? args.get("limit").asInt() : 50;

        ObjectNode node = readNodeFile(worldId, nodeId);
        JsonNode cps = node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            return toJson(Map.of("worldId", worldId, "nodeId", nodeId,
                "checkpoint", checkpointName, "elements", List.of(), "count", 0));

        JsonNode cp = cps.get(checkpointName);
        JsonNode elements = cp.get("elements");
        if (elements == null || !elements.isArray())
            return toJson(Map.of("worldId", worldId, "nodeId", nodeId,
                "checkpoint", checkpointName, "elements", List.of(), "count", 0));

        // Collect tags filter
        Set<String> filterTags = new HashSet<>();
        if (args.has("tags") && args.get("tags").isArray()) {
            for (JsonNode t : args.get("tags")) filterTags.add(t.asText());
        }

        List<Object> filtered = new ArrayList<>();
        for (JsonNode el : elements) {
            if (filterKey != null && !filterKey.isBlank()) {
                String ek = el.has("key") ? el.get("key").asText() : "";
                if (!ek.equals(filterKey)) continue;
            }
            if (!filterTags.isEmpty()) {
                Set<String> elTags = new HashSet<>();
                if (el.has("tags") && el.get("tags").isArray()) {
                    for (JsonNode t : el.get("tags")) elTags.add(t.asText());
                }
                if (!elTags.containsAll(filterTags)) continue;
            }
            filtered.add(MAPPER.treeToValue(el, Map.class));
            if (filtered.size() >= limit) break;
        }

        return toJson(Map.of("worldId", worldId, "nodeId", nodeId,
            "checkpoint", checkpointName,
            "label", cp.has("label") ? cp.get("label").asText() : "",
            "elements", filtered, "count", filtered.size()));
    }

    // ── Tool: gsim_add_checkpoint_element ───────────────────

    private String handleAddCheckpointElement(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        String checkpointName = args.get("checkpoint").asText();
        String key = args.get("key").asText();

        ObjectNode node = readNodeFile(worldId, nodeId);
        ObjectNode cps = getOrCreateCheckpoints(node);
        ObjectNode cp = getOrCreateCheckpoint(cps, checkpointName, checkpointName);
        ArrayNode elements = getOrCreateElements(cp);

        // Check for duplicate key
        for (JsonNode el : elements) {
            if (el.has("key") && el.get("key").asText().equals(key)) {
                return toJson(Map.of("ok", false, "error",
                    "Element with key '" + key + "' already exists in " + checkpointName));
            }
        }

        String elType = args.has("type") ? args.get("type").asText() : "text";
        String value = args.has("value") ? args.get("value").asText() : "";

        ObjectNode el = MAPPER.createObjectNode();
        el.put("key", key);
        el.put("type", elType);
        el.put("value", value);
        ArrayNode tagArr = MAPPER.createArrayNode();
        if (args.has("tags") && args.get("tags").isArray())
            for (JsonNode t : args.get("tags")) tagArr.add(t.asText());
        el.set("tags", tagArr);
        el.set("links", MAPPER.createArrayNode());
        String ts = now();
        el.put("createdAt", ts);
        el.put("updatedAt", ts);
        elements.add(el);

        writeNodeFile(worldId, nodeId, node);
        return toJson(Map.of("ok", true, "worldId", worldId, "nodeId", nodeId,
            "checkpoint", checkpointName, "key", key, "type", elType));
    }

    // ── Tool: gsim_update_checkpoint_element ────────────────

    private String handleUpdateCheckpointElement(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        String checkpointName = args.get("checkpoint").asText();
        String key = args.get("key").asText();

        ObjectNode node = readNodeFile(worldId, nodeId);
        ObjectNode cps = (ObjectNode) node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            return toJson(Map.of("ok", false, "error", "Checkpoint not found: " + checkpointName));

        ArrayNode elements = (ArrayNode) cps.get(checkpointName).get("elements");
        if (elements == null)
            return toJson(Map.of("ok", false, "error", "No elements in checkpoint: " + checkpointName));

        ObjectNode target = null;
        for (JsonNode el : elements) {
            if (el.has("key") && el.get("key").asText().equals(key)) { target = (ObjectNode) el; break; }
        }
        if (target == null)
            return toJson(Map.of("ok", false, "error", "Element not found: " + key));

        if (args.has("value")) target.put("value", args.get("value").asText());
        if (args.has("type")) target.put("type", args.get("type").asText());
        if (args.has("tags") && args.get("tags").isArray()) {
            ArrayNode tagArr = MAPPER.createArrayNode();
            for (JsonNode t : args.get("tags")) tagArr.add(t.asText());
            target.set("tags", tagArr);
        }
        target.put("updatedAt", now());

        writeNodeFile(worldId, nodeId, node);
        return toJson(Map.of("ok", true, "worldId", worldId, "nodeId", nodeId,
            "checkpoint", checkpointName, "key", key));
    }

    // ── Tool: gsim_delete_checkpoint_element ────────────────

    private String handleDeleteCheckpointElement(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String nodeId = args.get("nodeId").asText();
        String checkpointName = args.get("checkpoint").asText();
        String key = args.get("key").asText();

        ObjectNode node = readNodeFile(worldId, nodeId);
        ObjectNode cps = (ObjectNode) node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            return toJson(Map.of("ok", false, "error", "Checkpoint not found: " + checkpointName));

        ArrayNode elements = (ArrayNode) cps.get(checkpointName).get("elements");
        if (elements == null)
            return toJson(Map.of("ok", false, "error", "No elements in checkpoint: " + checkpointName));

        int idx = -1;
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i).has("key") && elements.get(i).get("key").asText().equals(key))
            { idx = i; break; }
        }
        if (idx < 0)
            return toJson(Map.of("ok", false, "error", "Element not found: " + key));

        elements.remove(idx);
        writeNodeFile(worldId, nodeId, node);
        return toJson(Map.of("ok", true, "worldId", worldId, "nodeId", nodeId,
            "checkpoint", checkpointName, "key", key));
    }

    // ── Tool: gsim_search ───────────────────────────────────

    private String handleSearch(JsonNode args) throws Exception {
        String query = args.get("query").asText();
        String scope = args.has("scope") ? args.get("scope").asText() : "all";
        String filterWorld = args.has("worldId") ? args.get("worldId").asText() : null;
        int limit = args.has("limit") ? args.get("limit").asInt() : 20;

        List<Map<String, Object>> results = new ArrayList<>();
        String queryLower = query.toLowerCase();

        // Search world checkpoint elements
        if ("world".equals(scope) || "all".equals(scope)) {
            java.io.File[] worldDirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
            if (worldDirs != null) {
                for (java.io.File wf : worldDirs) {
                    String wid = wf.getName();
                    if (filterWorld != null && !filterWorld.equals(wid)) continue;
                    Path nodesDir = wf.toPath().resolve("nodes");
                    if (!Files.isDirectory(nodesDir)) continue;

                    try (var files = Files.list(nodesDir)) {
                        files.filter(f -> f.getFileName().toString().matches("n\\d+\\.json"))
                            .forEach(nf -> {
                                try {
                                    String nid = nf.getFileName().toString().replace(".json", "");
                                    JsonNode node = MAPPER.readTree(nf.toFile());
                                    if (!node.has("checkpoints")) return;
                                    var cps = node.get("checkpoints").fields();
                                    while (cps.hasNext() && results.size() < limit) {
                                        var cpEntry = cps.next();
                                        String cpName = cpEntry.getKey();
                                        JsonNode elements = cpEntry.getValue().get("elements");
                                        if (elements == null || !elements.isArray()) continue;
                                        for (JsonNode el : elements) {
                                            if (results.size() >= limit) break;
                                            String value = el.has("value") ? el.get("value").asText() : "";
                                            String key = el.has("key") ? el.get("key").asText() : "";
                                            if (value.toLowerCase().contains(queryLower) ||
                                                key.toLowerCase().contains(queryLower)) {
                                                Map<String, Object> hit = new LinkedHashMap<>();
                                                hit.put("source", "world");
                                                hit.put("worldId", wid);
                                                hit.put("nodeId", nid);
                                                hit.put("checkpoint", cpName);
                                                hit.put("key", key);
                                                // Truncate value to ~300 chars with context around match
                                                String excerpt = value;
                                                if (excerpt.length() > 300) {
                                                    int pos = Math.max(0, excerpt.toLowerCase().indexOf(queryLower) - 40);
                                                    excerpt = (pos > 0 ? "…" : "")
                                                        + excerpt.substring(pos, Math.min(pos + 300, excerpt.length()))
                                                        + (pos + 300 < excerpt.length() ? "…" : "");
                                                }
                                                hit.put("excerpt", excerpt);
                                                hit.put("ref", "@world:" + nid + ":" + cpName + ":" + key);
                                                results.add(hit);
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {}
                            });
                    } catch (Exception ignored) {}
                }
            }
        }

        // Search import documents
        if ("docs".equals(scope) || "all".equals(scope)) {
            Path importDir = this.importDir;
            if (Files.isDirectory(importDir)) {
                try (var files = Files.walk(importDir)) {
                    files.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".txt") || f.toString().endsWith(".md"))
                        .forEach(f -> {
                            if (results.size() >= limit) return;
                            try {
                                String content = Files.readString(f);
                                if (content.toLowerCase().contains(queryLower)) {
                                    Map<String, Object> hit = new LinkedHashMap<>();
                                    hit.put("source", "doc");
                                    String docId = importDir.relativize(f).toString();
                                    hit.put("docId", docId);
                                    int pos = Math.max(0, content.toLowerCase().indexOf(queryLower) - 40);
                                    String excerpt = (pos > 0 ? "…" : "")
                                        + content.substring(pos, Math.min(pos + 300, content.length()))
                                        + (pos + 300 < content.length() ? "…" : "");
                                    hit.put("excerpt", excerpt);
                                    hit.put("ref", "@doc:" + docId);
                                    results.add(hit);
                                }
                            } catch (Exception ignored) {}
                        });
                } catch (Exception ignored) {}
            }
        }

        return toJson(Map.of("query", query, "scope", scope, "results", results, "count", results.size()));
    }

    // ── Tool: gsim_resolve_ref ──────────────────────────────

    private String handleResolveRef(JsonNode args) throws Exception {
        String ref = args.get("ref").asText().strip();
        String explicitNodeId = args.has("nodeId") ? args.get("nodeId").asText() : null;

        // Parse: @world:worldId:nodeId:checkpoint:key → ["world", ...]
        // Parse: @doc:some_doc → ["doc", "some_doc"]
        if (!ref.startsWith("@")) return toJson(Map.of("error", "Invalid ref: must start with @", "ref", ref));

        String[] parts = ref.substring(1).split(":", -1);
        if (parts.length < 2) return toJson(Map.of("error", "Invalid ref: at least @type:id required", "ref", ref));

        return switch (parts[0]) {
            case "world" -> resolveWorldRef(parts, explicitNodeId);
            case "doc" -> resolveDocRef(parts[1]);
            default -> toJson(Map.of("error", "Unknown ref type: " + parts[0], "ref", ref));
        };
    }

    private String resolveWorldRef(String[] parts, String explicitNodeId) throws Exception {
        String worldId, nodeId, checkpointName, key;

        if (parts.length >= 5) {
            // @world:worldId:nodeId:checkpoint:key — fully explicit
            worldId = parts[1];
            nodeId = parts[2];
            checkpointName = parts[3];
            key = parts[4];
        } else if (parts.length == 4) {
            // @world:worldId:checkpoint:key — requires explicit nodeId parameter
            if (explicitNodeId == null || explicitNodeId.isBlank())
                return toJson(Map.of("error", "4-segment @world ref requires explicit nodeId parameter", "ref", "@" + String.join(":", parts)));
            worldId = parts[1];
            nodeId = explicitNodeId;
            checkpointName = parts[2];
            key = parts[3];
        } else {
            return toJson(Map.of("error", "Invalid @world ref: need 4-5 segments (worldId:nodeId:checkpoint:key or worldId:checkpoint:key with nodeId param)", "ref", "@" + String.join(":", parts)));
        }

        // Verify world exists
        if (!Files.isDirectory(worldsDir.resolve(worldId)))
            return toJson(Map.of("error", "World not found: " + worldId, "ref", "@" + String.join(":", parts)));

        // Resolve the element
        ObjectNode node = readNodeFile(worldId, nodeId);
        JsonNode cps = node.get("checkpoints");
        if (cps == null || !cps.has(checkpointName))
            return toJson(Map.of("error", "Checkpoint not found: " + checkpointName,
                "worldId", worldId, "nodeId", nodeId));

        JsonNode elements = cps.get(checkpointName).get("elements");
        if (elements == null || !elements.isArray())
            return toJson(Map.of("error", "No elements in checkpoint",
                "worldId", worldId, "nodeId", nodeId, "checkpoint", checkpointName));

        for (JsonNode el : elements) {
            if (el.has("key") && el.get("key").asText().equals(key)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", "world");
                result.put("worldId", worldId);
                result.put("nodeId", nodeId);
                result.put("checkpoint", checkpointName);
                result.put("key", key);
                result.put("type", el.has("type") ? el.get("type").asText() : "");
                result.put("value", el.has("value") ? el.get("value").asText() : "");
                result.put("ref", "@world:" + nodeId + ":" + checkpointName + ":" + key);
                List<String> tags = new ArrayList<>();
                if (el.has("tags") && el.get("tags").isArray())
                    for (JsonNode t : el.get("tags")) tags.add(t.asText());
                result.put("tags", tags);
                return toJson(result);
            }
        }
        return toJson(Map.of("error", "Element not found: " + key,
            "worldId", worldId, "nodeId", nodeId, "checkpoint", checkpointName));
    }

    private String resolveDocRef(String docId) {
        Path importDir = this.importDir;
        Path docPath = importDir.resolve(docId);
        if (!Files.exists(docPath)) {
            // Try resolving just the filename
            try (var files = Files.walk(importDir)) {
                var found = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals(docId))
                    .findFirst();
                if (found.isPresent()) docPath = found.get();
            } catch (Exception ignored) {}
        }
        if (!Files.exists(docPath))
            return toJson(Map.of("error", "Document not found: " + docId));

        try {
            String content = Files.readString(docPath);
            return toJson(Map.of("source", "doc", "docId", docId,
                "content", content, "ref", "@doc:" + docId));
        } catch (IOException e) {
            return toJson(Map.of("error", "Failed to read document: " + e.getMessage()));
        }
    }

    // ── Tool: gsim_list_docs ────────────────────────────────

    private String handleListDocs(JsonNode args) {
        Path importDir = this.importDir;
        String filterQuery = args.has("query") ? args.get("query").asText().toLowerCase() : null;

        List<Map<String, Object>> docs = new ArrayList<>();
        if (Files.isDirectory(importDir)) {
            try (var files = Files.walk(importDir)) {
                files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".txt") || f.toString().endsWith(".md"))
                    .forEach(f -> {
                        String relPath = importDir.relativize(f).toString();
                        if (filterQuery != null && !relPath.toLowerCase().contains(filterQuery)) return;
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("docId", relPath);
                        try {
                            d.put("size", Files.size(f));
                        } catch (Exception ignored) {}
                        docs.add(d);
                    });
            } catch (Exception ignored) {}
        }
        return toJson(Map.of("docs", docs, "count", docs.size()));
    }

    // ── Tool: gsim_get_doc ──────────────────────────────────

    private String handleGetDoc(JsonNode args) throws Exception {
        String docId = args.get("docId").asText();
        int offset = args.has("offset") ? args.get("offset").asInt() : 0;
        int limit = args.has("limit") ? args.get("limit").asInt() : 200;

        Path importDir = this.importDir;
        Path docPath = importDir.resolve(docId);
        if (!Files.exists(docPath)) {
            // Try by filename
            try (var files = Files.walk(importDir)) {
                var found = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals(docId))
                    .findFirst();
                if (found.isPresent()) docPath = found.get();
            } catch (Exception ignored) {}
        }
        if (!Files.exists(docPath))
            return toJson(Map.of("error", "Document not found: " + docId));

        String content = Files.readString(docPath);
        String[] lines = content.split("\n");
        int totalLines = lines.length;

        int from = Math.max(0, offset);
        int to = Math.min(from + limit, lines.length);
        String excerpt = String.join("\n", Arrays.copyOfRange(lines, from, to));

        return toJson(Map.of("docId", docId, "totalLines", totalLines,
            "offset", offset, "limit", limit, "content", excerpt,
            "ref", "@doc:" + docId));
    }

    // ── JSON helpers (shared) ───────────────────────────────

    private ObjectNode getOrCreateCheckpoints(ObjectNode node) {
        if (!node.has("checkpoints") || node.get("checkpoints").isNull())
            node.set("checkpoints", MAPPER.createObjectNode());
        return (ObjectNode) node.get("checkpoints");
    }

    private ObjectNode getOrCreateCheckpoint(ObjectNode cps, String name, String label) {
        if (!cps.has(name) || cps.get(name).isNull()) {
            ObjectNode cp = MAPPER.createObjectNode();
            cp.put("label", label);
            cp.put("type", "misc");
            cp.set("elements", MAPPER.createArrayNode());
            cps.set(name, cp);
        }
        return (ObjectNode) cps.get(name);
    }

    private ArrayNode getOrCreateElements(ObjectNode cp) {
        if (!cp.has("elements") || cp.get("elements").isNull())
            cp.set("elements", MAPPER.createArrayNode());
        return (ArrayNode) cp.get("elements");
    }

    // ── Directory resolution helpers ────────────────────────

    private Path resolveCachesPath() {
        return worldsDir.resolveSibling("caches");
    }

    private Path resolveDocsPath() {
        return worldsDir.resolveSibling("docs");
    }

    private Path resolveDocFilePath(String type, String docId) {
        return resolveDocsPath().resolve(type).resolve(docId + ".md");
    }

    private Path resolveTextCacheDir() {
        return resolveDocsPath().resolve(".cache");
    }

    // ── YAML frontmatter parser ──────────────────────────────

    /** Parse YAML frontmatter from a Markdown document.
     *  Returns a map of key-value pairs (type, title, tags, version, ...). */
    private Map<String, String> parseYamlFrontmatter(String content) {
        Map<String, String> fm = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) return fm;
        int end = content.indexOf("\n---\n", 3);
        if (end < 0) end = content.indexOf("\n---\r\n", 3);
        if (end < 0) return fm;
        String yaml = content.substring(content.indexOf('\n', 3) + 1, end);
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String k = trimmed.substring(0, colon).trim();
            String v = trimmed.substring(colon + 1).trim();
            // Remove surrounding brackets and quotes
            if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
                v = v.substring(1, v.length() - 1);
            fm.put(k, v);
        }
        return fm;
    }

    /** Extract body content after YAML frontmatter. */
    private String extractMarkdownBody(String content) {
        if (content == null || !content.startsWith("---")) return content;
        int end = content.indexOf("\n---\n", 3);
        if (end < 0) end = content.indexOf("\n---\r\n", 3);
        if (end < 0) return content;
        return content.substring(end + 5).trim();
    }

    /** Build a YAML frontmatter + body Markdown document string. */
    private String buildDocContent(String type, String title, String tagsStr, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("type: ").append(type != null ? type : "other").append("\n");
        sb.append("title: ").append(title != null ? title : "").append("\n");
        if (tagsStr != null && !tagsStr.isBlank()) {
            sb.append("tags: [").append(tagsStr).append("]\n");
        }
        sb.append("version: 1\n");
        sb.append("updated: ").append(System.currentTimeMillis()).append("\n");
        sb.append("---\n");
        if (body != null) sb.append(body);
        return sb.toString();
    }

    // ── World index helpers ───────────────────────────────────

    private List<Map<String, Object>> loadWorldIndex() {
        Path idx = worldsDir.resolve("_index.json");
        if (!Files.exists(idx)) return new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(idx.toFile());
            List<Map<String, Object>> result = new ArrayList<>();
            if (arr.isArray()) for (JsonNode n : arr) result.add(MAPPER.treeToValue(n, Map.class));
            return result;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private void saveWorldIndex(List<Map<String, Object>> entries) throws IOException {
        Path idx = worldsDir.resolve("_index.json");
        Files.createDirectories(worldsDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(idx.toFile(), entries);
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) deleteRecursive(child);
            }
        }
        Files.delete(dir);
    }

    // ── LLM Provider handlers (direct file access) ──────────

    private LlmConfigManager getLlmConfigManager() {
        Path llmsPath = worldsDir.resolveSibling("llms.json");
        return new LlmConfigManager(llmsPath);
    }

    private Path resolveDataDir() {
        // Default data dir is {worldsDir}/../data
        return worldsDir.getParent() != null ? worldsDir.getParent().resolve("data") : Path.of("data");
    }

    private String handleLlmList(JsonNode args) throws Exception {
        return toJson(Map.of("providers", getLlmConfigManager().listProviders()));
    }

    private String handleLlmGet(JsonNode args) throws Exception {
        String id = args.get("id").asText();
        return toJson(getLlmConfigManager().getProvider(id));
    }

    private String handleLlmAdd(JsonNode args) throws Exception {
        String id = args.get("id").asText();
        String name = args.has("name") ? args.get("name").asText() : null;
        String baseUrl = args.get("baseUrl").asText();
        String model = args.get("model").asText();
        String apiKey = args.has("apiKey") ? args.get("apiKey").asText() : null;
        return toJson(getLlmConfigManager().addProvider(id, name, baseUrl, model, apiKey));
    }

    private String handleLlmUpdate(JsonNode args) throws Exception {
        String id = args.get("id").asText();
        String field = args.get("field").asText();
        String value = args.get("value").asText();
        return toJson(getLlmConfigManager().updateProvider(id, field, value));
    }

    private String handleLlmDelete(JsonNode args) throws Exception {
        String id = args.get("id").asText();
        return toJson(getLlmConfigManager().deleteProvider(id));
    }

    // ── Agent Config handlers (direct file access) ──────────

    private AgentConfigStore getAgentConfigStore() {
        AgentConfigStore store = new AgentConfigStore();
        // Agent configs live at project root as sibling of worlds/ — same as gsim-app
        Path agentsDir = worldsDir.resolveSibling("agents");
        try {
            store.reload(agentsDir);
        } catch (Exception e) {
            log.warn("Failed to load agent configs from {}: {}", agentsDir, e.getMessage());
        }
        return store;
    }

    private String handleAgentConfigList(JsonNode args) throws Exception {
        return toJson(Map.of("configs", getAgentConfigStore().all().values().stream()
            .map(this::cfgToMap).toList()));
    }

    private String handleAgentConfigGet(JsonNode args) throws Exception {
        String configId = args.get("configId").asText();
        AgentConfig cfg = getAgentConfigStore().get(configId);
        if (cfg == null) return toJson(Map.of("error", "Config not found: " + configId));
        return toJson(cfgToMap(cfg));
    }

    private String handleAgentConfigCreate(JsonNode args) throws Exception {
        String agentId = args.get("agentId").asText();
        String llmProvider = args.has("llmProvider") ? args.get("llmProvider").asText() : "base";
        String systemPrompt = args.get("staticSystemPrompt").asText();
        String userTemplate = args.has("userTemplate") ? args.get("userTemplate").asText() : "";
        int maxToolRounds = args.has("maxToolRounds") ? args.get("maxToolRounds").asInt() : 32;
        double temperature = args.has("temperature") ? args.get("temperature").asDouble() : 0.3;
        int maxTokens = args.has("maxTokens") ? args.get("maxTokens").asInt() : 2048;

        ToolFilterConfig toolFilter = new ToolFilterConfig("all", List.of(), List.of());
        AgentConfig cfg = new AgentConfig(agentId, llmProvider, systemPrompt, systemPrompt,
            userTemplate, toolFilter, maxToolRounds, temperature, maxTokens);
        getAgentConfigStore().saveConfig(cfg);
        return toJson(Map.of("ok", true, "configId", agentId));
    }

    private String handleAgentConfigUpdate(JsonNode args) throws Exception {
        String configId = args.get("configId").asText();
        AgentConfigStore store = getAgentConfigStore();
        AgentConfig existing = store.get(configId);
        if (existing == null) return toJson(Map.of("ok", false, "error", "Config not found: " + configId));

        String llmProvider = args.has("llmProvider") ? args.get("llmProvider").asText() : existing.llmProvider();
        String systemPrompt = args.has("staticSystemPrompt") ? args.get("staticSystemPrompt").asText() : existing.staticSystemPrompt();
        String userTemplate = args.has("userTemplate") ? args.get("userTemplate").asText() : existing.userTemplate();
        int maxToolRounds = args.has("maxToolRounds") ? args.get("maxToolRounds").asInt() : existing.maxToolRounds();
        double temperature = args.has("temperature") ? args.get("temperature").asDouble() : existing.temperature();
        int maxTokens = args.has("maxTokens") ? args.get("maxTokens").asInt() : existing.maxTokens();

        AgentConfig updated = new AgentConfig(configId, llmProvider, systemPrompt, existing.systemPrompt(),
            userTemplate, existing.toolFilter(), maxToolRounds, temperature, maxTokens);
        store.saveConfig(updated);
        return toJson(Map.of("ok", true, "configId", configId));
    }

    private String handleAgentConfigDelete(JsonNode args) throws Exception {
        String configId = args.get("configId").asText();
        getAgentConfigStore().deleteConfig(configId);
        return toJson(Map.of("ok", true, "configId", configId));
    }

    private Map<String, Object> cfgToMap(AgentConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configId", cfg.agentId());
        m.put("llmProvider", cfg.llmProvider());
        m.put("staticSystemPrompt", cfg.staticSystemPrompt());
        m.put("userTemplate", cfg.userTemplate());
        m.put("maxToolRounds", cfg.maxToolRounds());
        m.put("temperature", cfg.temperature());
        m.put("maxTokens", cfg.maxTokens());
        m.put("toolFilterMode", cfg.toolFilter() != null ? cfg.toolFilter().mode() : "all");
        return m;
    }

    // ── World CRUD handlers ──────────────────────────────────

    private String handleCreateWorld(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        if (!worldId.matches("[a-zA-Z0-9_\\-]+"))
            return toJson(Map.of("ok", false, "error", "Invalid worldId: use alphanumeric, dash, underscore"));
        Path worldDir = worldsDir.resolve(worldId);
        if (Files.exists(worldDir))
            return toJson(Map.of("ok", false, "error", "World already exists: " + worldId));

        String name = args.has("name") ? args.get("name").asText() : worldId;
        String now = Instant.now().toString();

        ObjectNode worldMeta = MAPPER.createObjectNode();
        worldMeta.put("id", worldId);
        worldMeta.put("name", name);
        worldMeta.put("createdAt", now);
        worldMeta.put("currentNodeId", "n0000");
        Files.createDirectories(worldDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(worldDir.resolve("world.json").toFile(), worldMeta);

        ObjectNode rootNode = MAPPER.createObjectNode();
        rootNode.put("nodeId", "n0000");
        rootNode.putNull("parentId");
        rootNode.put("turn", 0);
        rootNode.put("worldTime", "时间原点");
        rootNode.put("status", "initial");
        rootNode.put("createdAt", now);
        ObjectNode rootCps = MAPPER.createObjectNode();
        ObjectNode worldviewCp = MAPPER.createObjectNode();
        worldviewCp.put("label", "世界观");
        worldviewCp.put("type", "worldview");
        worldviewCp.set("elements", MAPPER.createArrayNode());
        rootCps.set("worldview", worldviewCp);
        ObjectNode narrativeCp = MAPPER.createObjectNode();
        narrativeCp.put("label", "推文");
        narrativeCp.put("type", "narrative");
        narrativeCp.set("elements", MAPPER.createArrayNode());
        rootCps.set("narrative", narrativeCp);
        rootNode.set("checkpoints", rootCps);
        Path nodesDir = worldDir.resolve("nodes");
        Files.createDirectories(nodesDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(nodesDir.resolve("n0000.json").toFile(), rootNode);

        ObjectNode activeState = MAPPER.createObjectNode();
        activeState.put("nodeId", "n0000");
        activeState.set("sessions", MAPPER.createObjectNode());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(worldDir.resolve("active.json").toFile(), activeState);

        List<Map<String, Object>> index = loadWorldIndex();
        index.add(Map.of("id", worldId, "name", name, "createdAt", now));
        saveWorldIndex(index);

        return toJson(Map.of("ok", true, "worldId", worldId, "name", name, "createdAt", now, "currentNodeId", "n0000"));
    }

    private String handleDeleteWorld(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        Path worldDir = worldsDir.resolve(worldId);
        if (!Files.isDirectory(worldDir) || !Files.exists(worldDir.resolve("world.json")))
            return toJson(Map.of("ok", false, "error", "World not found: " + worldId));

        deleteRecursive(worldDir);
        List<Map<String, Object>> index = loadWorldIndex();
        index.removeIf(e -> worldId.equals(e.get("id")));
        saveWorldIndex(index);

        return toJson(Map.of("ok", true, "worldId", worldId, "deleted", true));
    }

    // ── Node create handler (direct file I/O) ──────────────────

    private String handleCreateNode(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String parentId = args.get("parentId").asText();
        String worldTime = args.get("worldTime").asText();

        Path worldDir = worldsDir.resolve(worldId);
        if (!Files.isDirectory(worldDir) || !Files.exists(worldDir.resolve("world.json")))
            return toJson(Map.of("ok", false, "error", "World not found: " + worldId));

        Path parentFile = worldsDir.resolve(worldId).resolve("nodes").resolve(parentId + ".json");
        if (!Files.exists(parentFile))
            return toJson(Map.of("ok", false, "error", "Parent node not found: " + parentId));

        // Read parent turn
        ObjectNode parentNode = (ObjectNode) MAPPER.readTree(parentFile.toFile());
        int parentTurn = parentNode.has("turn") ? parentNode.get("turn").asInt() : 0;
        int nextTurn = parentTurn + 1;

        // Scan disk for max nXXXX → next node ID
        Path nodesDir = worldsDir.resolve(worldId).resolve("nodes");
        int maxNum = -1;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("n(\\d{4})\\.json$");
        if (Files.isDirectory(nodesDir)) {
            try (var files = Files.list(nodesDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    var m = p.matcher(f.getFileName().toString());
                    if (m.find()) {
                        int num = Integer.parseInt(m.group(1));
                        if (num > maxNum) maxNum = num;
                    }
                }
            }
        }
        String newNodeId = String.format("n%04d", maxNum + 1);

        // Build child node JSON
        ObjectNode childNode = MAPPER.createObjectNode();
        childNode.put("nodeId", newNodeId);
        childNode.put("parentId", parentId);
        childNode.put("turn", nextTurn);
        childNode.put("worldTime", worldTime);
        childNode.put("status", "active");
        childNode.put("createdAt", now());
        childNode.set("checkpoints", MAPPER.createObjectNode());

        writeNodeFile(worldId, newNodeId, childNode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("nodeId", newNodeId);
        result.put("parentId", parentId);
        result.put("turn", nextTurn);
        result.put("worldTime", worldTime);
        if (args.has("title") && !args.get("title").asText().isBlank())
            result.put("title", args.get("title").asText());
        result.put("action", "created");
        return toJson(result);
    }

    // ── Import doc CRUD handlers ──────────────────────────────

    private String handleCreateDoc(JsonNode args) throws Exception {
        String name = args.get("name").asText();
        String content = args.has("content") ? args.get("content").asText() : "";

        // Sanitize filename
        String safeName = name.replaceAll("[^a-zA-Z0-9_\\-.一-鿿]", "_");
        if (!safeName.endsWith(".txt") && !safeName.endsWith(".md") && !safeName.endsWith(".markdown"))
            safeName += ".txt";

        Path target = importDir.resolve(safeName);
        Files.createDirectories(target.getParent());
        if (Files.exists(target))
            return toJson(Map.of("ok", false, "error", "Document already exists: " + safeName));

        Files.writeString(target, content);
        return toJson(Map.of("ok", true, "docId", importDir.relativize(target).toString()));
    }

    private String handleUpdateDoc(JsonNode args) throws Exception {
        String docId = args.get("docId").asText();
        String content = args.has("content") ? args.get("content").asText() : "";
        Path docPath = importDir.resolve(docId);
        if (!Files.exists(docPath)) {
            try (var files = Files.walk(importDir)) {
                var found = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals(docId))
                    .findFirst();
                if (found.isPresent()) docPath = found.get();
            } catch (Exception ignored) {}
        }
        if (!Files.exists(docPath))
            return toJson(Map.of("ok", false, "error", "Document not found: " + docId));

        Files.writeString(docPath, content);
        return toJson(Map.of("ok", true, "docId", docId));
    }

    private String handleDeleteDoc(JsonNode args) throws Exception {
        String docId = args.get("docId").asText();
        Path docPath = importDir.resolve(docId);
        if (!Files.exists(docPath)) {
            try (var files = Files.walk(importDir)) {
                var found = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals(docId))
                    .findFirst();
                if (found.isPresent()) docPath = found.get();
            } catch (Exception ignored) {}
        }
        if (!Files.exists(docPath))
            return toJson(Map.of("ok", false, "error", "Document not found: " + docId));

        Files.delete(docPath);
        return toJson(Map.of("ok", true, "docId", docId, "deleted", true));
    }

    // ── DocStore handlers ─────────────────────────────────────

    private String handleDocStoreList(JsonNode args) throws Exception {
        Path docsDir = resolveDocsPath();
        String filterType = args.has("type") ? args.get("type").asText() : null;
        String filterTag = args.has("tag") ? args.get("tag").asText() : null;

        List<Map<String, Object>> docs = new ArrayList<>();
        if (Files.isDirectory(docsDir)) {
            try (var files = Files.walk(docsDir)) {
                files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".md"))
                    .forEach(f -> {
                        try {
                            String content = Files.readString(f);
                            Map<String, String> fm = parseYamlFrontmatter(content);
                            String docType = fm.getOrDefault("type", "other");
                            if (filterType != null && !filterType.equals(docType)) return;

                            Map<String, Object> d = new LinkedHashMap<>();
                            Path rel = docsDir.relativize(f);
                            String docId = rel.toString().replace(".md", "");
                            d.put("docId", docId);
                            d.put("type", docType);
                            d.put("title", fm.getOrDefault("title", ""));
                            d.put("tags", fm.getOrDefault("tags", ""));
                            d.put("version", fm.getOrDefault("version", "1"));
                            d.put("size", Files.size(f));

                            // Tag filter
                            if (filterTag != null && !filterTag.isBlank()) {
                                String tags = fm.getOrDefault("tags", "");
                                if (!tags.contains(filterTag)) return;
                            }
                            docs.add(d);
                        } catch (Exception ignored) {}
                    });
            } catch (Exception ignored) {}
        }
        return toJson(Map.of("docs", docs, "count", docs.size()));
    }

    private String handleDocStoreGet(JsonNode args) throws Exception {
        String docId = args.get("docId").asText();
        int offset = args.has("offset") ? args.get("offset").asInt() : 0;
        int limit = args.has("limit") ? args.get("limit").asInt() : 200;

        // Try to find doc by ID in all type subdirectories
        Path docsDir = resolveDocsPath();
        Path docPath = null;
        if (Files.isDirectory(docsDir)) {
            try (var subdirs = Files.list(docsDir)) {
                for (Path sd : (Iterable<Path>) subdirs::iterator) {
                    Path candidate = sd.resolve(docId + ".md");
                    if (Files.exists(candidate)) { docPath = candidate; break; }
                }
            } catch (Exception ignored) {}
        }
        if (docPath == null)
            return toJson(Map.of("error", "Document not found: " + docId));

        String content = Files.readString(docPath);
        Map<String, String> fm = parseYamlFrontmatter(content);
        String body = extractMarkdownBody(content);

        String[] lines = body.split("\n");
        int totalLines = lines.length;
        int from = Math.min(offset, totalLines);
        int to = Math.min(from + limit, totalLines);
        String excerpt = String.join("\n", Arrays.copyOfRange(lines, from, to));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", docId);
        result.put("type", fm.getOrDefault("type", "other"));
        result.put("title", fm.getOrDefault("title", ""));
        result.put("tags", fm.getOrDefault("tags", ""));
        result.put("totalLines", totalLines);
        result.put("offset", from);
        result.put("limit", limit);
        result.put("content", excerpt);
        return toJson(result);
    }

    private String handleDocStoreCreate(JsonNode args) throws Exception {
        String docId = args.get("docId").asText();
        String type = args.has("type") ? args.get("type").asText() : "other";
        String title = args.has("title") ? args.get("title").asText() : docId;
        String content = args.has("content") ? args.get("content").asText() : "";
        String tags = args.has("tags") ? args.get("tags").asText() : "";

        Path docPath = resolveDocFilePath(type, docId);
        if (Files.exists(docPath))
            return toJson(Map.of("ok", false, "error", "Document already exists: " + docId));

        Files.createDirectories(docPath.getParent());
        Files.writeString(docPath, buildDocContent(type, title, tags, content));
        return toJson(Map.of("ok", true, "docId", docId, "type", type, "title", title));
    }

    // ── Agent Cache handlers ──────────────────────────────────

    private String handleAgentCacheList(JsonNode args) throws Exception {
        Path cachesDir = resolveCachesPath();
        String filterWorldId = args.has("worldId") ? args.get("worldId").asText() : null;
        String filterAgentType = args.has("agentType") ? args.get("agentType").asText() : null;

        List<Map<String, Object>> caches = new ArrayList<>();
        if (Files.isDirectory(cachesDir)) {
            try (var files = Files.list(cachesDir)) {
                for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                    if (!f.getFileName().toString().endsWith(".json")) continue;
                    try {
                        JsonNode cache = MAPPER.readTree(f.toFile());
                        String worldId = cache.has("worldId") ? cache.get("worldId").asText() : "";
                        String agentName = cache.has("agentName") ? cache.get("agentName").asText() : "";
                        if (filterWorldId != null && !filterWorldId.equals(worldId)) continue;
                        if (filterAgentType != null) {
                            String agentType = inferAgentType(agentName);
                            if (!filterAgentType.equals(agentType)) continue;
                        }
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("sessionId", cache.has("sessionId") ? cache.get("sessionId").asText() : f.getFileName().toString());
                        info.put("agentName", agentName);
                        info.put("agentType", inferAgentType(agentName));
                        info.put("worldId", worldId);
                        info.put("nodeId", cache.has("nodeId") ? cache.get("nodeId").asText() : "");
                        info.put("createdAt", cache.has("createdAt") ? cache.get("createdAt").asText() : "");
                        info.put("messageCount", cache.has("messages") ? cache.get("messages").size() : 0);
                        info.put("previousSessionId", cache.has("previousSessionId") && !cache.get("previousSessionId").isNull()
                            ? cache.get("previousSessionId").asText() : null);
                        caches.add(info);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return toJson(Map.of("caches", caches, "count", caches.size()));
    }

    private String handleAgentCacheGet(JsonNode args) throws Exception {
        String cacheId = args.get("cacheId").asText();
        boolean summaryOnly = args.has("summary") && args.get("summary").asBoolean();
        int offset = args.has("offset") ? args.get("offset").asInt() : 0;
        int limit = args.has("limit") ? args.get("limit").asInt() : 50;

        Path cacheFile = resolveCachesPath().resolve(cacheId);
        if (!Files.exists(cacheFile))
            return toJson(Map.of("error", "Cache not found: " + cacheId));

        JsonNode cache = MAPPER.readTree(cacheFile.toFile());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", cache.has("sessionId") ? cache.get("sessionId").asText() : cacheId);
        result.put("agentName", cache.has("agentName") ? cache.get("agentName").asText() : "");
        result.put("worldId", cache.has("worldId") ? cache.get("worldId").asText() : "");
        result.put("nodeId", cache.has("nodeId") ? cache.get("nodeId").asText() : "");
        result.put("createdAt", cache.has("createdAt") ? cache.get("createdAt").asText() : "");
        result.put("previousSessionId", cache.has("previousSessionId") && !cache.get("previousSessionId").isNull()
            ? cache.get("previousSessionId").asText() : null);

        if (summaryOnly) {
            result.put("messageCount", cache.has("messages") ? cache.get("messages").size() : 0);
        } else {
            JsonNode messages = cache.get("messages");
            if (messages != null && messages.isArray()) {
                int totalMsgs = messages.size();
                int from = Math.min(offset, totalMsgs);
                int to = Math.min(from + limit, totalMsgs);
                List<Object> page = new ArrayList<>();
                for (int i = from; i < to; i++) page.add(MAPPER.treeToValue(messages.get(i), Map.class));
                result.put("messages", page);
                result.put("offset", from);
                result.put("limit", limit);
                result.put("totalMessages", totalMsgs);
            }
        }
        return toJson(result);
    }

    private String handleAgentCacheCreate(JsonNode args) throws Exception {
        String worldId = args.get("worldId").asText();
        String agentName = args.has("agentName") ? args.get("agentName").asText() : "orchestrator";
        String nodeId = args.has("nodeId") ? args.get("nodeId").asText() : "n0000";
        String now = Instant.now().toString();
        String safeTime = now.replace(":", "-");
        if (safeTime.length() > 19) safeTime = safeTime.substring(0, 19);
        String sessionId = agentName + "_" + safeTime + ".json";

        // Build cache session JSON
        ObjectNode session = MAPPER.createObjectNode();
        session.put("agentName", agentName);
        session.put("worldId", worldId);
        session.put("nodeId", nodeId);
        session.put("sessionId", sessionId);
        session.put("createdAt", now);
        session.putNull("previousSessionId");
        session.putNull("compressionNote");

        // Inject system prompt from agent config if available
        ArrayNode messages = MAPPER.createArrayNode();
        try {
            AgentConfigStore store = getAgentConfigStore();
            AgentConfig cfg = store.get(agentName);
            if (cfg != null && cfg.staticSystemPrompt() != null && !cfg.staticSystemPrompt().isBlank()) {
                ObjectNode sysMsg = MAPPER.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", cfg.staticSystemPrompt());
                messages.add(sysMsg);
            }
        } catch (Exception ignored) {}
        session.set("messages", messages);

        Path cacheFile = resolveCachesPath().resolve(sessionId);
        Files.createDirectories(resolveCachesPath());
        if (Files.exists(cacheFile))
            return toJson(Map.of("ok", false, "error", "Cache already exists: " + sessionId));

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), session);
        return toJson(Map.of("ok", true, "sessionId", sessionId, "agentName", agentName,
            "worldId", worldId, "nodeId", nodeId, "messageCount", messages.size()));
    }

    private String handleAgentCacheDelete(JsonNode args) throws Exception {
        String cacheId = args.get("cacheId").asText();
        Path cacheFile = resolveCachesPath().resolve(cacheId);
        if (!Files.exists(cacheFile))
            return toJson(Map.of("ok", false, "error", "Cache not found: " + cacheId));

        Files.delete(cacheFile);
        return toJson(Map.of("ok", true, "cacheId", cacheId, "deleted", true));
    }

    private static String inferAgentType(String agentName) {
        if (agentName == null) return "unknown";
        String lower = agentName.toLowerCase();
        if (lower.startsWith("orchestrator")) return "orchestrator";
        if (lower.startsWith("sim")) return "sim";
        if (lower.startsWith("search")) return "search";
        int dash = agentName.indexOf('-');
        return dash > 0 ? agentName.substring(0, dash) : agentName;
    }

    // ── Skill handlers ────────────────────────────────────────

    private String handleSkillList(JsonNode args) throws Exception {
        Path skillDir = resolveDocsPath().resolve("skill");
        List<Map<String, Object>> skills = new ArrayList<>();
        if (Files.isDirectory(skillDir)) {
            try (var files = Files.list(skillDir)) {
                for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                    if (!f.getFileName().toString().endsWith(".md")) continue;
                    try {
                        String content = Files.readString(f);
                        Map<String, String> fm = parseYamlFrontmatter(content);
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("id", f.getFileName().toString().replace(".md", ""));
                        s.put("title", fm.getOrDefault("title", ""));
                        s.put("type", fm.getOrDefault("type", "skill"));
                        s.put("tags", fm.getOrDefault("tags", ""));
                        s.put("version", fm.getOrDefault("version", "1"));
                        String body = extractMarkdownBody(content);
                        s.put("summary", body.length() <= 200 ? body : body.substring(0, 197) + "...");
                        skills.add(s);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return toJson(Map.of("skills", skills, "count", skills.size()));
    }

    private String handleSkillGet(JsonNode args) throws Exception {
        String skillId = args.get("skillId").asText();
        int offset = args.has("offset") ? args.get("offset").asInt() : 0;
        int limit = args.has("limit") ? args.get("limit").asInt() : 200;

        Path skillFile = resolveDocsPath().resolve("skill").resolve(skillId + ".md");
        if (!Files.exists(skillFile))
            return toJson(Map.of("error", "Skill not found: " + skillId));

        String content = Files.readString(skillFile);
        Map<String, String> fm = parseYamlFrontmatter(content);
        String body = extractMarkdownBody(content);

        String[] lines = body.split("\n");
        int totalLines = lines.length;
        int from = Math.min(offset, totalLines);
        int to = Math.min(from + limit, totalLines);
        String excerpt = String.join("\n", Arrays.copyOfRange(lines, from, to));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", skillId);
        result.put("title", fm.getOrDefault("title", ""));
        result.put("type", fm.getOrDefault("type", "skill"));
        result.put("tags", fm.getOrDefault("tags", ""));
        result.put("totalLines", totalLines);
        result.put("offset", from);
        result.put("limit", limit);
        result.put("content", excerpt);
        return toJson(result);
    }

    private String handleSkillSearch(JsonNode args) throws Exception {
        String query = args.get("query").asText().toLowerCase();
        Path skillDir = resolveDocsPath().resolve("skill");
        List<Map<String, Object>> results = new ArrayList<>();
        if (Files.isDirectory(skillDir)) {
            try (var files = Files.list(skillDir)) {
                for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                    if (!f.getFileName().toString().endsWith(".md") || results.size() >= 20) continue;
                    try {
                        String content = Files.readString(f);
                        Map<String, String> fm = parseYamlFrontmatter(content);
                        String body = extractMarkdownBody(content);
                        String title = fm.getOrDefault("title", "");
                        if (!title.toLowerCase().contains(query) && !body.toLowerCase().contains(query)) continue;

                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("id", f.getFileName().toString().replace(".md", ""));
                        hit.put("title", title);
                        hit.put("tags", fm.getOrDefault("tags", ""));
                        int pos = Math.max(0, body.toLowerCase().indexOf(query) - 40);
                        String excerpt = (pos > 0 ? "…" : "")
                            + body.substring(pos, Math.min(pos + 300, body.length()))
                            + (pos + 300 < body.length() ? "…" : "");
                        hit.put("excerpt", excerpt);
                        results.add(hit);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return toJson(Map.of("query", query, "results", results, "count", results.size()));
    }

    // ── Text Cache handlers ───────────────────────────────────

    private String handleCacheList(JsonNode args) throws Exception {
        Path cacheDir = resolveTextCacheDir();
        List<Map<String, Object>> caches = new ArrayList<>();
        if (Files.isDirectory(cacheDir)) {
            try (var files = Files.list(cacheDir)) {
                for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                    if (!f.getFileName().toString().endsWith(".txt")) continue;
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", f.getFileName().toString());
                    try { c.put("size", Files.size(f)); } catch (Exception ignored) {}
                    caches.add(c);
                }
            } catch (Exception ignored) {}
        }
        return toJson(Map.of("caches", caches, "count", caches.size()));
    }

    private String handleCacheGet(JsonNode args) throws Exception {
        String cacheId = args.get("cacheId").asText();
        int offset = args.has("offset") ? args.get("offset").asInt() : 0;
        int limit = args.has("limit") ? args.get("limit").asInt() : 200;

        Path cacheFile = resolveTextCacheDir().resolve(cacheId);
        if (!Files.exists(cacheFile))
            return toJson(Map.of("error", "Text cache not found: " + cacheId));

        String content = Files.readString(cacheFile);
        String[] lines = content.split("\n");
        int totalLines = lines.length;
        int from = Math.min(offset, totalLines);
        int to = Math.min(from + limit, totalLines);
        String excerpt = String.join("\n", Arrays.copyOfRange(lines, from, to));

        return toJson(Map.of("cacheId", cacheId, "totalLines", totalLines,
            "offset", from, "limit", limit, "content", excerpt,
            "ref", "@cache:" + cacheId));
    }

    private String handleCacheEdit(JsonNode args) throws Exception {
        String cacheId = args.get("cacheId").asText();
        Path cacheFile = resolveTextCacheDir().resolve(cacheId);
        if (!Files.exists(cacheFile))
            return toJson(Map.of("error", "Text cache not found: " + cacheId));

        String content = Files.readString(cacheFile);
        boolean modified = false;

        // Keyword replace
        if (args.has("replace_from") && args.has("replace_to")) {
            String from = args.get("replace_from").asText();
            String to = args.get("replace_to").asText();
            content = content.replace(from, to);
            modified = true;
        }

        // Append text
        if (args.has("insert_text")) {
            content += "\n" + args.get("insert_text").asText();
            modified = true;
        }

        if (!modified)
            return toJson(Map.of("ok", true, "cacheId", cacheId, "message", "No changes"));

        // Save as new cache
        String newId = "edited_" + System.currentTimeMillis() + "_" + cacheId;
        // Ensure ends with .txt
        if (!newId.endsWith(".txt")) newId += ".txt";
        Path newFile = resolveTextCacheDir().resolve(newId);
        Files.createDirectories(resolveTextCacheDir());
        Files.writeString(newFile, content);

        return toJson(Map.of("ok", true, "sourceCacheId", cacheId,
            "newCacheId", newId, "ref", "@cache:" + newId));
    }

    // ── Status handler ────────────────────────────────────────

    private String handleGetStatus(JsonNode args) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("server", "GSimulator-MCP");
        status.put("version", "0.1.0");
        status.put("worldsDir", worldsDir.toString());
        status.put("importDir", importDir.toString());
        status.put("dataDir", resolveDataDir().toString());
        status.put("httpBaseUrl", httpBaseUrl);
        int worldCount = 0;
        try {
            java.io.File[] dirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
            worldCount = dirs != null ? dirs.length : 0;
        } catch (Exception ignored) {}
        status.put("worldCount", worldCount);
        status.put("toolCount", tools.size());
        return toJson(status);
    }

    // ── HTTP helpers (LLM/Agent/Config tools) ───────────────

    private String httpGet(String path, JsonNode args) {
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(httpBaseUrl + path))
                .GET().build();
            var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return toJson(Map.of("status", resp.statusCode(), "data",
                resp.statusCode() == 200 ? MAPPER.readTree(resp.body()) : resp.body()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    private String httpPost(String path, JsonNode args) {
        try {
            // Extract body from "body" field, or use all args minus special fields
            JsonNode body = args.has("body") ? args.get("body") : args;
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(httpBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return toJson(Map.of("status", resp.statusCode(), "data",
                resp.statusCode() < 400 ? MAPPER.readTree(resp.body()) : resp.body()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    private String httpPatch(String path, JsonNode args) {
        try {
            JsonNode body = args.has("body") ? args.get("body") : args;
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(httpBaseUrl + path))
                .header("Content-Type", "application/json")
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return toJson(Map.of("status", resp.statusCode(), "data",
                resp.statusCode() < 400 ? MAPPER.readTree(resp.body()) : resp.body()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    private String httpDelete(String path, JsonNode args) {
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(httpBaseUrl + path))
                .DELETE().build();
            var resp = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return toJson(Map.of("status", resp.statusCode(), "data", resp.body()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    private String httpPostBody(String path, JsonNode body) {
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(httpBaseUrl + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            return HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
