package com.yu.retailops;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.runtime.*;
import com.yu.retailops.tool.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;


class HypothesisReviewTests {
    final Clock clock = Clock.systemUTC();
    Hypothesis h(TemporalScope scope, HypothesisStatus status, List<String> support, List<String> counter) {
        return new Hypothesis("H1", "目标时段 POS 缺失", status, support, counter, List.of("具体故障原因"),
                scope, scope == TemporalScope.CURRENT ? "当前" : "用户描述的此前阶段，具体时间未知",
                Proposition.POS_ABSENT, "目标时段 POS 明确存在的观察");
    }
    Task task(Hypothesis old) {
        Task t = new Task(); t.schemaVersion = "investigation-v2"; t.investigation = new Trace();
        t.input = new Input("查原因", "A001", "O100", "synced", "investigation");
        if (old != null) t.investigation.hypotheses.add(old);
        t.evidence.add(new Evidence("pos-new", ReadOnlyGateway.POS, new Arguments("A001", "O100"),
                new ToolResult(ResultStatus.SUCCESS, "FOUND", true, Map.of(), null), clock.instant(), clock.instant().plusSeconds(120), true));
        return t;
    }
    Review review(ReviewDisposition d) { return new Review("H1", List.of("pos-new"), d, "已检查最新观察对目标时段的影响"); }
    Proposal proposal(List<Hypothesis> updates, List<Review> reviews) {
        return new Proposal(Action.NEEDS_HUMAN, "待复核", updates, null, List.of(), List.of("缺少历史记录"), reviews);
    }
    void validate(Task t, Proposal p) throws Exception {
        new InvestigationPolicy(new ReadOnlyGateway(new FixtureCatalog(JsonMapper.builder().build()), "A001", clock), clock).validate(p,t);
    }
    void rejected(String code, Task t, Proposal p) {
        assertEquals(code, assertThrows(IllegalArgumentException.class, () -> validate(t,p)).getMessage());
    }
    @Test void everyExistingHypothesisRequiresReviewEvenWhenUnchanged() {
        var old = h(TemporalScope.HISTORICAL, HypothesisStatus.PROPOSED, List.of(), List.of());
        rejected("HYPOTHESIS_REVIEW_REQUIRED", task(old), proposal(List.of(), List.of()));
    }
    @Test void reviewCannotIgnoreTheNewPosObservation() {
        var old = h(TemporalScope.HISTORICAL, HypothesisStatus.PROPOSED, List.of(), List.of());
        rejected("NEW_EVIDENCE_NOT_REVIEWED", task(old), proposal(List.of(), List.of(new Review("H1",List.of(),ReviewDisposition.MAINTAIN,"没有变化"))));
    }
    @Test void currentAbsenceCannotBeMaintainedAfterPosFound() {
        var old = h(TemporalScope.CURRENT, HypothesisStatus.PROPOSED, List.of(), List.of());
        rejected("LATEST_FACT_CONTRADICTS_HYPOTHESIS", task(old), proposal(List.of(), List.of(review(ReviewDisposition.MAINTAIN))));
    }
    @Test void newPosFactCanRefuteCurrentAbsence() throws Exception {
        var old = h(TemporalScope.CURRENT, HypothesisStatus.PROPOSED, List.of(), List.of());
        validate(task(old), proposal(List.of(h(TemporalScope.CURRENT,HypothesisStatus.CONTRADICTED,List.of(),List.of("pos-new"))),
                List.of(review(ReviewDisposition.REVISE))));
    }
    @Test void currentSuccessDoesNotRefuteAPastFailure() throws Exception {
        var old = h(TemporalScope.HISTORICAL, HypothesisStatus.PROPOSED, List.of(), List.of());
        validate(task(old),proposal(List.of(),List.of(review(ReviewDisposition.MAINTAIN))));
        rejected("HISTORICAL_EVIDENCE_REQUIRED", task(old), proposal(List.of(h(TemporalScope.HISTORICAL,HypothesisStatus.CONTRADICTED,List.of(),List.of("pos-new"))),
                List.of(review(ReviewDisposition.REVISE))));
    }
    @Test void cannotRewriteCurrentHypothesisAsHistoryToEscapeCounterEvidence() {
        var old = h(TemporalScope.CURRENT, HypothesisStatus.PROPOSED, List.of(), List.of());
        rejected("HYPOTHESIS_SCOPE_CHANGED", task(old), proposal(List.of(h(TemporalScope.HISTORICAL,HypothesisStatus.PROPOSED,List.of(),List.of())),List.of(review(ReviewDisposition.REVISE))));
    }
    @Test void insufficientReviewMustActuallyLeaveHypothesisUnconfirmed() {
        var old = h(TemporalScope.HISTORICAL, HypothesisStatus.PROPOSED, List.of(), List.of());
        rejected("REVIEW_UPDATE_MISMATCH", task(old), proposal(List.of(),List.of(review(ReviewDisposition.INSUFFICIENT))));
    }
    @Test void maintainingCannotHideStatusChanges() {
        var old = h(TemporalScope.HISTORICAL, HypothesisStatus.PROPOSED, List.of(), List.of());
        rejected("REVIEW_UPDATE_MISMATCH", task(old), proposal(List.of(h(TemporalScope.HISTORICAL,HypothesisStatus.UNCONFIRMED,List.of(),List.of())),List.of(review(ReviewDisposition.MAINTAIN))));
    }
    @Test void missingTemporalFieldsInModelJsonAreRejectedButLegacySnapshotsRemainReadable() {
        var json=JsonMapper.builder().build();
        Hypothesis legacy=json.readValue("""
                {"id":"H1","statement":"old hypothesis","status":"PROPOSED","supportingEvidenceIds":[],"contradictingEvidenceIds":[],"missingEvidence":[]}
                """,Hypothesis.class);
        assertNull(legacy.temporalScope());
        rejected("HYPOTHESIS_SCOPE_REQUIRED",task(null),proposal(List.of(legacy),List.of()));
    }
}

