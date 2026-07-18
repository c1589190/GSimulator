package com.gsim.resource;

import java.io.IOException;
import java.util.Map;

/**
 * 模板资源管理器 — 从 classpath gsim/templates/ 渲染 Markdown 模板。
 */
public final class TemplateResourceManager {

    private TemplateResourceManager() {}

    /**
     * 渲染分支模板（branch-template.md）。
     *
     * @param vars 模板变量
     * @return 渲染后的 Markdown 文本
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String renderBranchTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/branch-template.md", vars);
    }

    /**
     * 渲染世界观模板（world-template.md）。
     *
     * @param vars 模板变量
     * @return 渲染后的 Markdown 文本
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String renderWorldTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/world-template.md", vars);
    }

    /**
     * 渲染实体模板（entities-template.md）。
     *
     * @param vars 模板变量
     * @return 渲染后的 Markdown 文本
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String renderEntitiesTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/entities-template.md", vars);
    }

    /**
     * 渲染规则模板（rules-template.md）。
     *
     * @param vars 模板变量
     * @return 渲染后的 Markdown 文本
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String renderRulesTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/rules-template.md", vars);
    }

    /**
     * 渲染输入模板（input-template.md）。
     *
     * @param vars 模板变量
     * @return 渲染后的 Markdown 文本
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String renderInputTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/input-template.md", vars);
    }

    /**
     * 渲染技能系统模板（skill-system-template.md）。
     *
     * @param vars 模板变量
     * @return 渲染后的 Markdown 文本
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String renderSkillSystemTemplate(Map<String, String> vars) throws IOException {
        return ResourceManager.renderTemplate("gsim/templates/skill-system-template.md", vars);
    }

    /**
     * 读取原始模板内容（不替换变量）。
     *
     * @param name 模板文件名（如 {@code "branch-template.md"}）
     * @return 模板原始内容
     * @throws IOException 如果模板文件未找到或读取失败
     */
    public static String readRaw(String name) throws IOException {
        return ResourceManager.readText("gsim/templates/" + name);
    }
}
