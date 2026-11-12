package com.yu.retailops.adapter;

import com.yu.retailops.domain.Investigation.*;
import java.util.*;

/** Wire contract: immutable definitions are sent once; subsequent updates contain only judgments. */
public record InvestigationDelta(Action action, String reasonSummary, List<Hypothesis> newHypotheses,
                                 List<Update> updates, Query query, List<Claim> claims, List<String> unresolvedQuestions) {
    public record Update(String id, HypothesisStatus status, List<String> addSupportingEvidenceIds,
                         List<String> addContradictingEvidenceIds, List<String> missingEvidence, String reason) {}
    public Proposal expand(Context context) {
        if (newHypotheses == null || updates == null) throw new ModelCallException("MODEL_DELTA_INVALID");
        Map<String,Hypothesis> old = new LinkedHashMap<>();
        context.hypotheses().forEach(h -> old.put(h.id(),h));
        Set<String> seen = new HashSet<>();
        List<Hypothesis> changes = new ArrayList<>();
        List<Review> reviews = new ArrayList<>();
        for (Hypothesis h : newHypotheses) {
            if (h == null || old.containsKey(h.id()) || !seen.add(h.id())) throw new ModelCallException("MODEL_DELTA_INVALID");
            changes.add(h);
        }
        Set<String> updated = new HashSet<>();
        for (Update u : updates) {
            if (u == null || !old.containsKey(u.id()) || !updated.add(u.id()) || u.reason() == null || u.reason().isBlank()
                    || u.addSupportingEvidenceIds() == null || u.addContradictingEvidenceIds() == null || u.missingEvidence() == null)
                throw new ModelCallException("MODEL_DELTA_INVALID");
            Hypothesis h = old.get(u.id());
            Hypothesis next = new Hypothesis(h.id(),h.statement(),u.status(),union(h.supportingEvidenceIds(),u.addSupportingEvidenceIds()),
                    union(h.contradictingEvidenceIds(),u.addContradictingEvidenceIds()),u.missingEvidence(),h.temporalScope(),h.timeWindow(),h.proposition(),h.falsificationCriterion());
            changes.add(next);
            reviews.add(new Review(h.id(),context.newEvidenceIds(),next.equals(h) ? ReviewDisposition.MAINTAIN : ReviewDisposition.REVISE,u.reason()));
        }
        if (!updated.equals(old.keySet())) throw new ModelCallException("MODEL_DELTA_REVIEW_MISSING");
        return new Proposal(action,reasonSummary,changes,query,claims,unresolvedQuestions,reviews);
    }
    private List<String> union(List<String> a,List<String> b) {
        var ids = new LinkedHashSet<>(a); ids.addAll(b); return new ArrayList<>(ids);
    }
}
