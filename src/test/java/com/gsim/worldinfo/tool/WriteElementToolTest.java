package com.gsim.worldinfo.tool;

import com.gsim.tool.ToolCall;
import com.gsim.tool.ToolResult;
import com.gsim.worldinfo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteElementToolTest {

    @TempDir
    Path tmpDir;

    private WorldInformation wi;

    @BeforeEach
    void setUp() {
        NodeSnapshot n0 = new NodeSnapshot("n0000", null, 0, "origin", "initial", "t0",
            new LinkedHashMap<>(Map.of("worldview", new Checkpoint("世界观", "worldview", new ArrayList<>()))));
        wi = new WorldInformation("test-world", List.of(n0));
    }

    @Test
    void writeElementAppendsToCheckpoint() {
        var tool = new WriteElementTool(() -> wi, tmpDir);
        ToolResult r = tool.execute(new ToolCall("write_element", Map.of(
            "nodeId", "n0000",
            "checkpointId", "worldview",
            "key", "气候.中原",
            "value", "中原大旱蝗灾四起",
            "tags", "气候,灾害"
        )));

        assertTrue(r.success());

        // verify in memory
        List<ElementRef> history = wi.checkpointHistory("worldview");
        assertEquals(1, history.size());
        assertEquals("气候.中原", history.get(0).element().key());
        assertEquals("中原大旱蝗灾四起", history.get(0).element().value());
        assertTrue(history.get(0).element().tags().contains("气候"));
    }
}
