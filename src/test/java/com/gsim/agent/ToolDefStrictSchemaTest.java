package com.gsim.agent;

import com.gsim.agent.tool.ConsolePrintTool;
import com.gsim.agent.tool.FinishActionTool;
import com.gsim.llm.ToolDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ToolDef 参数 schema 的生成。
 * 覆盖 FinishActionTool 和 ConsolePrintTool 的 getParameters()。
 */
@DisplayName("ToolDef 严格 schema 测试")
class ToolDefStrictSchemaTest {

    // ===== Test 6: FinishActionToolDefStrictSchemaTest =====

    @Test
    @DisplayName("FinishActionTool.getParameters() 返回严格 schema，required 含 message，additionalProperties=false")
    void finishActionToolDefStrictSchema() {
        FinishActionTool tool = new FinishActionTool();
        Map<String, Object> params = tool.getParameters();

        assertNotNull(params, "getParameters() 不应返回 null");
        assertEquals("object", params.get("type"),
                "top-level type 应为 object");
        assertEquals(false, params.get("additionalProperties"),
                "additionalProperties 应为 false");

        @SuppressWarnings("unchecked")
        java.util.List<String> required = (java.util.List<String>) params.get("required");
        assertNotNull(required, "required 数组不应为 null");
        assertTrue(required.contains("message"),
                "required 应包含 message，实际: " + required);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties =
                (Map<String, Map<String, Object>>) params.get("properties");
        assertNotNull(properties, "properties 不应为 null");
        assertTrue(properties.containsKey("message"),
                "properties 应包含 message key");

        Map<String, Object> messageProp = properties.get("message");
        assertEquals("string", messageProp.get("type"),
                "message property type 应为 string");
    }

    // ===== Test 7: ConsolePrintToolDefSchemaTest =====

    @Test
    @DisplayName("ConsolePrintTool.getParameters() 返回严格 schema，required 含 message，additionalProperties=false")
    void consolePrintToolDefSchema() {
        ConsolePrintTool tool = new ConsolePrintTool(null);
        Map<String, Object> params = tool.getParameters();

        assertNotNull(params, "getParameters() 不应返回 null");
        assertEquals("object", params.get("type"),
                "top-level type 应为 object");
        assertEquals(false, params.get("additionalProperties"),
                "additionalProperties 应为 false");

        @SuppressWarnings("unchecked")
        java.util.List<String> required = (java.util.List<String>) params.get("required");
        assertNotNull(required, "required 数组不应为 null");
        assertTrue(required.contains("message"),
                "required 应包含 message，实际: " + required);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> properties =
                (Map<String, Map<String, Object>>) params.get("properties");
        assertNotNull(properties, "properties 不应为 null");
        assertTrue(properties.containsKey("message"),
                "properties 应包含 message key");
    }

    // ===== 额外：验证 ToolDef 包装后 schema 不丢失 =====

    @Test
    @DisplayName("ToolDef 从 AgentTool.getParameters() 构造后参数 schema 保留")
    void toolDefPreservesSchemaFromAgentTool() {
        FinishActionTool tool = new FinishActionTool();
        ToolDef td = new ToolDef(tool.name(), tool.description(), tool.getParameters());

        assertEquals("finish_action", td.name());
        assertNotNull(td.parameters(), "ToolDef.parameters() 不应为 null");
        assertEquals(false, td.parameters().get("additionalProperties"),
                "ToolDef 应保留 additionalProperties=false");
    }

    @Test
    @DisplayName("不带 parameters 的 ToolDef 使用 defaultOpenSchema")
    void toolDefWithoutParametersUsesDefaultOpenSchema() {
        ToolDef td = new ToolDef("echo", "Echo tool");
        assertNotNull(td.parameters(), "默认 schema 不应为 null");
        assertEquals("object", td.parameters().get("type"));
        assertEquals(true, td.parameters().get("additionalProperties"),
                "默认 open schema 的 additionalProperties 应为 true");
    }
}
