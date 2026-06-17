package com.gsim.resource;

import java.io.IOException;
import java.util.Map;

/**
 * 模板资源管理器 — 从 classpath gsim/templates/ 渲染 Markdown 模板。
 */
public final class TemplateResourceManager {

    private TemplateResourceManager() {}

    public static String renderBranchTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/branch-template.md", vars);
    }

    public static String renderWorldTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/world-template.md", vars);
    }

    public static String renderEntitiesTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/entities-template.md", vars);
    }

    public static String renderRulesTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/rules-template.md", vars);
    }

    public static String renderInputTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/input-template.md", vars);
    }

    public static String renderSkillSystemTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/skill-system-template.md", vars);
    }

    /** 读取原始模板内容（不替换变量）。 */
    public static String readRaw(String name) throws IOException {
        return ResourceManager.readText("gsim/templates/" + name);
    }
}
