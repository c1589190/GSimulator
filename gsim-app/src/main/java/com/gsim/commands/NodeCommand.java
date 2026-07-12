package com.gsim.commands;

import com.gsim.util.IdGenerator;
import com.gsim.worldinfo.*;
import com.gsim.worldinfo.loader.ActiveStateManager;
import com.gsim.worldinfo.loader.NodeLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * /node — node management commands.
 *   /node status                   — show current node info
 *   /node list                     — list nodes in chain
 *   /node goto <nodeId>            — switch to a different node in chain
 *   /node create <worldTime>       — create child node (next turn)
 */
public final class NodeCommand {

    private static final Pattern NODE_FILE_PATTERN = Pattern.compile("n(\\d{4})\\.json$");

    private final Path worldsDir;
    private final Supplier<WorldInformation> worldInfo;
    private final Runnable onNodeChanged;

    public NodeCommand(Path worldsDir, Supplier<WorldInformation> worldInfo, Runnable onNodeChanged) {
        this.worldsDir = worldsDir;
        this.worldInfo = worldInfo;
        this.onNodeChanged = onNodeChanged;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) return "Usage: /node [status|list|goto|create] ...";
        return switch (args.get(0)) {
            case "status" -> nodeStatus();
            case "list" -> nodeList();
            case "goto" -> gotoNode(args);
            case "create" -> createChild(args);
            default -> "Unknown subcommand: " + args.get(0);
        };
    }

    private String nodeStatus() {
        WorldInformation wi = worldInfo.get();
        NodeSnapshot active = wi.activeNode();
        return """
            Node: %s (turn %d)
            World time: %s
            Status: %s
            Parent: %s
            Chain length: %d
            Checkpoints: %s
            """.formatted(active.nodeId(), active.turn(), active.worldTime(),
                active.status(), active.parentId(),
                wi.branchChain().size(), wi.allCheckpointIds());
    }

    private String nodeList() {
        WorldInformation wi = worldInfo.get();
        StringBuilder sb = new StringBuilder("Branch chain:\n");
        for (NodeSnapshot n : wi.branchChain()) {
            String marker = n.nodeId().equals(wi.activeNodeId()) ? " ← active" : "";
            sb.append("  %s [turn %d] %s%s\n".formatted(n.nodeId(), n.turn(), n.worldTime(), marker));
        }
        return sb.toString();
    }

    private String gotoNode(List<String> args) {
        if (args.size() < 2) return "Usage: /node goto <nodeId>";
        String nodeId = args.get(1);
        WorldInformation wi = worldInfo.get();
        if (wi.nodeById(nodeId) == null) {
            return "Node not found in chain: " + nodeId;
        }
        // update active
        String worldId = wi.worldId();
        ActiveStateManager.save(worldsDir, worldId,
            new ActiveStateManager.ActiveState(nodeId, Map.of()));
        onNodeChanged.run();
        return "Switched to node: " + nodeId + ". Reloading...";
    }

    private String createChild(List<String> args) {
        if (args.size() < 2) return "Usage: /node create <worldTime>";
        String worldTime = args.get(1);
        WorldInformation wi = worldInfo.get();
        String parentId = wi.activeNodeId();
        int nextTurn = wi.activeNode().turn() + 1;

        // Seed counter from existing nodes before generating new ID
        seedNodeCounterFromDisk(worldsDir, wi.worldId());

        String newNodeId = IdGenerator.nodeId();

        NodeSnapshot child = new NodeSnapshot(newNodeId, parentId, nextTurn,
            worldTime, "active", Instant.now().toString(),
            new LinkedHashMap<>());

        NodeLoader.save(NodeLoader.nodeFile(worldsDir, wi.worldId(), newNodeId), child);

        ActiveStateManager.save(worldsDir, wi.worldId(),
            new ActiveStateManager.ActiveState(newNodeId, Map.of()));
        onNodeChanged.run();
        return "Created child node: " + newNodeId + " (turn " + nextTurn + ")";
    }

    /** Scan nodes/ directory, find max nXXXX, set counter to max+1. */
    private static void seedNodeCounterFromDisk(Path worldsDir, String worldId) {
        Path nodesDir = NodeLoader.nodesDir(worldsDir, worldId);
        if (!Files.isDirectory(nodesDir)) return;

        int max = -1;
        try (Stream<Path> files = Files.list(nodesDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Matcher m = NODE_FILE_PATTERN.matcher(p.getFileName().toString());
                if (m.find()) {
                    int num = Integer.parseInt(m.group(1));
                    if (num > max) max = num;
                }
            }
        } catch (Exception e) {
            System.err.println("[NodeCommand] Failed to scan node directory for counter seeding: " + e.getMessage());
        }
        if (max >= 0) {
            IdGenerator.seedNodeCounter(max + 1);
        }
    }
}
