package com.yu.retailops.domain;

import java.time.Instant;
import java.util.*;

/**
 * Framework-independent contracts. All business observations in this milestone are fixtures.
 */
public final class Diagnosis {
    private Diagnosis() {
    }

    public enum Status {CREATED, CLARIFYING, WAITING_USER, PAUSED, PLANNING, EXECUTING_TOOL, REASONING, COMPLETED, NEEDS_HUMAN, FAILED, CANCELLED}

    public enum Outcome {ALREADY_SYNCED, REPUSH_RECOMMENDED, RESTORE_RECOMMENDED, OUTSIDE_SEARCH_WINDOW, UNRESOLVED}

    public enum ResultStatus {SUCCESS, NOT_FOUND, TIMEOUT, ERROR, PARTIAL, DENIED, UNKNOWN}

    public enum DecisionType {CALL_TOOL, FINISH, NEEDS_HUMAN}

    public record Input(String userGoal, String storeId, String orderId, String fixtureId, String strategy,
                        Investigation.Intent intent) {
        public Input(String userGoal, String storeId, String orderId, String fixtureId, String strategy) {
            this(userGoal, storeId, orderId, fixtureId, strategy, null);
        }
    }

    public record Arguments(String storeId, String orderId) {
    }

    public record ToolResult(ResultStatus status, String outcome, boolean complete, Map<String, Object> data,
                             String errorCode) {
    }

    public record Evidence(String id, String tool, Arguments query, ToolResult result, Instant observedAt,
                           Instant validUntil, boolean simulated) {
    }

    public record Decision(DecisionType type, String tool, Arguments arguments, Outcome outcome,
                           List<String> evidenceIds, String reasonSummary) {
        public static Decision call(String tool, Input input, String reason) {
            return new Decision(DecisionType.CALL_TOOL, tool, new Arguments(input.storeId(), input.orderId()), null, List.of(), reason);
        }

        public static Decision finish(Outcome outcome, List<Evidence> evidence, String reason) {
            return new Decision(DecisionType.FINISH, null, null, outcome, evidence.stream().map(Evidence::id).toList(), reason);
        }
    }

    public record Event(long sequence, Instant at, Status status, String code, String summary) {
    }

    public record Report(Outcome outcome, String summary, List<String> evidenceIds, String rootCauseStatus,
                         List<String> limitations, String mode, String dataSource) {
    }

    public record ModelReply(Decision decision, long promptTokens, long completionTokens) {
    }

    public static class Task {
        public String id;
        public Input input;
        public Status status;
        public long version;
        public Instant createdAt;
        public Instant updatedAt;
        public String provider;
        public String modelVersion;
        public String promptVersion = "order-diagnosis-v3";
        public String schemaVersion = "decision-v1";
        public int decisions;
        public int toolCalls;
        public long promptTokens;
        public long completionTokens;
        public long activeMillis;
        public List<Evidence> evidence = new ArrayList<>();
        public List<Decision> planHistory = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public Report report;
        public Investigation.Trace investigation;
        public AgentState agentState = new AgentState();

        public boolean terminal() {
            return Set.of(Status.COMPLETED, Status.NEEDS_HUMAN, Status.FAILED, Status.CANCELLED).contains(status);
        }
    }
}

