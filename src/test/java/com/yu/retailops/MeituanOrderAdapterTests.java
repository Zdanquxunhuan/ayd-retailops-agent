package com.yu.retailops;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.Intent;
import com.yu.retailops.tool.*;
import com.yu.retailops.tool.MeituanOrderClient.*;
import com.yu.retailops.runtime.*;
import com.yu.retailops.persistence.FileTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yu.retailops.tool.ReadOnlyGateway.*;

class MeituanOrderAdapterTests {
    final JsonMapper json = JsonMapper.builder().build();
    final Clock clock = Clock.systemUTC();
    Input input(String fixture) { return new Input("调查原因", "A001", "O100", fixture, "investigation", Intent.CHECK_STATUS); }

    @ParameterizedTest
    @CsvSource({"complete,SUCCESS,FOUND,true", "boundary,SUCCESS,FOUND,true",
            "incomplete,SUCCESS,FOUND,false", "wrong-order,DENIED,INDETERMINATE,false",
            "wrong-store,DENIED,INDETERMINATE,false", "multiple,UNKNOWN,MULTIPLE,false",
            "partial,PARTIAL,INDETERMINATE,false", "empty,NOT_FOUND,ABSENT,false",
            "outside-window,SUCCESS,OUTSIDE_WINDOW,false", "unknown-time,NOT_FOUND,ABSENT,false",
            "timeout,TIMEOUT,INDETERMINATE,false", "rate-limit,ERROR,INDETERMINATE,false",
            "rejected,ERROR,INDETERMINATE,false"})
    void rawResponsesAreValidated(String fixture, String status, String outcome, boolean validated) throws Exception {
        try (var gateway = new ReadOnlyGateway(new FixtureCatalog(json), "A001", clock)) {
            Input input = input("meituan-" + fixture);
            var evidence = gateway.invoke(input, Decision.call(PLATFORM, input, "查平台"), List.of());
            assertTrue(evidence.simulated());
            assertEquals(status, evidence.result().status().name());
            assertEquals(outcome, evidence.result().outcome());
            assertEquals(validated, evidence.result().data().get("payloadValidated"));
            assertEquals(false, evidence.result().data().get("productionPayloadValidated"));
            String serialized = json.writeValueAsString(evidence);
            assertFalse(serialized.contains("FOREIGN-ORDER")); assertFalse(serialized.contains("B002"));
            assertFalse(serialized.contains("SKU-DEMO")); assertFalse(serialized.contains("candidates"));
            assertFalse(serialized.contains("fixtureId"));
        }
    }

    @Test void rawIdentityIsNeverRewrittenToRequestedOrder() throws Exception {
        var scenarios = new MockMeituanOrderClient.Scenarios(json);
        Input in = input("meituan-wrong-order");
        var response = scenarios.forInvocation(in, List.of()).search(new Request(in.storeId(), in.orderId()));
        assertEquals("FOREIGN-ORDER", response.candidates().get(0).orderId());
        var result = new MeituanOrderValidator().validate(new Request("A001", "O100"), response);
        assertEquals("PLATFORM_TARGET_MISMATCH", result.errorCode());
    }

    @Test void scopeTimeAndIncompleteSearchCannotBecomeOutsideWindow() {
        Instant now = Instant.parse("2026-11-13T04:00:00Z");
        var validator = new MeituanOrderValidator(); var request = new Request("A001", "O100");
        assertEquals("PLATFORM_SCOPE_MISMATCH", validator.validate(request,
                new Response(ResultStatus.SUCCESS,true,"A001","OTHER",now,now.minusSeconds(600000),List.of(),null)).errorCode());
        assertEquals(ResultStatus.PARTIAL, validator.validate(request,
                new Response(ResultStatus.SUCCESS,false,"A001","O100",now,now.minusSeconds(600000),List.of(),null)).status());
        assertEquals("PLATFORM_TIME_INVALID", validator.validate(request,
                new Response(ResultStatus.SUCCESS,true,"A001","O100",now,now.plusSeconds(1),List.of(),null)).errorCode());
        assertEquals("PLATFORM_TIMEOUT", validator.validate(request,
                new Response(ResultStatus.TIMEOUT,true,"A001","O100",now,now.minusSeconds(600000),List.of(),"secret upstream")).errorCode());
    }

    @Test void incompleteAndMixedObservationsCannotProduceRestoreRecommendation() throws Exception {
        try (var gateway = new ReadOnlyGateway(new FixtureCatalog(json),"A001",clock)) {
            var policy = new ReportPolicy(clock);
            var evidence = new ArrayList<Evidence>();
            Input in=input("meituan-incomplete");
            for (String tool : List.of(POS,LOCAL,PLATFORM)) evidence.add(gateway.invoke(in,Decision.call(tool,in,""),evidence));
            assertTrue(evidence.get(2).result().complete());
            assertEquals(Outcome.UNRESOLVED,policy.supported(evidence));
            in=input("meituan-complete");evidence.set(2,gateway.invoke(in,Decision.call(PLATFORM,in,""),List.of()));
            assertEquals(Outcome.RESTORE_RECOMMENDED,policy.supported(evidence));
            for(int i=0;i<2;i++) { var e=evidence.get(i);evidence.set(i,new Evidence(e.id(),e.tool(),e.query(),e.result(),e.observedAt(),e.validUntil(),false)); }
            assertEquals(Outcome.UNRESOLVED,policy.supported(evidence));
        }
    }

    @Test void restartUsesPersistedObservationSequenceAndDoesNotShareCursor(@TempDir Path dir) throws Exception {
        Input in=input("meituan-timeout-recovery");String id=UUID.randomUUID().toString();
        try (var gateway=new ReadOnlyGateway(new FixtureCatalog(json),"A001",clock);
             var repo=new FileTaskRepository(dir,json)) {
            Task task=new Task();task.id=id;task.input=in;task.status=Status.PAUSED;
            task.evidence.add(gateway.invoke(in,Decision.call(PLATFORM,in,""),List.of()));
            assertEquals(ResultStatus.TIMEOUT,task.evidence.get(0).result().status());repo.save(task);
        }
        try (var gateway=new ReadOnlyGateway(new FixtureCatalog(json),"A001",clock);
             var repo=new FileTaskRepository(dir,json)) {
            Task task=repo.get(id);
            assertEquals("FOUND",gateway.invoke(in,Decision.call(PLATFORM,in,""),task.evidence).result().outcome());
            assertEquals(ResultStatus.TIMEOUT,gateway.invoke(in,Decision.call(PLATFORM,in,""),List.of()).result().status());
        }
    }

    @Test void unexpectedClientFailureDoesNotLeakResponseOrImplyAbsence() {
        var adapter=new MeituanOrderAdapter(request -> {throw new IllegalStateException("private upstream body");});
        try(var gateway=new ReadOnlyGateway(new ToolRegistry(List.of(adapter.definition())),"A001",clock)) {
            Input in=input("unused");var result=gateway.invoke(in,Decision.call(PLATFORM,in,""),List.of()).result();
            assertEquals(ResultStatus.ERROR,result.status());assertFalse(result.complete());
            assertFalse(result.toString().contains("private"));
        }
    }
}
