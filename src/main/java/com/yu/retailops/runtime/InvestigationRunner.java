package com.yu.retailops.runtime;

import com.yu.retailops.adapter.ModelCallException;
import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.domain.Investigation.*;
import com.yu.retailops.tool.ReadOnlyGateway;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static com.yu.retailops.tool.ReadOnlyGateway.*;

/**
 * Java collects facts; the model alone proposes the investigative sequence. No scripted model fallback.
 */
public class InvestigationRunner {
    private final ReadOnlyGateway gateway;
    private final ReportPolicy reports;
    private final InvestigationPolicy proposals;
    private final Clock clock;
    private final int maxDecisions, maxTools;
    private final long timeoutMillis;
    private final DiagnosisRunner events;
    private final ExecutorService executor;
    public InvestigationRunner(ReadOnlyGateway gateway, ReportPolicy reports, Clock clock, int maxDecisions,
                               int maxTools, long timeoutMillis, DiagnosisRunner events, ExecutorService executor) {
        this.gateway = gateway;
        this.reports = reports;
        this.clock = clock;
        this.proposals = new InvestigationPolicy(gateway, clock);
        this.maxDecisions = Math.min(6, maxDecisions);
        this.maxTools = maxTools;
        this.timeoutMillis = timeoutMillis;
        this.events = events;
        this.executor = executor;
    }

