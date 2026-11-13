package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.tool.ReadOnlyGateway;

import java.time.Clock;
import java.util.*;

/**
 * Validates provenance and shape, not the truth of arbitrary natural-language causal claims.
 */
public class InvestigationPolicy {
    private final ReadOnlyGateway gateway;
    private final Clock clock;

    public InvestigationPolicy(ReadOnlyGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    public List<Hypothesis> validate(Proposal p, Task task) {
        require(p != null && p.action() != null && text(p.reasonSummary(), 1500), "INVALID_INVESTIGATION");
        require(p.hypothesisUpdates() != null && p.hypothesisUpdates().size() <= 6
                && p.claims() != null && p.claims().size() <= 6 && strings(p.unresolvedQuestions(), 8), "INVALID_INVESTIGATION");
        Map<String, Hypothesis> merged = new LinkedHashMap<>();
        task.investigation.hypotheses.forEach(h -> merged.put(h.id(), h));
        Set<String> updated = new HashSet<>();
        for (Hypothesis h : p.hypothesisUpdates()) {
            require(h != null && h.id() != null && h.id().matches("H[1-9][0-9]?") && updated.add(h.id())
                    && text(h.statement(), 600) && h.status() != null && strings(h.missingEvidence(), 8), "INVALID_HYPOTHESIS");
            refs(h.supportingEvidenceIds(), task);
            refs(h.contradictingEvidenceIds(), task);
            require(Collections.disjoint(h.supportingEvidenceIds(), h.contradictingEvidenceIds()), "INVALID_HYPOTHESIS");
            Hypothesis old = merged.get(h.id());
            if (old != null) {
                require(old.statement().equals(h.statement())
                        && h.supportingEvidenceIds().containsAll(old.supportingEvidenceIds())
                        && h.contradictingEvidenceIds().containsAll(old.contradictingEvidenceIds()), "HYPOTHESIS_HISTORY_LOSS");
            }
            if (h.status() == HypothesisStatus.SUPPORTED) {
                require(!h.supportingEvidenceIds().isEmpty() && h.contradictingEvidenceIds().isEmpty(), "INVALID_HYPOTHESIS");
                usable(h.supportingEvidenceIds(), task);
            }
            if (h.status() == HypothesisStatus.CONTRADICTED) {
                require(!h.contradictingEvidenceIds().isEmpty(), "INVALID_HYPOTHESIS");
                usable(h.contradictingEvidenceIds(), task);
            }
            merged.put(h.id(), h);
        }
        require(merged.size() <= 6, "INVALID_HYPOTHESIS");
        if ("investigation-v2".equals(task.schemaVersion)) new HypothesisReviewPolicy(clock).validate(p, task, merged);
        for (Claim claim : p.claims()) {
            require(claim != null && text(claim.text(), 1500) && claim.qualification() != null, "INVALID_CLAIM");
            refs(claim.evidenceIds(), task);
            if (claim.qualification() == Qualification.HYPOTHESIS)
                require(!claim.evidenceIds().isEmpty(), "INVALID_CLAIM");
        }
        if (p.action() == Action.QUERY) {
            Query q = p.query();
            require(q != null && q.tool() != null && q.arguments() != null && q.hypothesisId() != null && merged.containsKey(q.hypothesisId())
                    && text(q.questionToVerify(), 600) && p.claims().isEmpty(), "INVALID_INVESTIGATION_QUERY");
            gateway.validate(task.input, new Decision(DecisionType.CALL_TOOL, q.tool(), q.arguments(), null, List.of(), q.questionToVerify()));
        } else {
            require(p.query() == null, "INVALID_INVESTIGATION");
            if (p.action() == Action.NEEDS_HUMAN || p.action() == Action.ASK_USER)
                require(!p.unresolvedQuestions().isEmpty(), "INVALID_INVESTIGATION");
            if (p.action() == Action.ASK_USER) require(p.claims().isEmpty(), "INVALID_INVESTIGATION");
            if (p.action() == Action.DIAGNOSIS_READY) {
                require(task.evidence.stream().anyMatch(e -> Set.of(ReadOnlyGateway.LOGS, ReadOnlyGateway.PUSH_HISTORY).contains(e.tool()))
                        && p.claims().stream().anyMatch(c -> c.qualification() == Qualification.HYPOTHESIS), "INSUFFICIENT_INVESTIGATION");
                int lastPos = -1, lastInvestigation = -1;
                for (int i = 0; i < task.evidence.size(); i++) {
                    String tool = task.evidence.get(i).tool();
                    if (ReadOnlyGateway.POS.equals(tool)) lastPos = i;
                    if (Set.of(ReadOnlyGateway.LOGS, ReadOnlyGateway.PUSH_HISTORY).contains(tool))
                        lastInvestigation = i;
                }
                Evidence pos = lastPos < 0 ? null : task.evidence.get(lastPos);
                boolean found = pos != null && pos.result().complete() && pos.result().status() == ResultStatus.SUCCESS
                        && "FOUND".equals(pos.result().outcome()) && pos.validUntil().isAfter(clock.instant());
                // Conservative final-status gate, independent of model interpretation of log contents.
                require(found || lastPos > lastInvestigation, "POS_RECHECK_REQUIRED");
                // Citing a failed/incomplete observation cannot turn it into positive investigative evidence.
                for (Claim claim : p.claims())
                    if (claim.qualification() == Qualification.HYPOTHESIS) usable(claim.evidenceIds(), task);
            }
        }
        return List.copyOf(merged.values());
    }

    private void refs(List<String> refs, Task task) {
        require(refs != null && refs.size() <= 24 && new HashSet<>(refs).size() == refs.size()
                && refs.stream().allMatch(id -> task.evidence.stream().anyMatch(e -> e.id().equals(id))), "INVALID_EVIDENCE_REFERENCE");
    }

    private void usable(List<String> refs, Task task) {
        require(refs.stream().allMatch(id -> task.evidence.stream().anyMatch(e -> e.id().equals(id)
                && !(e.simulated() && task.evidence.stream().anyMatch(observation -> !observation.simulated()
                    && Set.of(ReadOnlyGateway.LOCAL,ReadOnlyGateway.POS).contains(observation.tool())))
                && e.validUntil().isAfter(clock.instant()) && (usableSnapshot(e) || (e.result().complete()
                && Set.of(ResultStatus.SUCCESS, ResultStatus.NOT_FOUND).contains(e.result().status())))
                && hasHistoricalObservations(e))), "UNUSABLE_HYPOTHESIS_EVIDENCE");
    }
    // A partial search can retain intact positive observations; it cannot establish absence.
    private boolean usableSnapshot(Evidence e) {
        if (!ReadOnlyGateway.LOGS.equals(e.tool()) || e.simulated()
                || !"LOG_SNAPSHOT".equals(e.result().outcome())
                || !Set.of(ResultStatus.SUCCESS, ResultStatus.PARTIAL).contains(e.result().status())) return false;
        Object value=e.result().data().get("lines");
        return value instanceof List<?> lines && !lines.isEmpty() && lines.stream().allMatch(item ->
            item instanceof Map<?,?> row && Boolean.TRUE.equals(row.get("eventComplete"))
                && Set.of("ORDER_ID", "TRACE_ID").contains(row.get("correlation")));
    }
    private boolean hasHistoricalObservations(Evidence evidence) {
        // A successful search with no retained records is not evidence that an event never occurred.
        String field = ReadOnlyGateway.PUSH_HISTORY.equals(evidence.tool()) ? "records"
                : ReadOnlyGateway.LOGS.equals(evidence.tool()) ? "lines" : null;
        if (field == null) return true;
        Object observations = evidence.result().data().get(field);
        return !(observations instanceof List<?> items) || !items.isEmpty();
    }

    private boolean text(String s, int max) {
        return s != null && !s.isBlank() && s.length() <= max;
    }

    private boolean strings(List<String> items, int max) {
        return items != null && items.size() <= max && items.stream().allMatch(s -> text(s, 600));
    }

    private void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
}

