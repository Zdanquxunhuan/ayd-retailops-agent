package com.yu.retailops.runtime;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.tool.ReadOnlyGateway;
import com.yu.retailops.adapter.ModelCallException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class DiagnosisRunner implements AutoCloseable {
    private final ReadOnlyGateway gateway;
    private final ReportPolicy policy;
    private final Clock clock;
    private final int maxDecisions, maxTools;
    private final long timeoutMillis;
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-call");
        t.setDaemon(true);
        return t;
    });

    public DiagnosisRunner(ReadOnlyGateway gateway, ReportPolicy policy, Clock clock, int maxDecisions, int maxTools, int seconds) {
        if (maxDecisions < 1 || maxTools < 1 || seconds < 1) throw new IllegalArgumentException("Invalid budget");
        this.gateway = gateway;
        this.policy = policy;
        this.clock = clock;
        this.maxDecisions = maxDecisions;
        this.maxTools = maxTools;
        timeoutMillis = seconds * 1000L;
    }

    public void event(Task task, Status state, String code, String summary, Consumer<Task> checkpoint) {
        task.status = state;
        task.updatedAt = clock.instant();
        task.version++;
        task.events.add(new Event(task.events.size() + 1L, task.updatedAt, state, code, summary));
        checkpoint.accept(task);
    }

    public void unresolved(Task task, Status state, String code, Consumer<Task> checkpoint) {
        if (task.investigation != null) {
            task.investigation.stopCode = code;
            task.investigation.unresolvedQuestions = List.of("调查中断，停止原因：" + code);
        }
        task.report = policy.validate(Decision.finish(task.investigation == null ? Outcome.UNRESOLVED : policy.supported(task.evidence), task.evidence, code), task.evidence);
        event(task, state, code, "只读调查停止，保留当前证据；未执行修复。", checkpoint);
    }

    public void run(Task task, Planner planner, Consumer<Task> checkpoint, BooleanSupplier cancelled) {
        if ("investigation".equals(task.input.strategy())) {
            new InvestigationRunner(gateway, policy, clock, maxDecisions, maxTools, timeoutMillis, this, modelExecutor)
                    .run(task, planner, checkpoint, cancelled);
            return;
        }
        long start = System.nanoTime();
        long previousMillis = task.activeMillis;
        try {
            gateway.authorize(task.input);
            while (true) {
                task.activeMillis = previousMillis + (System.nanoTime() - start) / 1_000_000;
                if (cancelled.getAsBoolean()) {
                    unresolved(task, Status.CANCELLED, "USER_CANCELLED", checkpoint);
                    return;
                }
                if (task.decisions >= maxDecisions || task.activeMillis >= timeoutMillis) {
                    unresolved(task, Status.NEEDS_HUMAN, "BUDGET_EXHAUSTED", checkpoint);
                    return;
                }
                task.decisions++;
                event(task, Status.PLANNING, "DECISION_STARTED", "选择下一步调查；决策预算已扣减。", checkpoint);
                Planner.Context context = new Planner.Context(task.input.userGoal(), task.input.storeId(), task.input.orderId(),
                        List.copyOf(task.evidence), maxTools - task.toolCalls);
                Future<ModelReply> future = modelExecutor.submit(() -> planner.next(context));
                ModelReply reply;
                try {
                    reply = future.get(Math.min(30000, Math.max(1, timeoutMillis - task.activeMillis)), TimeUnit.MILLISECONDS);
                } finally {
                    if (!future.isDone()) future.cancel(true);
                }
                task.promptTokens += Math.max(0, reply.promptTokens());
                task.completionTokens += Math.max(0, reply.completionTokens());
                if (cancelled.getAsBoolean()) {
                    unresolved(task, Status.CANCELLED, "USER_CANCELLED", checkpoint);
                    return;
                }
                Decision d = reply.decision();
                // Retain parsed proposals, even when rejected, to distinguish contract errors from poor planning.
                // These are model proposals, never tool observations or approved actions.
                if (d != null) task.planHistory.add(d);
                validateShape(d);
                if (d.type() != DecisionType.CALL_TOOL) {
                    task.report = policy.validate(d, task.evidence);
                    event(task, d.outcome() == Outcome.UNRESOLVED ? Status.NEEDS_HUMAN : Status.COMPLETED,
                            "REPORT_READY", task.report.summary(), checkpoint);
                    return;
                }
                if (task.toolCalls >= maxTools) {
                    unresolved(task, Status.NEEDS_HUMAN, "TOOL_BUDGET_EXHAUSTED", checkpoint);
                    return;
                }
                var prior = task.evidence.stream().filter(e -> e.tool().equals(d.tool())).toList();
                if (prior.size() >= 3 || (prior.size() >= 2 && prior.get(prior.size() - 1).result().equals(prior.get(prior.size() - 2).result()))) {
                    unresolved(task, Status.NEEDS_HUMAN, "NO_PROGRESS", checkpoint);
                    return;
                }
                task.toolCalls++;
                event(task, Status.EXECUTING_TOOL, "TOOL_STARTED", d.tool(), checkpoint);
                task.evidence.add(gateway.invoke(task.input, d, task.evidence));
                event(task, Status.REASONING, "EVIDENCE_RECORDED", d.tool(), checkpoint);
            }
        } catch (SecurityException e) {
            unresolved(task, Status.NEEDS_HUMAN, "POLICY_DENIED", checkpoint);
        } catch (IllegalArgumentException e) {
            String code = Set.of("INVALID_DECISION", "INVALID_CALL", "INVALID_FINISH", "INVALID_REPORT",
                            "INVALID_EVIDENCE_REFERENCE", "UNSUPPORTED_CONCLUSION", "LATEST_EVIDENCE_REQUIRED")
                    .contains(Objects.toString(e.getMessage(), "")) ? e.getMessage() : "INVALID_DECISION";
            unresolved(task, Status.NEEDS_HUMAN, code, checkpoint);
        } catch (TimeoutException e) {
            unresolved(task, Status.NEEDS_HUMAN, "MODEL_TIMEOUT", checkpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unresolved(task, Status.NEEDS_HUMAN, "INTERRUPTED", checkpoint);
        } catch (ExecutionException e) {
            String code = e.getCause() instanceof ModelCallException failure ? failure.code() : "MODEL_ERROR";
            unresolved(task, Status.FAILED, code, checkpoint);
        } finally {
            task.activeMillis = previousMillis + (System.nanoTime() - start) / 1_000_000;
            checkpoint.accept(task);
        }
    }

    private void validateShape(Decision d) {
        if (d == null || d.type() == null || d.reasonSummary() == null || d.reasonSummary().length() > 2000)
            throw new IllegalArgumentException("INVALID_DECISION");
        if (d.type() == DecisionType.CALL_TOOL) {
            if (d.tool() == null || d.arguments() == null || d.outcome() != null || d.evidenceIds() == null || !d.evidenceIds().isEmpty())
                throw new IllegalArgumentException("INVALID_CALL");
        } else if (d.tool() != null || d.arguments() != null || d.outcome() == null || d.evidenceIds() == null
                || (d.type() == DecisionType.NEEDS_HUMAN && d.outcome() != Outcome.UNRESOLVED))
            throw new IllegalArgumentException("INVALID_FINISH");
    }

    public void close() {
        modelExecutor.shutdownNow();
    }
}
