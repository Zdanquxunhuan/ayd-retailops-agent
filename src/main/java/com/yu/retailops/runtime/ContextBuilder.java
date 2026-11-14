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
                task.evidence.stream().map(ContextBuilder::withProvenance).toList(), List.copyOf(task.investigation.hypotheses),
                task.investigation.turns.stream().filter(Turn::accepted).map(Turn::proposal)
                        .map(Proposal::query).filter(Objects::nonNull).toList(), tools, budget,
                task.evidence.stream().map(Evidence::id)
                        .filter(id -> !task.investigation.reviewedEvidenceIds.contains(id)).toList(), task.agentState.feedback);
    }
    static Evidence withProvenance(Evidence e) {
        String source = switch (e.tool()) {
            case "logs.search", "order.local.query", "order.push.history" -> "PUSH_CENTER";
            case "pos.order.query" -> "POS_DATABASE";
            case "meituan.order.search" -> "MEITUAN_PLATFORM";
            default -> "UNKNOWN";
        };
        var data = new LinkedHashMap<String, Object>(e.result().data());
        data.put("sourceSystem", source);
        data.put("businessStage", switch (e.tool()) {
            case "order.local.query" -> "LOCAL_PERSISTENCE";
            case "pos.order.query" -> "CURRENT_POS_EXISTENCE";
            case "meituan.order.search" -> "PLATFORM_LOOKUP";
            default -> "UNKNOWN";
        });
        data.put("provenanceBasis", "SERVER_TOOL_CONTRACT");
        data.put("limitations", "来源不等于事件目标；日志阶段未知时不能猜测。当前存在性不能单独证明历史因果。模拟证据不证明生产状态。");
        return new Evidence(e.id(), e.tool(), e.query(), new ToolResult(e.result().status(),
                e.result().outcome(), e.result().complete(), data, e.result().errorCode()),
                e.observedAt(), e.validUntil(), e.simulated());
    }
}
