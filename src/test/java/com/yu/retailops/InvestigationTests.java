package com.yu.retailops;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.runtime.*;
import com.yu.retailops.tool.*;
import com.yu.retailops.persistence.FileTaskRepository;
import com.yu.retailops.application.TaskService;
import com.yu.retailops.evaluation.InvestigationDemoRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static com.yu.retailops.tool.ReadOnlyGateway.*;
import static org.junit.jupiter.api.Assertions.*;

class InvestigationTests {
    final Clock clock = Clock.systemUTC();
    final JsonMapper json = JsonMapper.builder().build();
    FixtureCatalog fixtures;
    ReadOnlyGateway gateway;
    ReportPolicy policy;
    DiagnosisRunner runner;
    @BeforeEach void setup() throws Exception {
        fixtures = new FixtureCatalog(json); gateway = new ReadOnlyGateway(fixtures, "A001", clock);
        policy = new ReportPolicy(clock); runner = new DiagnosisRunner(gateway, policy, clock, 8, 12, 5);
    }
    @AfterEach void close() { runner.close(); }
    Task task(String fixture, Intent intent) {
        Task task = new Task(); task.id = UUID.randomUUID().toString(); task.createdAt = clock.instant();
        task.input = new Input("为什么此前没同步？", "A001", "O100", fixture, "investigation", intent);
        task.provider = "TEST_DOUBLE_NOT_LLM"; task.modelVersion = "scripted-contract-test";
        return task;
    }
    static class ScriptedPlanner implements Planner, InvestigationPlanner {
        final Function<com.yu.retailops.domain.Investigation.Context, Proposal> script;
        ScriptedPlanner(Function<com.yu.retailops.domain.Investigation.Context, Proposal> script) { this.script = script; }
        public Reply investigate(com.yu.retailops.domain.Investigation.Context c) {
            Proposal p = script.apply(c);
            // Legacy scenario scripts supply decisions; this test-only adapter supplies explicit review records.
            // Missing/incorrect review behavior is tested separately against the v2 policy.
            var reviews = c.hypotheses().stream().map(h -> {
                Hypothesis after = p.hypothesisUpdates().stream().filter(u -> u.id().equals(h.id())).findFirst().orElse(h);
                return new Review(h.id(), c.newEvidenceIds(), h.equals(after) ? ReviewDisposition.MAINTAIN : ReviewDisposition.REVISE,
                        "测试替身逐项复核，非真实模型推理");
            }).toList();
            return new Reply(new Proposal(p.action(), p.reasonSummary(), p.hypothesisUpdates(), p.query(), p.claims(), p.unresolvedQuestions(), reviews), 30, 10);
        }
        public ModelReply next(Planner.Context c) { throw new AssertionError("Legacy model path must not be used"); }
        public String provider() { return "TEST_DOUBLE_NOT_LLM"; }
        public String modelVersion() { return "scripted-contract-test"; }
    }
    Hypothesis initial() { return new Hypothesis("H1", "投递可能未完成", HypothesisStatus.PROPOSED, List.of(), List.of(), List.of("执行记录")); }
    Proposal query(String tool, List<Hypothesis> updates) {
        return new Proposal(Action.QUERY, "核查投递过程", updates,
                new Query(tool, new Arguments("A001", "O100"), "H1", "投递是否已完成"), List.of(), List.of());
    }
    Proposal human(List<Hypothesis> updates) {
        return new Proposal(Action.NEEDS_HUMAN, "历史证据不足", updates, null, List.of(), List.of("缺少当时耗时及运行记录"));
    }
    String id(com.yu.retailops.domain.Investigation.Context c, String tool) { return ReportPolicy.latest(c.evidence(), tool).id(); }
    void run(Task task, Function<com.yu.retailops.domain.Investigation.Context, Proposal> script) {
        runner.run(task, new ScriptedPlanner(script), ignored -> {}, () -> false);
    }
    @Test void simpleStatusNeedsZeroModelCalls() {
        Task task = task("synced", Intent.CHECK_STATUS);
        run(task, c -> { fail("Status lookup must not invoke model"); return null; });
        assertEquals(0, task.decisions); assertEquals(1, task.toolCalls);
        assertEquals(Outcome.ALREADY_SYNCED, task.report.outcome());
        assertEquals("STATUS_CHECK_READY", task.investigation.stopCode);
    }
    @Test void oneRejectedQueryCanBeCorrectedWithoutApplyingRejectedState() {
        Task task = task("investigation-no-history", Intent.DIAGNOSE_CAUSE);
        AtomicInteger round = new AtomicInteger();
        run(task, c -> {
            if (round.incrementAndGet() == 1) return query(PUSH_HISTORY, List.of()); // H1 does not exist.
            if (round.get() == 2) {
                assertEquals("INVALID_INVESTIGATION_QUERY", c.validationFeedback().code());
                assertTrue(c.hypotheses().isEmpty()); assertEquals(2, c.evidence().size());
                assertEquals(2, c.newEvidenceIds().size());
                return query(PUSH_HISTORY,List.of(initial()));
            }
            assertNull(c.validationFeedback());
            return human(List.of());
        });
        assertEquals("INVESTIGATION_NEEDS_HUMAN",task.investigation.stopCode);
        assertEquals(3,task.decisions); assertEquals(3,task.toolCalls);
        assertFalse(task.investigation.turns.get(0).accepted());
        assertEquals(1,task.events.stream().filter(e -> e.code().equals("MODEL_CORRECTION_REQUESTED")).count());
    }
    @Test void repeatedBadQueryGetsAtMostOneCorrectionAttempt() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        run(task,c -> query(PUSH_HISTORY,List.of()));
        assertEquals("INVALID_INVESTIGATION_QUERY",task.investigation.stopCode);
        assertEquals(2,task.decisions); assertEquals(2,task.toolCalls);
        assertTrue(task.investigation.hypotheses.isEmpty());
    }
    @Test void syncedNowDoesNotSkipHistoricalCauseInvestigation() {
        Task task = task("investigation-no-history", Intent.DIAGNOSE_CAUSE);
        AtomicInteger calls = new AtomicInteger();
        run(task, c -> {
            assertEquals(com.yu.retailops.domain.Investigation.Environment.SIMULATION, c.executionEnvironment());
            assertEquals(Intent.DIAGNOSE_CAUSE, c.intent());
            assertEquals(Outcome.ALREADY_SYNCED, c.currentBusinessOutcome());
            String serialized = json.writeValueAsString(c);
            assertFalse(serialized.contains("fixtureId")); assertFalse(serialized.contains("requiredObservations"));
            assertFalse(serialized.contains("investigation-no-history"));
            return calls.getAndIncrement() == 0 ? query(PUSH_HISTORY, List.of(initial())) : human(List.of());
        });
        assertEquals(2, calls.get()); assertEquals(Status.NEEDS_HUMAN, task.status);
        assertEquals(Outcome.ALREADY_SYNCED, task.report.outcome());
        assertEquals("UNCONFIRMED", task.investigation.rootCauseStatus);
    }
    @Test void newEvidenceRevisesHypothesisAndPreservesTrace(@TempDir Path dir) throws Exception {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        AtomicInteger round = new AtomicInteger();
        try (var repository = new FileTaskRepository(dir, json)) {
            runner.run(task, new ScriptedPlanner(c -> switch (round.incrementAndGet()) {
                case 1 -> query(PUSH_HISTORY, List.of(initial()));
                case 2 -> query(LOGS, List.of());
                case 3 -> query(POS, List.of(new Hypothesis("H1", "投递可能未完成", HypothesisStatus.UNCONFIRMED,
                        List.of(), List.of(id(c, LOGS)), List.of("最新 POS 状态"))));
                default -> new Proposal(Action.DIAGNOSIS_READY, "新观察推翻尚未完成的假设", List.of(
                        new Hypothesis("H1", "投递可能未完成", HypothesisStatus.CONTRADICTED, List.of(),
                                List.of(id(c, LOGS), id(c, POS)), List.of())), null,
                        List.of(new Claim("异步受理可能晚于首次查询；精确延迟原因仍未知", List.of(id(c, PUSH_HISTORY), id(c, LOGS), id(c, POS)), Qualification.HYPOTHESIS)),
                        List.of("缺少当时耗时和线程指标"));
            }), repository::save, () -> false);
            Task saved = repository.get(task.id);
            assertEquals(List.of(POS, LOCAL, PUSH_HISTORY, LOGS, POS), saved.evidence.stream().map(Evidence::tool).toList());
            assertEquals(Outcome.ALREADY_SYNCED, saved.report.outcome());
            assertEquals(Status.COMPLETED, saved.status);
            assertEquals(4, saved.investigation.turns.size());
            assertEquals(List.of(HypothesisStatus.PROPOSED, HypothesisStatus.UNCONFIRMED, HypothesisStatus.CONTRADICTED),
                    saved.investigation.hypothesisHistory.stream().map(r -> r.hypothesis().status()).toList());
            assertEquals(2, saved.investigation.hypotheses.get(0).contradictingEvidenceIds().size());
            assertEquals("UNCONFIRMED", saved.report.rootCauseStatus());
            assertEquals(4 * 30, saved.promptTokens);
            assertTrue(InvestigationDemoRunner.render(saved).contains("CONTRADICTED"));
            assertTrue(saved.planHistory.isEmpty()); // New contract is separate from legacy business decisions.
        }
    }
    @Test void modelCannotInventCitationsOrLoseCounterEvidence() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        run(task, c -> query(PUSH_HISTORY, List.of(new Hypothesis("H1", "x", HypothesisStatus.SUPPORTED,
                List.of("invented"), List.of(), List.of()))));
        assertEquals("INVALID_EVIDENCE_REFERENCE", task.investigation.stopCode);
        assertEquals(2, task.evidence.size()); assertTrue(task.investigation.hypotheses.isEmpty());
        assertFalse(task.investigation.turns.get(0).accepted());

        Task second = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        AtomicInteger round = new AtomicInteger();
        run(second, c -> round.incrementAndGet() == 1
                ? query(PUSH_HISTORY, List.of(new Hypothesis("H1", "假设", HypothesisStatus.UNCONFIRMED, List.of(), List.of(id(c, LOCAL)), List.of())))
                : human(List.of(new Hypothesis("H1", "假设", HypothesisStatus.PROPOSED, List.of(), List.of(), List.of()))));
        assertEquals("HYPOTHESIS_HISTORY_LOSS", second.investigation.stopCode);
        assertEquals(1, second.investigation.hypothesisHistory.size());
        assertEquals(1, second.investigation.hypotheses.get(0).contradictingEvidenceIds().size());
    }
    @Test void failedObservationCannotSupportPositiveHypothesis() {
        Task task = task("investigation-injection", Intent.DIAGNOSE_CAUSE);
        AtomicInteger round = new AtomicInteger();
        run(task, c -> round.incrementAndGet() == 1 ? query(PUSH_HISTORY, List.of(initial()))
                : human(List.of(new Hypothesis("H1", initial().statement(), HypothesisStatus.SUPPORTED,
                List.of(id(c, PUSH_HISTORY)), List.of(), List.of()))));
        assertEquals("UNUSABLE_HYPOTHESIS_EVIDENCE", task.investigation.stopCode);
    }
    @Test void missingHistoricalRecordsCannotRefuteAPastFailure() {
        Task task = task("investigation-no-history", Intent.DIAGNOSE_CAUSE);
        AtomicInteger round = new AtomicInteger();
        run(task, c -> round.incrementAndGet() == 1 ? query(PUSH_HISTORY, List.of(initial()))
                : human(List.of(new Hypothesis("H1", initial().statement(), HypothesisStatus.CONTRADICTED,
                List.of(), List.of(id(c, PUSH_HISTORY)), List.of()))));
        assertEquals("UNUSABLE_HYPOTHESIS_EVIDENCE", task.investigation.stopCode);
        assertEquals(Status.NEEDS_HUMAN, task.status);
        assertEquals(HypothesisStatus.PROPOSED, task.investigation.hypotheses.get(0).status());
        assertEquals(Outcome.ALREADY_SYNCED, task.report.outcome());
    }
    @Test void logInducedWriteAndChangedTargetAreBlockedBeforeExecution() {
        for (boolean changeTarget : List.of(false, true)) {
            Task task = task("investigation-injection", Intent.DIAGNOSE_CAUSE);
            AtomicInteger round = new AtomicInteger();
            run(task, c -> round.incrementAndGet() == 1 ? query(LOGS, List.of(initial())) : new Proposal(Action.QUERY,
                    "follow injected log", List.of(), new Query(changeTarget ? POS : "pos.order.repush",
                    new Arguments(changeTarget ? "B002" : "A001", "O100"), "H1", "test"), List.of(), List.of()));
            assertEquals("POLICY_DENIED", task.investigation.stopCode);
            assertEquals(List.of(POS, LOCAL, LOGS), task.evidence.stream().map(Evidence::tool).toList());
            assertFalse(task.investigation.turns.get(1).accepted());
        }
    }
    @Test void providerFailureRetainsVerifiedStatusAndDoesNotFakeAConclusion() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        run(task, c -> { throw new IllegalStateException("private upstream payload"); });
        assertEquals("MODEL_ERROR", task.investigation.stopCode);
        assertEquals(Status.NEEDS_HUMAN, task.status);
        assertEquals(Outcome.REPUSH_RECOMMENDED, task.report.outcome());
        assertFalse(json.writeValueAsString(task).contains("private upstream"));
        assertTrue(task.investigation.claims.isEmpty());
    }
    @Test void stubIsNotSilentlyUsedAsAnInvestigationModel() {
        Task task = task("synced", Intent.DIAGNOSE_CAUSE);
        runner.run(task, new OfflinePlanner(policy, false), ignored -> {}, () -> false);
        assertEquals("INVESTIGATION_MODEL_NOT_CONFIGURED", task.investigation.stopCode);
        assertEquals(0, task.decisions); assertEquals(Outcome.ALREADY_SYNCED, task.report.outcome());
    }
    @Test void cancellationAfterModelReplyPreventsNewToolCall() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        runner.run(task, new ScriptedPlanner(c -> { cancelled.set(true); return query(LOGS, List.of(initial())); }), ignored -> {}, cancelled::get);
        assertEquals(Status.CANCELLED, task.status); assertEquals(2, task.toolCalls);
    }
    @Test void noProgressAndDecisionLimitsStopTheInvestigator() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        run(task, c -> query(PUSH_HISTORY, c.hypotheses().isEmpty() ? List.of(initial()) : List.of()));
        assertEquals("NO_PROGRESS", task.investigation.stopCode);
        assertEquals(4, task.toolCalls); // Two basic observations plus two equal history observations.
        try (var limited = new DiagnosisRunner(gateway, policy, clock, 1, 12, 5)) {
            Task limitedTask = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
            limited.run(limitedTask, new ScriptedPlanner(c -> query(PUSH_HISTORY, List.of(initial()))), ignored -> {}, () -> false);
            assertEquals(1, limitedTask.decisions); assertEquals("BUDGET_EXHAUSTED", limitedTask.investigation.stopCode);
        }
    }
    @Test void prematureCauseConclusionIsRejected() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        run(task, c -> new Proposal(Action.DIAGNOSIS_READY, "guess", List.of(), null,
                List.of(new Claim("推送失败", List.of(id(c, LOCAL)), Qualification.HYPOTHESIS)), List.of()));
        assertEquals("INSUFFICIENT_INVESTIGATION", task.investigation.stopCode);
        assertTrue(task.investigation.claims.isEmpty());
    }
    @Test void qualifiedCauseMayUseTimestampedBaselineWithoutMandatoryPosRecheck() {
        Task task = task("investigation-reversal", Intent.DIAGNOSE_CAUSE);
        AtomicInteger round = new AtomicInteger();
        run(task, c -> round.incrementAndGet() == 1 ? query(LOGS, List.of(initial()))
                : new Proposal(Action.DIAGNOSIS_READY, "premature conclusion", List.of(), null,
                List.of(new Claim("可能延迟", List.of(id(c, LOGS)), Qualification.HYPOTHESIS)), List.of("精确原因未知")));
        assertEquals("INVESTIGATION_READY", task.investigation.stopCode);
        assertTrue(task.investigation.turns.get(1).accepted());
        assertFalse(task.investigation.claims.isEmpty());
    }
    @Test void clarificationPreservesExplicitIntent(@TempDir Path dir) throws Exception {
        try (var repository = new FileTaskRepository(dir, json);
             var service = new TaskService(repository, runner, new OfflinePlanner(policy, false), new OfflinePlanner(policy, true), fixtures, gateway, clock)) {
            Task initial = service.create(new Input("查状态", null, null, "synced", "investigation", Intent.CHECK_STATUS));
            Task clarified = service.clarify(initial.id, "A001", "O100", initial.version);
            assertEquals(Intent.CHECK_STATUS, clarified.input.intent());
        }
    }
    @Test void interruptedWorkerCanPersistItsLastCheckpoint(@TempDir Path dir) throws Exception {
        try (var repository = new FileTaskRepository(dir, json)) {
            Task task = task("synced", Intent.CHECK_STATUS); task.status = Status.CANCELLED;
            Thread.currentThread().interrupt();
            try {
                repository.save(task);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertEquals(Status.CANCELLED, repository.get(task.id).status);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 4})
    void semanticRejectionPreservesBusinessFactsAndConsumesBudget(int alreadyUsed) {
        Task task = task("meituan-db-failure", Intent.DIAGNOSE_CAUSE);
        task.decisions = alreadyUsed;
        class ReviewedPlanner extends ScriptedPlanner implements DiagnosisReviewer {
            ReviewedPlanner() { super(c -> new Proposal(Action.NEEDS_HUMAN, "待审查", List.of(), null,
                    List.of(new Claim("POS 已收到", List.of(id(c, POS)), Qualification.HYPOTHESIS)),
                    List.of("缺少证据"))); }
            public ReviewReply review(com.yu.retailops.domain.Investigation.Context c, Proposal p) {
                return new ReviewReply(new ReviewResult(false, List.of(new Finding("SOURCE_CONFUSION",
                        "POS 缺失不能证明收到", List.of(id(c, POS))))), 7, 3);
            }
        }
        runner.run(task, new ReviewedPlanner(), ignored -> {}, () -> false);
        assertEquals("SEMANTIC_REVIEW_REJECTED", task.investigation.stopCode);
        assertEquals(Status.NEEDS_HUMAN, task.status);
        assertEquals(alreadyUsed == 0 ? 4 : 6, task.decisions);
        assertEquals(alreadyUsed == 0 ? 74 : 37, task.promptTokens);
        assertTrue(task.investigation.claims.isEmpty());
        assertEquals(alreadyUsed == 0 ? 2 : 1, task.investigation.semanticReviews.size());
        assertEquals(alreadyUsed == 0 ? List.of(1, 2) : List.of(1), task.investigation.turns.stream().map(Turn::round).toList());
        assertEquals(alreadyUsed == 0, task.investigation.semanticCorrectionUsed);
        assertEquals(2, task.evidence.size());
    }

    @Test void correctedClaimMustPassSecondIndependentReview() {
        Task task = task("meituan-db-failure", Intent.DIAGNOSE_CAUSE);
        AtomicInteger calls = new AtomicInteger();
        class Correcting extends ScriptedPlanner implements DiagnosisReviewer {
            Correcting() { super(c -> {
                boolean correcting = c.validationFeedback() != null;
                return new Proposal(Action.NEEDS_HUMAN, "证据不足", List.of(), null,
                        List.of(new Claim(correcting ? "历史响应情况未知" : "历史没有响应",
                                List.of(id(c, POS)), Qualification.HYPOTHESIS)), List.of("需要历史响应证据"));
            }); }
            public ReviewReply review(com.yu.retailops.domain.Investigation.Context c, Proposal p) {
                boolean approved = calls.incrementAndGet() == 2;
                if (approved) {
                    assertEquals("SEMANTIC_REVIEW_REJECTED", c.validationFeedback().code());
                    assertEquals("历史响应情况未知", p.claims().get(0).text());
                }
                return new ReviewReply(new ReviewResult(approved, approved ? List.of() :
                        List.of(new Finding("TEMPORAL_OVERREACH", "当前缺失不能反推历史响应", List.of(id(c, POS))))), 5, 2);
            }
        }
        runner.run(task, new Correcting(), ignored -> {}, () -> false);
        assertEquals(2, calls.get());
        assertEquals(4, task.decisions);
        assertEquals("历史响应情况未知", task.investigation.claims.get(0).text());
        assertFalse(task.investigation.turns.get(0).accepted());
        assertTrue(task.investigation.turns.get(1).accepted());
        assertEquals(List.of(1, 2), task.investigation.turns.stream().map(Turn::round).toList());
    }

    @Test void clearLogDiagnosisUsesTwoModelCallsAndNoIndependentReview() {
        Task task = task("meituan-db-failure", Intent.DIAGNOSE_CAUSE);
        AtomicInteger rounds = new AtomicInteger();
        class Simple extends ScriptedPlanner implements DiagnosisReviewer {
            Simple() { super(c -> rounds.incrementAndGet() == 1 ? query(LOGS, List.of(initial())) :
                    new Proposal(Action.DIAGNOSIS_READY, "保存异常可能影响同步", List.of(), null,
                            List.of(new Claim("日志记录保存异常，可能影响同步；历史响应未知",
                                    List.of(id(c, LOGS)), Qualification.HYPOTHESIS)), List.of("历史响应未知"))); }
            public ReviewReply review(com.yu.retailops.domain.Investigation.Context c, Proposal p) {
                throw new AssertionError("Clear qualified diagnosis should not require a review call");
            }
        }
        runner.run(task, new Simple(), ignored -> {}, () -> false);
        assertEquals("INVESTIGATION_READY", task.investigation.stopCode);
        assertEquals(2, task.decisions);
        assertEquals(3, task.toolCalls);
        assertTrue(task.investigation.semanticReviews.isEmpty());
        assertEquals("UNCONFIRMED", task.report.rootCauseStatus());
    }
}
