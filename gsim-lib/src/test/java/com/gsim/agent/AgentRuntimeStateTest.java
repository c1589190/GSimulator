package com.gsim.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AgentRuntimeState 和 ToolGroupEvent 单元测试。
 * 验证结构化事件的写入、Cache 重放、以及多 Agent 隔离。
 */
@DisplayName("AgentRuntimeState 测试")
class AgentRuntimeStateTest {

    @Test
    @DisplayName("empty 返回空状态")
    void emptyReturnsBlankState() {
        AgentRuntimeState state = AgentRuntimeState.empty();
        assertTrue(state.activeToolGroups().isEmpty());
        assertNull(state.boundWorldId());
        assertNull(state.boundNodeId());
    }

    @Test
    @DisplayName("replay 空事件列表返回空状态")
    void replayEmptyEventsReturnsEmpty() {
        AgentRuntimeState state = AgentRuntimeState.replay(List.of());
        assertTrue(state.activeToolGroups().isEmpty());
    }

    @Test
    @DisplayName("replay null 返回空状态")
    void replayNullReturnsEmpty() {
        AgentRuntimeState state = AgentRuntimeState.replay(null);
        assertTrue(state.activeToolGroups().isEmpty());
    }

    @Test
    @DisplayName("replay 单个 activated 事件恢复激活组")
    void replaySingleActivated() {
        List<ToolGroupEvent> events = List.of(
                ToolGroupEvent.activated(Set.of("world_info")));
        AgentRuntimeState state = AgentRuntimeState.replay(events);
        assertEquals(Set.of("world_info"), state.activeToolGroups());
    }

    @Test
    @DisplayName("replay activated + deactivated 正确计算最终状态")
    void replayActivateAndDeactivate() {
        List<ToolGroupEvent> events = List.of(
                ToolGroupEvent.activated(Set.of("world_info", "node_mgmt")),
                ToolGroupEvent.deactivated(Set.of("node_mgmt")));
        AgentRuntimeState state = AgentRuntimeState.replay(events);
        assertEquals(Set.of("world_info"), state.activeToolGroups());
        assertFalse(state.activeToolGroups().contains("node_mgmt"));
    }

    @Test
    @DisplayName("replay 多个事件按顺序重放")
    void replayMultipleEventsInOrder() {
        List<ToolGroupEvent> events = List.of(
                ToolGroupEvent.activated(Set.of("world_info")),
                ToolGroupEvent.activated(Set.of("import_doc", "search")),
                ToolGroupEvent.deactivated(Set.of("world_info")));
        AgentRuntimeState state = AgentRuntimeState.replay(events);
        assertEquals(Set.of("import_doc", "search"), state.activeToolGroups());
    }

    @Test
    @DisplayName("withActivatedGroups 返回新实例（不可变）")
    void withActivatedGroupsReturnsNewInstance() {
        AgentRuntimeState original = AgentRuntimeState.empty();
        AgentRuntimeState updated = original.withActivatedGroups(Set.of("world_info"));

        assertTrue(original.activeToolGroups().isEmpty());
        assertEquals(Set.of("world_info"), updated.activeToolGroups());
        assertNotSame(original, updated);
    }

    @Test
    @DisplayName("withDeactivatedGroups 返回新实例（不可变）")
    void withDeactivatedGroupsReturnsNewInstance() {
        AgentRuntimeState original = AgentRuntimeState.empty()
                .withActivatedGroups(Set.of("world_info", "node_mgmt"));
        AgentRuntimeState updated = original.withDeactivatedGroups(Set.of("node_mgmt"));

        assertEquals(Set.of("world_info", "node_mgmt"), original.activeToolGroups());
        assertEquals(Set.of("world_info"), updated.activeToolGroups());
    }

    @Test
    @DisplayName("不同 Agent 的状态完全隔离")
    void statesAreIsolatedBetweenAgents() {
        AgentRuntimeState agentA = AgentRuntimeState.empty()
                .withActivatedGroups(Set.of("world_info"));
        AgentRuntimeState agentB = AgentRuntimeState.empty()
                .withActivatedGroups(Set.of("search"));

        assertEquals(Set.of("world_info"), agentA.activeToolGroups());
        assertEquals(Set.of("search"), agentB.activeToolGroups());
    }

    @Test
    @DisplayName("ToolGroupEvent.activated 工厂方法")
    void toolGroupEventActivatedFactory() {
        ToolGroupEvent event = ToolGroupEvent.activated(Set.of("world_info", "import_doc"));
        assertEquals("activated", event.type());
        assertEquals(Set.of("world_info", "import_doc"), event.groups());
        assertTrue(event.timestamp() > 0);
    }

    @Test
    @DisplayName("ToolGroupEvent.deactivated 工厂方法")
    void toolGroupEventDeactivatedFactory() {
        ToolGroupEvent event = ToolGroupEvent.deactivated(Set.of("node_mgmt"));
        assertEquals("deactivated", event.type());
        assertEquals(Set.of("node_mgmt"), event.groups());
    }
}
