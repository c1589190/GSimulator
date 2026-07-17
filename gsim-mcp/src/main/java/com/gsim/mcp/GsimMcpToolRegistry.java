package com.gsim.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            case "gsim_list_checkpoints"          -> handleListCheckpoints(args);
            case "gsim_get_checkpoint"            -> handleGetCheckpoint(args);
            case "gsim_add_checkpoint_element"    -> handleAddCheckpointElement(args);
            case "gsim_update_checkpoint_element" -> handleUpdateCheckpointElement(args);
            case "gsim_delete_checkpoint_element" -> handleDeleteCheckpointElement(args);
            case "gsim_search"                    -> handleSearch(args);
            case "gsim_resolve_ref"               -> handleResolveRef(args);
            case "gsim_list_docs"                 -> handleListDocs(args);
            case "gsim_get_doc"                   -> handleGetDoc(args);
            case "gsim_llm_list"                  -> httpGet("/api/llm", args);
            case "gsim_llm_get"                   -> httpGet("/api/llm/" + args.get("id").asText(), args);
            case "gsim_llm_add"                   -> httpPost("/api/llm", args);
            case "gsim_llm_update"               -> httpPatch("/api/llm/" + args.get("id").asText(), args);
            case "gsim_llm_delete"               -> httpDelete("/api/llm/" + args.get("id").asText(), args);
            case "gsim_llm_test"                 -> httpPost("/api/llm/" + args.get("id").asText() + "/test", args);
            case "gsim_agent_list"               -> httpGet("/api/agents", args);
            case "gsim_agent_get"                -> httpGet("/api/agents/" + args.get("instanceId").asText(), args);
            case "gsim_agent_run"                -> httpPost("/api/agents/run", args);
            case "gsim_agent_cancel"             -> httpPost("/api/agents/" + args.get("instanceId").asText() + "/cancel", args);
            case "gsim_agent_output"             -> httpGet("/api/agents/" + args.get("instanceId").asText() + "/output", args);
            case "gsim_agent_config_list"        -> httpGet("/api/agent-configs", args);
            case "gsim_agent_config_get"         -> httpGet("/api/agent-configs/" + args.get("configId").asText(), args);
            case "gsim_agent_config_create"      -> httpPost("/api/agent-configs", args);
            case "gsim_agent_config_update"      -> httpPatch("/api/agent-configs/" + args.get("configId").asText(), args);
            case "gsim_agent_config_delete"      -> httpDelete("/api/agent-configs/" + args.get("configId").asText(), args);
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
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (e.g. n0000). Defaults to active node."}
            },"required":["worldId"]}""");

        register("gsim_list_checkpoints",
            "List all checkpoints in a GSim node with element counts.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string","description":"Node ID (optional, defaults to active node)"}
            },"required":["worldId"]}""");

        register("gsim_get_checkpoint",
            "Get elements from a GSim checkpoint. Filter by key or tags (all must match).",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name: narrative, factions, worldview, characters, map"},
              "key":{"type":"string","description":"Filter by specific element key (optional)"},
              "tags":{"type":"array","items":{"type":"string"},"description":"Filter by tags — element must have ALL specified tags (optional)"},
              "limit":{"type":"integer","description":"Max elements to return (default 50)"}
            },"required":["worldId","checkpoint"]}""");

        register("gsim_add_checkpoint_element",
            "Add a new element to a GSim checkpoint.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Unique element key"},
              "value":{"type":"string","description":"Full text content"},
              "type":{"type":"string","description":"Element type: text, character_state, map-region, map-city (default: text)"},
              "tags":{"type":"array","items":{"type":"string"},"description":"Tags for categorization"}
            },"required":["worldId","checkpoint","key","value"]}""");

        register("gsim_update_checkpoint_element",
            "Update an existing element in a GSim checkpoint. Only provided fields are changed.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to update"},
              "value":{"type":"string","description":"New text content (optional)"},
              "type":{"type":"string","description":"New element type (optional)"},
              "tags":{"type":"array","items":{"type":"string"},"description":"New tags list (optional, replaces all existing tags)"}
            },"required":["worldId","checkpoint","key"]}""");

        register("gsim_delete_checkpoint_element",
            "Delete an element from a GSim checkpoint by key.",
            """
            {"type":"object","properties":{
              "worldId":{"type":"string"},
              "nodeId":{"type":"string"},
              "checkpoint":{"type":"string","description":"Checkpoint name"},
              "key":{"type":"string","description":"Element key to delete"}
            },"required":["worldId","checkpoint","key"]}""");

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
            "Resolve a GSim @ reference (e.g. '@world:n0002:characters:曹操') to full element content. Supports 3-segment (world:node:checkpoint:key), 2-segment (world:checkpoint:key using active node), and @doc:id references.",
            """
            {"type":"object","properties":{
              "ref":{"type":"string","description":"Reference string: @world:nodeId:checkpoint:key, @world:checkpoint:key, or @doc:docId"},
              "worldId":{"type":"string","description":"World ID (required for @world refs without explicit world prefix)"}
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
            "Launch a new agent with given input. Returns instanceId for tracking.",
            """
            {"type":"object","properties":{
              "sessionId":{"type":"string","description":"Session ID (default: 'default')"},
              "input":{"type":"string","description":"Agent prompt/input text"},
              "agentConfig":{"type":"string","description":"Agent config ID to use (optional)"},
              "model":{"type":"string","description":"Override model (optional)"}
            },"required":["sessionId","input"]}""");

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
            "Create a new agent configuration.",
            """
            {"type":"object","properties":{
              "configId":{"type":"string","description":"Unique config ID"},
              "name":{"type":"string","description":"Display name (optional)"},
              "persona":{"type":"string","description":"Agent persona/system prompt"},
              "model":{"type":"string","description":"Preferred model (optional)"},
              "temperature":{"type":"number","description":"LLM temperature (default: 0.7)"},
              "maxTokens":{"type":"integer","description":"Max tokens per response"},
              "toolGroups":{"type":"array","items":{"type":"string"},"description":"Active tool group keys"}
            },"required":["configId","persona"]}""");

        register("gsim_agent_config_update",
            "Update fields of an existing agent configuration.",
            """
            {"type":"object","properties":{
              "configId":{"type":"string","description":"Config ID to update"},
              "name":{"type":"string","description":"New name (optional)"},
              "persona":{"type":"string","description":"New persona (optional)"},
              "model":{"type":"string","description":"New model (optional)"},
              "temperature":{"type":"number","description":"New temperature (optional)"},
              "maxTokens":{"type":"integer","description":"New max tokens (optional)"},
              "toolGroups":{"type":"array","items":{"type":"string"},"description":"New tool groups (optional)"}
            },"required":["configId"]}""");

        register("gsim_agent_config_delete",
            "Delete an agent configuration by ID.",
            """
            {"type":"object","properties":{
              "configId":{"type":"string","description":"Config ID to delete"}
            },"required":["configId"]}""");
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

    private String resolveNodeId(String worldId, String providedNodeId) {
        if (providedNodeId != null && !providedNodeId.isBlank()) return providedNodeId;
        Path activeFile = worldsDir.resolve(worldId).resolve("active.json");
        if (Files.exists(activeFile)) {
            try {
                JsonNode n = MAPPER.readTree(activeFile.toFile());
                if (n.has("nodeId") && !n.get("nodeId").isNull()) return n.get("nodeId").asText();
            } catch (Exception ignored) {}
        }
        return "n0000";
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

        // Active node
        Path activeFile = w.resolve("active.json");
        String activeNode = "n0000";
        if (Files.exists(activeFile)) {
            JsonNode an = MAPPER.readTree(activeFile.toFile());
            if (an.has("nodeId")) activeNode = an.get("nodeId").asText();
        }
        result.put("activeNode", activeNode);

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
        String nodeId = resolveNodeId(worldId, args.has("nodeId") ? args.get("nodeId").asText() : null);
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
        String nodeId = resolveNodeId(worldId, args.has("nodeId") ? args.get("nodeId").asText() : null);
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
        String nodeId = resolveNodeId(worldId, args.has("nodeId") ? args.get("nodeId").asText() : null);
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
        String nodeId = resolveNodeId(worldId, args.has("nodeId") ? args.get("nodeId").asText() : null);
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
        String nodeId = resolveNodeId(worldId, args.has("nodeId") ? args.get("nodeId").asText() : null);
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
        String nodeId = resolveNodeId(worldId, args.has("nodeId") ? args.get("nodeId").asText() : null);
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
        String defaultWorld = args.has("worldId") ? args.get("worldId").asText() : null;

        // Parse: @world:n0002:characters:曹操 → ["world", "n0002", "characters", "曹操"]
        // Parse: @world:characters:曹操 → ["world", "characters", "曹操"] (2-segment, uses active node)
        // Parse: @doc:some_doc → ["doc", "some_doc"]
        if (!ref.startsWith("@")) return toJson(Map.of("error", "Invalid ref: must start with @", "ref", ref));

        String[] parts = ref.substring(1).split(":", -1);
        if (parts.length < 2) return toJson(Map.of("error", "Invalid ref: at least @type:id required", "ref", ref));

        return switch (parts[0]) {
            case "world" -> resolveWorldRef(parts, defaultWorld);
            case "doc" -> resolveDocRef(parts[1]);
            default -> toJson(Map.of("error", "Unknown ref type: " + parts[0], "ref", ref));
        };
    }

    private String resolveWorldRef(String[] parts, String defaultWorld) throws Exception {
        String worldId, nodeId, checkpointName, key;

        if (parts.length >= 5) {
            // @world:worldId:nodeId:checkpoint:key (5 segments)
            worldId = parts[1];
            nodeId = parts[2];
            checkpointName = parts[3];
            key = parts[4];
        } else if (parts.length == 4) {
            // @world:worldId:checkpoint:key (4 segments, node defaults to active)
            worldId = parts[1];
            nodeId = resolveNodeId(worldId, null);
            checkpointName = parts[2];
            key = parts[3];
        } else if (parts.length == 3) {
            // @world:checkpoint:key (3 segments, use default world + active node)
            worldId = defaultWorld != null ? defaultWorld : findFirstWorld();
            if (worldId == null) return toJson(Map.of("error", "No world available", "ref", "@" + String.join(":", parts)));
            nodeId = resolveNodeId(worldId, null);
            checkpointName = parts[1];
            key = parts[2];
        } else {
            return toJson(Map.of("error", "Invalid @world ref: need 3-5 segments (world[:nodeId]:checkpoint:key)", "ref", "@" + String.join(":", parts)));
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

    private String findFirstWorld() {
        java.io.File[] dirs = worldsDir.toFile().listFiles(java.io.File::isDirectory);
        if (dirs == null || dirs.length == 0) return null;
        for (java.io.File d : dirs) {
            if (Files.exists(d.toPath().resolve("world.json"))) return d.getName();
        }
        return dirs[0].getName();
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
}
