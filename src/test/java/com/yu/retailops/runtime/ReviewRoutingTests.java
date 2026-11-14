package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRoutingTests {
    @Test void contradictionsAndSemanticCorrectionsStillRequireReview() {
        Task task = new Task(); task.investigation = new Trace();
        Proposal p = new Proposal(Action.DIAGNOSIS_READY, "可能原因", List.of(), null,
                List.of(new Claim("可能失败", List.of("e1"), Qualification.HYPOTHESIS)), List.of());
        Hypothesis h = new Hypothesis("H1", "可能失败", HypothesisStatus.UNCONFIRMED,
                List.of(), List.of("e2"), List.of("更多记录"));
        assertFalse(InvestigationRunner.requiresSemanticReview(p, List.of(), task));
        assertTrue(InvestigationRunner.requiresSemanticReview(p, List.of(h), task));
        task.investigation.semanticCorrectionUsed = true;
        assertTrue(InvestigationRunner.requiresSemanticReview(p, List.of(), task));
    }
}
