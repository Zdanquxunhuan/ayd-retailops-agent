package com.yu.retailops;
import com.yu.retailops.tool.*;
import com.yu.retailops.domain.Diagnosis.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class LocalLogAdapterTests {
    @TempDir Path root;
    void write(String text) throws Exception {
        try(var out=new GZIPOutputStream(Files.newOutputStream(root.resolve("b-push-center-server.log.2026-11-11.2.gz")))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
    ToolResult query(String id) throws Exception {
        return new LocalLogAdapter(root,"2026-11-11").definition().handler()
            .execute(new Input("query","A001",id,"restore","investigation"),List.of());
    }
    @Test void exactMatchAndPayloadExclusion() throws Exception {
        write("2026-11-11 10:00:00 order_id=1234\njava.lang.NullPointerException\n2026-11-11 10:00:01 order_id=123\ncom.example.NewDatabaseException: phone=13812345678 token=secret");
        var r=query("123");
        assertTrue(r.toString().contains("NewDatabaseException"));
        assertFalse(r.toString().contains("NullPointerException"));
        assertFalse(r.toString().contains("13812345678"));assertFalse(r.toString().contains("secret"));
        assertFalse(r.complete());assertFalse(new LocalLogAdapter(root,"2026-11-11").definition().simulated());
    }
    @Test void emptySnapshotDoesNotMeanMissingOrder() throws Exception {
        var r=query("123");assertFalse(r.complete());assertNotEquals(ResultStatus.NOT_FOUND,r.status());
        assertEquals(List.of(),r.data().get("lines"));
    }
    @Test void invalidInputAndOversizedLines() throws Exception {
        assertEquals("INVALID_ORDER_ID",query("../x;rm").errorCode());
        write("x".repeat(40000));assertEquals(ResultStatus.PARTIAL,query("123").status());
    }
    @Test void hitLimitIsExplicit() throws Exception {
        write("2026-11-11 10:00:00 order_id=123 SQLException\n".repeat(30));
        var r=query("123");assertEquals(true,r.data().get("scanTruncated"));
        assertEquals(10,((List<?>)r.data().get("lines")).size());
    }
    @Test void badGzipIsToolError() throws Exception {
        Files.writeString(root.resolve("b-push-center-server.log.2026-11-11.2.gz"),"not gzip");
        try(var executor=new ToolExecutor()) {
            var r=executor.execute(new LocalLogAdapter(root,"2026-11-11").definition(),new Input("q","A001","123","restore","investigation"),List.of());
            assertEquals(ResultStatus.ERROR,r.status());assertFalse(r.complete());
        }
    }
    @Test void meituanMockScenariosRemainSimulated() throws Exception {
        var registry=new FixtureToolAdapter(new FixtureCatalog(new tools.jackson.databind.json.JsonMapper())).registry();
        var d=registry.require(ReadOnlyGateway.PLATFORM);assertTrue(d.simulated());
        for(String fixture:List.of("restore","outside-window","platform-timeout","platform-empty")) {
            var r=d.handler().execute(new Input("q","A001","123",fixture,"investigation"),List.of());
            assertNotNull(r.status());
            if(fixture.equals("platform-timeout"))assertEquals(ResultStatus.TIMEOUT,r.status());
            if(fixture.equals("restore"))assertEquals("FOUND",r.outcome());
            if(fixture.equals("outside-window"))assertEquals("OUTSIDE_WINDOW",r.outcome());
        }
    }
}
