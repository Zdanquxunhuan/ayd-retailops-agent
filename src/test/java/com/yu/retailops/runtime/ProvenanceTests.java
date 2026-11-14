package com.yu.retailops.runtime;
import com.yu.retailops.domain.Diagnosis.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class ProvenanceTests {
    @Test void ambiguousReceivedDoesNotBecomePosReceipt() {
        var data = Map.<String,Object>of("diagnostics", "received; saveData connection unavailable");
        var original = new Evidence("e1", "logs.search", new Arguments("A001", "O100"),
                new ToolResult(ResultStatus.SUCCESS, "OBSERVED", true, data, null), Instant.now(), Instant.now(), true);
        var enriched = ContextBuilder.withProvenance(original);
        assertEquals("PUSH_CENTER", enriched.result().data().get("sourceSystem"));
        assertEquals("UNKNOWN", enriched.result().data().get("businessStage"));
        assertEquals(data.get("diagnostics"), enriched.result().data().get("diagnostics"));
        assertFalse(original.result().data().containsKey("sourceSystem"));
        assertEquals(original.id(), enriched.id());
    }
}
