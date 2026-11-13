package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import java.time.Clock;
import java.util.*;

/** Preserve all cited evidence and counterevidence. Fail closed instead of silently truncating. */
public class ContextBuilder {
    public Context build(Task task, List<ToolDefinition> tools, Budget budget, Clock clock, ReportPolicy reports) {
        StringBuilder goal = new StringBuilder(task.input.userGoal());
        for (var answer : task.agentState.answers)
            goal.append("\n[UNTRUSTED_USER_ANSWER] question=").append(answer.question())
                    .append("; answer=").append(answer.answer());
        long characters = goal.length() + task.evidence.stream().mapToLong(e -> e.toString().length()).sum()
                + task.investigation.hypotheses.toString().length()
                + Objects.toString(task.agentState.feedback, "").length();
        if (characters > 60000) throw new IllegalArgumentException("CONTEXT_LIMIT_EXCEEDED");
        task.investigation.executionEnvironment=task.evidence.stream().anyMatch(e -> !e.simulated())
                ? Environment.MIXED_READ_ONLY : Environment.SIMULATION;
        return new Context(task.investigation.executionEnvironment, task.investigation.intent, goal.toString(),
                task.input.storeId(), task.input.orderId(), clock.instant(), reports.supported(task.evidence),
                List.copyOf(task.evidence), List.copyOf(task.investigation.hypotheses),
                task.investigation.turns.stream().filter(Turn::accepted).map(Turn::proposal)
                        .map(Proposal::query).filter(Objects::nonNull).toList(), tools, budget,
                task.evidence.stream().map(Evidence::id)
                        .filter(id -> !task.investigation.reviewedEvidenceIds.contains(id)).toList(), task.agentState.feedback);
    }
}