    public void run(Task task, Planner planner, Consumer<Task> checkpoint, BooleanSupplier cancelled) {
        long start = System.nanoTime(), previousMillis = task.activeMillis;
        task.promptVersion = "order-investigation-v10";
        task.schemaVersion = "investigation-v2";
        if (task.investigation == null) {
            task.investigation = new Trace();
            task.investigation.intent = task.input.intent() == null ? Intent.DIAGNOSE_CAUSE : task.input.intent();
        }
        Consumer<Task> originalCheckpoint = checkpoint;
        checkpoint = t -> {
            t.activeMillis = previousMillis + (System.nanoTime() - start) / 1_000_000;
            originalCheckpoint.accept(t);
        };
        try {
            gateway.authorize(task.input);
            check(task, start, previousMillis, cancelled);
            if (task.agentState.pendingQuery != null) {
                Query pending = task.agentState.pendingQuery;
                invoke(task, pending.tool(), pending.questionToVerify(), checkpoint);
            }
            check(task, start, previousMillis, cancelled);
            if (!fresh(task, POS)) invoke(task, POS, "Java 基础状态采集", checkpoint);
            if (task.investigation.intent != Intent.CHECK_STATUS || reports.supported(task.evidence) != Outcome.ALREADY_SYNCED) {
                check(task, start, previousMillis, cancelled);
                if (!fresh(task, LOCAL)) invoke(task, LOCAL, "Java 基础状态采集", checkpoint);
                Evidence local = ReportPolicy.latest(task.evidence, LOCAL);
                if (local.result().complete() && local.result().status() == ResultStatus.NOT_FOUND && "ABSENT".equals(local.result().outcome())) {
                    check(task, start, previousMillis, cancelled);
                    if (!fresh(task, PLATFORM)) invoke(task, PLATFORM, "Java 基础状态采集", checkpoint);
                }
            }
            check(task, start, previousMillis, cancelled);
            if (task.investigation.intent == Intent.CHECK_STATUS) {
                finish(task, reports.supported(task.evidence) == Outcome.UNRESOLVED ? Status.NEEDS_HUMAN : Status.COMPLETED,
                        "STATUS_CHECK_READY", checkpoint);
                return;
            }
            if (!(planner instanceof InvestigationPlanner investigator)) {
                task.investigation.unresolvedQuestions = List.of("未配置真实调查模型；基础业务状态已保留，请使用 qwen 或 remote provider。");
                finish(task, Status.NEEDS_HUMAN, "INVESTIGATION_MODEL_NOT_CONFIGURED", checkpoint);
                return;
            }
            task.agentState.nextStep = "PLAN";
            while (true) {
                check(task, start, previousMillis, cancelled);
                if (task.decisions >= maxDecisions) throw new Stop("BUDGET_EXHAUSTED");
                task.decisions++;
                events.event(task, Status.PLANNING, "INVESTIGATION_STARTED", "模型更新假设并选择下一项调查。", checkpoint);
                Context context = new ContextBuilder().build(task, gateway.definitions(),
                        new Budget(maxDecisions - task.decisions, maxTools - task.toolCalls,
                                timeoutMillis - task.activeMillis), clock, reports);
                Future<Reply> future = executor.submit(() -> investigator.investigate(context));
                Reply reply;
                try {
                    reply = future.get(Math.min(30000, Math.max(1, timeoutMillis - task.activeMillis)), TimeUnit.MILLISECONDS);
                } finally {
                    if (!future.isDone()) future.cancel(true);
                }
                if (reply == null) throw new Stop("INVALID_INVESTIGATION");
                task.promptTokens += Math.max(0, reply.promptTokens());
                task.completionTokens += Math.max(0, reply.completionTokens());
                check(task, start, previousMillis, cancelled);
                Proposal proposal = reply.proposal();
                List<Hypothesis> hypotheses;
                try {
                    hypotheses = proposals.validate(proposal, task);
                } catch (IllegalArgumentException | SecurityException invalid) {
                    String code = invalid instanceof SecurityException ? "POLICY_DENIED" : invalid.getMessage();
                    task.investigation.turns.add(new Turn(task.decisions, clock.instant(), proposal, false, code));
                    String correction = switch (code) {
                        case "INVALID_INVESTIGATION_QUERY" -> "QUERY 必须引用已存在或本轮完整新增的假设 id；补齐 hypothesisUpdates，并检查 query 字段。";
                        case "HYPOTHESIS_REVIEW_REQUIRED" -> "reviews 必须逐项覆盖上下文的所有旧假设，并完整引用 newEvidenceIds。";
                        case "POS_RECHECK_REQUIRED" -> "先提出 pos.order.query 复查当前状态，不能结束；保留旧假设定义并重新提交本轮复核。";
                        default -> null;
                    };
                    if (!task.agentState.correctionUsed && correction != null && task.decisions < maxDecisions) {
                        task.agentState.correctionUsed = true;
                        task.agentState.feedback = new ValidationFeedback(code, correction, proposal);
                        events.event(task, Status.REASONING, "MODEL_CORRECTION_REQUESTED", "提议被拒绝，允许一次受预算约束的纠正；未执行动作或更新假设。", checkpoint);
                        continue;
                    }
                    throw new Stop(code);
                }
                task.agentState.feedback = null;
                task.investigation.turns.add(new Turn(task.decisions, clock.instant(), proposal, true, "ACCEPTED"));
                task.investigation.hypotheses = new ArrayList<>(hypotheses);
                task.investigation.reviewedEvidenceIds = task.evidence.stream().map(Evidence::id).toList();
                for (Hypothesis h : proposal.hypothesisUpdates())
                    task.investigation.hypothesisHistory.add(new Revision(task.decisions, clock.instant(), h));
                task.investigation.unresolvedQuestions = List.copyOf(proposal.unresolvedQuestions());
                task.agentState.pendingQuery = proposal.query();
                task.agentState.nextStep = proposal.action().name();
                if (proposal.action() != Action.ASK_USER)
                    events.event(task, Status.REASONING, "HYPOTHESES_RECORDED", "已记录模型假设及引用；根因未确认。", checkpoint);
                if (proposal.action() == Action.ASK_USER) {
                    task.agentState.question = String.join("；", proposal.unresolvedQuestions());
                    task.agentState.waitingUntil = clock.instant().plusSeconds(86400);
                    task.agentState.nextStep = "WAITING_USER";
                    events.event(task, Status.WAITING_USER, "USER_INPUT_REQUIRED", task.agentState.question, checkpoint);
                    return;
                }
                if (proposal.action() != Action.QUERY) {
                    task.investigation.claims = List.copyOf(proposal.claims());
                    finish(task, proposal.action() == Action.NEEDS_HUMAN ? Status.NEEDS_HUMAN : Status.COMPLETED,
                            proposal.action() == Action.NEEDS_HUMAN ? "INVESTIGATION_NEEDS_HUMAN" : "INVESTIGATION_READY", checkpoint);
                    return;
                }
                check(task, start, previousMillis, cancelled);
                invoke(task, proposal.query().tool(), proposal.query().questionToVerify(), checkpoint);
            }
        } catch (Stop stop) {
            finish(task, "USER_CANCELLED".equals(stop.getMessage()) ? Status.CANCELLED : Status.NEEDS_HUMAN, stop.getMessage(), checkpoint);
        } catch (SecurityException denied) {
            finish(task, Status.NEEDS_HUMAN, "POLICY_DENIED", checkpoint);
        } catch (TimeoutException timeout) {
            finish(task, Status.NEEDS_HUMAN, "MODEL_TIMEOUT", checkpoint);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            events.event(task, Status.PAUSED, "WORKER_INTERRUPTED", "执行被中断，保留现场等待恢复。", checkpoint);
        } catch (ExecutionException failed) {
            finish(task, Status.NEEDS_HUMAN, failed.getCause() instanceof ModelCallException model ? model.code() : "MODEL_ERROR", checkpoint);
        } catch (RuntimeException failure) {
            if (failure instanceof java.io.UncheckedIOException) throw failure;
            finish(task, Status.FAILED, "CONTEXT_LIMIT_EXCEEDED".equals(failure.getMessage())
                    ? "CONTEXT_LIMIT_EXCEEDED" : "RUNTIME_ERROR", checkpoint);
        } finally {
            task.activeMillis = previousMillis + (System.nanoTime() - start) / 1_000_000;
            checkpoint.accept(task);
        }
    }

