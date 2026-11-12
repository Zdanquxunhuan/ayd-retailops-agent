package com.yu.retailops.application;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.persistence.FileTaskRepository;
import com.yu.retailops.runtime.*;
import com.yu.retailops.tool.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskService implements AutoCloseable {
    private final FileTaskRepository repository;
    private final DiagnosisRunner runner;
    private final Planner agent, workflow;
    private final FixtureCatalog fixtures;
    private final ReadOnlyGateway gateway;
    private final Clock clock;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), r -> new Thread(r, "diagnosis-worker"));
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public TaskService(FileTaskRepository repository, DiagnosisRunner runner, Planner agent, Planner workflow,
                       FixtureCatalog fixtures, ReadOnlyGateway gateway, Clock clock) {
        this.repository = repository;
        this.runner = runner;
        this.agent = agent;
        this.workflow = workflow;
        this.fixtures = fixtures;
        this.gateway = gateway;
        this.clock = clock;
        for (Task task : repository.all()) {
            if (!task.terminal() && task.status != Status.CLARIFYING)
                runner.unresolved(task, Status.NEEDS_HUMAN, "RESTART_INTERRUPTED", repository::save);
        }
    }

    public synchronized Task create(Input supplied) {
        if (supplied == null || supplied.userGoal() == null || supplied.userGoal().isBlank() || supplied.userGoal().length() > 2000)
            throw new IllegalArgumentException("userGoal is required (max 2000 characters)");
        String strategy = supplied.strategy() == null ? (supplied.intent() == null ? "agent" : "investigation") : supplied.strategy();
        if (!Set.of("agent", "workflow", "investigation").contains(strategy))
            throw new IllegalArgumentException("Invalid strategy");
        if (supplied.intent() != null && !"investigation".equals(strategy))
            throw new IllegalArgumentException("Intent requires investigation strategy");
        String fixture = supplied.fixtureId() == null ? "repush" : supplied.fixtureId();
        fixtures.get(fixture);
        Input input = new Input(supplied.userGoal(), supplied.storeId(), supplied.orderId(), fixture, strategy, supplied.intent());
        if (input.storeId() != null && !input.storeId().isBlank()) gateway.authorize(input);
        checkEntity(input.orderId());
        Task task = new Task();
        task.id = UUID.randomUUID().toString();
        task.input = input;
        task.createdAt = clock.instant();
        task.status = Status.CREATED;
        Planner planner = planner(input);
        task.provider = planner.provider();
        task.modelVersion = planner.modelVersion();
        if (missing(input))
            runner.event(task, Status.CLARIFYING, "MISSING_ENTITY", "请补充 storeId 与 orderId。", repository::save);
        else {
            runner.event(task, Status.CREATED, "TASK_CREATED", "任务已接收；数据源 STUB，模式 OBSERVE_ONLY。", repository::save);
            submit(task);
        }
        return repository.get(task.id);
    }

    private void checkEntity(String id) {
        if (id != null && !id.isBlank() && !id.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid orderId");
    }

    private boolean missing(Input input) {
        return input.storeId() == null || input.storeId().isBlank() || input.orderId() == null || input.orderId().isBlank();
    }

    private Planner planner(Input input) {
        return "workflow".equals(input.strategy()) ? workflow : agent;
    }

    public Task get(String id) {
        Task task = repository.get(id);
        if (task.input.storeId() != null && !task.input.storeId().isBlank()) gateway.authorize(task.input);
        return task;
    }

    public synchronized Task clarify(String id, String store, String order, long expectedVersion) {
        Task task = get(id);
        if (task.version != expectedVersion || task.status != Status.CLARIFYING) throw new ConflictException();
        Input next = new Input(task.input.userGoal(), store, order, task.input.fixtureId(), task.input.strategy(), task.input.intent());
        if (missing(next)) throw new IllegalArgumentException("storeId and orderId required");
        gateway.authorize(next);
        checkEntity(order);
        task.input = next;
        runner.event(task, Status.CREATED, "CLARIFICATION_ACCEPTED", "调查对象已确认。", repository::save);
        submit(task);
        return get(id);
    }

    public synchronized Task cancel(String id) {
        Task task = get(id);
        if (task.terminal()) return task;
        AtomicBoolean flag = cancellations.get(id);
        if (flag != null) flag.set(true);
        else runner.unresolved(task, Status.CANCELLED, "USER_CANCELLED", repository::save);
        return get(id);
    }

    private void submit(Task task) {
        AtomicBoolean flag = new AtomicBoolean();
        cancellations.put(task.id, flag);
        try {
            worker.execute(() -> {
                try {
                    runner.run(task, planner(task.input), repository::save, flag::get);
                } finally {
                    cancellations.remove(task.id);
                }
            });
        } catch (RejectedExecutionException e) {
            cancellations.remove(task.id);
            runner.unresolved(task, Status.NEEDS_HUMAN, "QUEUE_FULL", repository::save);
        }
    }

    public static class ConflictException extends RuntimeException {
    }

    public void close() {
        cancellations.values().forEach(f -> f.set(true));
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
