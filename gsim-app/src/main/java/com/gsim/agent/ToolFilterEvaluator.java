package com.gsim.agent;

import com.gsim.agent.ToolCategory;
import com.gsim.agent.ToolCategoryRegistry;

public class ToolFilterEvaluator {

    public static boolean allows(ToolFilterConfig config, String toolName) {
        return switch (config.mode()) {
            case "all" -> true;
            case "read_only" -> ToolCategoryRegistry.isReadOnly(toolName)
                    || ToolCategoryRegistry.isControl(toolName);
            case "none" -> "finish_action".equals(toolName);
            case "custom" -> {
                if (config.deny().contains(toolName)) yield false;
                if (config.allow().isEmpty()) yield true;
                yield config.allow().contains(toolName);
            }
            default -> false;
        };
    }
}