    private void check(Task task, long start, long previous, BooleanSupplier cancelled) {
        task.activeMillis = previous + (System.nanoTime() - start) / 1_000_000;
        if (cancelled.getAsBoolean()) throw new Stop("USER_CANCELLED");
        if (task.activeMillis >= timeoutMillis) throw new Stop("BUDGET_EXHAUSTED");
    }

    private boolean fresh(Task task, String tool) {
        Evidence e = ReportPolicy.latest(task.evidence, tool);
        return e != null && e.validUntil().isAfter(clock.instant());
    }

    private void invoke(Task task, String tool, String reason, Consumer<Task> checkpoint) {
        if (task.toolCalls >= maxTools) throw new Stop("TOOL_BUDGET_EXHAUSTED");
        var prior = task.evidence.stream().filter(e -> e.tool().equals(tool)).toList();
        if (fresh(task, tool) && (prior.size() >= 3 || (prior.size() >= 2 && prior.get(prior.size() - 1).result().equals(prior.get(prior.size() - 2).result()))))
            throw new Stop("NO_PROGRESS");
        task.agentState.pendingQuery = new Query(tool, new Arguments(task.input.storeId(), task.input.orderId()), null, reason);
        task.agentState.nextStep = "QUERY";
        task.toolCalls++;
        events.event(task, Status.EXECUTING_TOOL, "TOOL_STARTED", tool + ": " + reason, checkpoint);
        task.evidence.add(gateway.invoke(task.input, Decision.call(tool, task.input, reason), task.evidence));
        task.investigation.executionEnvironment = task.evidence.stream().anyMatch(e -> !e.simulated())
                ? Environment.MIXED_READ_ONLY : Environment.SIMULATION;
        task.agentState.pendingQuery = null;
        task.agentState.nextStep = "PLAN";
        events.event(task, Status.REASONING, "EVIDENCE_RECORDED", tool, checkpoint);
    }

    private void finish(Task task, Status status, String code, Consumer<Task> checkpoint) {
        task.agentState.nextStep = "FINISHED";
        task.agentState.pendingQuery = null;
        task.investigation.stopCode = code;
        if (status != Status.COMPLETED && task.investigation.unresolvedQuestions.isEmpty())
            task.investigation.unresolvedQuestions = List.of("调查尚未完成，停止原因：" + code);
        // Model failure never discards already verified business status or promotes model claims into facts.
        task.report = reports.validate(Decision.finish(reports.supported(task.evidence), task.evidence, code), task.evidence);
        events.event(task, status, code, task.report.summary(), checkpoint);
    }

    private static class Stop extends RuntimeException {
        Stop(String code) {
            super(code);
        }
    }
}

