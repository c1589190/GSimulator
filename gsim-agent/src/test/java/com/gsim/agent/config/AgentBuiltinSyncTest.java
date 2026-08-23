package com.gsim.agent.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内置 Agent 配置治理测试 — 根目录 {@code agents/*.json}（运行时编辑层）
 * 必须与 classpath 模板 {@code gsim/agents/&ast;/config.json}（唯一源）JSON 语义相等。
 *
 * <p>AgentConfigStore 文件系统优先（reload 先扫 agentsDir），陈旧副本会实际生效；
 * 本测试防止文件系统副本与 classpath 模板再次漂移。
 */
@DisplayName("Agent 内置配置：文件系统副本与 classpath 模板同步")
class AgentBuiltinSyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> BUILTIN_AGENTS = List.of("orchestrator", "sim", "search");

    @Test
    @DisplayName("orchestrator/sim/search 的文件系统 agents/*.json 与 classpath 模板语义相等")
    void filesystemCopyMatchesClasspathTemplate() throws Exception {
        for (String agentId : BUILTIN_AGENTS) {
            Path fsFile = locateAgentsDir().resolve(agentId + ".json");
            assertTrue(Files.isRegularFile(fsFile), "根 agents/ 下应存在 " + agentId + ".json: " + fsFile);

            JsonNode fsNode = MAPPER.readTree(Files.readString(fsFile));
            JsonNode cpNode = readClasspathTemplate(agentId);

            assertNotNull(cpNode, "classpath 模板缺失: gsim/agents/" + agentId + "/config.json");
            assertEquals(
                    cpNode,
                    fsNode,
                    agentId + ": agents/" + agentId + ".json 与 classpath 模板必须 JSON 语义相等" + "（类路径是唯一源，运行时可编辑副本不得漂移）");
        }
    }

    /** 从当前 CWD 向上查找仓库根 agents/ 目录（surefire CWD = 模块目录）。 */
    private static Path locateAgentsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = dir.resolve("agents");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
            if (dir == null) break;
        }
        throw new AssertionError("未找到仓库根 agents/ 目录（从 CWD 向上最多 5 层）");
    }

    private static JsonNode readClasspathTemplate(String agentId) throws Exception {
        String path = "gsim/agents/" + agentId + "/config.json";
        try (InputStream is = AgentBuiltinSyncTest.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return MAPPER.readTree(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
