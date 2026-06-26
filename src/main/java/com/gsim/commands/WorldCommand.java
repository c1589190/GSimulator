package com.gsim.commands;

import com.gsim.worldinfo.loader.WorldIndexManager;

import java.nio.file.Path;
import java.util.List;

/**
 * /world — world management commands.
 *   /world list                    — list all worlds
 *   /world create <id> <name>      — create new world
 *   /world switch <id>             — switch active world (reload)
 */
public final class WorldCommand {

    private final Path worldsDir;
    private final Runnable onWorldChanged; // callback to re-bootstrap

    public WorldCommand(Path worldsDir, Runnable onWorldChanged) {
        this.worldsDir = worldsDir;
        this.onWorldChanged = onWorldChanged;
    }

    public String execute(List<String> args) {
        if (args.isEmpty()) {
            return "Usage: /world [list|create|switch] ...";
        }
        return switch (args.get(0)) {
            case "list" -> listWorlds();
            case "create" -> createWorld(args);
            case "switch" -> switchWorld(args);
            default -> "Unknown subcommand: " + args.get(0);
        };
    }

    private String listWorlds() {
        List<WorldIndexManager.WorldEntry> worlds = WorldIndexManager.listWorlds(worldsDir);
        if (worlds.isEmpty()) return "No worlds found.";
        StringBuilder sb = new StringBuilder("Worlds:\n");
        for (var w : worlds) {
            sb.append("  - ").append(w.id()).append(" (").append(w.name()).append(")\n");
        }
        return sb.toString();
    }

    private String createWorld(List<String> args) {
        if (args.size() < 3) return "Usage: /world create <id> <name>";
        String id = args.get(1);
        String name = args.get(2);
        WorldIndexManager.createWorld(worldsDir, id, name);
        return "World created: " + id + " (" + name + ")";
    }

    private String switchWorld(List<String> args) {
        if (args.size() < 2) return "Usage: /world switch <id>";
        String id = args.get(1);
        // validate world exists
        if (WorldIndexManager.loadWorldMeta(worldsDir, id) == null) {
            return "World not found: " + id;
        }
        // trigger re-bootstrap
        onWorldChanged.run();
        return "Switched to world: " + id + ". Reloading...";
    }
}
