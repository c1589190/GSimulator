package com.gsim.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ToolGroupEvent 工厂方法单元测试。
 */
@DisplayName("ToolGroupEvent 测试")
class ToolGroupEventTest {

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
