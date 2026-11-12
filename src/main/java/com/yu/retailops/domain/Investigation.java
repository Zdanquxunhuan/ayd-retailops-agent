package com.yu.retailops.domain;

import com.yu.retailops.domain.Diagnosis.*;

import java.time.Instant;
import java.util.*;

/**
 * Model proposals are deliberately separate from authoritative tool observations.
 */
public final class Investigation {
    private Investigation() {
    }

    public enum Intent {CHECK_STATUS, DIAGNOSE_CAUSE}

    public enum Environment {SIMULATION}

    public enum Action {QUERY, DIAGNOSIS_READY, NEEDS_HUMAN}

    public enum HypothesisStatus {PROPOSED, SUPPORTED, CONTRADICTED, UNCONFIRMED}

    public enum Qualification {HYPOTHESIS, UNKNOWN}
    public enum TemporalScope { CURRENT, HISTORICAL }
    public enum Proposition { POS_ABSENT, POS_PRESENT, OTHER }
    public enum ReviewDisposition { MAINTAIN, REVISE, INSUFFICIENT }
    public record Review(String hypothesisId, List<String> assessedEvidenceIds,
                         ReviewDisposition disposition, String reason) {}

    public record Hypothesis(String id, String statement, HypothesisStatus status,
                             List<String> supportingEvidenceIds, List<String> contradictingEvidenceIds,
                             List<String> missingEvidence, TemporalScope temporalScope, String timeWindow,
                             Proposition proposition, String falsificationCriterion) {
        public Hypothesis(String id, String statement, HypothesisStatus status, List<String> supportingEvidenceIds,
                          List<String> contradictingEvidenceIds, List<String> missingEvidence) {
            this(id, statement, status, supportingEvidenceIds, contradictingEvidenceIds, missingEvidence,
                    TemporalScope.CURRENT, "当前调查时点", Proposition.OTHER, "取得与该命题相反的业务观察");
        }
    }

    public record Query(String tool, Arguments arguments, String hypothesisId, String questionToVerify) {
    }

    public record Claim(String text, List<String> evidenceIds, Qualification qualification) {
    }

    public record Proposal(Action action, String reasonSummary, List<Hypothesis> hypothesisUpdates,
                           Query query, List<Claim> claims, List<String> unresolvedQuestions, List<Review> reviews) {
        public Proposal(Action action, String reasonSummary, List<Hypothesis> hypothesisUpdates,
                        Query query, List<Claim> claims, List<String> unresolvedQuestions) {
            this(action, reasonSummary, hypothesisUpdates, query, claims, unresolvedQuestions, List.of());
        }
    }

    public record Reply(Proposal proposal, long promptTokens, long completionTokens) {
    }

    public record ToolDefinition(String name, String purpose) {
    }

    public record Budget(int remainingDecisions, int remainingTools, long remainingMillis) {
    }
    public record ValidationFeedback(String code, String instruction, Proposal rejectedProposal) {}

    public record Context(Environment executionEnvironment, Intent intent, String userGoal,
                          String storeId, String orderId, Instant now, Outcome currentBusinessOutcome,
                          List<Evidence> evidence, List<Hypothesis> hypotheses, List<Query> previousQueries,
                          List<ToolDefinition> availableTools, Budget budget, List<String> newEvidenceIds,
                          ValidationFeedback validationFeedback) {
    }

    public record Turn(int round, Instant at, Proposal proposal, boolean accepted, String validationCode) {
    }

    public record Revision(int round, Instant at, Hypothesis hypothesis) {
    }

    public static class Trace {
        public Environment executionEnvironment = Environment.SIMULATION;
        public Intent intent;
        public List<Hypothesis> hypotheses = new ArrayList<>();
        public List<Revision> hypothesisHistory = new ArrayList<>();
        public List<Turn> turns = new ArrayList<>();
        public List<Claim> claims = new ArrayList<>();
        public List<String> reviewedEvidenceIds = new ArrayList<>();
        public List<String> unresolvedQuestions = new ArrayList<>();
        public String rootCauseStatus = "UNCONFIRMED";
        public String explanationNotice = "以下为模型提出的调查假设；Java 仅校验引用和动作约束，不确认自然语言因果关系。";
        public String stopCode;
    }
}
