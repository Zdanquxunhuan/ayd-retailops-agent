package com.yu.retailops.adapter;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.domain.Diagnosis.Outcome;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class InvestigationDeltaTests {
    Hypothesis h = new Hypothesis("H1","此前无响应",HypothesisStatus.PROPOSED,List.of("old"),List.of(),List.of("日志"),TemporalScope.HISTORICAL,"此前",Proposition.OTHER,"明确响应");
    Context context() { return new Context(Environment.SIMULATION,Intent.DIAGNOSE_CAUSE,"why","A001","O100",Instant.now(),Outcome.UNRESOLVED,List.of(),List.of(h),List.of(),List.of(),new Budget(3,3,1000),List.of("new"),null); }
    InvestigationDelta delta(List<InvestigationDelta.Update> updates) { return new InvestigationDelta(Action.NEEDS_HUMAN,"未知",List.of(),updates,null,List.of(),List.of("原因未知")); }
    @Test void incrementalUpdatePreservesDefinitionAndPriorEvidence() {
        var p=delta(List.of(new InvestigationDelta.Update("H1",HypothesisStatus.CONTRADICTED,List.of(),List.of("new"),List.of(),"新日志反驳"))).expand(context());
        var next=p.hypothesisUpdates().get(0);
        assertEquals(h.statement(),next.statement()); assertEquals(h.timeWindow(),next.timeWindow());
        assertEquals(h.falsificationCriterion(),next.falsificationCriterion()); assertEquals(List.of("old"),next.supportingEvidenceIds());
        assertEquals(List.of("new"),next.contradictingEvidenceIds()); assertEquals(ReviewDisposition.REVISE,p.reviews().get(0).disposition());
    }
    @Test void missingReviewCannotBeFilledByProgram() { assertThrows(ModelCallException.class,()->delta(List.of()).expand(context())); }
    @Test void unknownIdCannotReplaceExistingHypothesis() {
        assertThrows(ModelCallException.class,()->delta(List.of(new InvestigationDelta.Update("H2",HypothesisStatus.UNCONFIRMED,List.of(),List.of(),List.of(),"unknown"))).expand(context()));
    }
}
