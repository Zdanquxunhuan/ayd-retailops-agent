package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import java.time.Clock;
import java.util.*;
import static com.yu.retailops.tool.ReadOnlyGateway.*;

/** Completeness and narrow typed facts only; free-text causal interpretation remains the model's job. */
public class HypothesisReviewPolicy {
    private final Clock clock;
    public HypothesisReviewPolicy(Clock clock) { this.clock = clock; }
    public void validate(Proposal p, Task task, Map<String, Hypothesis> merged) {
        Map<String, Hypothesis> old = new LinkedHashMap<>();
        task.investigation.hypotheses.forEach(h -> old.put(h.id(), h));
        Set<String> newIds = new HashSet<>(task.evidence.stream().map(Evidence::id).toList());
        newIds.removeAll(task.investigation.reviewedEvidenceIds);
        require(p.reviews() != null && p.reviews().size() == old.size(), "HYPOTHESIS_REVIEW_REQUIRED");
        Set<String> seen = new HashSet<>();
        for (Review r : p.reviews()) {
            require(r != null && old.containsKey(r.hypothesisId()) && seen.add(r.hypothesisId())
                    && r.disposition() != null && text(r.reason()), "INVALID_HYPOTHESIS_REVIEW");
            require(r.assessedEvidenceIds() != null && r.assessedEvidenceIds().size() == newIds.size()
                    && new HashSet<>(r.assessedEvidenceIds()).equals(newIds), "NEW_EVIDENCE_NOT_REVIEWED");
            Hypothesis before = old.get(r.hypothesisId()), after = merged.get(r.hypothesisId());
            boolean changed = !before.equals(after);
            require(r.disposition() != ReviewDisposition.MAINTAIN || !changed, "REVIEW_UPDATE_MISMATCH");
            require(r.disposition() != ReviewDisposition.REVISE || changed, "REVIEW_UPDATE_MISMATCH");
            require(r.disposition() != ReviewDisposition.INSUFFICIENT
                    || (after.status() == HypothesisStatus.UNCONFIRMED && !after.missingEvidence().isEmpty()), "REVIEW_UPDATE_MISMATCH");
        }
        for (Hypothesis h : merged.values()) {
            require(h.temporalScope() != null && h.proposition() != null && text(h.timeWindow()) && text(h.falsificationCriterion()), "HYPOTHESIS_SCOPE_REQUIRED");
            Hypothesis before = old.get(h.id());
            if (before != null) require(before.temporalScope() == h.temporalScope() && before.proposition() == h.proposition()
                    && Objects.equals(before.timeWindow(), h.timeWindow())
                    && Objects.equals(before.falsificationCriterion(), h.falsificationCriterion()), "HYPOTHESIS_SCOPE_CHANGED");
            if (h.temporalScope() == TemporalScope.HISTORICAL && (h.status() == HypothesisStatus.SUPPORTED || h.status() == HypothesisStatus.CONTRADICTED)) {
                List<String> decisive = h.status() == HypothesisStatus.SUPPORTED ? h.supportingEvidenceIds() : h.contradictingEvidenceIds();
                require(task.evidence.stream().anyMatch(e -> decisive.contains(e.id()) && Set.of(LOGS, PUSH_HISTORY).contains(e.tool())), "HISTORICAL_EVIDENCE_REQUIRED");
            }
            if (h.temporalScope() == TemporalScope.CURRENT && h.proposition() != Proposition.OTHER) {
                Evidence pos = ReportPolicy.latest(task.evidence, POS);
                boolean usable = pos != null && pos.result().complete() && pos.validUntil().isAfter(clock.instant());
                boolean found = usable && pos.result().status() == ResultStatus.SUCCESS && "FOUND".equals(pos.result().outcome());
                boolean absent = usable && pos.result().status() == ResultStatus.NOT_FOUND && "ABSENT".equals(pos.result().outcome());
                boolean opposite = h.proposition() == Proposition.POS_ABSENT ? found : absent;
                if (opposite) require(h.status() == HypothesisStatus.CONTRADICTED && h.contradictingEvidenceIds().contains(pos.id()), "LATEST_FACT_CONTRADICTS_HYPOTHESIS");
                if (h.status() == HypothesisStatus.SUPPORTED) require((found || absent) && !opposite
                        && h.supportingEvidenceIds().contains(pos.id()), "LATEST_FACT_REQUIRED");
            }
        }
    }
    private boolean text(String s) { return s != null && !s.isBlank() && s.length() <= 600; }
    private void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
}
