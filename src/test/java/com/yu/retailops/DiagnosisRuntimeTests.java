package com.yu.retailops;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.runtime.*;
import com.yu.retailops.tool.*;
import com.yu.retailops.persistence.FileTaskRepository;
import com.yu.retailops.application.TaskService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static com.yu.retailops.tool.ReadOnlyGateway.*;

class DiagnosisRuntimeTests {
    final Clock clock = Clock.systemUTC();
    final JsonMapper json = JsonMapper.builder().build();
    FixtureCatalog catalog;
    ReportPolicy policy;
    ReadOnlyGateway gateway;
    DiagnosisRunner runner;
    @BeforeEach void setup() throws Exception {
        catalog = new FixtureCatalog(json); policy = new ReportPolicy(clock);
        gateway = new ReadOnlyGateway(catalog, "A001", clock);
        runner = new DiagnosisRunner(gateway, policy, clock, 8, 12, 5);
    }
    @AfterEach void close() { runner.close(); }
    Task task(String fixture) {
        Task t = new Task(); t.id = UUID.randomUUID().toString(); t.createdAt = clock.instant();
        t.input = new Input("调查订单", "A001", "O100", fixture, "agent"); return t;
    }
    Planner malicious(Function<Planner.Context,Decision> fn) {
        return new Planner() {
            public ModelReply next(Context c) { return new ModelReply(fn.apply(c), 0, 0); }
            public String provider() { return "TEST"; }
            public String modelVersion() { return "test"; }
        };
    }
    @ParameterizedTest @ValueSource(strings={"synced","repush","restore","outside-window","pos-timeout","platform-timeout","partial-pos","platform-empty","contradiction","injection"})
    void offlineDiagnosisMatchesDevelopmentOracle(String fixture) {
        Task t = task(fixture);
        runner.run(t, new OfflinePlanner(policy, false), ignored -> {}, () -> false);
        assertEquals(catalog.get(fixture).expected(), t.report.outcome());
        assertEquals("REPORT_READY", t.events.get(t.events.size()-1).code());
        assertEquals("UNCONFIRMED", t.report.rootCauseStatus());
        assertTrue(t.evidence.stream().allMatch(e -> TOOLS.contains(e.tool()) && e.simulated()));
    }
    @Test void contradictoryReceiptCausesRequeryAndChangesConclusion() {
        Task t = task("contradiction"); runner.run(t, new OfflinePlanner(policy, false), ignored -> {}, () -> false);
        assertEquals(List.of(POS, LOCAL, LOGS, POS), t.evidence.stream().map(Evidence::tool).toList());
        assertEquals(Outcome.ALREADY_SYNCED, t.report.outcome());
        Task baseline = task("contradiction"); runner.run(baseline, new OfflinePlanner(policy, true), ignored -> {}, () -> false);
        assertEquals(Outcome.REPUSH_RECOMMENDED, baseline.report.outcome()); // Explicit limitation of baseline v1.
    }
    @Test void modelCannotInvokeWrites() {
        Task t = task("repush"); runner.run(t, malicious(c -> Decision.call("pos.order.repush", c.input(), "write")), ignored -> {}, () -> false);
        assertEquals("POLICY_DENIED", t.events.get(t.events.size()-1).code()); assertTrue(t.evidence.isEmpty());
    }
    @Test void invalidCallIsRetainedAsProposalButNeverExecuted() {
        Task t = task("synced");
        runner.run(t, malicious(c -> new Decision(DecisionType.CALL_TOOL, POS,
                new Arguments("A001", "O100"), null, List.of("unexpected-reference"), "query")), ignored -> {}, () -> false);
        assertEquals("INVALID_CALL", t.events.get(t.events.size()-1).code());
        assertEquals(1, t.planHistory.size());
        assertTrue(t.evidence.isEmpty()); assertEquals(0, t.toolCalls);
    }
    @Test void modelCannotChangeBusinessTarget() {
        Task t = task("repush"); runner.run(t, malicious(c -> Decision.call(POS,
                new Input("", "A001", "DIFFERENT", null, null), "change")), ignored -> {}, () -> false);
        assertEquals("POLICY_DENIED", t.events.get(t.events.size()-1).code());
    }
    @Test void crossStoreIsDeniedBeforeQuery() {
        Task t = task("repush"); t.input = new Input("", "B002", "O100", "repush", "agent");
        runner.run(t, new OfflinePlanner(policy, false), ignored -> {}, () -> false); assertTrue(t.evidence.isEmpty());
        assertEquals(Status.NEEDS_HUMAN, t.status);
    }
    @Test void fabricatedEvidenceAndUnsupportedConclusionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate(new Decision(DecisionType.FINISH, null, null,
                Outcome.ALREADY_SYNCED, List.of("invented"), ""), List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Decision.finish(Outcome.ALREADY_SYNCED, List.of(), ""), List.of()));
    }
    @Test void latestFailureCannotBeHiddenByCitingEarlierSuccess() {
        Input input = task("synced").input;
        Evidence first = gateway.invoke(input, Decision.call(POS,input,""), List.of());
        Evidence failure = new Evidence("failure", POS, first.query(), new ToolResult(ResultStatus.TIMEOUT,"INDETERMINATE",false,Map.of(),"TIMEOUT"), clock.instant(),clock.instant().plusSeconds(60),true);
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Decision.finish(Outcome.ALREADY_SYNCED,List.of(first),""),List.of(first,failure)));
    }
    @Test void expiredEvidenceCannotSupportConclusion() {
        var evidence = new Evidence("old", POS,new Arguments("A001","O100"),new ToolResult(ResultStatus.SUCCESS,"FOUND",true,Map.of(),null),
                Instant.EPOCH,Instant.EPOCH.plusSeconds(1),true);
        assertEquals(Outcome.UNRESOLVED,policy.supported(List.of(evidence)));
    }
    @Test void repeatedIdenticalQueryStopsWithoutInfiniteLoop() {
        Task t=task("repush"); runner.run(t,malicious(c -> Decision.call(POS,c.input(),"repeat")),ignored -> {},()->false);
        assertEquals("NO_PROGRESS",t.events.get(t.events.size()-1).code()); assertEquals(2,t.toolCalls);
    }
    @Test void decisionBudgetStopsBeforeSecondCall() {
        try(var shortRunner=new DiagnosisRunner(gateway,policy,clock,1,12,5)) {
            Task t=task("repush"); shortRunner.run(t,new OfflinePlanner(policy,false),ignored -> {},()->false);
            assertEquals(1,t.decisions); assertEquals("BUDGET_EXHAUSTED",t.events.get(t.events.size()-1).code());
        }
    }
    @Test void cancellationPreventsAnyQuery() {
        Task t=task("repush"); runner.run(t,new OfflinePlanner(policy,false),ignored -> {},()->true);
        assertEquals(Status.CANCELLED,t.status); assertEquals(0,t.toolCalls);
    }
    @Test void evaluatorDoesNotCreditGuessingUnknownWithoutEvidence() {
        Task t=task("pos-timeout");
        t.report=policy.validate(Decision.finish(Outcome.UNRESOLVED,List.of(),"guess"),List.of());
        assertFalse(com.yu.retailops.evaluation.EvaluationRunner.hasRequiredObservations(catalog.get("pos-timeout"),t));
    }
    @Test void modelTimeoutIsBoundedAndSafe() {
        try(var shortRunner=new DiagnosisRunner(gateway,policy,clock,8,12,1)) {
            Planner slow=new Planner() {
                public ModelReply next(Context c) throws Exception {Thread.sleep(10000);return null;}
                public String provider(){return "TEST";} public String modelVersion(){return "slow";}
            };
            Task t=task("repush"); shortRunner.run(t,slow,ignored -> {},()->false);
            assertEquals("MODEL_TIMEOUT",t.events.get(t.events.size()-1).code()); assertEquals(0,t.toolCalls);
        }
    }
    @Test void modelFailureProducesSafeReport() {
        Task t=task("repush"); runner.run(t,malicious(c -> {throw new IllegalStateException("secret upstream data");}),ignored -> {},()->false);
        assertEquals(Status.FAILED,t.status); assertEquals(Outcome.UNRESOLVED,t.report.outcome());
        assertFalse(t.events.toString().contains("secret"));
    }
    @Test void restartPreservesEvidenceAndSafelyStopsInFlightTask(@TempDir Path dir) throws Exception {
        Task t=task("repush"); t.status=Status.EXECUTING_TOOL;
        t.evidence.add(gateway.invoke(t.input,Decision.call(POS,t.input,""),List.of()));
        try(var repo=new FileTaskRepository(dir,json)) {repo.save(t);}
        try(var repo=new FileTaskRepository(dir,json);
            var service=new TaskService(repo,runner,new OfflinePlanner(policy,false),new OfflinePlanner(policy,true),catalog,gateway,clock)) {
            Task recovered=service.get(t.id); assertEquals(Status.NEEDS_HUMAN,recovered.status);
            assertEquals(1,recovered.evidence.size());
            assertEquals("RESTART_INTERRUPTED",recovered.events.get(recovered.events.size()-1).code());
        }
    }
    @Test void clarificationUsesVersionAndRejectsCrossStore(@TempDir Path dir) throws Exception {
        try(var repo=new FileTaskRepository(dir,json);
            var service=new TaskService(repo,runner,new OfflinePlanner(policy,false),new OfflinePlanner(policy,true),catalog,gateway,clock)) {
            Task t=service.create(new Input("调查订单",null,null,"repush","agent"));
            assertEquals(Status.CLARIFYING,t.status);
            assertThrows(TaskService.ConflictException.class,()->service.clarify(t.id,"A001","O100",t.version+1));
            assertThrows(SecurityException.class,()->service.clarify(t.id,"B002","O100",t.version));
        }
    }
}
